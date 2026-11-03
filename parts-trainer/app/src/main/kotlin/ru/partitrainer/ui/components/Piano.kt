package ru.partitrainer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.partitrainer.R
import ru.partitrainer.core.music.Notation
import ru.partitrainer.core.music.NoteNames
import kotlin.math.max

/**
 * Экранная клавиатура. Работает как настоящая:
 *  — нота звучит, пока клавиша нажата, и затихает при отпускании;
 *  — несколько пальцев — аккорд; ведение пальца по клавишам — глиссандо;
 *  — касания клавиш никогда не прокручивают клавиатуру (случайных нот при прокрутке нет): перемещаться можно стрелками
 *    на октаву, ползунком-обзором под клавишами; клавиатура сама показывает целевую ноту, если она вышла из окна;
 *  — нажатая клавиша подсвечивается и подписывается, целевые ноты отмечены маркером.
 *
 * Доступны все ноты от диапазона голоса минус октава до диапазона плюс октава (но не шире C2–C7).
 */
@Composable
fun PianoKeyboard(
    low: Int, high: Int, notation: Notation, targets: Set<Int>, pressed: Set<Int>,
    onKeyDown: (Int) -> Unit, onKeyUp: (Int) -> Unit, haptics: Boolean = true, modifier: Modifier = Modifier,
) {
    var lo = (low - 12).coerceIn(36, 96); while (NoteNames.isBlack(lo)) lo--
    var hi = (high + 12).coerceIn(lo + 12, 96); while (NoteNames.isBlack(hi)) hi++
    val whites = remember(lo, hi) { (lo..hi).filter { !NoteNames.isBlack(it) } }
    val keyW = 46.dp
    val keyH = 144.dp
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val white = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val pressedColor = MaterialTheme.colorScheme.primary
    val playingColor = MaterialTheme.colorScheme.primaryContainer
    val marker = MaterialTheme.colorScheme.tertiary
    val blackColor = Color(0xFF1B1B1F)
    val desc = stringResource(R.string.keyboard_desc, NoteNames.nameWithOctave(lo, notation, SpellingState.flats), NoteNames.nameWithOctave(hi, notation, SpellingState.flats))

    val wPx = with(density) { keyW.toPx() }
    val hPx = with(density) { keyH.toPx() }
    val bwPx = wPx * 0.62f
    val bhPx = hPx * 0.6f
    var viewW by remember { mutableIntStateOf(0) }
    val octavePx = (wPx * 7).toInt()

    // Нажатые пальцами клавиши: палец → нота. Читается при рисовании, поэтому перерисовывается только холст.
    val held = remember { mutableStateMapOf<Long, Int>() }
    DisposableEffect(Unit) { onDispose { held.values.toList().forEach(onKeyUp); held.clear() } }

    fun blackX(whiteIdx: Int) = (whiteIdx + 1) * wPx - bwPx / 2
    fun blackAfter(m: Int) = m + 1 <= hi && NoteNames.isBlack(m + 1)
    fun hit(o: Offset): Int? {
        if (o.x < 0 || o.y < 0 || o.y > hPx) return null
        if (o.y <= bhPx) for ((i, m) in whites.withIndex()) if (blackAfter(m)) {
            val x = blackX(i); if (o.x in x..(x + bwPx)) return m + 1
        }
        return whites.getOrNull((o.x / wPx).toInt())
    }

    // Начальное положение — по центру диапазона голоса; дальше — следим за целевой нотой, но только если она вне окна
    LaunchedEffect(lo, hi, viewW) {
        if (viewW == 0) return@LaunchedEffect
        val mid = (low + high) / 2
        val idx = whites.indexOfFirst { it >= mid }.coerceAtLeast(0)
        scroll.scrollTo(max(0, (wPx * idx - viewW / 2f).toInt()))
    }
    LaunchedEffect(targets.minOrNull(), viewW) {
        val t = targets.minOrNull() ?: return@LaunchedEffect
        if (viewW == 0) return@LaunchedEffect
        val idx = whites.indexOfFirst { it >= t }.coerceAtLeast(0)
        val x = wPx * idx
        if (x < scroll.value || x + wPx > scroll.value + viewW) scroll.animateScrollTo(max(0, (x - viewW / 2f).toInt()))
    }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { scroll.animateScrollTo(max(0, scroll.value - octavePx)) } }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.keys_octave_down))
            }
            Box(Modifier.weight(1f).onSizeChanged { viewW = it.width }) {
                Canvas(
                    Modifier.horizontalScroll(scroll, enabled = false).width(keyW * whites.size).height(keyH)
                        .semantics { contentDescription = desc }
                        .pointerInput(whites, haptics) {
                            awaitPointerEventScope {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    for (ch in ev.changes) {
                                        val id = ch.id.value
                                        if (ch.pressed) {
                                            val key = hit(ch.position)
                                            val cur = held[id]
                                            if (key != cur) {
                                                cur?.let(onKeyUp)
                                                if (key != null) {
                                                    held[id] = key; onKeyDown(key)
                                                    if (haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                } else held.remove(id)
                                            }
                                            ch.consume()
                                        } else held.remove(id)?.let(onKeyUp)
                                    }
                                }
                            }
                        },
                ) {
                    val down = held.values.toSet()
                    for ((i, m) in whites.withIndex()) {
                        val x = i * wPx
                        val isDown = m in down
                        drawRect(if (isDown) pressedColor else if (m in pressed) playingColor else white, Offset(x, 0f), Size(wPx, hPx))
                        drawRect(outline, Offset(x, 0f), Size(wPx, hPx), style = Stroke(1f))
                        val label = NoteNames.name(m, notation, SpellingState.flats)
                        val tl = measurer.measure(label, TextStyle(fontSize = if (isDown) 13.sp else 11.sp, fontWeight = if (isDown) FontWeight.Bold else FontWeight.Normal, color = if (isDown) onPrimary else onSurface))
                        drawText(tl, topLeft = Offset(x + (wPx - tl.size.width) / 2, hPx - tl.size.height - 8f))
                        if (NoteNames.pitchClass(m) == 0) {
                            val ot = measurer.measure("${NoteNames.octave(m)}", TextStyle(fontSize = 10.sp, color = if (isDown) onPrimary else outline))
                            drawText(ot, topLeft = Offset(x + (wPx - ot.size.width) / 2, hPx - tl.size.height - ot.size.height - 10f))
                        }
                        if (m in targets) drawCircle(marker, 8f * density.density / 2.6f, Offset(x + wPx / 2, hPx - 48f * density.density / 2.6f))
                    }
                    for ((i, m) in whites.withIndex()) if (blackAfter(m)) {
                        val b = m + 1
                        val isDown = b in down
                        drawRoundRect(if (isDown) pressedColor else if (b in pressed) playingColor.copy(alpha = 0.85f) else blackColor, Offset(blackX(i), 0f), Size(bwPx, bhPx), CornerRadius(6f))
                        if (isDown) {
                            val tl = measurer.measure(NoteNames.name(b, notation, SpellingState.flats), TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = onPrimary))
                            drawText(tl, topLeft = Offset(blackX(i) + (bwPx - tl.size.width) / 2, bhPx - tl.size.height - 6f))
                        }
                        if (b in targets) drawCircle(marker, 7f * density.density / 2.6f, Offset(blackX(i) + bwPx / 2, bhPx - 16f * density.density / 2.6f))
                    }
                }
            }
            IconButton(onClick = { scope.launch { scroll.animateScrollTo(scroll.value + octavePx) } }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.keys_octave_up))
            }
        }
        // Обзор: полоса всей клавиатуры с окном; тянуть или коснуться — прокрутить
        val track = outline.copy(alpha = 0.35f)
        val thumb = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        val totalPx = wPx * whites.size
        Canvas(
            Modifier.padding(horizontal = 48.dp).fillMaxWidth().height(22.dp)
                .semantics { contentDescription = "keyboard scroll" }
                .pointerInput(totalPx, viewW) {
                    fun jump(x: Float) {
                        val center = x / size.width * totalPx
                        scope.launch { scroll.scrollTo(max(0, (center - viewW / 2f).toInt())) }
                    }
                    detectTapGestures { jump(it.x) }
                }
                .pointerInput(totalPx, viewW) {
                    fun jump(x: Float) {
                        val center = x / size.width * totalPx
                        scope.launch { scroll.scrollTo(max(0, (center - viewW / 2f).toInt())) }
                    }
                    detectDragGestures(onDragStart = { jump(it.x) }) { ch, _ -> jump(ch.position.x); ch.consume() }
                },
        ) {
            drawRoundRect(track, Offset(0f, size.height / 2 - 3f), Size(size.width, 6f), CornerRadius(3f))
            if (totalPx > 0) {
                val tx = scroll.value / totalPx * size.width
                val tw = (viewW / totalPx * size.width).coerceAtMost(size.width)
                drawRoundRect(thumb, Offset(tx, size.height / 2 - 8f), Size(tw, 16f), CornerRadius(8f))
            }
        }
    }
}
