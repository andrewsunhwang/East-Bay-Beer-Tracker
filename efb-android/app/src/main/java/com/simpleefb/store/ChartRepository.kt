package com.simpleefb.store

import android.content.Context
import android.util.Xml
import com.simpleefb.core.ChartSupplement
import com.simpleefb.core.Cycle
import com.simpleefb.core.Cycles
import com.simpleefb.core.TppChart
import com.simpleefb.core.TppMetafileParser
import com.simpleefb.core.TppUrls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class NotFoundException(message: String) : IOException(message)

/** Which d-TPP cycle the app is showing, and whether it is out of date. */
data class ActiveCycle(val cycle: Cycle, val expired: Boolean)

/**
 * Downloads and stores FAA data:
 * - d-TPP metafile → SQLite index; chart PDFs → files/tpp/<cycle>/<PDF>
 * - Chart Supplement page PDFs → files/cs/<edition>/<file>.pdf
 */
class ChartRepository(private val context: Context, val db: ChartDb) {

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    private val tppRoot = File(context.filesDir, "tpp")
    private val csRoot = File(context.filesDir, "cs")

    fun now(): Instant = Instant.now()
    fun currentTppCycle(): Cycle = Cycles.airacAt(now())
    fun currentChartEdition(): Cycle = Cycles.chartEditionAt(now())

    /**
     * The newest imported cycle that is already in effect. Falls back to an expired one when the
     * current cycle hasn't been downloaded (e.g. offline on cycle day), flagged as expired.
     */
    fun activeTppCycle(): ActiveCycle? {
        val current = currentTppCycle()
        val imported = db.importedCycles()
        if (current.ident in imported) return ActiveCycle(current, expired = false)
        val older = imported.filter { it < current.ident }.maxOrNull() ?: return null
        val prev = generateSequence(current) { Cycles.previousAirac(it) }.take(30).firstOrNull { it.ident == older }
            ?: return null
        return ActiveCycle(prev, expired = true)
    }

    // ------------------------------------------------------------------ d-TPP

    /** Downloads and imports the d-TPP index for [cycle] unless already present. */
    suspend fun ensureTppIndex(cycle: Cycle, onProgress: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (cycle.ident in db.importedCycles()) return@withContext false
        onProgress("Downloading d-TPP index ${cycle.ident}…")
        val tmp = File(context.cacheDir, "metafile-${cycle.ident}.xml")
        download(TppUrls.metafile(cycle.ident), tmp)
        onProgress("Indexing d-TPP ${cycle.ident}…")
        try {
            var count = 0
            db.importCycle(cycle.ident) { sink ->
                var from = ""
                var to = ""
                tmp.inputStream().buffered().use { input ->
                    TppMetafileParser { Xml.newPullParser() }.parse(
                        input,
                        onHeader = { h -> from = h.fromDate; to = h.toDate },
                        onAirport = { a -> sink(a); count++ },
                    )
                }
                from to to
            }
            if (count == 0) {
                db.deleteCycle(cycle.ident)
                throw IOException("d-TPP index ${cycle.ident} contained no airports")
            }
        } finally {
            tmp.delete()
        }
        true
    }

    fun chartFile(cycle: String, pdf: String): File = File(File(tppRoot, cycle), pdf)

    suspend fun ensureChart(cycle: String, chart: TppChart): File = withContext(Dispatchers.IO) {
        val f = chartFile(cycle, chart.pdfName)
        if (!f.exists() || f.length() == 0L) download(TppUrls.pdf(cycle, chart.pdfName), f, requirePdf = true)
        f
    }

    /** Deletes chart folders and index rows for cycles other than [keep]. */
    fun pruneTpp(keep: Set<String>) {
        tppRoot.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach { it.deleteRecursively() }
        db.importedCycles().filter { it !in keep }.forEach { db.deleteCycle(it) }
    }

    // ------------------------------------------------------------------ Chart Supplement

    /** FAA identifiers for the d-CS search: "KOAK" → "OAK" when the index knows the airport. */
    fun csIdent(ident: String): String {
        val id = ident.trim().uppercase()
        db.airport(id)?.let { return it.faaId }
        return if (id.length == 4 && id.startsWith("K")) id.substring(1) else id
    }

