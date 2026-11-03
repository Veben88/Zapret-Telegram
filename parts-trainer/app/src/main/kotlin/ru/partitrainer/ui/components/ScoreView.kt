package ru.partitrainer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import ru.partitrainer.core.edit.MeasureLayout
import ru.partitrainer.core.edit.MeasureView
import ru.partitrainer.core.edit.NoteCell
import ru.partitrainer.core.edit.RestCell
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.notation.Clef
import ru.partitrainer.core.notation.Dur
import ru.partitrainer.core.notation.Engraving
import ru.partitrainer.core.notation.PlayMap
import ru.partitrainer.core.notation.SystemPacker
import ru.partitrainer.core.structure.MeasureMark
import kotlin.math.max

/** Что рисовать: ноты голоса «как напечатано» (без раскрытия повторов) и параметры стана. */
class ScoreSpec(
    val notes: List<Note>, val marks: List<MeasureMark>, val fifths: Int, val timeNum: Int, val timeDen: Int,
    val beatsPerMeasure: Double, val clef: Clef,
    /** Сколько полутонов добавить к звучащей высоте для записи (тенор в скрипичном ключе пишется на октаву выше). */
    val writtenShift: Int,
)

private class Sys(val line: SystemPacker.Line, val top: Float, val height: Float)

/**
 * Нотный стан голоса с бегущей кареткой. Каретка двигается по долям внутри такта, текущий такт подсвечен;
 * касание такта перематывает воспроизведение туда ([onSeek] получает долю в напечатанных нотах).
 */
