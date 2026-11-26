package com.simpleefb.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A publication cycle. [ident] is the FAA/AIRAC style "YYNN" number of the cycle that starts
 * on [effective] (for example "2612" for 26 Nov 2026).
 */
data class Cycle(val ident: String, val effective: LocalDate, val lengthDays: Int) {
    val expires: LocalDate get() = effective.plusDays(lengthDays.toLong())

    /** FAA products become effective at 0901Z on the effective date. */
    val effectiveInstant: Instant get() = effective.atTime(9, 1).toInstant(ZoneOffset.UTC)
    val expiresInstant: Instant get() = expires.atTime(9, 1).toInstant(ZoneOffset.UTC)

    fun contains(instant: Instant): Boolean =
        !instant.isBefore(effectiveInstant) && instant.isBefore(expiresInstant)

    /** e.g. "29OCT2026": the date format the FAA uses in Chart Supplement folder/file names. */
    val faaDate: String get() = effective.format(FAA_DATE).uppercase(Locale.US)

    /** e.g. "Nov 26, 2026". */
    val display: String get() = effective.format(DISPLAY_DATE)

    companion object {
        private val FAA_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMMyyyy", Locale.US)
        private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    }
}

/**
 * Publication-cycle arithmetic.
 *
 * - Terminal procedures (d-TPP: approach plates, SIDs, STARs, ODPs, airport diagrams) follow the
 *   28-day AIRAC cycle.
 * - VFR sectionals/TACs, IFR enroute charts and the Chart Supplement follow a 56-day cycle that
 *   starts on every other AIRAC date.
 */
object Cycles {
    /** AIRAC cycle 2001 became effective on 2 Jan 2020; every cycle since is exactly 28 days. */
    private val AIRAC_EPOCH: LocalDate = LocalDate.of(2020, 1, 2)

    /** A known 56-day chart/Chart Supplement edition date (FAA d-CS edition of 29 Oct 2026). */
    private val CHART_EDITION_ANCHOR: LocalDate = LocalDate.of(2026, 10, 29)

    private val SWITCH_OFFSET: Duration = Duration.ofHours(9).plusMinutes(1)

    /** The 28-day AIRAC cycle in effect at [instant] (cycles switch at 0901Z). */
    fun airacAt(instant: Instant): Cycle = airacContaining(dateForSwitch(instant))

    /** The 28-day AIRAC cycle whose effective period contains the calendar date [date]. */
    fun airacContaining(date: LocalDate): Cycle {
        val days = ChronoUnit.DAYS.between(AIRAC_EPOCH, date)
        val n = Math.floorDiv(days, 28L)
        val effective = AIRAC_EPOCH.plusDays(n * 28)
        return Cycle(identFor(effective), effective, 28)
    }

    fun nextAirac(cycle: Cycle): Cycle = airacContaining(cycle.effective.plusDays(28))
    fun previousAirac(cycle: Cycle): Cycle = airacContaining(cycle.effective.minusDays(28))

    /** The 56-day chart edition (sectionals, TACs, IFR enroute, Chart Supplement) in effect at [instant]. */
    fun chartEditionAt(instant: Instant): Cycle = chartEditionContaining(dateForSwitch(instant))

    fun chartEditionContaining(date: LocalDate): Cycle {
        val days = ChronoUnit.DAYS.between(CHART_EDITION_ANCHOR, date)
        val n = Math.floorDiv(days, 56L)
        val effective = CHART_EDITION_ANCHOR.plusDays(n * 56)
        return Cycle(identFor(effective), effective, 56)
    }

    fun nextChartEdition(cycle: Cycle): Cycle = chartEditionContaining(cycle.effective.plusDays(56))

    /** "YYNN": two-digit year plus the ordinal of the cycle within that year. */
    fun identFor(effective: LocalDate): String {
        val ordinal = (effective.dayOfYear - 1) / 28 + 1
        return String.format(Locale.US, "%02d%02d", effective.year % 100, ordinal)
    }

    private fun dateForSwitch(instant: Instant): LocalDate =
        instant.minus(SWITCH_OFFSET).atOffset(ZoneOffset.UTC).toLocalDate()
}
