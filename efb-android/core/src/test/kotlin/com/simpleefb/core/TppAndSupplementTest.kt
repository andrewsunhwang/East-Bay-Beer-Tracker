package com.simpleefb.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class TppAndSupplementTest {

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<digital_tpp cycle="2612" from_edate="0901Z  11/26/26" to_edate="0901Z  12/24/26">
  <state_code ID="CA" state_fullname="California">
    <city_name ID="OAKLAND" volume="SW-2">
      <airport_name ID="METRO OAKLAND INTL" military="N" apt_ident="OAK" icao_ident="KOAK" alnum="356">
        <record>
          <chartseq>10100</chartseq><chart_code>MIN</chart_code><chart_name>TAKEOFF MINIMUMS</chart_name>
          <useraction></useraction><pdf_name>SW2TO.PDF</pdf_name><cn_flg>N</cn_flg><faanfd18/>
          <amdtnum></amdtnum>
        </record>
        <record>
          <chartseq>70000</chartseq><chart_code>APD</chart_code><chart_name>AIRPORT DIAGRAM</chart_name>
          <useraction/><pdf_name>00375AD.PDF</pdf_name><amdtnum/>
        </record>
        <record>
          <chartseq>50750</chartseq><chart_code>IAP</chart_code><chart_name>ILS OR LOC RWY 30</chart_name>
          <useraction>C</useraction><pdf_name>00375IL30.PDF</pdf_name><amdtnum>ORIG-A</amdtnum>
        </record>
        <record>
          <chartseq>60000</chartseq><chart_code>DP</chart_code><chart_name>OAKLAND SEVEN</chart_name>
          <useraction></useraction><pdf_name>00375OAKLAND.PDF</pdf_name><amdtnum></amdtnum>
        </record>
        <record>
          <chartseq>61000</chartseq><chart_code>STR</chart_code><chart_name>SILCN THREE</chart_name>
          <useraction></useraction><pdf_name>00375SILCN.PDF</pdf_name><amdtnum></amdtnum>
        </record>
        <record>
          <chartseq>50800</chartseq><chart_code>IAP</chart_code><chart_name>OLD APPROACH</chart_name>
          <useraction>D</useraction><pdf_name>DELETED_JOB.PDF</pdf_name><amdtnum></amdtnum>
        </record>
      </airport_name>
    </city_name>
  </state_code>
  <state_code ID="NV" state_fullname="Nevada">
    <city_name ID="RENO" volume="SW-1">
      <airport_name ID="RENO/TAHOE INTL" military="N" apt_ident="RNO" icao_ident="KRNO" alnum="1">
        <record><chartseq>1</chartseq><chart_code>ODP</chart_code><chart_name>RENO ODP</chart_name>
        <useraction></useraction><pdf_name>00999RNO.PDF</pdf_name></record>
      </airport_name>
    </city_name>
  </state_code>
</digital_tpp>"""

    @Test
    fun parsesMetafile() {
        val airports = ArrayList<TppAirportCharts>()
        var header: TppHeader? = null
        TppMetafileParser { KXmlParser() }.parse(xml.byteInputStream(), { header = it }) { airports += it }

        assertEquals("2612", header!!.cycle)
        assertEquals(2, airports.size)
        val oak = airports[0]
        assertEquals("OAK", oak.airport.faaId)
        assertEquals("KOAK", oak.airport.icaoId)
        assertEquals("METRO OAKLAND INTL", oak.airport.name)
        assertEquals("OAKLAND", oak.airport.city)
        assertEquals("CA", oak.airport.state)
        assertFalse(oak.airport.military)
        // Deleted record dropped, others sorted by chartseq.
        assertEquals(listOf("SW2TO.PDF", "00375IL30.PDF", "00375OAKLAND.PDF", "00375SILCN.PDF", "00375AD.PDF"),
            oak.charts.map { it.pdfName })
        assertEquals(ChartCategory.MINIMUMS, oak.charts[0].category)
        assertEquals(ChartCategory.APPROACH, oak.charts[1].category)
        assertEquals("ORIG-A", oak.charts[1].amendment)
        assertEquals(ChartCategory.DEPARTURE, oak.charts[2].category)
        assertEquals(ChartCategory.ARRIVAL, oak.charts[3].category)
        assertEquals(ChartCategory.AIRPORT, oak.charts[4].category)

        assertEquals(ChartCategory.DEPARTURE, airports[1].charts.single().category)
        assertEquals("NV", airports[1].airport.state)
    }

    @Test
    fun urls() {
        assertEquals("https://aeronav.faa.gov/d-tpp/2612/xml_data/d-tpp_Metafile.xml", TppUrls.metafile("2612"))
        assertEquals("https://aeronav.faa.gov/d-tpp/2612/00375AD.PDF", TppUrls.pdf("2612", "00375AD.PDF"))
    }

    @Test
    fun extractsSupplementLinks() {
        val html = """
            <table><tr><td>OAKLAND</td><td>METRO OAKLAND INTL</td><td>OAK</td>
            <td><a href="https://aeronav.faa.gov/afd/29oct2026/sw_268_29OCT2026.pdf">268</a>
                <a href='https://aeronav.faa.gov/afd/29oct2026/sw_269_29OCT2026.pdf'>269</a></td>
            <td><a href="https://aeronav.faa.gov/d-tpp/2611/00375AD.PDF">Diagram</a></td>
            <td><a href="/afd/29oct2026/sw_268_29OCT2026.pdf">dup relative</a></td></tr></table>"""
        val links = ChartSupplement.extractPageLinks(html, "https://www.faa.gov/x/results/?ident=OAK")
        assertEquals(
            listOf(
                "https://aeronav.faa.gov/afd/29oct2026/sw_268_29OCT2026.pdf",
                "https://aeronav.faa.gov/afd/29oct2026/sw_269_29OCT2026.pdf",
                "https://www.faa.gov/afd/29oct2026/sw_268_29OCT2026.pdf",
            ),
            links,
        )
        assertEquals("sw_268_29OCT2026.pdf", ChartSupplement.fileName(links[0]))
        val urls = ChartSupplement.searchUrls("oak", Cycles.chartEditionContaining(java.time.LocalDate.of(2026, 11, 26)))
        assertTrue(urls[0].endsWith("?cycle=2611&ident=OAK"))
        assertTrue(urls[1].endsWith("?ident=OAK"))
    }

    @Test
    fun keepsOnlyAirportPagesAndNotices() {
        // Links as returned by the live FAA search page for OAK (Nov 2026).
        val links = listOf(
            "http://aeronav.faa.gov/afd/29oct2026/SW_rear_29oct2026.pdf",
            "http://aeronav.faa.gov/afd/29oct2026/sw_224_29OCT2026.pdf",
            "http://aeronav.faa.gov/afd/29oct2026/sw_OAK_notices_29OCT2026.pdf",
            "http://aeronav.faa.gov/afd/29oct2026/SW_front_29oct2026.pdf",
            "http://aeronav.faa.gov/afd/29oct2026/ak_front_29oct2026.pdf",
            "http://aeronav.faa.gov/afd/29oct2026/pac_front_29oct2026.pdf",
            "http://aeronav.faa.gov/afd/29oct2026/SW_hotspot.pdf",
            "http://aeronav.faa.gov/afd/29oct2026/all_hotspot.pdf",
        )
        assertEquals(
            listOf(
                "https://aeronav.faa.gov/afd/29oct2026/sw_224_29OCT2026.pdf",
                "https://aeronav.faa.gov/afd/29oct2026/sw_OAK_notices_29OCT2026.pdf",
            ),
            ChartSupplement.airportPages(links),
        )
        assertEquals(emptyList<String>(), ChartSupplement.airportPages(links.filter { "224" !in it }))
    }

    @Test
    fun parsesSupplementIndex() {
        val idx = """<airports><location state="CALIFORNIA">
            <airport><aptname>METRO OAKLAND INTL</aptname><aptcity>OAKLAND</aptcity><aptid>OAK</aptid>
              <navidname/><pages><pdf>sw_268_29OCT2026.pdf</pdf><pdf>sw_269_29OCT2026.pdf</pdf></pages></airport>
            <airport><aptname>X</aptname><aptid>O88</aptid><pages><pdf>sw_100_29OCT2026.pdf</pdf></pages></airport>
            </location></airports>"""
        val got = LinkedHashMap<String, List<String>>()
        ChartSupplement.parseIndex(idx.byteInputStream(), { KXmlParser() }) { id, pdfs -> got[id] = pdfs }
        assertEquals(listOf("sw_268_29OCT2026.pdf", "sw_269_29OCT2026.pdf"), got["OAK"])
        assertEquals(listOf("sw_100_29OCT2026.pdf"), got["O88"])
    }
}
