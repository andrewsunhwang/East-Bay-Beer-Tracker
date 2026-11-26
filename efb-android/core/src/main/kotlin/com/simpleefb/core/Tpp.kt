package com.simpleefb.core

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** An airport listed in the FAA d-TPP metafile. */
data class TppAirport(
    val faaId: String,
    val icaoId: String,
    val name: String,
    val city: String,
    val state: String,
    val military: Boolean,
)

/** One chart (PDF) from the FAA d-TPP metafile. */
data class TppChart(
    val seq: Int,
    val code: String,
    val name: String,
    val pdfName: String,
    val userAction: String,
    val amendment: String,
) {
    val category: ChartCategory get() = ChartCategory.forTppCode(code)
}

data class TppAirportCharts(val airport: TppAirport, val charts: List<TppChart>)

data class TppHeader(val cycle: String, val fromDate: String, val toDate: String)

enum class ChartCategory(val label: String) {
    SUPPLEMENT("Chart Supplement"),
    AIRPORT("Airport Diagram & Hot Spots"),
    MINIMUMS("Takeoff Minimums, ODPs & Alternate Minimums"),
    DEPARTURE("Departures (SIDs & ODPs)"),
    ARRIVAL("Arrivals (STARs)"),
    APPROACH("Approaches"),
    OTHER("Other");

    companion object {
        /**
         * Maps the d-TPP `chart_code` to a category. The FAA has announced STAR may become STR,
         * so both are accepted.
         */
        fun forTppCode(code: String): ChartCategory = when (code.trim().uppercase()) {
            "APD", "HOT", "LAH" -> AIRPORT
            "MIN" -> MINIMUMS
            "DP", "ODP", "DAU" -> DEPARTURE
            "STAR", "STR" -> ARRIVAL
            "IAP" -> APPROACH
            else -> OTHER
        }
    }
}

object TppUrls {
    const val BASE = "https://aeronav.faa.gov/d-tpp"
    fun metafile(cycleIdent: String) = "$BASE/$cycleIdent/xml_data/d-tpp_Metafile.xml"
    fun pdf(cycleIdent: String, pdfName: String) = "$BASE/$cycleIdent/$pdfName"
}

/**
 * Streaming parser for `d-tpp_Metafile.xml` (about 15 MB). It works with both `<tag/>` and
 * `<tag></tag>` forms, which the FAA switched between in 2026.
 *
 * Structure:
 * ```
 * <digital_tpp cycle="2612" from_edate="0901Z  11/26/26" to_edate="0901Z  12/24/26">
 *   <state_code ID="CA" state_fullname="California">
 *     <city_name ID="OAKLAND" volume="SW-2">
 *       <airport_name ID="METRO OAKLAND INTL" military="N" apt_ident="OAK" icao_ident="KOAK" alnum="...">
 *         <record>
 *           <chartseq>10100</chartseq><chart_code>MIN</chart_code><chart_name>TAKEOFF MINIMUMS</chart_name>
 *           <useraction></useraction><pdf_name>SW2TO.PDF</pdf_name> ... <amdtnum></amdtnum> ...
 *         </record>
 * ```
 */
class TppMetafileParser(private val newParser: () -> XmlPullParser) {

    fun parse(
        input: InputStream,
        onHeader: (TppHeader) -> Unit = {},
        onAirport: (TppAirportCharts) -> Unit,
    ) {
        val p = newParser()
        p.setInput(input, null)
        var state = ""
        var city = ""
        var airport: TppAirport? = null
        val charts = ArrayList<TppChart>()
        var rec: MutableMap<String, String>? = null

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (val tag = p.name) {
                    "digital_tpp" -> onHeader(
                        TppHeader(
                            cycle = p.attr("cycle"),
                            fromDate = p.attr("from_edate"),
                            toDate = p.attr("to_edate"),
                        )
                    )
                    "state_code" -> state = p.attr("ID")
                    "city_name" -> city = p.attr("ID")
                    "airport_name" -> {
                        charts.clear()
                        airport = TppAirport(
                            faaId = p.attr("apt_ident").trim().uppercase(),
                            icaoId = p.attr("icao_ident").trim().uppercase(),
                            name = p.attr("ID").trim(),
                            city = city.trim(),
                            state = state.trim(),
                            military = p.attr("military").trim().equals("Y", ignoreCase = true),
                        )
                    }
                    "record" -> rec = HashMap()
                    else -> {
                        val r = rec
                        if (r != null) {
                            r[tag] = p.nextText().trim()
                            // nextText() leaves the parser on the END_TAG of this element.
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "record" -> {
                        val r = rec
                        rec = null
                        if (r != null) toChart(r)?.let(charts::add)
                    }
                    "airport_name" -> {
                        val a = airport
                        if (a != null && a.faaId.isNotEmpty()) {
                            onAirport(TppAirportCharts(a, charts.sortedBy { it.seq }))
                        }
                        airport = null
                        charts.clear()
                    }
                }
            }
            event = p.next()
        }
    }

    private fun toChart(r: Map<String, String>): TppChart? {
        val pdf = r["pdf_name"].orEmpty()
        val action = r["useraction"].orEmpty().uppercase()
        if (pdf.isEmpty() || action == "D" || pdf.startsWith("DELETED", ignoreCase = true)) return null
        return TppChart(
            seq = r["chartseq"]?.toIntOrNull() ?: Int.MAX_VALUE,
            code = r["chart_code"].orEmpty().uppercase(),
            name = r["chart_name"].orEmpty(),
            pdfName = pdf,
            userAction = action,
            amendment = r["amdtnum"].orEmpty(),
        )
    }

    private fun XmlPullParser.attr(name: String): String = getAttributeValue(null, name).orEmpty()
}
