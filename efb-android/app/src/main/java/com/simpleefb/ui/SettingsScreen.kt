package com.simpleefb.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpleefb.AppGraph
import com.simpleefb.BuildConfig
import com.simpleefb.core.Cycles
import com.simpleefb.map.ChartLayer
import com.simpleefb.position.PositionManager
import com.simpleefb.store.PositionSourceType
import com.simpleefb.store.SavedRegion
import com.simpleefb.update.UpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = graph.settings
    val position = graph.position
    val status by position.status.collectAsStateWithLifecycle()
    val update by graph.updater.status.collectAsStateWithLifecycle()
    val sourceName by s.positionSource.flow.collectAsStateWithLifecycle()
    val source = runCatching { PositionSourceType.valueOf(sourceName) }.getOrDefault(PositionSourceType.NONE)
    var refresh by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        position.restart()
        refresh++
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // ------------------------------------------------------------ position
        SectionHeader("Position source")
        PositionSourceType.entries.forEach { t ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    s.positionSource.value = t.name
                    position.restart()
                }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = source == t, onClick = null)
                Text(t.label)
            }
        }

        when (source) {
            PositionSourceType.INTERNAL -> if (!position.hasLocationPermission()) {
                PermissionButton("Allow location access") {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                }
            }
            PositionSourceType.GDL90_WIFI -> Gdl90WifiSettings(graph)
            PositionSourceType.BLUETOOTH -> BluetoothSettings(graph, refresh) {
                val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
                if (perms.isNotEmpty()) permissionLauncher.launch(perms)
            }
            PositionSourceType.NONE -> Unit
        }

        if (source != PositionSourceType.NONE) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Status", fontWeight = FontWeight.Bold)
                    Text(status.state)
                    status.stats?.let { st ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Protocol: ${st.protocol}" + (st.deviceName?.let { " · Device: $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Bytes ${st.bytes} · GDL 90 msgs ${st.gdl90Messages} (CRC errors ${st.gdl90CrcErrors}) · NMEA ${st.nmeaSentences}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (st.messageIds.isNotEmpty()) {
                            Text(
                                "GDL 90 message IDs: " + st.messageIds.entries.sortedBy { it.key }
                                    .joinToString { "0x%02X×%d".format(Locale.US, it.key, it.value) },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (status.recentBytesHex.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Last bytes received:", style = MaterialTheme.typography.labelSmall)
                        Text(status.recentBytesHex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { position.restart() }) { Text("Reconnect") }
                    }
                }
            }
            if (source != PositionSourceType.INTERNAL) {
                val capture by s.captureRaw.flow.collectAsStateWithLifecycle()
                SwitchRow(
                    "Record raw data to a file",
                    "For troubleshooting a device the app can't decode yet: record, then share the file.",
                    capture,
                ) {
                    s.captureRaw.value = it
                    position.restart()
                }
                val latest = remember(status.captureFile, refresh) { position.captures().firstOrNull() }
                if (latest != null) {
                    TextButton(
                        onClick = {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", latest)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/octet-stream"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share capture"))
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("Share latest capture (${latest.length() / 1024} KB)") }
                }
            }
        }
        val showTraffic by s.showTraffic.flow.collectAsStateWithLifecycle()
        SwitchRow("Show ADS-B traffic on the map", "From GDL 90 sources.", showTraffic) { s.showTraffic.value = it }

        HorizontalDivider(Modifier.padding(top = 12.dp))

        // ------------------------------------------------------------ data & updates
        SectionHeader("Charts & automatic updates")
        val now = Instant.now()
        val tpp = Cycles.airacAt(now)
        val edition = Cycles.chartEditionAt(now)
        var imported by remember { mutableStateOf<List<String>>(emptyList()) }
        var storage by remember { mutableLongStateOf(-1L) }
        LaunchedEffect(update.running, update.lastSuccess, refresh) {
            withContext(Dispatchers.IO) {
                imported = graph.db.importedCycles()
                storage = graph.repo.storageBytes()
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            InfoLine(
                "Procedures (d-TPP)",
                "Cycle ${tpp.ident}: ${tpp.display} – ${Cycles.nextAirac(tpp).display}" +
                    when {
                        tpp.ident !in imported -> " · not downloaded"
                        Cycles.nextAirac(tpp).ident in imported -> " · next cycle ready"
                        else -> " · downloaded"
                    },
            )
            InfoLine(
                "Charts & Chart Supplement",
                "${edition.display} edition · next ${Cycles.nextChartEdition(edition).display}",
            )
            InfoLine(
                "Last successful update",
                if (update.lastSuccess > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(update.lastSuccess)) else "Never",
            )
            if (update.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            if (update.message.isNotEmpty()) Text(update.message, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { UpdateWorker.runNow(context) }, enabled = !update.running) { Text("Update now") }
        }
        val auto by s.autoUpdate.flow.collectAsStateWithLifecycle()
        val wifiOnly by s.wifiOnly.flow.collectAsStateWithLifecycle()
        SwitchRow(
            "Update automatically",
            "Checks twice a day; downloads new cycles, favorites' charts and offline map areas.",
            auto,
        ) {
            s.autoUpdate.value = it
            UpdateWorker.schedule(context, s)
        }
        SwitchRow("Only on Wi-Fi / unmetered networks", null, wifiOnly) {
            s.wifiOnly.value = it
            UpdateWorker.schedule(context, s)
        }

        // ------------------------------------------------------------ offline map areas
        SectionHeader("Offline map areas")
        var regions by remember { mutableStateOf<List<SavedRegion>>(emptyList()) }
        LaunchedEffect(refresh, update.running) { regions = withContext(Dispatchers.IO) { graph.db.regions() } }
        if (regions.isEmpty()) {
            Text(
                "None yet. On the map, pan/zoom to an area and tap ⇩ to save it for offline use.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        regions.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontWeight = FontWeight.Bold)
                    Text(
                        r.layers.joinToString { ChartLayer.parse(it).label } +
                            " · ${String.format(Locale.US, "%,d", r.tileCount)} tiles · " +
                            if (graph.tiles.isRegionReady(r)) "ready offline" else "pending download",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = {
                    graph.tiles.deleteRegion(r)
                    refresh++
                }) { Text("Delete") }
            }
        }

        // ------------------------------------------------------------ storage
        SectionHeader("Storage")
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                if (storage >= 0) "Downloaded data: ${String.format(Locale.US, "%.1f", storage / 1048576.0)} MB" else "Calculating…",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { graph.repo.deleteDownloadedCharts() }
                        refresh++
                    }
                }) { Text("Delete plates & CS") }
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { graph.tiles.purgeAll() }
                        refresh++
                    }
                }) { Text("Delete map tiles") }
            }
        }

        // ------------------------------------------------------------ display
        SectionHeader("Display")
        val keepOn by s.keepScreenOn.flow.collectAsStateWithLifecycle()
        val night by s.nightCharts.flow.collectAsStateWithLifecycle()
        SwitchRow("Keep screen on", null, keepOn) { s.keepScreenOn.value = it }
        SwitchRow("Night mode for plates & Chart Supplement", "Inverts colors (white on black).", night) {
            s.nightCharts.value = it
        }

        // ------------------------------------------------------------ about
        SectionHeader("About")
        Text(
            "SimpleEFB ${BuildConfig.VERSION_NAME}. Charts, procedures and the Chart Supplement come directly " +
                "from the FAA (aeronav.faa.gov and FAA AIS chart services).\n\n" +
                "Not an FAA-approved or certified product. Use only as a supplement to current official " +
                "sources. Always confirm cycle dates, NOTAMs and chart currency before flight.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun Gdl90WifiSettings(graph: AppGraph) {
    val port by graph.settings.udpPort.flow.collectAsStateWithLifecycle()
    var text by remember(port) { mutableStateOf(port.toString()) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Connect this device to your ADS-B receiver's Wi-Fi network (Stratux, Sentry, Stratus, SkyEcho…). " +
                "GDL 90 data normally arrives on UDP port 4000.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                label = { Text("UDP port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                text.toIntOrNull()?.takeIf { it in 1..65535 }?.let {
                    graph.settings.udpPort.value = it
                    graph.position.restart()
                }
            }) { Text("Apply") }
        }
    }
}

