package ru.partitrainer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.partitrainer.R
import ru.partitrainer.core.music.Notation
import ru.partitrainer.core.music.NoteNames

/**
 * Экранная клавиатура под диапазон голоса. На белых клавишах — названия нот,
 * целевые ноты отмечены маркером, нажатая клавиша подсвечивается.
 */
@Composable
fun PianoKeyboard(
    low: Int, high: Int, notation: Notation, targets: Set<Int>, pressed: Set<Int>,
    onKey: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    // Диапазон по белым клавишам
    var lo = low.coerceIn(0, 120); while (NoteNames.isBlack(lo)) lo--
    var hi = high.coerceIn(lo + 12, 127); while (NoteNames.isBlack(hi)) hi++
    val whites = remember(lo, hi) { (lo..hi).filter { !NoteNames.isBlack(it) } }
    val keyW = 46.dp
    val keyH = 132.dp
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val white = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val pressedColor = MaterialTheme.colorScheme.primaryContainer
    val marker = MaterialTheme.colorScheme.tertiary
    val blackColor = Color(0xFF1B1B1F)
    val desc = stringResource(R.string.keyboard_desc, NoteNames.nameWithOctave(lo, notation), NoteNames.nameWithOctave(hi, notation))

    LaunchedEffect(lo, targets.minOrNull()) {
        val t = targets.minOrNull() ?: return@LaunchedEffect
        val idx = whites.indexOfFirst { it >= t }.coerceAtLeast(0)
        scroll.animateScrollTo(with(density) { (keyW * idx).roundToPx() } - 160)
    }

    val wPx = with(density) { keyW.toPx() }
    val hPx = with(density) { keyH.toPx() }
    val bwPx = wPx * 0.62f
    val bhPx = hPx * 0.6f

    fun blackX(whiteIdx: Int) = (whiteIdx + 1) * wPx - bwPx / 2
    fun blackAfter(m: Int) = m + 1 <= hi && NoteNames.isBlack(m + 1)

    fun hit(o: Offset): Int? {
        // чёрные клавиши сверху
        if (o.y <= bhPx) for ((i, m) in whites.withIndex()) if (blackAfter(m)) {
            val x = blackX(i); if (o.x in x..(x + bwPx)) return m + 1
        }
        val i = (o.x / wPx).toInt()
        return whites.getOrNull(i)
    }

    Canvas(
        modifier.fillMaxWidth().horizontalScroll(scroll).width(keyW * whites.size).height(keyH)
            .semantics { contentDescription = desc }
            .pointerInput(whites) { detectTapGestures(onPress = { o -> hit(o)?.let(onKey) }) },
    ) {
        for ((i, m) in whites.withIndex()) {
            val x = i * wPx
            drawRect(if (m in pressed) pressedColor else white, Offset(x, 0f), Size(wPx, hPx))
            drawRect(outline, Offset(x, 0f), Size(wPx, hPx), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            val label = NoteNames.name(m, notation)
            val tl = measurer.measure(label, TextStyle(fontSize = 11.sp, color = onSurface))
            drawText(tl, topLeft = Offset(x + (wPx - tl.size.width) / 2, hPx - tl.size.height - 6f))
            if (NoteNames.pitchClass(m) == 0) {
                val ot = measurer.measure("${NoteNames.octave(m)}", TextStyle(fontSize = 9.sp, color = outline))
                drawText(ot, topLeft = Offset(x + (wPx - ot.size.width) / 2, hPx - tl.size.height - ot.size.height - 8f))
            }
            if (m in targets) drawCircle(marker, 8f * density.density / 2.6f, Offset(x + wPx / 2, hPx - 44f * density.density / 2.6f))
        }
        for ((i, m) in whites.withIndex()) if (blackAfter(m)) {
            val b = m + 1
            drawRoundRect(if (b in pressed) pressedColor else blackColor, Offset(blackX(i), 0f), Size(bwPx, bhPx), CornerRadius(4f))
            if (b in targets) drawCircle(marker, 7f * density.density / 2.6f, Offset(blackX(i) + bwPx / 2, bhPx - 16f * density.density / 2.6f))
        }
    }
}
