package com.simpleefb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpleefb.AppGraph
import com.simpleefb.core.TppAirport
import com.simpleefb.update.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AirportsScreen(graph: AppGraph, onAirport: (String) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val update by graph.updater.status.collectAsStateWithLifecycle()
    var results by remember { mutableStateOf<List<TppAirport>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<TppAirport>>(emptyList()) }
    var favoriteIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasIndex by remember { mutableStateOf(true) }

    LaunchedEffect(query, update.running, update.lastSuccess) {
        withContext(Dispatchers.IO) {
            results = graph.db.searchAirports(query)
            favoriteIds = graph.db.favorites()
            favorites = favoriteIds.mapNotNull { graph.db.airport(it) }
            hasIndex = graph.db.importedCycles().isNotEmpty()
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            singleLine = true,
            label = { Text("Airport ID, name or city") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
        )

        if (!hasIndex) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("The FAA procedures index hasn't been downloaded yet.", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.padding(4.dp))
                    if (update.running) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(update.message, style = MaterialTheme.typography.bodySmall)
                    } else {
                        if (update.message.isNotEmpty()) Text(update.message, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { UpdateWorker.runNow(context) }) { Text("Download now") }
                    }
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isBlank()) {
                item {
                    SectionHeader("Favorites")
                    Text(
                        "Charts and Chart Supplement pages for favorites are downloaded and kept current " +
                            "automatically. Tap ★ on an airport to add it.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(favorites, key = { it.faaId }) { a -> AirportRow(a, true) { onAirport(a.faaId) } }
                // Favorites without d-TPP entries (Chart Supplement only).
                items(favoriteIds.filter { id -> favorites.none { it.faaId == id } }) { id ->
                    PlainRow(id, "Chart Supplement only") { onAirport(id) }
                }
            } else {
                items(results, key = { it.faaId }) { a -> AirportRow(a, a.faaId in favoriteIds) { onAirport(a.faaId) } }
                val q = query.trim().uppercase()
                if (q.length in 2..4 && q.all { it.isLetterOrDigit() } && results.none { it.faaId == q || it.icaoId == q }) {
                    item {
                        PlainRow(q, "Look up Chart Supplement for $q (airports without instrument procedures)") {
                            onAirport(q)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun AirportRow(a: TppAirport, favorite: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(64.dp)) {
            Text(a.icaoId.ifEmpty { a.faaId }, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            if (a.icaoId.isNotEmpty() && a.icaoId != a.faaId) {
                Text(a.faaId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(a.name, maxLines = 1)
            Text("${a.city}, ${a.state}" + if (a.military) " · Military" else "", style = MaterialTheme.typography.bodySmall)
        }
        if (favorite) Icon(Icons.Default.Star, contentDescription = "Favorite", tint = EfbPalette.caution)
    }
    HorizontalDivider()
}

@Composable
private fun PlainRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}
