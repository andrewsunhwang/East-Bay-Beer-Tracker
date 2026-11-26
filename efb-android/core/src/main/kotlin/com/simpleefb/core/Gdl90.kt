package com.simpleefb.core

/**
 * GDL 90 decoding, per the public "GDL 90 Data Interface Specification" (560-1058-00 Rev A).
 * Used by most portable ADS-B receivers over Wi-Fi (UDP port 4000), and by some over Bluetooth.
 */
object Gdl90Crc {
    private val table = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var crc = i shl 8
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            t[i] = crc and 0xFFFF
        }
    }

    /** CRC-16-CCITT as defined in the GDL 90 spec (computed over message ID + data, unescaped). */
    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = (table[crc ushr 8] xor (crc shl 8) xor (data[i].toInt() and 0xFF)) and 0xFFFF
        }
        return crc
    }
}

/**
 * Splits a byte stream into GDL 90 messages: flag bytes (0x7E), byte un-stuffing (0x7D escape),
 * and CRC check. [onMessage] receives the message ID + data with the CRC removed.
 */
class Gdl90Framer(
    private val onMessage: (ByteArray) -> Unit,
    private val onCrcError: () -> Unit = {},
) {
    private val buf = ByteArray(2048)
    private var len = 0
    private var inFrame = false
    private var escape = false

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            if (b == 0x7E) {
                if (inFrame && len >= 3) finishFrame()
                len = 0
                escape = false
                inFrame = true
                continue
            }
            if (!inFrame) continue
            if (b == 0x7D) {
                escape = true
                continue
            }
            val v = if (escape) b xor 0x20 else b
            escape = false
            if (len < buf.size) {
                buf[len++] = v.toByte()
            } else {
                // Oversized: not a GDL 90 frame. Wait for the next flag byte.
                len = 0
                inFrame = false
            }
        }
    }

    private fun finishFrame() {
        val crcRx = (buf[len - 2].toInt() and 0xFF) or ((buf[len - 1].toInt() and 0xFF) shl 8)
        if (Gdl90Crc.compute(buf, 0, len - 2) == crcRx) {
            onMessage(buf.copyOf(len - 2))
        } else {
            onCrcError()
        }
    }

    companion object {
        /** Builds a framed GDL 90 message (used by tests and simulators). */
        fun frame(message: ByteArray): ByteArray {
            val crc = Gdl90Crc.compute(message)
            val clear = message + byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8).toByte())
            val out = ArrayList<Byte>(clear.size + 8)
            out += 0x7E.toByte()
            for (x in clear) {
                val v = x.toInt() and 0xFF
                if (v == 0x7E || v == 0x7D) {
                    out += 0x7D.toByte()
                    out += (v xor 0x20).toByte()
                } else {
                    out += x
                }
            }
            out += 0x7E.toByte()
            return out.toByteArray()
        }
    }
}

/** Ownship and Traffic Reports share this layout (GDL 90 spec section 3.5.1). */
data class Gdl90TargetReport(
    val alertStatus: Int,
    val addressType: Int,
    val address: Int,
    val latitude: Double,
    val longitude: Double,
    val positionValid: Boolean,
    /** Pressure altitude (29.92 inHg reference), feet. */
    val pressureAltitudeFt: Int?,
    val airborne: Boolean,
    val extrapolated: Boolean,
    /** 0 = invalid, 1 = true track, 2 = magnetic heading, 3 = true heading. */
    val trackType: Int,
    val nic: Int,
    val nacp: Int,
    val horizontalVelocityKt: Int?,
    val verticalVelocityFpm: Int?,
    val trackDeg: Double?,
    val emitterCategory: Int,
    val callsign: String,
    val emergencyCode: Int,
)

sealed interface Gdl90Message {
    data class Heartbeat(
        val gpsPositionValid: Boolean,
        val uatInitialized: Boolean,
        val utcSecondsOfDay: Int?,
    ) : Gdl90Message

    data class Ownship(val report: Gdl90TargetReport) : Gdl90Message
    data class Traffic(val report: Gdl90TargetReport) : Gdl90Message

    /** Height above the WGS-84 ellipsoid, feet. */
    data class OwnshipGeoAltitude(val altitudeFt: Int, val verticalWarning: Boolean, val vfomMeters: Int?) :
        Gdl90Message

    /** ForeFlight extension ID message (0x65 / sub-ID 0), sent by many receivers. */
    data class DeviceId(val shortName: String, val longName: String) : Gdl90Message

