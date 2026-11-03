package ru.partitrainer.omr.homr

import ru.partitrainer.core.omr.MeasureRect

/** Положение системы на странице: размеры выровненной страницы и угол, на который её повернули при выравнивании. */
class PageFrame(val width: Int, val height: Int, val angle: Double)

/** Колонки тактов системы: x-границы (пиксели выровненной страницы) и вертикальный размах системы. */
class SysColumns(val frame: PageFrame, val top: Double, val bottom: Double, val xs: List<Double>)

/**
 * Тактовые черты нотоносца: узкие вертикальные тёмные линии от верхней до нижней линейки.
 * Штили нот почти никогда не покрывают весь стан целиком, поэтому порог покрытия отсекает их.
 */
object Barlines {
    private const val DARK = 150

    /** Границы тактов: левый край стана, тактовые черты, правый край. */
    fun boundaries(page: GrayImage, staff: DetectedStaff): List<Double> {
        val u = staff.unit
        val runs = ArrayList<Pair<Int, Int>>()
        var start = -1
        val x0 = staff.minX.coerceAtLeast(0); val x1 = staff.maxX.coerceAtMost(page.width - 1)
        for (x in x0..x1) {
            val top = staff.topAt(x.toDouble()) + 0.15 * u
            val bot = staff.bottomAt(x.toDouble()) - 0.15 * u
            val y0 = top.toInt().coerceIn(0, page.height - 1); val y1 = bot.toInt().coerceIn(y0, page.height - 1)
            var dark = 0
            for (y in y0..y1) if (page[x, y] < DARK) dark++
            val ok = (y1 - y0 + 1) >= 3 && dark >= 0.93 * (y1 - y0 + 1)
            if (ok && start < 0) start = x
            if (!ok && start >= 0) { runs += start to x - 1; start = -1 }
        }
        if (start >= 0) runs += start to x1
        // слияние двойных/жирных черт и отбрасывание черты у самого края
        val merged = ArrayList<Pair<Int, Int>>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.second <= 1.2 * u) merged[merged.lastIndex] = last.first to r.second else merged += r
        }
        val centers = merged.map { (it.first + it.second) / 2.0 }
        val left = staff.minX.toDouble(); val right = staff.maxX.toDouble()
        val inner = centers.filter { it > left + 2.0 * u && it < right - 1.2 * u }
        val endCenter = centers.lastOrNull { it >= right - 1.2 * u } ?: right
        return listOf(left) + inner + listOf(endCenter)
    }

    /** Колонки тактов системы по тактовым чертам нотоносца [staff] (верх/низ — по всем нотоносцам системы). */
    fun columns(page: GrayImage, staff: DetectedStaff, system: List<DetectedStaff>, angle: Double): SysColumns {
        val top = system.minOf { it.topAt((it.minX + it.maxX) / 2.0) } - 0.8 * staff.unit
        val bottom = system.maxOf { it.bottomAt((it.minX + it.maxX) / 2.0) } + 0.8 * staff.unit
        return SysColumns(PageFrame(page.width, page.height, angle), top, bottom, boundaries(page, staff))
    }
}

/** Преобразование колонок системы в прямоугольники тактов на исходной странице. */
object LayoutBuilder {
    /**
     * Раскладка тактов всей партии: для каждого нотоносца (в порядке чтения) — столько прямоугольников, сколько в нём тактов
     * по распознанным нотам. [slots] — номер страницы в [pages] для каждого нотоносца.
     */
    fun build(
        tokens: List<List<EncodedSymbol>>, columns: List<SysColumns?>, slots: List<Int>, pages: List<ru.partitrainer.core.omr.PageRef>,
    ): ru.partitrainer.core.omr.SourceLayout {
        val rects = ArrayList<MeasureRect?>()
        for ((i, t) in tokens.withIndex()) {
            val count = TokenParser.parse(listOf(t)).measures.size
            val cols = columns.getOrNull(i)
            if (cols == null) repeat(count) { rects += null } else rects += rects(cols, count, slots.getOrElse(i) { 0 })
        }
        return ru.partitrainer.core.omr.SourceLayout(pages, rects)
    }

    /**
     * @param measureCount сколько тактов в этой системе по распознанным нотам. Если найденных черт столько же — берём их,
     * иначе делим стан поровну (индексы тактов не должны смещаться).
     */
    fun rects(cols: SysColumns, measureCount: Int, pageSlot: Int): List<MeasureRect> {
        if (measureCount <= 0 || cols.xs.size < 2) return emptyList()
        val xs = if (cols.xs.size - 1 == measureCount) cols.xs else {
            val a = cols.xs.first(); val b = cols.xs.last()
            List(measureCount + 1) { a + (b - a) * it / measureCount }
        }
        return (0 until measureCount).map { i -> toSource(cols, xs[i], xs[i + 1], pageSlot) }
    }

    /** Прямоугольник выровненной страницы → ограничивающая рамка на исходной (обратный поворот вокруг центра). */
    private fun toSource(c: SysColumns, xa: Double, xb: Double, slot: Int): MeasureRect {
        val w = c.frame.width.toDouble(); val h = c.frame.height.toDouble()
        val cx = (w - 1) / 2; val cy = (h - 1) / 2
        val cs = Math.cos(c.frame.angle); val sn = Math.sin(c.frame.angle)
        val xsPts = ArrayList<Double>(); val ysPts = ArrayList<Double>()
        for ((px, py) in listOf(xa to c.top, xb to c.top, xa to c.bottom, xb to c.bottom)) {
            val dx = px - cx; val dy = py - cy
            xsPts += cx + dx * cs - dy * sn      // тот же поворот, что в ImageOps.rotate: из выровненной страницы в исходную
            ysPts += cy + dx * sn + dy * cs
        }
        fun f(v: Double, size: Double) = (v / size).toFloat().coerceIn(0f, 1f)
        return MeasureRect(slot, f(xsPts.min(), w), f(ysPts.min(), h), f(xsPts.max(), w), f(ysPts.max(), h))
    }
}
