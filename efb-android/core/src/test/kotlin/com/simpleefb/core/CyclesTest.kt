package com.simpleefb.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CyclesTest {
    @Test
    fun knownAiracCycles() {
        assertEquals("2001", Cycles.airacContaining(LocalDate.of(2020, 1, 2)).ident)
        assertEquals("2513", Cycles.airacContaining(LocalDate.of(2025, 12, 25)).ident)
        assertEquals("2601", Cycles.airacContaining(LocalDate.of(2026, 1, 22)).ident)
        assertEquals("2608", Cycles.airacContaining(LocalDate.of(2026, 8, 6)).ident)
        assertEquals("2612", Cycles.airacContaining(LocalDate.of(2026, 11, 26)).ident)
        assertEquals("2613", Cycles.airacContaining(LocalDate.of(2026, 12, 24)).ident)
        assertEquals("2701", Cycles.airacContaining(LocalDate.of(2027, 1, 21)).ident)
        // Day before a cycle starts still belongs to the previous one.
        assertEquals("2611", Cycles.airacContaining(LocalDate.of(2026, 11, 25)).ident)
    }

    @Test
    fun switchesAt0901Z() {
        assertEquals("2611", Cycles.airacAt(Instant.parse("2026-11-26T09:00:00Z")).ident)
        assertEquals("2612", Cycles.airacAt(Instant.parse("2026-11-26T09:01:00Z")).ident)
    }

    @Test
    fun chartEditions() {
        val e = Cycles.chartEditionContaining(LocalDate.of(2026, 11, 26))
        assertEquals(LocalDate.of(2026, 10, 29), e.effective)
        assertEquals("2611", e.ident)
        assertEquals("29OCT2026", e.faaDate)
        assertEquals(LocalDate.of(2026, 12, 24), Cycles.nextChartEdition(e).effective)
        assertEquals(LocalDate.of(2026, 9, 3), Cycles.chartEditionContaining(LocalDate.of(2026, 10, 28)).effective)
        assertEquals(LocalDate.of(2025, 8, 7), Cycles.chartEditionContaining(LocalDate.of(2025, 8, 7)).effective)
    }

    @Test
    fun contains() {
        val c = Cycles.airacContaining(LocalDate.of(2026, 11, 26))
        assertTrue(c.contains(Instant.parse("2026-12-01T00:00:00Z")))
        assertFalse(c.contains(Instant.parse("2026-12-24T09:01:00Z")))
        assertEquals("2613", Cycles.nextAirac(c).ident)
        assertEquals("2611", Cycles.previousAirac(c).ident)
    }
}
