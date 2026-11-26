package com.simpleefb.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simpleefb.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Several PDF files viewed as one document (e.g. a SID and its continuation page). */
class PdfDocs(files: List<File>) : Closeable {
    private val lock = Any()
    private val renderers: List<PdfRenderer> = files.mapNotNull { f ->
        runCatching { PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull()
    }
    val pageCount: Int = renderers.sumOf { it.pageCount }

    /** Renders [index] to fit [targetWidth] px wide (capped so a page stays a few tens of MB). */
    fun render(index: Int, targetWidth: Int): Bitmap? {
        synchronized(lock) {
            return renderLocked(index, targetWidth)
        }
    }

    private fun renderLocked(index: Int, targetWidth: Int): Bitmap? {
        var i = index
        for (r in renderers) {
            if (i < r.pageCount) {
                r.openPage(i).use { page ->
                    var w = targetWidth.coerceIn(400, MAX_DIM)
                    var h = (page.height.toFloat() * w / page.width).toInt()
                    if (h > MAX_DIM) {
                        w = (w.toFloat() * MAX_DIM / h).toInt()
                        h = MAX_DIM
                    }
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
            i -= r.pageCount
        }
        return null
    }

    override fun close() = synchronized(lock) {
        renderers.forEach { runCatching { it.close() } }
    }

    companion object {
        private const val MAX_DIM = 4096
    }
}

private val InvertColors = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

@Composable
fun PdfViewerScreen(graph: AppGraph, doc: Route.Document, onBack: () -> Unit) {
    val night by graph.settings.nightCharts.flow.collectAsStateWithLifecycle()
    val docs = remember(doc) { PdfDocs(doc.files) }
    DisposableEffect(docs) { onDispose { docs.close() } }

    var page by remember(doc) { mutableIntStateOf(0) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(doc) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(doc) { mutableFloatStateOf(1f) }
    var offset by remember(doc) { mutableStateOf(Offset.Zero) }
    var chromeVisible by remember { mutableStateOf(true) }

    LaunchedEffect(page, viewSize) {
        if (viewSize.width == 0) return@LaunchedEffect
        scale = 1f
        offset = Offset.Zero
        // Render at 2.5x the screen width so zooming in stays sharp.
        bitmap = withContext(Dispatchers.Default) { docs.render(page, (viewSize.width * 2.5f).toInt()) }
    }

    fun clampOffset(o: Offset, s: Float): Offset {
        val maxX = (s - 1f) * viewSize.width / 2f
        val maxY = (s - 1f) * viewSize.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (night) Color.Black else Color.White)
            .onSizeChanged { viewSize = it },
    ) {
        val bmp = bitmap
        if (bmp == null) {
            if (docs.pageCount == 0) {
                Text("Couldn't open this document.", color = Color.Red, modifier = Modifier.align(Alignment.Center))
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = doc.title,
                contentScale = ContentScale.Fit,
                colorFilter = if (night) InvertColors else null,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(doc, page) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 8f)
                            val center = Offset(size.width / 2f, size.height / 2f)
                            // Keep the point under the fingers fixed while zooming.
                            val o = (centroid - center) - (centroid - center - offset) * (newScale / scale) + pan
                            scale = newScale
                            offset = clampOffset(o, newScale)
                        }
                    }
                    .pointerInput(doc, page) {
                        detectTapGestures(
                            onTap = { chromeVisible = !chromeVisible },
                            onDoubleTap = { tap ->
                                if (scale > 1.5f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    val s = 3f
                                    offset = clampOffset((center - tap) * (s - 1f), s)
                                    scale = s
                                }
                            },
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }

        if (chromeVisible) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(EfbPalette.overlay)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(doc.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(doc.subtitle, color = Color.LightGray, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    TextButton(onClick = { graph.settings.nightCharts.value = !night }) {
                        Text(if (night) "Day" else "Night", color = Color.White)
                    }
                }
                doc.warning?.let {
                    Text(
                        it,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().background(EfbPalette.warning).padding(8.dp),
                    )
                }
            }

            if (docs.pageCount > 1) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(bottom = 12.dp)
                        .background(EfbPalette.overlay, MaterialTheme.shapes.large),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (page > 0) page-- }, enabled = page > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous page", tint = Color.White)
                    }
                    Text("${page + 1} / ${docs.pageCount}", color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(onClick = { if (page < docs.pageCount - 1) page++ }, enabled = page < docs.pageCount - 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next page", tint = Color.White)
                    }
                }
            }
        }
    }
}
