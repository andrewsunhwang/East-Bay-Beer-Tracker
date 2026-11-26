package com.simpleefb.core

import org.kxml2.io.KXmlParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Checks the live FAA endpoints the app depends on and prints a report. Run with
 * `./gradlew -PcoreOnly=true :core:liveCheck` (needs internet). CI runs it on every push so
 * that FAA-side changes show up quickly.
 */
private const val UA = "SimpleEFB-LiveCheck/1.0 (+https://github.com)"
private var failures = 0

private class Resp(val code: Int, val type: String?, val body: ByteArray, val finalUrl: String)

private fun get(url: String, maxBytes: Int = 64 * 1024 * 1024, ua: String = UA): Resp {
    var current = url
    repeat(5) {
        val c = URL(current).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", ua)
        val code = c.responseCode
        if (code in 300..399) {
            val loc = c.getHeaderField("Location") ?: return Resp(code, null, ByteArray(0), current)
            current = URL(URL(current), loc).toString()
            c.disconnect()
            return@repeat
        }
        val stream: InputStream? = if (code >= 400) c.errorStream else c.inputStream
        val out = ByteArrayOutputStream()
        stream?.use { s ->
            val buf = ByteArray(64 * 1024)
            while (out.size() < maxBytes) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return Resp(code, c.contentType, out.toByteArray(), current)
    }
    return Resp(-1, null, ByteArray(0), current)
}

private fun safeGet(url: String, maxBytes: Int = 64 * 1024 * 1024, ua: String = UA): Resp? = try {
    get(url, maxBytes, ua)
} catch (e: Exception) {
    println("   ERROR $url -> ${e.javaClass.simpleName}: ${e.message}")
    null
}

private fun section(t: String) = println("\n=== $t ===")
private fun fail(msg: String) { failures++; println("   FAIL: $msg") }

fun main() {
    val now = Instant.now()
    val tpp = Cycles.airacAt(now)
    val edition = Cycles.chartEditionAt(now)
    println("Now: $now  d-TPP cycle: ${tpp.ident} (${tpp.effective})  chart edition: ${edition.ident} (${edition.effective}, ${edition.faaDate})")

    // ---------------------------------------------------------------- d-TPP
    section("d-TPP metafile")
    val metaUrl = TppUrls.metafile(tpp.ident)
    val meta = safeGet(metaUrl)
    var oakApd: String? = null
    if (meta == null || meta.code != 200) {
        fail("metafile $metaUrl -> ${meta?.code}")
    } else {
        println("   $metaUrl -> 200, ${meta.body.size} bytes, ${meta.type}")
        File("build/live").mkdirs()
        File("build/live/metafile-head.xml").writeBytes(meta.body.copyOf(minOf(meta.body.size, 20000)))
        var header: TppHeader? = null
        var airports = 0
        var charts = 0
        val codes = HashMap<String, Int>()
        val samples = ArrayList<String>()
        try {
            TppMetafileParser { KXmlParser() }.parse(meta.body.inputStream(), { header = it }) { a ->
                airports++
                charts += a.charts.size
                a.charts.forEach { codes[it.code] = (codes[it.code] ?: 0) + 1 }
                if (a.airport.faaId in setOf("OAK", "SFO", "RNO", "JFK", "LVK")) {
                    samples += "${a.airport.faaId}/${a.airport.icaoId} ${a.airport.name}, ${a.airport.city} ${a.airport.state}: " +
                        a.charts.groupBy { it.category }.map { (k, v) -> "$k=${v.size}" }.joinToString()
                    if (a.airport.faaId == "OAK") {
                        a.charts.forEach { println("      OAK ${it.seq} ${it.code} '${it.name}' ${it.pdfName} ${it.amendment}") }
                        oakApd = a.charts.firstOrNull { it.code == "APD" }?.pdfName
                    }
                }
            }
        } catch (e: Exception) {
            fail("metafile parse error: $e")
        }
        println("   header=$header airports=$airports charts=$charts")
        println("   chart codes: ${codes.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }}")
        samples.forEach { println("   $it") }
        if (airports < 1000) fail("too few airports parsed: $airports")
        val unknown = codes.keys.filter { ChartCategory.forTppCode(it) == ChartCategory.OTHER }
        if (unknown.isNotEmpty()) println("   NOTE: uncategorized codes: $unknown")
    }
    oakApd?.let {
        val r = safeGet(TppUrls.pdf(tpp.ident, it), maxBytes = 2048)
        val isPdf = r != null && r.body.size >= 4 && String(r.body, 0, 4) == "%PDF"
        println("   OAK diagram ${TppUrls.pdf(tpp.ident, it)} -> ${r?.code} pdf=$isPdf")
        if (!isPdf) fail("OAK diagram not a PDF")
    }
    val nextTpp = Cycles.nextAirac(tpp)
    safeGet(TppUrls.metafile(nextTpp.ident), maxBytes = 1024)?.let {
        println("   next cycle ${nextTpp.ident} metafile -> ${it.code} (published early if 200)")
    }

    // ---------------------------------------------------------------- Chart Supplement
    section("Chart Supplement")
    var csOk = false
    for (ua in listOf(UA, "Mozilla/5.0 (Linux; Android 14) SimpleEFB/1.0")) {
        for (url in ChartSupplement.searchUrls("OAK", edition)) {
            val r = safeGet(url, ua = ua) ?: continue
            val html = String(r.body)
            val links = ChartSupplement.airportPages(ChartSupplement.extractPageLinks(html, r.finalUrl))
            println("   [$ua] $url -> ${r.code} ${r.body.size} bytes, airport pages=$links")
            if (links.isEmpty()) {
                File("build/live").mkdirs()
                File("build/live/cs-search-${r.code}.html").writeText(html.take(200_000))
                val pdfs = Regex("""href\s*=\s*["']([^"']+?\.pdf)["']""", RegexOption.IGNORE_CASE)
                    .findAll(html).map { it.groupValues[1] }.take(10).toList()
                println("      any pdf hrefs: $pdfs")
                println("      title: ${Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim()}")
            } else {
                val p = safeGet(links[0], maxBytes = 2048)
                val isPdf = p != null && p.body.size >= 4 && String(p.body, 0, 4) == "%PDF"
                println("      first page ${links[0]} -> ${p?.code} pdf=$isPdf")
                if (isPdf) csOk = true
            }
        }
    }
    if (!csOk) fail("could not locate a Chart Supplement page for OAK via the FAA search page")
    // d-CS XML index (the app's fallback when the search page fails).
    val d = edition.faaDate
    val indexUrl = "https://aeronav.faa.gov/afd/${d.lowercase()}/afd_$d.xml"
    val idx = safeGet(indexUrl)
    if (idx == null || idx.code != 200) {
        fail("d-CS index $indexUrl -> ${idx?.code}")
    } else {
        println("   $indexUrl -> 200, ${idx.body.size} bytes")
        println("      head: ${String(idx.body, 0, minOf(1500, idx.body.size)).replace("\n", " ")}")
        var n = 0
        val wanted = HashMap<String, List<String>>()
        try {
            ChartSupplement.parseIndex(idx.body.inputStream(), { KXmlParser() }) { id, pdfs ->
                n++
                if (id in setOf("OAK", "SFO", "O88", "LVK", "JFK")) wanted[id] = pdfs
            }
        } catch (e: Exception) {
            fail("d-CS index parse error: $e")
        }
        println("      parsed $n airports; samples: $wanted")
        if (n < 1000) fail("d-CS index: too few airports ($n)")
        wanted["OAK"]?.firstOrNull()?.let { pdf ->
            val p = safeGet("https://aeronav.faa.gov/afd/${d.lowercase()}/$pdf", maxBytes = 2048)
            println("      OAK via index -> ${p?.code} pdf=${p != null && p.body.size > 4 && String(p.body, 0, 4) == "%PDF"}")
        }
    }

    // ---------------------------------------------------------------- APRA
    section("FAA APRA API")
    for (u in listOf(
        "https://external-api.faa.gov/apra/dtpp/info?edition=current",
        "https://external-api.faa.gov/apra/dtpp/info?edition=next",
        "https://external-api.faa.gov/apra/supplement/info?edition=current",
        "https://external-api.faa.gov/apra/supplement/chart?edition=current",
        "https://external-api.faa.gov/apra/vfr/sectional/info?edition=current&geoname=San%20Francisco",
    )) {
        safeGet(u, maxBytes = 8192)?.let { println("   $u -> ${it.code}\n      ${String(it.body).take(1200).replace("\n", " ")}") }
    }

    // ---------------------------------------------------------------- Tiles
    section("FAA ArcGIS chart tiles")
    val base = "https://tiles.arcgis.com/tiles/ssFJjBXIUyZDrSYZ/arcgis/rest/services"
    safeGet("$base?f=json", maxBytes = 200_000)?.let {
        val names = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(String(it.body)).map { m -> m.groupValues[1] }.toList()
        println("   services (${it.code}): $names")
    }
    // OAK area: lat 37.72, lon -122.22
    for (svc in listOf("VFR_Sectional", "VFR_Terminal", "IFR_AreaLow", "IFR_High", "Helicopter")) {
        val info = safeGet("$base/$svc/MapServer?f=json", maxBytes = 200_000)
        if (info == null) continue
        val body = String(info.body)
        val levels = Regex("\"level\"\\s*:\\s*(\\d+)").findAll(body).map { it.groupValues[1].toInt() }.toList()
        val minLod = Regex("\"minLOD\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)
        val maxLod = Regex("\"maxLOD\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)
        val caps = Regex("\"capabilities\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
        val wkid = Regex("\"latestWkid\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)
        val tileRows = Regex("\"rows\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)
        println("   $svc -> ${info.code} levels=${levels.minOrNull()}..${levels.maxOrNull()} minLOD=$minLod maxLOD=$maxLod caps=$caps wkid=$wkid tile=$tileRows")
        if (info.code != 200) { fail("$svc service info ${info.code}"); continue }
        if (body.length < 3000) println("      body: ${body.take(1500)}")
        for (z in listOf(4, 6, 8, 9, 10, 11, 12, 13, 14)) {
            val n = 1 shl z
            val x = ((-122.22 + 180) / 360 * n).toInt()
            val latRad = Math.toRadians(37.72)
            val y = ((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n).toInt()
            val t = safeGet("$base/$svc/MapServer/tile/$z/$y/$x", maxBytes = 512_000)
            println("      z=$z tile $y/$x -> ${t?.code} ${t?.type} ${t?.body?.size} bytes")
        }
    }

    println("\nLive check finished with $failures failure(s).")
    if (failures > 0) kotlin.system.exitProcess(1)
}
