package ru.partitrainer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ru.partitrainer.core.omr.SourceLayout
import ru.partitrainer.omr.PageRenderer
import java.io.File

/**
 * Страницы исходного PDF/фото с подсветкой текущего такта. Касание такта перематывает воспроизведение.
 * Страницы рисуются по мере прокрутки, чтобы не держать в памяти весь документ.
 */
@Composable
fun OriginalView(
    path: String, isPdf: Boolean, layout: SourceLayout, curMeasure: Int, onSeekMeasure: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val renderer = remember(path) { PageRenderer(File(path), isPdf) }
    DisposableEffect(renderer) { onDispose { runCatching { renderer.close() } } }
    val listState = rememberLazyListState()
    val accent = MaterialTheme.colorScheme.primary
    val curSlot = layout.rects.getOrNull(curMeasure)?.page ?: -1
    LaunchedEffect(curSlot) { if (curSlot >= 0) listState.animateScrollToItem(curSlot) }

    LazyColumn(modifier, state = listState) {
        itemsIndexed(layout.pages) { slot, ref ->
            var bmp by remember(path, slot) { mutableStateOf<ImageBitmap?>(null) }
            var failed by remember(path, slot) { mutableStateOf(false) }
            LaunchedEffect(path, slot) {
                try { bmp = renderer.thumbnail(ref.index, 1400, ref.rotation).asImageBitmap() } catch (e: Exception) { failed = true }
            }
            val b = bmp
            val boxMod = if (b != null) Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height) else Modifier.fillMaxWidth().height(320.dp)
            Box(boxMod.clipToBounds()) {
                if (b != null) {
                    Image(b, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Canvas(
                        Modifier.matchParentSize().pointerInput(layout) {
                            detectTapGestures { p ->
                                val fx = p.x / size.width; val fy = p.y / size.height
                                val i = layout.rects.indexOfFirst { it != null && it.page == slot && it.contains(fx, fy) }
                                if (i >= 0) onSeekMeasure(i)
                            }
                        },
                    ) {
                        val r = layout.rects.getOrNull(curMeasure)
                        if (r != null && r.page == slot) {
                            val tl = Offset(r.x0 * size.width, r.y0 * size.height)
                            val sz = Size((r.x1 - r.x0) * size.width, (r.y1 - r.y0) * size.height)
                            drawRoundRect(accent.copy(alpha = 0.22f), tl, sz, CornerRadius(8f))
                            drawRoundRect(accent, tl, sz, CornerRadius(8f), style = Stroke(3f))
                        }
                    }
                }
            }
        }
    }
}

