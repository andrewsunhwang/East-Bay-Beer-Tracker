@file:OptIn(ExperimentalMaterial3Api::class)

package com.simpleefb.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpleefb.AppGraph
import com.simpleefb.core.OwnshipFix
import com.simpleefb.map.ChartLayer
import com.simpleefb.map.OwnshipOverlay
import com.simpleefb.map.TileMath
import com.simpleefb.position.SourceStatus
import com.simpleefb.store.PositionSourceType
import com.simpleefb.store.SavedRegion
import com.simpleefb.update.UpdateWorker
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import java.util.Locale

private const val MAX_REGION_TILES = 150_000L

@SuppressLint("ClickableViewAccessibility")
@Composable
fun MapScreen(graph: AppGraph) {
    val context = LocalContext.current
    val settings = graph.settings
    val fix by graph.position.ownship.collectAsStateWithLifecycle()
    val traffic by graph.position.traffic.collectAsStateWithLifecycle()
    val status by graph.position.status.collectAsStateWithLifecycle()
    val layerName by settings.mapLayer.flow.collectAsStateWithLifecycle()
    val trackUp by settings.trackUp.flow.collectAsStateWithLifecycle()
    val follow by settings.followOwnship.flow.collectAsStateWithLifecycle()
    val showTraffic by settings.showTraffic.flow.collectAsStateWithLifecycle()
    val edition by graph.tiles.activeEdition.collectAsStateWithLifecycle()
    val layer = ChartLayer.parse(layerName)

    val density = context.resources.displayMetrics.density
    val ownshipOverlay = remember { OwnshipOverlay(density) }
    val mapView = remember {
        MapView(context).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            overlays.add(ownshipOverlay)
            controller.setZoom(settings.mapZoom.value)
            controller.setCenter(GeoPoint(settings.mapLat.value, settings.mapLon.value))
            // Dragging the map with one finger stops following ownship.
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_MOVE && ev.pointerCount == 1 && settings.followOwnship.value) {
                    settings.followOwnship.value = false
                }
                false
            }
        }
    }
    var tacOverlay by remember { mutableStateOf<TilesOverlay?>(null) }
    var showDownload by remember { mutableStateOf(false) }
    var centeredOnce by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val c = mapView.mapCenter
            settings.mapLat.value = c.latitude
            settings.mapLon.value = c.longitude
            settings.mapZoom.value = mapView.zoomLevelDouble
            tacOverlay?.onDetach(mapView)
            mapView.onDetach()
        }
    }

    // Chart layer (TAC is drawn over the sectional so areas outside TAC coverage still show).
    LaunchedEffect(layer, edition) {
        val base = if (layer == ChartLayer.TAC) ChartLayer.SECTIONAL else layer
        mapView.setTileSource(graph.tiles.source(base))
        tacOverlay?.let {
            mapView.overlays.remove(it)
            it.onDetach(mapView)
        }
        tacOverlay = null
        if (layer == ChartLayer.TAC) {
            val provider = MapTileProviderBasic(context.applicationContext, graph.tiles.source(ChartLayer.TAC))
            provider.tileRequestCompleteHandlers.add(mapView.tileRequestCompleteHandler)
            val overlay = TilesOverlay(provider, context.applicationContext).apply {
                setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT)
                setLoadingLineColor(android.graphics.Color.TRANSPARENT)
            }
            mapView.overlays.add(0, overlay)
            tacOverlay = overlay
        }
        mapView.minZoomLevel = base.minZoom.toDouble()
        mapView.maxZoomLevel = layer.maxZoom.toDouble()
        val z = mapView.zoomLevelDouble
        when {
            layer == ChartLayer.TAC && z < ChartLayer.TAC.minZoom -> mapView.controller.setZoom(ChartLayer.TAC.minZoom.toDouble())
            z > layer.maxZoom -> mapView.controller.setZoom(layer.maxZoom.toDouble())
            z < base.minZoom -> mapView.controller.setZoom(base.minZoom.toDouble())
        }
        mapView.invalidate()
    }

    // Ownship, follow, track-up.
    LaunchedEffect(fix, follow, trackUp) {
        ownshipOverlay.fix = fix
        val f = fix
        if (f != null && (follow || !centeredOnce)) {
            mapView.controller.setCenter(GeoPoint(f.latitude, f.longitude))
            if (!centeredOnce && mapView.zoomLevelDouble < 9) mapView.controller.setZoom(10.0)
            centeredOnce = true
        }
        mapView.mapOrientation = if (trackUp && f?.trackDeg != null) -f.trackDeg!!.toFloat() else 0f
        mapView.invalidate()
    }
    LaunchedEffect(traffic, showTraffic) {
        ownshipOverlay.traffic = if (showTraffic) traffic else emptyList()
        mapView.invalidate()
    }

    // Re-evaluate staleness of the position indicator every second.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        DataStrip(fix, status, now, Modifier.align(Alignment.TopCenter).fillMaxWidth())

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapButton(if (follow) "◎" else "○", "Follow ownship", active = follow) {
                settings.followOwnship.value = !follow
            }
            MapButton(if (trackUp) "TRK" else "N", if (trackUp) "Track up" else "North up", active = trackUp) {
                settings.trackUp.value = !trackUp
            }
            MapButton("+", "Zoom in") { mapView.controller.zoomIn() }
            MapButton("−", "Zoom out") { mapView.controller.zoomOut() }
            MapButton("⇩", "Download area for offline use") { showDownload = true }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(EfbPalette.overlay)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChartLayer.entries.forEach { l ->
                FilterChip(
                    selected = l == layer,
                    onClick = { settings.mapLayer.value = l.name },
                    label = { Text(l.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }

    if (showDownload) {
        DownloadAreaDialog(
            bounds = mapView.boundingBox,
            current = layer,
            regionCount = remember { graph.db.regions().size },
            onDismiss = { showDownload = false },
            onConfirm = { region ->
                graph.db.addRegion(region)
                UpdateWorker.runNow(context)
                showDownload = false
            },
        )
    }
}

@Composable
private fun MapButton(text: String, description: String, active: Boolean = false, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = if (active) MaterialTheme.colorScheme.primary else EfbPalette.overlay,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = if (text.length > 1) 12.sp else 20.sp)
    }
}

