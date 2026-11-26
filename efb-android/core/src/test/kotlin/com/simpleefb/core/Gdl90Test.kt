package com.simpleefb.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Gdl90Test {

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `heartbeat example from the GDL 90 spec has a valid CRC`() {
        val framed = hex("7E 00 81 41 DB D0 08 02 B3 8B 7E")
        val got = ArrayList<ByteArray>()
        var crcErrors = 0
        Gdl90Framer({ got += it }, { crcErrors++ }).feed(framed)
        assertEquals(0, crcErrors)
        assertEquals(1, got.size)
        val hb = Gdl90.decode(got[0]) as Gdl90Message.Heartbeat
        assertTrue(hb.gpsPositionValid)
        assertTrue(hb.uatInitialized)
        assertEquals(0xD0DB, hb.utcSecondsOfDay)
    }

    @Test
    fun `traffic report example from the GDL 90 spec decodes`() {
        val msg = hex("14 00 AB 45 49 1F EF 15 A8 89 78 0F 09 A9 07 B0 01 20 01 4E 38 32 35 56 20 20 20 00")
        val r = (Gdl90.decode(msg) as Gdl90Message.Traffic).report
        assertEquals(0xAB4549, r.address)
        assertEquals(44.90708, r.latitude, 1e-4)
        assertEquals(-122.99488, r.longitude, 1e-4)
        assertEquals(5000, r.pressureAltitudeFt)
        assertTrue(r.airborne)
        assertEquals(1, r.trackType)
        assertEquals(10, r.nic)
        assertEquals(9, r.nacp)
        assertEquals(123, r.horizontalVelocityKt)
        assertEquals(64, r.verticalVelocityFpm)
        assertEquals(45.0, r.trackDeg!!, 1e-9)
        assertEquals(1, r.emitterCategory)
        assertEquals("N825V", r.callsign)
    }

    @Test
    fun `byte stuffing round-trips through framer`() {
        // Message containing both flag and escape bytes.
        val msg = byteArrayOf(0x65, 0x7E, 0x7D, 0x01, 0x7E)
        val framed = Gdl90Framer.frame(msg)
        // Only the first and last bytes may be flags.
        assertTrue(framed.drop(1).dropLast(1).none { it == 0x7E.toByte() })
        val got = ArrayList<ByteArray>()
        Gdl90Framer({ got += it }).feed(framed)
        assertEquals(msg.toList(), got.single().toList())
    }

    @Test
    fun `corrupted frame is rejected and stream resyncs`() {
        val good = Gdl90Framer.frame(hex("00 81 41 DB D0 08 02"))
        val bad = good.copyOf().also { it[3] = (it[3] + 1).toByte() }
        val got = ArrayList<ByteArray>()
        var crcErrors = 0
        val framer = Gdl90Framer({ got += it }, { crcErrors++ })
        // Garbage, a corrupted frame, then a good frame split across two reads.
        framer.feed(byteArrayOf(1, 2, 3) + bad)
        framer.feed(good, 0, 4)
        framer.feed(good, 4, good.size - 4)
        assertEquals(1, crcErrors)
        assertEquals(1, got.size)
    }

    @Test
    fun `ownship stream produces fixes with geometric altitude`() {
        val ownship = hex("0A 00 AB 45 49 1F EF 15 A8 89 78 0F 09 A9 07 B0 01 20 01 4E 38 32 35 56 20 20 20 00")
        // Geo altitude 5500 ft = 1100 * 5 = 0x044C
        val geo = hex("0B 04 4C 00 0A")
        val fixes = ArrayList<OwnshipFix>()
        val dec = StreamDecoder("test", clock = { 1000L }, onOwnship = { fixes += it })
        dec.feed(Gdl90Framer.frame(geo) + Gdl90Framer.frame(ownship))
        val f = fixes.single()
        assertEquals(44.90708, f.latitude, 1e-4)
        assertEquals(5500, f.gpsAltitudeFt)
        assertEquals(5000, f.pressureAltitudeFt)
        assertEquals(123.0, f.groundSpeedKt!!, 1e-9)
        assertEquals(45.0, f.trackDeg!!, 1e-9)
        assertEquals("GDL 90", dec.stats.protocol)
    }

    @Test
    fun `ownship with no position is ignored`() {
        val noPos = hex("0A 00 AB 45 49 00 00 00 00 00 00 0F 09 09 07 B0 01 20 01 4E 38 32 35 56 20 20 20 00")
        val fixes = ArrayList<OwnshipFix>()
        StreamDecoder("t", onOwnship = { fixes += it }).feed(Gdl90Framer.frame(noPos))
        assertTrue(fixes.isEmpty())
    }

    @Test
    fun `invalid altitude and velocity markers decode to null`() {
        val msg = hex("14 00 AB 45 49 1F EF 15 A8 89 78 FF F8 A9 FF F8 00 00 01 4E 38 32 35 56 20 20 20 00")
        val r = (Gdl90.decode(msg) as Gdl90Message.Traffic).report
        assertNull(r.pressureAltitudeFt)
        assertNull(r.horizontalVelocityKt)
        assertNull(r.verticalVelocityFpm)
        assertNull(r.trackDeg) // track type 0 = invalid
        assertNotNull(r.callsign)
    }
}
