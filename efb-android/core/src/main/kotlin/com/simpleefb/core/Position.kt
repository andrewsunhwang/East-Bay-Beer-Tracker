package com.simpleefb.core

/** Ownship position from any source. */
data class OwnshipFix(
    val latitude: Double,
    val longitude: Double,
    /** GPS altitude in feet (MSL when the source provides it, otherwise ellipsoid height). */
    val gpsAltitudeFt: Int?,
    /** Pressure altitude in feet (from a transponder or ADS-B receiver). */
    val pressureAltitudeFt: Int?,
    val groundSpeedKt: Double?,
    /** True track over the ground, degrees. */
    val trackDeg: Double?,
    val verticalSpeedFpm: Int?,
    val timeMillis: Long,
    val source: String,
)

data class TrafficTarget(
    val address: Int,
    val callsign: String,
    val latitude: Double,
    val longitude: Double,
    val pressureAltitudeFt: Int?,
    val trackDeg: Double?,
    val groundSpeedKt: Int?,
    val verticalSpeedFpm: Int?,
    val airborne: Boolean,
    val timeMillis: Long,
)

/** Byte-level statistics, shown on the diagnostics screen to identify an unknown device's protocol. */
data class StreamStats(
    val bytes: Long = 0,
    val gdl90Messages: Long = 0,
    val gdl90CrcErrors: Long = 0,
    val nmeaSentences: Long = 0,
    val messageIds: Map<Int, Long> = emptyMap(),
    val deviceName: String? = null,
) {
    val protocol: String
        get() = when {
            gdl90Messages > 0 && gdl90Messages >= nmeaSentences -> "GDL 90"
            nmeaSentences > 0 -> "NMEA 0183"
            bytes > 0 -> "Unrecognized"
            else -> "No data"
        }
}

/**
 * Decodes a raw byte stream that may carry GDL 90 or NMEA (detected automatically) and turns it
 * into ownship fixes and traffic. Not thread-safe: feed from one thread.
 */
class StreamDecoder(
    private val sourceName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onOwnship: (OwnshipFix) -> Unit,
    private val onTraffic: (TrafficTarget) -> Unit = {},
) {
    var stats = StreamStats()
        private set

    private val ids = HashMap<Int, Long>()
    private var geoAltFt: Int? = null
    private var geoAltTime = 0L

    // NMEA state: RMC carries speed/track, GGA carries altitude.
    private var nmeaAltFt: Int? = null
    private var nmeaPressureAltFt: Int? = null

    private val framer = Gdl90Framer(
        onMessage = ::onGdl90,
        onCrcError = { stats = stats.copy(gdl90CrcErrors = stats.gdl90CrcErrors + 1) },
    )
    private val nmea = NmeaLineAssembler(::onNmea)

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        stats = stats.copy(bytes = stats.bytes + length)
        framer.feed(data, offset, length)
        nmea.feed(data, offset, length)
    }

    private fun onGdl90(m: ByteArray) {
        val id = m[0].toInt() and 0xFF
        ids[id] = (ids[id] ?: 0) + 1
        stats = stats.copy(gdl90Messages = stats.gdl90Messages + 1, messageIds = HashMap(ids))
        when (val msg = Gdl90.decode(m)) {
            is Gdl90Message.Heartbeat -> Unit
            is Gdl90Message.OwnshipGeoAltitude -> {
                geoAltFt = msg.altitudeFt
                geoAltTime = clock()
            }
            is Gdl90Message.Ownship -> {
                val r = msg.report
                if (!r.positionValid) return
                val now = clock()
                onOwnship(
                    OwnshipFix(
                        latitude = r.latitude,
                        longitude = r.longitude,
                        gpsAltitudeFt = geoAltFt.takeIf { now - geoAltTime < 5_000 },
                        pressureAltitudeFt = r.pressureAltitudeFt,
                        groundSpeedKt = r.horizontalVelocityKt?.toDouble(),
                        trackDeg = r.trackDeg.takeIf { r.trackType == 1 || r.trackType == 3 },
                        verticalSpeedFpm = r.verticalVelocityFpm,
                        timeMillis = now,
                        source = sourceName,
                    )
                )
            }
            is Gdl90Message.Traffic -> {
                val r = msg.report
                if (!r.positionValid) return
                onTraffic(
                    TrafficTarget(
                        address = r.address,
                        callsign = r.callsign,
                        latitude = r.latitude,
                        longitude = r.longitude,
                        pressureAltitudeFt = r.pressureAltitudeFt,
                        trackDeg = r.trackDeg,
                        groundSpeedKt = r.horizontalVelocityKt,
                        verticalSpeedFpm = r.verticalVelocityFpm,
                        airborne = r.airborne,
                        timeMillis = clock(),
                    )
                )
            }
            is Gdl90Message.DeviceId -> stats = stats.copy(deviceName = msg.longName.ifEmpty { msg.shortName })
            is Gdl90Message.Other -> Unit
        }
    }

    private fun onNmea(line: String) {
        val s = Nmea.parse(line) ?: return
        stats = stats.copy(nmeaSentences = stats.nmeaSentences + 1)
        when (s) {
            is NmeaSentence.Gga -> nmeaAltFt = if (s.fixQuality > 0) s.altitudeMslM?.let { (it * 3.28084).toInt() } else null
            is NmeaSentence.Pgrmz -> nmeaPressureAltFt = s.altitudeFt
            is NmeaSentence.Rmc -> {
                val lat = s.latitude
                val lon = s.longitude
                if (!s.valid || lat == null || lon == null) return
                onOwnship(
                    OwnshipFix(
                        latitude = lat,
                        longitude = lon,
                        gpsAltitudeFt = nmeaAltFt,
                        pressureAltitudeFt = nmeaPressureAltFt,
                        groundSpeedKt = s.speedKt,
                        trackDeg = s.courseDeg,
                        verticalSpeedFpm = null,
                        timeMillis = clock(),
                        source = sourceName,
                    )
                )
            }
        }
    }
}
