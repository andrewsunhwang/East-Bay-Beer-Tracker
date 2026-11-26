@file:OptIn(ExperimentalMaterial3Api::class)

package com.simpleefb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.simpleefb.AppGraph
import com.simpleefb.core.ChartCategory
import com.simpleefb.core.Cycle
import com.simpleefb.core.TppAirport
import com.simpleefb.core.TppChart
import com.simpleefb.store.ActiveCycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A chart plus its continuation pages ("…, CONT.1"), opened together. */
data class ChartGroup(val title: String, val category: ChartCategory, val charts: List<TppChart>) {
    val amendment: String get() = charts.first().amendment

    companion object {
        private val CONT = Regex("""^(.*?),\s*CONT\.\s*\d+$""", RegexOption.IGNORE_CASE)

        fun group(charts: List<TppChart>): List<ChartGroup> {
            val out = ArrayList<ChartGroup>()
            for (c in charts) {
                val base = CONT.find(c.name)?.groupValues?.get(1)
                if (base != null) {
                    val i = out.indexOfLast { it.title == base && it.category == c.category }
                    if (i >= 0) {
                        out[i] = out[i].copy(charts = out[i].charts + c)
                        continue
                    }
                }
                out += ChartGroup(c.name, c.category, listOf(c))
            }
            return out
        }
    }
}

@Composable
fun AirportScreen(graph: AppGraph, ident: String, onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val scope = rememberCoroutineScope()
    val repo = graph.repo
    var airport by remember { mutableStateOf<TppAirport?>(null) }
    var active by remember { mutableStateOf<ActiveCycle?>(null) }
    var groups by remember { mutableStateOf<List<ChartGroup>>(emptyList()) }
    var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var supplementCached by remember { mutableStateOf(false) }
    var favorite by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    val edition: Cycle = remember { repo.currentChartEdition() }

    LaunchedEffect(ident, refresh) {
        withContext(Dispatchers.IO) {
            val a = graph.db.airport(ident)
            val cycle = repo.activeTppCycle()
            val charts = if (a != null && cycle != null) graph.db.charts(cycle.cycle.ident, a.faaId) else emptyList()
            airport = a
            active = cycle
            groups = ChartGroup.group(charts)
            downloaded = if (cycle == null) emptySet() else
                charts.filter { repo.chartFile(cycle.cycle.ident, it.pdfName).exists() }.map { it.pdfName }.toSet()
            supplementCached = repo.cachedSupplement(ident, edition) != null
            favorite = (a?.faaId ?: ident) in graph.db.favorites()
            loaded = true
        }
    }
    val id = airport?.faaId ?: ident

    fun openGroup(g: ChartGroup) {
        val cycle = active ?: return
        scope.launch {
            busy = "Loading ${g.title}…"
            error = null
            try {
                val files = g.charts.map { repo.ensureChart(cycle.cycle.ident, it) }
                onOpen(
                    Route.Document(
                        title = g.title,
                        subtitle = "${airport?.icaoId?.ifEmpty { null } ?: id} · d-TPP cycle ${cycle.cycle.ident}" +
                            (g.amendment.takeIf { it.isNotEmpty() }?.let { " · Amdt $it" } ?: ""),
                        files = files,
                        warning = if (cycle.expired) "EXPIRED: cycle ${cycle.cycle.ident} ended ${cycle.cycle.expires}. Connect to update." else null,
                    )
                )
            } catch (e: Exception) {
                error = "Couldn't load ${g.title}: ${e.message}"
            } finally {
                busy = null
                refresh++
            }
        }
    }

    fun openSupplement() {
        scope.launch {
            busy = "Loading Chart Supplement…"
            error = null
            try {
                val files = withContext(Dispatchers.IO) { repo.cachedSupplement(id, edition) } ?: repo.ensureSupplement(id, edition)
                onOpen(Route.Document("Chart Supplement", "$id · ${edition.display} edition", files))
            } catch (e: Exception) {
                error = "Couldn't load the Chart Supplement for $id: ${e.message}"
            } finally {
                busy = null
                refresh++
            }
        }
    }

    fun downloadAll() {
        val cycle = active
        scope.launch {
            error = null
            var failures = 0
            val all = groups.flatMap { it.charts }
            all.forEachIndexed { i, c ->
                busy = "Downloading ${i + 1}/${all.size}: ${c.name}"
                try {
                    if (cycle != null) repo.ensureChart(cycle.cycle.ident, c)
                } catch (e: Exception) {
                    failures++
                }
            }
            busy = "Downloading Chart Supplement…"
            try {
                repo.ensureSupplement(id, edition)
            } catch (e: Exception) {
                failures++
            }
            busy = null
            if (failures > 0) error = "$failures item(s) failed to download. Check your connection and try again."
            refresh++
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(airport?.icaoId?.ifEmpty { null } ?: id, fontWeight = FontWeight.Bold)
                    airport?.let { Text("${it.name} · ${it.city}, ${it.state}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = {
                    favorite = !favorite
                    graph.db.setFavorite(id, favorite)
                }) {
                    if (favorite) Icon(Icons.Filled.Star, contentDescription = "Remove favorite", tint = EfbPalette.caution)
                    else Icon(Icons.Outlined.Star, contentDescription = "Add favorite")
                }
            },
        )

        active?.takeIf { it.expired }?.let {
            Text(
                "These charts are from cycle ${it.cycle.ident}, which has EXPIRED. Connect to the internet to update.",
                color = MaterialTheme.colorScheme.onError,
                modifier = Modifier.fillMaxWidth().background(EfbPalette.warning).padding(10.dp),
            )
        }
        busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                SectionHeader(ChartCategory.SUPPLEMENT.label)
                ChartRow(
                    title = "Chart Supplement — ${edition.display} edition",
                    detail = "Airport/Facility directory page and notices",
                    downloaded = supplementCached,
                    onClick = ::openSupplement,
                )
            }
            if (loaded && airport == null) {
                item {
                    Text(
                        "$id has no instrument procedures in the FAA d-TPP (or the index hasn't been downloaded yet).",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            val order = listOf(
                ChartCategory.AIRPORT, ChartCategory.MINIMUMS, ChartCategory.DEPARTURE,
                ChartCategory.ARRIVAL, ChartCategory.APPROACH, ChartCategory.OTHER,
            )
            for (cat in order) {
                val inCat = groups.filter { it.category == cat }
                if (inCat.isEmpty()) continue
                item(key = "header-$cat") { SectionHeader(cat.label) }
                // No item keys: several records can share one PDF (e.g. takeoff minimums pages).
                items(inCat) { g ->
                    ChartRow(
                        title = g.title,
                        detail = buildString {
                            if (g.charts.size > 1) append("${g.charts.size} pages")
                            if (g.amendment.isNotEmpty()) {
                                if (isNotEmpty()) append(" · ")
                                append("Amdt ${g.amendment}")
                            }
                        },
                        downloaded = g.charts.all { it.pdfName in downloaded },
                        onClick = { openGroup(g) },
                    )
                }
            }
            if (groups.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = ::downloadAll, enabled = busy == null) { Text("Download all for offline") }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (favorite) "Favorites stay updated automatically." else "Tip: ★ keeps this airport updated.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartRow(title: String, detail: String, downloaded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (downloaded) Icon(Icons.Default.CheckCircle, contentDescription = "Available offline", tint = EfbPalette.good)
    }
    HorizontalDivider()
}