    data class Other(val id: Int, val length: Int) : Gdl90Message
}

object Gdl90 {
    const val ID_HEARTBEAT = 0x00
    const val ID_OWNSHIP = 0x0A
    const val ID_GEO_ALTITUDE = 0x0B
    const val ID_TRAFFIC = 0x14
    const val ID_FOREFLIGHT = 0x65

    fun decode(m: ByteArray): Gdl90Message {
        if (m.isEmpty()) return Gdl90Message.Other(-1, 0)
        val id = m.u(0)
        return when {
            id == ID_HEARTBEAT && m.size >= 7 -> {
                val s1 = m.u(1)
                val s2 = m.u(2)
                val ts = ((s2 and 0x80) shl 9) or (m.u(4) shl 8) or m.u(3)
                Gdl90Message.Heartbeat(
                    gpsPositionValid = s1 and 0x80 != 0,
                    uatInitialized = s1 and 0x01 != 0,
                    utcSecondsOfDay = ts.takeIf { it < 86400 },
                )
            }
            id == ID_OWNSHIP && m.size >= 28 -> Gdl90Message.Ownship(targetReport(m))
            id == ID_TRAFFIC && m.size >= 28 -> Gdl90Message.Traffic(targetReport(m))
            id == ID_GEO_ALTITUDE && m.size >= 5 -> {
                val alt = ((m.u(1) shl 8) or m.u(2)).toShort().toInt() * 5
                val metrics = (m.u(3) shl 8) or m.u(4)
                val vfom = metrics and 0x7FFF
                Gdl90Message.OwnshipGeoAltitude(alt, metrics and 0x8000 != 0, vfom.takeIf { it != 0x7FFF })
            }
            id == ID_FOREFLIGHT && m.size >= 35 && m.u(1) == 0 -> Gdl90Message.DeviceId(
                shortName = ascii(m, 11, 8),
                longName = ascii(m, 19, 16),
            )
            else -> Gdl90Message.Other(id, m.size)
        }
    }

    private fun targetReport(m: ByteArray): Gdl90TargetReport {
        // Offsets below are relative to the message ID at index 0.
        val lat = s24(m, 5) * 180.0 / 8388608.0
        val lon = s24(m, 8) * 180.0 / 8388608.0
        val ddd = (m.u(11) shl 4) or (m.u(12) ushr 4)
        val misc = m.u(12) and 0x0F
        val nic = m.u(13) ushr 4
        val nacp = m.u(13) and 0x0F
        val hvel = (m.u(14) shl 4) or (m.u(15) ushr 4)
        var vvel = ((m.u(15) and 0x0F) shl 8) or m.u(16)
        val vvelValid = vvel != 0x800
        if (vvel and 0x800 != 0) vvel -= 0x1000
        val trackType = misc and 0x03
        return Gdl90TargetReport(
            alertStatus = m.u(1) ushr 4,
            addressType = m.u(1) and 0x0F,
            address = (m.u(2) shl 16) or (m.u(3) shl 8) or m.u(4),
            latitude = lat,
            longitude = lon,
            positionValid = !(lat == 0.0 && lon == 0.0 && nic == 0),
            pressureAltitudeFt = if (ddd == 0xFFF) null else ddd * 25 - 1000,
            airborne = misc and 0x08 != 0,
            extrapolated = misc and 0x04 != 0,
            trackType = trackType,
            nic = nic,
            nacp = nacp,
            horizontalVelocityKt = hvel.takeIf { it != 0xFFF },
            verticalVelocityFpm = if (vvelValid) vvel * 64 else null,
            trackDeg = if (trackType == 0) null else m.u(17) * 360.0 / 256.0,
            emitterCategory = m.u(18),
            callsign = ascii(m, 19, 8),
            emergencyCode = m.u(27) ushr 4,
        )
    }

    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

    private fun s24(m: ByteArray, i: Int): Int {
        var v = (m.u(i) shl 16) or (m.u(i + 1) shl 8) or m.u(i + 2)
        if (v and 0x800000 != 0) v -= 0x1000000
        return v
    }

    private fun ascii(m: ByteArray, start: Int, len: Int): String {
        val sb = StringBuilder()
        for (i in start until minOf(m.size, start + len)) {
            val c = m.u(i)
            if (c in 0x20..0x7E) sb.append(c.toChar())
        }
        return sb.toString().trim()
    }
}
