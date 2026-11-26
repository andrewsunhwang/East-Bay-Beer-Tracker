package com.simpleefb.core

import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder

/**
 * Locating Chart Supplement (formerly A/FD) pages.
 *
 * The FAA publishes the d-CS as one PDF per page, for example
 * `https://aeronav.faa.gov/afd/29oct2026/sw_268_29OCT2026.pdf`. The FAA's public d-CS search
 * page lists the page PDFs for an airport, so the app reads those links from it.
 */
object ChartSupplement {

    private const val SEARCH =
        "https://www.faa.gov/air_traffic/flight_info/aeronav/digital_products/dafd/search/results/"

    /**
     * Search-page URLs to try, in order, for [ident] in the given edition. With no cycle, the
     * FAA page shows the current edition.
     */
    fun searchUrls(ident: String, edition: Cycle?): List<String> {
        val id = URLEncoder.encode(ident.trim().uppercase(), "UTF-8")
        val urls = ArrayList<String>()
        if (edition != null) urls += "$SEARCH?cycle=${edition.ident}&ident=$id"
        urls += "$SEARCH?ident=$id"
        return urls
    }

    private val HREF = Regex("""href\s*=\s*["']([^"']+?\.pdf)["']""", RegexOption.IGNORE_CASE)

    /** Extracts the Chart Supplement page PDF links (`.../afd/...pdf`) from search-result HTML. */
    fun extractPageLinks(html: String, pageUrl: String): List<String> =
        HREF.findAll(html)
            .map { it.groupValues[1].replace("&amp;", "&").trim() }
            .mapNotNull { resolve(pageUrl, it) }
            .filter { it.contains("/afd/", ignoreCase = true) }
            .distinct()
            .toList()

    private val AIRPORT_PAGE = Regex("""/[a-z]{2,3}_\d+_\d{2}[a-z]{3}\d{4}\.pdf$""", RegexOption.IGNORE_CASE)
    private val NOTICES_PAGE = Regex("""/[a-z]{2,3}_[a-z0-9]+_notices_\d{2}[a-z]{3}\d{4}\.pdf$""", RegexOption.IGNORE_CASE)

    /**
     * From all the links on a search-result page, keeps the airport's own page(s) (e.g.
     * `sw_224_29OCT2026.pdf`) followed by its notices page (`sw_OAK_notices_29OCT2026.pdf`).
     * Front/rear matter and hot-spot booklets are dropped. Links are upgraded to https.
     */
    fun airportPages(links: List<String>): List<String> {
        val https = links.map { it.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://") }
        val pages = https.filter { AIRPORT_PAGE.containsMatchIn(it) }
        val notices = https.filter { NOTICES_PAGE.containsMatchIn(it) }
        return if (pages.isEmpty()) emptyList() else (pages + notices).distinct()
    }

    /** File name such as `sw_268_29OCT2026.pdf` from a page URL. */
    fun fileName(url: String): String = url.substringAfterLast('/').substringBefore('?')

    private fun resolve(base: String, href: String): String? = try {
        URI(base).resolve(href.replace(" ", "%20")).toString()
    } catch (e: Exception) {
        null
    }

    /**
     * Parses the d-CS XML index (`afd_<date>.xml`, shipped with the FAA's d-CS download):
     * ```
     * <airport><aptname>..</aptname><aptcity>..</aptcity><aptid>OAK</aptid>
     *   <pages><pdf>sw_268_29OCT2026.pdf</pdf></pages></airport>
     * ```
     */
    fun parseIndex(
        input: InputStream,
        newParser: () -> XmlPullParser,
        onAirport: (ident: String, pdfs: List<String>) -> Unit,
    ) {
        val p = newParser()
        p.setInput(input, null)
        var inAirport = false
        var ident = ""
        val pdfs = ArrayList<String>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name.lowercase()) {
                    "airport" -> { inAirport = true; ident = ""; pdfs.clear() }
                    "aptid" -> if (inAirport) ident = p.nextText().trim().uppercase()
                    "pdf" -> if (inAirport) p.nextText().trim().takeIf { it.isNotEmpty() }?.let(pdfs::add)
                }
                XmlPullParser.END_TAG -> if (p.name.equals("airport", ignoreCase = true)) {
                    if (ident.isNotEmpty() && pdfs.isNotEmpty()) onAirport(ident, pdfs.toList())
                    inAirport = false
                }
            }
            event = p.next()
        }
    }
}