@Composable
private fun BluetoothSettings(graph: AppGraph, refresh: Int, requestPermission: () -> Unit) {
    val s = graph.settings
    val position = graph.position
    val address by s.btAddress.flow.collectAsStateWithLifecycle()
    val name by s.btName.flow.collectAsStateWithLifecycle()
    val serviceUuid by s.btServiceUuid.flow.collectAsStateWithLifecycle()
    val devices = remember(refresh) { position.pairedDevices() }
    val selected = devices.firstOrNull { it.address == address }

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Pair the device in Android's Bluetooth settings first, then choose it here. GDL 90 and NMEA " +
                "streams are detected automatically.\n\nGarmin GTX 345: its Bluetooth link uses Garmin's " +
                "Connext protocol, which Garmin hasn't published. SimpleEFB connects and decodes it if the " +
                "data is GDL 90 framed; if not, turn on \"Record raw data\" below and share the capture so " +
                "support can be added.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (!position.hasBluetoothPermission()) {
            PermissionButton("Allow Bluetooth access", requestPermission)
        } else {
            BluetoothDevicePicker(graph, devices, selected, name, serviceUuid)
        }
    }
}

@Composable
private fun BluetoothDevicePicker(
    graph: AppGraph,
    devices: List<PositionManager.BtDevice>,
    selected: PositionManager.BtDevice?,
    name: String,
    serviceUuid: String,
) {
    val context = LocalContext.current
    val s = graph.settings
    val position = graph.position
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(
                label = selected?.name ?: name.ifEmpty { "Choose device" },
                options = devices.map { it.name + "  (" + it.address + ")" },
            ) { i ->
                val d = devices[i]
                s.btAddress.value = d.address
                s.btName.value = d.name
                s.btServiceUuid.value = ""
                position.restart()
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS))
            }) { Text("Pair…") }
        }
        if (devices.isEmpty()) Text("No paired devices found.", style = MaterialTheme.typography.bodySmall)
        if (selected != null) {
            val spp = PositionManager.SPP_UUID.toString()
            val uuids = (listOf(spp) + selected.uuids).distinct()
            Spacer(Modifier.height(6.dp))
            Text("Service", style = MaterialTheme.typography.labelSmall)
            Dropdown(
                label = describeUuid(serviceUuid.ifEmpty { spp }),
                options = uuids.map(::describeUuid),
            ) { i ->
                s.btServiceUuid.value = if (uuids[i] == spp) "" else uuids[i]
                position.restart()
            }
        }
    }
}

private fun describeUuid(uuid: String): String = when (uuid.lowercase()) {
    "00001101-0000-1000-8000-00805f9b34fb" -> "Serial Port (SPP) — default"
    else -> uuid
}

@Composable
private fun Dropdown(label: String, options: List<String>, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = {
                    open = false
                    onSelect(i)
                })
            }
        }
    }
}

@Composable
private fun PermissionButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { Text(text) }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
