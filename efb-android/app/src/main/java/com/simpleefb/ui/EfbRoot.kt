package com.simpleefb.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.simpleefb.AppGraph
import java.io.File

sealed interface Route {
    data class Airport(val ident: String) : Route
    data class Document(
        val title: String,
        val subtitle: String,
        val files: List<File>,
        val warning: String? = null,
    ) : Route
}

enum class Tab(val label: String, val icon: ImageVector) {
    MAP("Map", Icons.Default.Place),
    AIRPORTS("Airports", Icons.AutoMirrored.Filled.List),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun EfbRoot(graph: AppGraph) {
    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
    val stack = remember { mutableStateListOf<Route>() }
    val push: (Route) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    val top = stack.lastOrNull()
    if (top is Route.Document) {
        PdfViewerScreen(graph, top, onBack = pop)
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = {
                            stack.clear()
                            tab = t
                        },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                top is Route.Airport -> AirportScreen(graph, top.ident, onBack = pop, onOpen = push)
                tab == Tab.MAP -> MapScreen(graph)
                tab == Tab.AIRPORTS -> AirportsScreen(graph, onAirport = { push(Route.Airport(it)) })
                else -> SettingsScreen(graph)
            }
        }
    }
}