@Composable
fun ScoreView(
    spec: ScoreSpec, beat: () -> Double, curMeasure: Int, playMap: PlayMap, showCaret: Boolean, onSeek: (Double) -> Unit, modifier: Modifier = Modifier,
    caretColor: Color = MaterialTheme.colorScheme.primary,
) {
    val density = LocalDensity.current
    val tm = rememberTextMeasurer(cacheSize = 512)
    val ink = MaterialTheme.colorScheme.onSurface
    val accent = caretColor
    val hasClefGlyph = remember { android.graphics.Paint().hasGlyph("𝄞") && android.graphics.Paint().hasGlyph("𝄢") }
    val measures = remember(spec) { MeasureLayout.build(spec.notes, spec.marks, spec.beatsPerMeasure) }
    val minWidths = remember(measures) { measures.map { m -> m.cells.size to m.length } }
    val scroll = rememberScrollState()
    val seek = androidx.compose.runtime.rememberUpdatedState(onSeek)

    BoxWithConstraints(modifier) {
        val widthPx = with(density) { maxWidth.toPx() }
        val s = with(density) { (if (maxWidth > 600.dp) 10.dp else 8.dp).toPx() }   // интервал между линейками
        val padL = s * 1.4f
        val headerW = s * (5.5f + 1.1f * Engraving.keySignature(spec.fifths).size + 3.2f)
        val hasLyrics = spec.notes.any { !it.lyric.isNullOrBlank() }
        val above = s * 6.5f
        val below = s * 4.5f + if (hasLyrics) s * 3f else 0f
        val sysH = above + 4 * s + below
        val gap = s * 2f

        val minW = remember(minWidths, s) { minWidths.map { (n, len) -> max(len.toFloat() * s * 3.6f, n * s * 3.4f) + padL * 2 } }
        val lines = remember(measures, widthPx, headerW) { SystemPacker.pack(minW.map { it.toDouble() }, headerW.toDouble(), widthPx.toDouble()) }
        val systems = remember(lines, sysH) { lines.mapIndexed { i, l -> Sys(l, i * (sysH + gap), sysH) } }
        val totalH = systems.size * (sysH + gap)

        val curSystem = systems.indexOfFirst { curMeasure >= it.line.from && curMeasure < it.line.toExclusive }

        LaunchedEffect(curSystem, showCaret) {
            if (showCaret && curSystem >= 0) scroll.animateScrollTo(max(0f, systems[curSystem].top - s * 3).toInt())
        }

        fun measureX(sys: Sys, mi: Int): Pair<Float, Float> {       // начало и ширина такта в строке
            var x = headerW
            for (k in sys.line.from until mi) x += sys.line.widths[k - sys.line.from].toFloat()
            return x to sys.line.widths[mi - sys.line.from].toFloat()
        }

        androidx.compose.foundation.layout.Column(Modifier.verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(with(density) { totalH.toDp() })) {
            // Слой нот рисуется один раз на раскладку; кадры движения каретки его не перерисовывают
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(systems, measures) {
                        detectTapGestures { p ->
                            val si = systems.indexOfFirst { p.y >= it.top && p.y < it.top + it.height + gap }.takeIf { it >= 0 } ?: return@detectTapGestures
                            val sys = systems[si]
                            for (mi in sys.line.from until sys.line.toExclusive) {
                                val (mx, mw) = measureX(sys, mi)
                                if (p.x >= mx && p.x < mx + mw) {
                                    val m = measures[mi]
                                    val frac = ((p.x - mx - padL) / (mw - 2 * padL)).coerceIn(0f, 1f)
                                    val raw = m.start + frac * m.length
                                    // перематываем на ближайшую слева ноту (начало звука)
                                    val snap = m.cells.filterIsInstance<NoteCell>().map { it.start }.lastOrNull { it <= raw + 1e-6 } ?: m.start
                                    seek.value(snap)
                                    return@detectTapGestures
                                }
                            }
                        }
                    },
            ) {
                for ((si, sys) in systems.withIndex()) {
                    drawSystem(sys, si == 0, spec, measures, tm, ink, accent, hasClefGlyph, s, padL, headerW, above)
                }
            }
            // Слой каретки: читает позицию только при рисовании
            Canvas(Modifier.fillMaxSize()) {
                if (!showCaret || curMeasure < 0) return@Canvas
                val si = systems.indexOfFirst { curMeasure >= it.line.from && curMeasure < it.line.toExclusive }
                if (si < 0) return@Canvas
                val sys = systems[si]
                val m = measures[curMeasure]
                val (mx, mw) = measureX(sys, curMeasure)
                val staffTop = sys.top + above
                val staffBottom = staffTop + 4 * s
                drawRoundRect(accent.copy(alpha = 0.10f), Offset(mx + 1f, staffTop - s * 2.2f), Size(mw - 2f, 4 * s + s * 4.4f), CornerRadius(s * 0.6f))
                val ob = playMap.originalBeat(beat())
                val cx = mx + padL + ((ob - m.start) / m.length).toFloat().coerceIn(0f, 1f) * (mw - 2 * padL)
                drawRoundRect(accent.copy(alpha = 0.16f), Offset(cx - s * 0.9f, staffTop - s * 1.6f), Size(s * 1.8f, 4 * s + s * 3.2f), CornerRadius(s * 0.9f))
                drawLine(accent, Offset(cx, staffTop - s * 1.6f), Offset(cx, staffBottom + s * 1.6f), max(2f, s * 0.22f), StrokeCap.Round)
            }
            }
        }
    }
}

