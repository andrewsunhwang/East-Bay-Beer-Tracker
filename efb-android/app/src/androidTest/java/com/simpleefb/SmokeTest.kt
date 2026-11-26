package com.simpleefb

import android.Manifest
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.simpleefb.core.Gdl90Framer
import com.simpleefb.store.PositionSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.config.Configuration
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.roundToInt

/**
 * End-to-end smoke test on an emulator with live FAA data: first-launch index download, map tiles,
 * airport search, plates, a SID with a continuation page, the Chart Supplement, and GDL 90 over UDP.
 * Screenshots are written to files/screens for CI to collect.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule(order = 0)
    val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = (compose.activity.application as EfbApp).graph

    @Test
    fun endToEnd() {
        // 1. First launch downloads the d-TPP index.
        waitUntil(300_000, "d-TPP index download") { graph.db.importedCycles().isNotEmpty() }
        log("Imported cycles: ${graph.db.importedCycles()}, airports: ${graph.db.airportCount()}")
        assertTrue(graph.db.airportCount() > 1000)

        // 2. Map with sectional tiles around the emulator's GPS position (OAK).
        try {
            waitUntil(30_000, "GPS fix") { graph.position.ownship.value != null }
        } catch (e: AssertionError) {
            log("No emulator GPS fix; continuing at the default map position")
        }
        Thread.sleep(12_000)
        val mapColors = shot("01-map-sectional")
        log("Sectional tiles cached: ${cachedTiles("FAA_SECTIONAL_")}, distinct colors: $mapColors")
        assertTrue("Map looks blank (colors=$mapColors)", mapColors > 100)

        compose.onNodeWithText("TAC").performClick()
        Thread.sleep(10_000)
        shot("02-map-tac")
        compose.onNodeWithText("IFR Low").performClick()
        Thread.sleep(10_000)
        shot("03-map-ifr-low")
        compose.onNodeWithText("Sectional").performClick()

        // 3. Airport search → OAK.
        compose.onNodeWithText("Airports").performClick()
        compose.onNodeWithText("Airport ID, name or city").performTextInput("OAK")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("KOAK").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("KOAK")[0].performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Approaches", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Departures", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        shot("04-airport-oak")

        // 4. An approach plate.
        openChart("ILS OR LOC RWY 30")
        shot("05-plate-ils30")
        back()

        // 5. A SID with a continuation page opens as one 2-page document.
        openChart("OAKLAND SEVEN")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("1 / 2").fetchSemanticsNodes().isNotEmpty() }
        shot("06-sid-oakland7")
        back()

        // 6. Chart Supplement.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Chart Supplement —", substring = true))
        compose.onNodeWithText("Chart Supplement —", substring = true).performClick()
        compose.waitUntil(90_000) {
            compose.onAllNodesWithContentDescription("Chart Supplement").fetchSemanticsNodes().isNotEmpty()
        }
        Thread.sleep(1500)
        shot("07-chart-supplement")
        back()
        back()

        // 7. GDL 90 over UDP (what a Wi-Fi ADS-B receiver sends).
        graph.settings.positionSource.value = PositionSourceType.GDL90_WIFI.name
        graph.position.restart()
        Thread.sleep(2000)
        val ownship = Gdl90Framer.frame(report(0x0A, 0xA1B2C3, 37.70, -122.25, 2500, 110, 300.0, "N12345"))
        val traffic = Gdl90Framer.frame(report(0x14, 0xABCDEF, 37.74, -122.20, 3000, 140, 120.0, "N825V"))
        DatagramSocket().use { s ->
            repeat(15) {
                val payload = ownship + traffic
                s.send(DatagramPacket(payload, payload.size, InetAddress.getLoopbackAddress(), 4000))
                Thread.sleep(200)
            }
        }
        waitUntil(5_000, "GDL 90 ownship") { graph.position.ownship.value?.source == "ADS-B Wi-Fi" }
        val fix = graph.position.ownship.value!!
        assertEquals(37.70, fix.latitude, 1e-3)
        assertEquals(-122.25, fix.longitude, 1e-3)
        assertEquals(2500, fix.pressureAltitudeFt)
        assertTrue("traffic not received", graph.position.traffic.value.any { it.callsign == "N825V" })
        compose.onNodeWithText("Map").performClick()
        Thread.sleep(6_000)
        shot("08-map-gdl90-traffic")

        compose.onNodeWithText("Settings").performClick()
        Thread.sleep(1500)
        shot("09-settings")
        log("SMOKE TEST PASSED")
    }

    private fun openChart(name: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(name))
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithContentDescription(name).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1500)
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** Saves a screenshot (full PNG + small JPEG for the CI log); returns the number of distinct colors. */
    private fun shot(name: String): Int {
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(compose.activity.filesDir, "screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val w = 360
        val small = Bitmap.createScaledBitmap(bmp, w, (w.toFloat() * bmp.height / bmp.width).roundToInt(), true)
        File(dir, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 55, it) }
        // Count colors in the middle of the screen only (away from bars, chips and buttons):
        // a loaded chart has hundreds, osmdroid's empty "loading" grid only a handful.
        val colors = HashSet<Int>()
        for (y in (small.height * 0.25).toInt() until (small.height * 0.70).toInt() step 2) {
            for (x in (small.width * 0.10).toInt() until (small.width * 0.75).toInt() step 2) colors += small.getPixel(x, y)
        }
        log("Screenshot $name: ${bmp.width}x${bmp.height}, ${colors.size} colors in center")
        return colors.size
    }

    private fun cachedTiles(prefix: String): Long = try {
        val db = File(Configuration.getInstance().osmdroidTileCache, "cache.db")
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT COUNT(*) FROM tiles WHERE provider LIKE ?", arrayOf("$prefix%")).use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1
            }
        }
    } catch (e: Exception) {
        log("tile count failed: $e")
        -1
    }

    private fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() > end) {
                throw AssertionError("Timed out waiting for $what; update status: ${graph.updater.status.value}")
            }
            Thread.sleep(500)
        }
    }

    private fun log(msg: String) {
        Log.i("SimpleEFB-Smoke", msg)
        println("SimpleEFB-Smoke: $msg")
    }

    /** Builds a GDL 90 Ownship (0x0A) or Traffic (0x14) report. */
    private fun report(id: Int, address: Int, lat: Double, lon: Double, altFt: Int, kt: Int, track: Double, call: String): ByteArray {
        fun s24(v: Double): Int = (v * 8388608.0 / 180.0).roundToInt() and 0xFFFFFF
        val la = s24(lat)
        val lo = s24(lon)
        val ddd = (altFt + 1000) / 25
        val m = ByteArray(28)
        m[0] = id.toByte()
        m[1] = 0x00
        m[2] = (address shr 16).toByte(); m[3] = (address shr 8).toByte(); m[4] = address.toByte()
        m[5] = (la shr 16).toByte(); m[6] = (la shr 8).toByte(); m[7] = la.toByte()
        m[8] = (lo shr 16).toByte(); m[9] = (lo shr 8).toByte(); m[10] = lo.toByte()
        m[11] = (ddd shr 4).toByte()
        m[12] = (((ddd and 0x0F) shl 4) or 0x09).toByte() // airborne, true track
        m[13] = 0xA9.toByte() // NIC 10, NACp 9
        m[14] = (kt shr 4).toByte()
        m[15] = ((kt and 0x0F) shl 4).toByte() // vertical velocity 0
        m[16] = 0
        m[17] = (track * 256.0 / 360.0).roundToInt().toByte()
        m[18] = 1
        call.padEnd(8).take(8).forEachIndexed { i, c -> m[19 + i] = c.code.toByte() }
        m[27] = 0
        return m
    }
}
