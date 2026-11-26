package com.simpleefb.map

import com.simpleefb.core.Cycles
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * FAA chart layers served by FAA Aeronautical Information Services as Web Mercator tiles
 * (tiles.arcgis.com, org ssFJjBXIUyZDrSYZ). The FAA updates them every 56-day chart cycle.
 * Zoom ranges are the levels the services actually have (checked against the live services).
 */
enum class ChartLayer(val label: String, val service: String, val minZoom: Int, val maxZoom: Int) {
    SECTIONAL("Sectional", "VFR_Sectional", 8, 12),
    TAC("TAC", "VFR_Terminal", 10, 12),
    IFR_LOW("IFR Low", "IFR_AreaLow", 7, 12),
    IFR_HIGH("IFR High", "IFR_High", 5, 9);

    companion object {
        fun parse(s: String): ChartLayer = entries.firstOrNull { it.name == s } ?: SECTIONAL
    }
}

/**
 * One FAA chart layer. The cache name includes the chart edition so that a new edition is
 * downloaded into a fresh cache while the previous one stays usable offline (see TileStore).
 */
class FaaChartTileSource(val layer: ChartLayer, val edition: String) : OnlineTileSourceBase(
    "FAA_${layer.name}_$edition",
    layer.minZoom,
    layer.maxZoom,
    256,
    ".png",
    arrayOf(BASE_URL),
    "FAA Aeronautical Information Services",
) {
    override fun getTileURLString(pMapTileIndex: Long): String = tileUrl(
        layer,
        MapTileIndex.getZoom(pMapTileIndex),
        MapTileIndex.getX(pMapTileIndex),
        MapTileIndex.getY(pMapTileIndex),
    )

    companion object {
        const val BASE_URL = "https://tiles.arcgis.com/tiles/ssFJjBXIUyZDrSYZ/arcgis/rest/services/"

        /** ArcGIS REST tile order is z/y/x. */
        fun tileUrl(layer: ChartLayer, z: Int, x: Int, y: Int) = "$BASE_URL${layer.service}/MapServer/tile/$z/$y/$x"
    }
}

object TileMath {
    fun x(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun y(lat: Double, z: Int): Int {
        val n = 1 shl z
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun count(north: Double, south: Double, east: Double, west: Double, minZ: Int, maxZ: Int): Long {
        var total = 0L
        for (z in minZ..maxZ) {
            val dx = (x(east, z) - x(west, z) + 1).toLong()
            val dy = (y(south, z) - y(north, z) + 1).toLong()
            total += dx * dy
        }
        return total
    }

    /**
     * Tiles get downloaded with an expiry one day after the next 56-day chart edition starts,
     * so cached charts refresh automatically after each new edition (and stay viewable offline).
     */
    fun expiryMillis(now: Instant = Instant.now()): Long {
        val next = Cycles.nextChartEdition(Cycles.chartEditionAt(now))
        return next.effectiveInstant.toEpochMilli() + TimeUnit.DAYS.toMillis(1)
    }
}