private fun DrawScope.drawSystem(
    sys: Sys, first: Boolean, spec: ScoreSpec, measures: List<MeasureView>, tm: TextMeasurer,
    ink: Color, accent: Color, hasClefGlyph: Boolean, s: Float, padL: Float, headerW: Float, above: Float,
) {
    val staffTop = sys.top + above
    val staffBottom = staffTop + 4 * s
    val lineColor = ink.copy(alpha = 0.55f)
    val thin = max(1f, s * 0.09f)
    fun yOf(pos: Int) = staffBottom - pos * s / 2f

    fun text(t: String, x: Float, y: Float, size: Float, color: Color = ink, weight: FontWeight = FontWeight.Normal, italic: Boolean = false) {
        drawText(tm, t, Offset(x, y), TextStyle(color = color, fontSize = size.toSp(), fontWeight = weight, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal))
    }

    // линейки
    for (i in 0..4) drawLine(lineColor, Offset(0f, staffTop + i * s), Offset(size.width, staffTop + i * s), thin)

    // ключ
    if (hasClefGlyph) {
        if (spec.clef == Clef.TREBLE) text("𝄞", s * 0.2f, staffTop - s * 1.9f, s * 6.2f)
        else text("𝄢", s * 0.2f, staffTop - s * 0.5f, s * 4.2f)
    } else {
        text(if (spec.clef == Clef.TREBLE) "G" else "F", s * 0.5f, staffTop - s * 0.4f, s * 5f, weight = FontWeight.Bold, italic = true)
    }
    if (spec.writtenShift == 12 && spec.clef == Clef.TREBLE) text("8", s * 1.3f, staffBottom + s * 0.2f, s * 1.8f, weight = FontWeight.Bold)
    // знаки при ключе
    val sigPos = if (spec.fifths > 0) intArrayOf(8, 5, 9, 6, 3, 7, 4) else intArrayOf(4, 7, 3, 6, 2, 5, 1)
    val shiftPos = if (spec.clef == Clef.BASS) -2 else 0
    var kx = s * 4.4f
    for ((k, _) in Engraving.keySignature(spec.fifths).withIndex()) {
        text(if (spec.fifths > 0) "♯" else "♭", kx, yOf(sigPos[k] + shiftPos) - s * 1.05f, s * 2.1f)
        kx += s * 1.1f
    }
    if (first) {
        val tx = kx + s * 0.6f
        text("${spec.timeNum}", tx, staffTop - s * 0.15f, s * 2.4f, weight = FontWeight.Bold)
        text("${spec.timeDen}", tx, staffTop + 2 * s - s * 0.15f, s * 2.4f, weight = FontWeight.Bold)
    }

    var mx = headerW
    for (mi in sys.line.from until sys.line.toExclusive) {
        val m = measures[mi]
        val mw = sys.line.widths[mi - sys.line.from].toFloat()
        val inner = mw - 2 * padL
        val mark = spec.marks.getOrNull(mi)
        fun xAt(beat: Double) = mx + padL + ((beat - m.start) / m.length).toFloat().coerceIn(0f, 1f) * inner

        // номер такта и вольта
        if (mi == sys.line.from) text("${m.number}", mx + s * 0.2f, staffTop - s * 2.9f, s * 1.5f, color = ink.copy(alpha = 0.6f))
        mark?.volta?.takeIf { it.isNotEmpty() }?.let { v ->
            text(v.joinToString(",") + ".", mx + s * 0.4f, staffTop - s * 2.5f, s * 1.5f, color = ink.copy(alpha = 0.8f))
            drawLine(ink.copy(alpha = 0.6f), Offset(mx, staffTop - s * 1.0f), Offset(mx + mw, staffTop - s * 1.0f), thin)
        }

        // ноты
        val noteCells = m.cells.filterIsInstance<NoteCell>()
        val accState = HashMap<Int, Int>()                         // (диатонический номер) → последняя альтерация в такте
        class Head(val x: Float, val y: Float, val pos: Int, val dur: Dur, val active: Boolean, val note: Note)
        val heads = HashMap<Int, Head>()
        for (c in m.cells) {
            val active = false
            val color = ink
            val x = xAt(c.start)
            when (c) {
                is RestCell -> drawRest(c, m, x, xAt(c.start + c.beats), staffTop, s, color, thin)
                is NoteCell -> {
                    val p = Engraving.spell(c.note.midi + spec.writtenShift, spec.fifths)
                    val pos = Engraving.staffPos(p, spec.clef)
                    val y = yOf(pos)
                    val dur = Engraving.durationOf(c.note.beats)
                    // знак альтерации
                    val key = p.diatonic
                    val shown = accState[key] ?: Engraving.keyAlter(p.step, spec.fifths)
                    if (p.alter != shown) {
                        text(when (p.alter) { 1 -> "♯"; -1 -> "♭"; 0 -> "♮"; 2 -> "𝄪"; else -> "𝄫" }, x - s * 2.1f, y - s * 1.2f, s * 2.2f, color)
                        accState[key] = p.alter
                    }
                    for (lp in Engraving.ledgerLines(pos)) drawLine(lineColor, Offset(x - s * 1.05f, yOf(lp)), Offset(x + s * 1.05f, yOf(lp)), thin * 1.2f)
                    drawHead(x, y, s, dur, color, thin)
                    if (dur.dots > 0) for (d in 1..dur.dots) drawCircle(color, s * 0.17f, Offset(x + s * (0.95f + 0.4f * (d - 1)), if (pos % 2 == 0) y - s * 0.3f else y))
                    heads[c.index] = Head(x, y, pos, dur, active, c.note)
                    c.note.lyric?.takeIf { it.isNotBlank() }?.let { text(it, x - s * 0.6f, staffBottom + s * 3.1f, s * 1.6f, ink.copy(alpha = 0.85f)) }
                }
            }
        }

        // штили, флажки, балки
        val beamIdx = Engraving.beamGroups(noteCells.map { it.start }, noteCells.map { it.beats })
        val inBeam = HashSet<Int>()
        for (g in beamIdx) {
            val hs = g.map { heads.getValue(noteCells[it].index) }
            val up = hs.map { Engraving.stemUp(it.pos) }.let { u -> u.count { it } * 2 >= u.size }
            val len = s * 3.4f
            val x0 = hs.first().x + if (up) s * 0.62f else -s * 0.62f
            val x1 = hs.last().x + if (up) s * 0.62f else -s * 0.62f
            val y0 = hs.first().y + if (up) -len else len
            val y1 = hs.last().y + if (up) -len else len
            for ((k, h) in hs.withIndex()) {
                val sx = h.x + if (up) s * 0.62f else -s * 0.62f
                val by = y0 + (y1 - y0) * (if (x1 == x0) 0f else (sx - x0) / (x1 - x0))
                drawLine(if (h.active) accent else ink, Offset(sx, h.y), Offset(sx, by), thin * 1.3f)
                inBeam += g[k]
            }
            val bw = s * 0.5f
            val dirY = if (up) bw else -bw
            drawLine(ink, Offset(x0, y0), Offset(x1, y1), bw)
            if (hs.any { it.dur.base >= 4 }) drawLine(ink, Offset(x0, y0 + dirY * 1.5f), Offset(x1, y1 + dirY * 1.5f), bw * 0.8f)
        }
        for ((i, c) in noteCells.withIndex()) {
            if (i in inBeam) continue
            val h = heads.getValue(c.index)
            if (!h.dur.hasStem) continue
            val up = Engraving.stemUp(h.pos)
            val sx = h.x + if (up) s * 0.62f else -s * 0.62f
            val ey = h.y + if (up) -s * 3.4f else s * 3.4f
            val color = if (h.active) accent else ink
            drawLine(color, Offset(sx, h.y), Offset(sx, ey), thin * 1.3f)
            for (f in 0 until h.dur.flags) {
                val fy = ey + (if (up) 1 else -1) * f * s * 0.7f
                val path = Path().apply {
                    moveTo(sx, fy)
                    cubicTo(sx + s * 1.2f, fy + (if (up) s * 0.7f else -s * 0.7f), sx + s * 1.1f, fy + (if (up) s * 1.7f else -s * 1.7f), sx + s * 0.5f, fy + (if (up) s * 2.4f else -s * 2.4f))
                }
                drawPath(path, color, style = Stroke(thin * 1.4f, cap = StrokeCap.Round))
            }
        }

        // тактовые черты
        mx += mw
        val last = mi == measures.lastIndex
        if (mark?.repeatEnd == true) {
            drawLine(ink, Offset(mx - s * 0.8f, staffTop), Offset(mx - s * 0.8f, staffBottom), thin)
            drawLine(ink, Offset(mx, staffTop), Offset(mx, staffBottom), thin * 3f)
            drawCircle(ink, s * 0.2f, Offset(mx - s * 1.5f, staffTop + 1.5f * s)); drawCircle(ink, s * 0.2f, Offset(mx - s * 1.5f, staffTop + 2.5f * s))
        } else if (last) {
            drawLine(ink, Offset(mx - s * 0.5f, staffTop), Offset(mx - s * 0.5f, staffBottom), thin)
            drawLine(ink, Offset(mx, staffTop), Offset(mx, staffBottom), thin * 3f)
        } else drawLine(ink.copy(alpha = 0.8f), Offset(mx, staffTop), Offset(mx, staffBottom), thin)
        if (mark?.repeatStart == true) {
            val bx = mx - mw
            drawLine(ink, Offset(bx + thin * 2, staffTop), Offset(bx + thin * 2, staffBottom), thin * 3f)
            drawCircle(ink, s * 0.2f, Offset(bx + s * 1.0f, staffTop + 1.5f * s)); drawCircle(ink, s * 0.2f, Offset(bx + s * 1.0f, staffTop + 2.5f * s))
        }
    }
    // начало строки
    drawLine(ink.copy(alpha = 0.8f), Offset(thin, staffTop), Offset(thin, staffBottom), thin * 1.5f)
}