@Composable
private fun DataStrip(fix: OwnshipFix?, status: SourceStatus, now: Long, modifier: Modifier) {
    val age = fix?.let { now - it.timeMillis }
    val dotColor = when {
        status.source == PositionSourceType.NONE -> Color.Gray
        age == null -> EfbPalette.warning
        age < 3_000 -> EfbPalette.good
        age < 10_000 -> EfbPalette.caution
        else -> EfbPalette.warning
    }
    Row(
        modifier.background(EfbPalette.overlay).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(8.dp))
        Datum("GS", fix?.groundSpeedKt?.let { "%.0f kt".format(Locale.US, it) } ?: "---")
        Datum("TRK", fix?.trackDeg?.let { "%03.0f°".format(Locale.US, it) } ?: "---")
        val alt = fix?.pressureAltitudeFt ?: fix?.gpsAltitudeFt
        Datum(
            if (fix?.pressureAltitudeFt != null) "PALT" else "GPS ALT",
            alt?.let { String.format(Locale.US, "%,d", it) } ?: "---",
        )
        Datum("VS", fix?.verticalSpeedFpm?.let { String.format(Locale.US, "%+d", it) } ?: "---")
        Text(
            when {
                status.source == PositionSourceType.NONE -> "No position source"
                age == null -> status.state
                age > 10_000 -> "Position lost (${age / 1000}s)"
                else -> fix?.source.orEmpty()
            },
            style = MaterialTheme.typography.labelSmall,
            color = Color.LightGray,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false).padding(start = 6.dp),
        )
    }
}

@Composable
private fun Datum(label: String, value: String) {
    Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
    }
}

@Composable
private fun DownloadAreaDialog(
    bounds: BoundingBox,
    current: ChartLayer,
    regionCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (SavedRegion) -> Unit,
) {
    var name by remember { mutableStateOf("Area ${regionCount + 1}") }
    var selected by remember {
        mutableStateOf(if (current == ChartLayer.TAC) setOf(ChartLayer.SECTIONAL, ChartLayer.TAC) else setOf(current))
    }
    val tiles = selected.sumOf {
        TileMath.count(bounds.latNorth, bounds.latSouth, bounds.lonEast, bounds.lonWest, it.minZoom, it.maxZoom)
    }
    val mb = tiles * 35 / 1024
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this area for offline use") },
        text = {
            Column {
                Text(
                    "Downloads the selected charts for the visible area. They are kept up to date " +
                        "automatically each 56-day chart cycle.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                ChartLayer.entries.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                            selected = if (l in selected) selected - l else selected + l
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = l in selected, onCheckedChange = null)
                        Text(l.label)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "≈ ${String.format(Locale.US, "%,d", tiles)} tiles, about $mb MB",
                    color = if (tiles > MAX_REGION_TILES) EfbPalette.warning else Color.Unspecified,
                )
                if (tiles > MAX_REGION_TILES) Text("Too large — zoom in to a smaller area.", color = EfbPalette.warning)
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty() && tiles in 1..MAX_REGION_TILES && name.isNotBlank(),
                onClick = {
                    onConfirm(
                        SavedRegion(
                            id = 0, name = name.trim(), north = bounds.latNorth, south = bounds.latSouth,
                            east = bounds.lonEast, west = bounds.lonWest,
                            layers = selected.map { it.name }, tileCount = tiles.toInt(),
                        )
                    )
                },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
