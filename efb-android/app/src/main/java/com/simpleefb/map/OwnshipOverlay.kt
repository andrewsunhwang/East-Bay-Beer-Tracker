package com.simpleefb.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import com.simpleefb.core.OwnshipFix
import com.simpleefb.core.TrafficTarget
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Draws ownship (airplane pointing along the ground track) and ADS-B traffic on the chart. */
class OwnshipOverlay(private val density: Float) : Overlay() {

    var fix: OwnshipFix? = null
    var traffic: List<TrafficTarget> = emptyList()

    // Airplane silhouette, nose up (north), roughly 1 unit = 1 dp.
    private val plane = Path().apply {
        moveTo(0f, -14f)
        lineTo(2.2f, -8f); lineTo(2.2f, -3f); lineTo(13f, 3f); lineTo(13f, 6f); lineTo(2.2f, 3f)
        lineTo(1.8f, 9f); lineTo(5f, 12f); lineTo(5f, 14f); lineTo(0f, 12.5f)
        lineTo(-5f, 14f); lineTo(-5f, 12f); lineTo(-1.8f, 9f); lineTo(-2.2f, 3f)
        lineTo(-13f, 6f); lineTo(-13f, 3f); lineTo(-2.2f, -3f); lineTo(-2.2f, -8f)
        close()
    }
    private val arrow = Path().apply {
        moveTo(0f, -9f); lineTo(7f, 8f); lineTo(0f, 4f); lineTo(-7f, 8f); close()
    }

    private val planeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 0); style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeJoin = Paint.Join.ROUND
    }
    private val trafficFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255); style = Paint.Style.FILL }
    private val trafficAlert = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 170, 0); style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * density; isFakeBoldText = true
        setShadowLayer(3f * density, 0f, 0f, Color.BLACK)
    }
    private val pt = Point()

    override fun draw(canvas: Canvas, projection: Projection) {
        val own = fix
        for (t in traffic) drawTraffic(canvas, projection, t, own)
        if (own != null) {
            projection.toPixels(GeoPoint(own.latitude, own.longitude), pt)
            canvas.save()
            canvas.translate(pt.x.toFloat(), pt.y.toFloat())
            canvas.rotate((own.trackDeg ?: 0.0).toFloat())
            canvas.scale(density * 1.6f, density * 1.6f)
            canvas.drawPath(plane, planeFill)
            canvas.drawPath(plane, outline)
            canvas.restore()
        }
    }

    private fun drawTraffic(canvas: Canvas, projection: Projection, t: TrafficTarget, own: OwnshipFix?) {
        projection.toPixels(GeoPoint(t.latitude, t.longitude), pt)
        val x = pt.x.toFloat()
        val y = pt.y.toFloat()

        val relAlt = if (own?.pressureAltitudeFt != null && t.pressureAltitudeFt != null) {
            t.pressureAltitudeFt!! - own.pressureAltitudeFt!!
        } else {
            null
        }
        val close = own != null && relAlt != null && abs(relAlt) < 1000 &&
            distanceNm(own.latitude, own.longitude, t.latitude, t.longitude) < 3.0
        val fill = if (close) trafficAlert else trafficFill

        canvas.save()
        canvas.translate(x, y)
        canvas.scale(density * 1.3f, density * 1.3f)
        if (t.trackDeg != null) {
            canvas.rotate(t.trackDeg!!.toFloat())
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
        } else {
            canvas.drawCircle(0f, 0f, 6f, fill)
            canvas.drawCircle(0f, 0f, 6f, outline)
        }
        canvas.restore()

        // Labels stay upright when the map is rotated (track-up).
        val text = when {
            relAlt != null -> String.format(Locale.US, "%+03d", (relAlt / 100.0).roundToInt()) + vsArrow(t)
            t.pressureAltitudeFt != null -> String.format(Locale.US, "%03d", (t.pressureAltitudeFt!! / 100.0).roundToInt()) + vsArrow(t)
            else -> ""
        }
        val name = t.callsign
        canvas.save()
        canvas.rotate(-projection.orientation, x, y)
        if (text.isNotEmpty()) canvas.drawText(text, x + 11 * density, y + 4 * density, label)
        if (name.isNotEmpty()) canvas.drawText(name, x + 11 * density, y + 17 * density, label)
        canvas.restore()
    }

    private fun vsArrow(t: TrafficTarget): String {
        val vs = t.verticalSpeedFpm ?: return ""
        return when {
            vs > 500 -> "↑"
            vs < -500 -> "↓"
            else -> ""
        }
    }

    private fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * 60.0
        val dLon = (lon2 - lon1) * 60.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLon * dLon)
    }
}