private fun DrawScope.drawHead(x: Float, y: Float, s: Float, dur: Dur, color: Color, thin: Float) {
    val w = s * 1.35f; val h = s * 0.95f
    rotate(-20f, Offset(x, y)) {
        if (dur.base <= 1) drawOval(color, Offset(x - w / 2, y - h / 2), Size(w, h), style = Stroke(thin * 1.8f))
        else drawOval(color, Offset(x - w / 2, y - h / 2), Size(w, h))
    }
}

private fun DrawScope.drawRest(c: RestCell, m: MeasureView, x: Float, xEnd: Float, staffTop: Float, s: Float, color: Color, thin: Float) {
    val mid = (x + xEnd) / 2
    val whole = c.beats >= m.length - 1e-6 || c.beats >= 4.0 - 1e-6
    val d = Engraving.durationOf(c.beats)
    val cx = if (whole) mid else x + s * 0.6f
    when {
        whole || d.base == 0 -> drawRect(color, Offset(cx - s * 0.8f, staffTop + s), Size(s * 1.6f, s * 0.5f))
        d.base == 1 -> drawRect(color, Offset(cx - s * 0.8f, staffTop + 1.5f * s), Size(s * 1.6f, s * 0.5f))
        d.base == 2 -> {
            val p = Path().apply {
                moveTo(cx - s * 0.4f, staffTop + s * 0.6f); lineTo(cx + s * 0.4f, staffTop + s * 1.5f); lineTo(cx - s * 0.4f, staffTop + s * 2.3f)
                lineTo(cx + s * 0.4f, staffTop + s * 3.2f)
            }
            drawPath(p, color, style = Stroke(thin * 2.2f, cap = StrokeCap.Round))
        }
        else -> {
            for (k in 0 until (d.base - 2).coerceAtLeast(1)) {
                val oy = staffTop + s * (1.2f + k * 0.9f)
                drawCircle(color, s * 0.22f, Offset(cx - s * 0.2f, oy))
                drawLine(color, Offset(cx + s * 0.35f, oy - s * 0.2f), Offset(cx - s * 0.2f, oy + s * 1.1f), thin * 1.6f, StrokeCap.Round)
            }
        }
    }
    if (d.dots > 0 && !whole) drawCircle(color, s * 0.17f, Offset(cx + s * 1.0f, staffTop + 2 * s - s * 0.5f))
}
