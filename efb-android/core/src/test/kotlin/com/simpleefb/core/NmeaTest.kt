package com.simpleefb.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaTest {
    private val rmc = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
    private val gga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"

    @Test
    fun checksums() {
        assertTrue(Nmea.checksumOk(rmc))
        assertTrue(Nmea.checksumOk(gga))
        assertFalse(Nmea.checksumOk(rmc.replace("*6A", "*6B")))
        assertFalse(Nmea.checksumOk("\$GPRMC,123519,A"))
    }

    @Test
    fun parsesRmc() {
        val s = Nmea.parse(rmc) as NmeaSentence.Rmc
        assertTrue(s.valid)
        assertEquals(48.1173, s.latitude!!, 1e-4)
        assertEquals(11.516667, s.longitude!!, 1e-4)
        assertEquals(22.4, s.speedKt!!, 1e-9)
        assertEquals(84.4, s.courseDeg!!, 1e-9)
    }

    @Test
    fun parsesGga() {
        val s = Nmea.parse(gga) as NmeaSentence.Gga
        assertEquals(1, s.fixQuality)
        assertEquals(8, s.satellites)
        assertEquals(545.4, s.altitudeMslM!!, 1e-9)
    }

    @Test
    fun hemispheres() {
        assertEquals(-37.72, Nmea.coord("3743.200", "S")!!, 1e-6)
        assertEquals(-122.22, Nmea.coord("12213.200", "W")!!, 1e-6)
        assertNull(Nmea.coord("", "N"))
    }

    @Test
    fun streamDecoderUsesNmea() {
        val fixes = ArrayList<OwnshipFix>()
        val dec = StreamDecoder("bt", onOwnship = { fixes += it })
        val bytes = ("noise\r\n$gga\r\n$rmc\r\n").toByteArray()
        // Feed in small chunks, as a Bluetooth socket would deliver them.
        bytes.toList().chunked(7).forEach { dec.feed(it.toByteArray()) }
        val f = fixes.single()
        assertEquals(48.1173, f.latitude, 1e-4)
        assertEquals(1789, f.gpsAltitudeFt) // 545.4 m
        assertEquals("NMEA 0183", dec.stats.protocol)
    }
}