    /**
     * Looks up (and caches) the Chart Supplement PDFs for [ident]: the airport's page(s), then its
     * notices page if it has one. Uses the FAA d-CS search page, falling back to the d-CS XML index.
     */
    suspend fun supplementPageUrls(ident: String, edition: Cycle = currentChartEdition()): List<String> =
        withContext(Dispatchers.IO) {
            val id = csIdent(ident)
            db.csPages(edition.ident, id)?.let { if (it.isNotEmpty()) return@withContext it }
            var lastError: Exception? = null
            var found: List<String> = try {
                supplementFromSearch(id, edition)
            } catch (e: Exception) {
                lastError = e
                emptyList()
            }
            if (found.isEmpty()) {
                found = try {
                    supplementFromIndex(id, edition)
                } catch (e: Exception) {
                    if (lastError == null) lastError = e
                    emptyList()
                }
            }
            if (found.isEmpty()) {
                throw (lastError as? IOException) ?: NotFoundException("No Chart Supplement entry found for $id")
            }
            db.setCsPages(edition.ident, id, found)
            found
        }

    private fun supplementFromSearch(id: String, edition: Cycle): List<String> {
        for (url in ChartSupplement.searchUrls(id, edition)) {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from the FAA Chart Supplement search")
                val html = resp.body?.string().orEmpty()
                val pages = ChartSupplement.airportPages(
                    ChartSupplement.extractPageLinks(html, resp.request.url.toString())
                )
                if (pages.isNotEmpty()) return pages
            }
        }
        return emptyList()
    }

    /** d-CS XML index (afd_<date>.xml, a few MB): downloaded once per edition, then parsed per lookup. */
    private suspend fun supplementFromIndex(id: String, edition: Cycle): List<String> {
        val folder = edition.faaDate.lowercase()
        val index = File(File(csRoot, edition.ident), "index.xml")
        if (!index.exists() || index.length() == 0L) {
            download("https://aeronav.faa.gov/afd/$folder/afd_${edition.faaDate}.xml", index)
        }
        var pdfs: List<String> = emptyList()
        index.inputStream().buffered().use { input ->
            ChartSupplement.parseIndex(input, { Xml.newPullParser() }) { aptId, files ->
                if (aptId == id && pdfs.isEmpty()) pdfs = files
            }
        }
        return pdfs.map { "https://aeronav.faa.gov/afd/$folder/$it" }
    }

    fun supplementFile(edition: Cycle, url: String): File =
        File(File(csRoot, edition.ident), ChartSupplement.fileName(url))

    suspend fun ensureSupplement(ident: String, edition: Cycle = currentChartEdition()): List<File> =
        withContext(Dispatchers.IO) {
            supplementPageUrls(ident, edition).map { url ->
                val f = supplementFile(edition, url)
                if (!f.exists() || f.length() == 0L) download(url, f, requirePdf = true)
                f
            }
        }

    /** Pages already on disk for [ident] (no network). */
    fun cachedSupplement(ident: String, edition: Cycle = currentChartEdition()): List<File>? {
        val urls = db.csPages(edition.ident, csIdent(ident)) ?: return null
        val files = urls.map { supplementFile(edition, it) }
        return files.takeIf { it.isNotEmpty() && it.all { f -> f.exists() && f.length() > 0 } }
    }

    fun pruneSupplement(keepEdition: String) {
        csRoot.listFiles()?.filter { it.isDirectory && it.name != keepEdition }?.forEach { it.deleteRecursively() }
        db.deleteCsPagesExcept(keepEdition)
    }

    // ------------------------------------------------------------------ helpers

    fun storageBytes(): Long =
        listOf(tppRoot, csRoot, File(context.filesDir, "osmdroid")).sumOf { dir ->
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

    fun deleteDownloadedCharts() {
        tppRoot.deleteRecursively()
        csRoot.deleteRecursively()
    }

    /** Downloads [url] to [dest] atomically (via a .part file). */
    suspend fun download(url: String, dest: File, requirePdf: Boolean = false) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) throw NotFoundException("Not found: $url")
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("Empty response for $url")
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        if (requirePdf && !isPdf(part)) {
            part.delete()
            throw IOException("Server did not return a PDF for $url")
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    }

    private fun isPdf(f: File): Boolean = f.length() > 4 && f.inputStream().use { s ->
        val b = ByteArray(4)
        s.read(b) == 4 && String(b, Charsets.US_ASCII) == "%PDF"
    }

    companion object {
        const val USER_AGENT = "SimpleEFB/1.0 (Android; personal EFB)"
    }
}
