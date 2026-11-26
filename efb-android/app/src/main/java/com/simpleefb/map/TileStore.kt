package com.simpleefb.map

import com.simpleefb.core.Cycles
import com.simpleefb.store.ChartDb
import com.simpleefb.store.SavedRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.util.MapTileIndex
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Chart tile cache management.
 *
 * Tiles live in osmdroid's SQLite tile cache under a per-edition name (FAA_SECTIONAL_2611, …).
 * When a new 56-day edition starts, saved offline regions are downloaded into the new edition's
 * cache first; only then does the map switch to it and the old edition get deleted. That way the
 * charts never disappear offline in the middle of an update.
 */
class TileStore(private val db: ChartDb, private val http: OkHttpClient) {

    private val writer by lazy { SqlTileWriter() }

    private val _activeEdition = MutableStateFlow(loadActiveEdition())
    val activeEdition: StateFlow<String> = _activeEdition.asStateFlow()

    private fun loadActiveEdition(): String =
        db.meta(KEY_EDITION) ?: Cycles.chartEditionAt(Instant.now()).ident.also { db.setMeta(KEY_EDITION, it) }

    fun source(layer: ChartLayer): FaaChartTileSource = FaaChartTileSource(layer, _activeEdition.value)

    fun regionTileCount(r: SavedRegion): Long = r.layers.map(ChartLayer::parse).sumOf {
        TileMath.count(r.north, r.south, r.east, r.west, it.minZoom, it.maxZoom)
    }

    /**
     * Brings offline regions up to date. Returns a short status message.
     * [onProgress] receives human-readable progress text.
     */
    suspend fun sync(onProgress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val current = Cycles.chartEditionAt(now)
        var active = _activeEdition.value
        val regions = db.regions()
        val messages = ArrayList<String>()

        // New edition: give the FAA a day to publish the new tiles, then re-download regions.
        val settled = now.isAfter(current.effectiveInstant.plus(Duration.ofDays(1)))
        if (current.ident != active && settled) {
            var ok = true
            for (r in regions) {
                val failed = downloadRegion(r, current.ident) { done, total ->
                    onProgress("New chart edition: ${r.name} $done/$total tiles")
                }
                if (failed > maxOf(20, regionTileCount(r) / 50)) ok = false
                else db.setMeta(regionKey(r.id), current.ident)
            }
            if (ok) {
                db.setMeta(KEY_EDITION, current.ident)
                _activeEdition.value = current.ident
                for (layer in ChartLayer.entries) {
                    if (active != current.ident) runCatching { writer.purgeCache(FaaChartTileSource(layer, active).name()) }
                }
                active = current.ident
                messages += "Map charts updated to the ${current.display} edition"
            } else {
                messages += "Map chart update incomplete; will retry"
            }
        }

        // Regions added (or not finished) for the active edition.
        for (r in regions) {
            if (db.meta(regionKey(r.id)) == active) continue
            val failed = downloadRegion(r, active) { done, total -> onProgress("Downloading ${r.name}: $done/$total tiles") }
            if (failed <= maxOf(20, regionTileCount(r) / 50)) {
                db.setMeta(regionKey(r.id), active)
                messages += "${r.name} ready offline"
            } else {
                messages += "${r.name}: $failed tiles failed; will retry"
            }
        }
        messages.joinToString("; ")
    }

    fun isRegionReady(r: SavedRegion): Boolean = db.meta(regionKey(r.id)) == _activeEdition.value

    fun deleteRegion(r: SavedRegion) {
        db.deleteRegion(r.id)
        db.setMeta(regionKey(r.id), null)
    }

    fun purgeAll() {
        runCatching { writer.purgeCache() }
        db.regions().forEach { db.setMeta(regionKey(it.id), null) }
    }

    /** Downloads every tile of [r] for [edition]; returns the number of tiles that failed. */
    private suspend fun downloadRegion(r: SavedRegion, edition: String, onProgress: (Long, Long) -> Unit): Long =
        coroutineScope {
            val total = r.layers.map(ChartLayer::parse).sumOf {
                TileMath.count(r.north, r.south, r.east, r.west, it.minZoom, it.maxZoom)
            }
            val done = AtomicLong(0)
            val failed = AtomicLong(0)
            val expiry = TileMath.expiryMillis()
            for (layer in r.layers.map(ChartLayer::parse)) {
                val src = FaaChartTileSource(layer, edition)
                val queue = Channel<Long>(capacity = 256)
                launch {
                    for (z in layer.minZoom..layer.maxZoom) {
                        for (x in TileMath.x(r.west, z)..TileMath.x(r.east, z)) {
                            for (y in TileMath.y(r.north, z)..TileMath.y(r.south, z)) {
                                queue.send(MapTileIndex.getTileIndex(z, x, y))
                            }
                        }
                    }
                    queue.close()
                }
                coroutineScope {
                    repeat(4) {
                        launch(Dispatchers.IO) {
                            for (index in queue) {
                                ensureActive()
                                if (!fetchTile(src, index, expiry)) failed.incrementAndGet()
                                val d = done.incrementAndGet()
                                if (d % 25 == 0L || d == total) onProgress(d, total)
                            }
                        }
                    }
                }
            }
            failed.get()
        }

    /** True if the tile is cached (or legitimately absent: the FAA has no tile there). */
    private fun fetchTile(src: FaaChartTileSource, index: Long, expiry: Long): Boolean {
        if (runCatching { writer.exists(src, index) }.getOrDefault(false)) return true
        val url = src.getTileURLString(index)
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                when {
                    resp.code == 404 -> true // outside chart coverage
                    !resp.isSuccessful -> false
                    else -> {
                        val bytes = resp.body?.bytes() ?: return false
                        writer.saveFile(src, index, bytes.inputStream(), expiry)
                        true
                    }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun regionKey(id: Long) = "region_edition_$id"

    companion object {
        private const val KEY_EDITION = "map_edition"
    }
}
