package com.simpleefb.core

/** Minimal NMEA 0183 support for Bluetooth GPS receivers (RMC, GGA, Garmin PGRMZ). */
sealed interface NmeaSentence {
    data class Rmc(
        val valid: Boolean,
        val latitude: Double?,
        val longitude: Double?,
        val speedKt: Double?,
        val courseDeg: Double?,
    ) : NmeaSentence

    data class Gga(
        val fixQuality: Int,
        val satellites: Int?,
        val latitude: Double?,
        val longitude: Double?,
        val altitudeMslM: Double?,
    ) : NmeaSentence

    /** Garmin proprietary altitude sentence, feet. */
    data class Pgrmz(val altitudeFt: Int) : NmeaSentence
}

object Nmea {
    /** True if the sentence's `*hh` checksum matches (sentences without a checksum are rejected). */
    fun checksumOk(sentence: String): Boolean {
        val s = sentence.trim()
        if (!s.startsWith("$")) return false
        val star = s.lastIndexOf('*')
        if (star < 0 || star + 3 > s.length) return false
        var sum = 0
        for (i in 1 until star) sum = sum xor s[i].code
        val given = s.substring(star + 1, star + 3).toIntOrNull(16) ?: return false
        return sum == given
    }

    fun parse(sentence: String): NmeaSentence? {
        val s = sentence.trim()
        if (!checksumOk(s)) return null
        val f = s.substring(1, s.lastIndexOf('*')).split(',')
        if (f.isEmpty() || f[0].length < 3) return null
        val type = f[0]
        return when {
            type.endsWith("RMC") && f.size >= 9 -> NmeaSentence.Rmc(
                valid = f[2] == "A",
                latitude = coord(f[3], f[4]),
                longitude = coord(f[5], f[6]),
                speedKt = f[7].toDoubleOrNull(),
                courseDeg = f[8].toDoubleOrNull(),
            )
            type.endsWith("GGA") && f.size >= 10 -> NmeaSentence.Gga(
                fixQuality = f[6].toIntOrNull() ?: 0,
                satellites = f[7].toIntOrNull(),
                latitude = coord(f[2], f[3]),
                longitude = coord(f[4], f[5]),
                altitudeMslM = f[9].toDoubleOrNull(),
            )
            type == "PGRMZ" && f.size >= 3 -> f[1].toDoubleOrNull()?.let {
                val ft = if (f[2].equals("m", ignoreCase = true)) it * 3.28084 else it
                NmeaSentence.Pgrmz(ft.toInt())
            }
            else -> null
        }
    }

    /** "3743.2000","N" -> 37.72; "12213.2000","W" -> -122.22 */
    fun coord(value: String, hemi: String): Double? {
        if (value.isEmpty() || hemi.isEmpty()) return null
        val dot = value.indexOf('.').let { if (it < 0) value.length else it }
        if (dot < 3) return null
        val deg = value.substring(0, dot - 2).toIntOrNull() ?: return null
        val min = value.substring(dot - 2).toDoubleOrNull() ?: return null
        val v = deg + min / 60.0
        return when (hemi.uppercase()) {
            "S", "W" -> -v
            "N", "E" -> v
            else -> null
        }
    }
}

/** Collects `$...` lines out of a byte stream. */
class NmeaLineAssembler(private val onSentence: (String) -> Unit) {
    private val sb = StringBuilder()
    private var active = false

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        for (i in offset until offset + length) {
            val c = (data[i].toInt() and 0xFF)
            when {
                c == '$'.code -> { sb.setLength(0); sb.append('$'); active = true }
                !active -> Unit
                c == '\r'.code || c == '\n'.code -> {
                    if (sb.length > 6) onSentence(sb.toString())
                    sb.setLength(0)
                    active = false
                }
                c < 0x20 || c > 0x7E || sb.length > 120 -> { sb.setLength(0); active = false }
                else -> sb.append(c.toChar())
            }
        }
    }
}
