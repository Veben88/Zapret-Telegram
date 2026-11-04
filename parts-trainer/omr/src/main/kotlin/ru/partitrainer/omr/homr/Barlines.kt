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
    private const val DARK = 165

    /** Кандидат в тактовую черту: центр и «грязь» вокруг (чем меньше, тем больше похоже на настоящую черту). */
    class Candidate(val x: Double, val score: Double)

    /**
     * Границы тактов: левый край стана, тактовые черты, правый край.
     * Если задано [expected] (число тактов, прочитанных нейросетью) и кандидатов больше, чем нужно, берутся самые «чистые»:
     * у настоящей черты рядом нет головок нот и она не выступает за стан, а штили аккордов, занимающие весь стан, — выступают
     * и с головками рядом.
     */
    fun boundaries(page: GrayImage, staff: DetectedStaff, expected: Int? = null, system: List<DetectedStaff> = listOf(staff)): List<Double> {
        val u = staff.unit
        val left = staff.minX.toDouble(); val right = staff.maxX.toDouble()
        val sets = system.map { if (it === staff) candidates(page, it) else candidates(page, it) }
        val all = sets[system.indexOf(staff).coerceAtLeast(0)]
        val inner = all.filter { it.x > left + 2.0 * u && it.x < right - 1.2 * u }
        val endCenter = all.lastOrNull { it.x >= right - 1.2 * u }?.x ?: right
        val need = expected?.let { it - 1 }
        // Тактовая черта есть у всех нотоносцев системы на одном x, а штиль — у отдельных; к тому же рядом со штилем головки нот.
        // Поэтому качество кандидата считаем по системе: доля нотоносцев, где такая черта есть, минус средняя «грязь».
        val total = sets.size
        fun quality(c: Candidate): Double {
            var present = 0; var dirt = 0.0
            for (set in sets) set.minByOrNull { Math.abs(it.x - c.x) }?.takeIf { Math.abs(it.x - c.x) <= 0.8 * u }?.let { present++; dirt += it.score }
            val mean = if (present == 0) 1.0 else dirt / present
            return present.toDouble() / total - 0.6 * mean
        }
        val chosen = if (need != null && need >= 0 && inner.size > need) selectBest(inner.map { it to quality(it) }, need, 2.5 * u) else inner.map { it.x }
        return listOf(left) + chosen.sorted() + listOf(endCenter)
    }

    /** Берёт [n] кандидатов с наибольшим качеством, не ближе [minGap] друг к другу. */
    private fun selectBest(c: List<Pair<Candidate, Double>>, n: Int, minGap: Double): List<Double> {
        val picked = ArrayList<Candidate>()
        for ((x, _) in c.sortedByDescending { it.second }) {
            if (picked.size >= n) break
            if (picked.none { Math.abs(it.x - x.x) < minGap }) picked += x
        }
        return picked.map { it.x }
    }

    fun candidates(page: GrayImage, staff: DetectedStaff): List<Candidate> {
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
            val ok = (y1 - y0 + 1) >= 3 && dark >= 0.82 * (y1 - y0 + 1)
            if (ok && start < 0) start = x
            if (!ok && start >= 0) { runs += start to x - 1; start = -1 }
        }
        if (start >= 0) runs += start to x1
        // слияние двойных/жирных черт
        val merged = ArrayList<Pair<Int, Int>>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && r.first - last.second <= 1.2 * u) merged[merged.lastIndex] = last.first to r.second else merged += r
        }
        return merged.map { (a, b) -> Candidate((a + b) / 2.0, dirt(page, staff, a, b)) }
    }

    /** Доля строк стана с тёмными пикселями по бокам (головки нот) плюс доля тёмного над и под станом (выступающий штиль). */
    private fun dirt(page: GrayImage, staff: DetectedStaff, a: Int, b: Int): Double {
        val u = staff.unit
        val cx = (a + b) / 2
        val top = staff.topAt(cx.toDouble()); val bot = staff.bottomAt(cx.toDouble())
        fun dark(x: Int, y: Int) = x in 0 until page.width && y in 0 until page.height && page[x, y] < DARK
        fun onLine(y: Int) = (0..4).any { Math.abs(y - (top + it * (bot - top) / 4)) <= 1.5 }
        var rows = 0; var sideLeft = 0; var sideRight = 0
        for (y in top.toInt()..bot.toInt()) {
            if (onLine(y)) continue
            rows++
            val l0 = (a - 0.8 * u).toInt(); val l1 = (a - 0.3 * u).toInt()
            val r0 = (b + 0.3 * u).toInt(); val r1 = (b + 0.8 * u).toInt()
            if ((l0..l1).any { dark(it, y) }) sideLeft++
            if ((r0..r1).any { dark(it, y) }) sideRight++
        }
        val side = if (rows == 0) 0.0 else Math.max(sideLeft, sideRight).toDouble() / rows
        var ext = 0; var extRows = 0
        for (dy in (0.25 * u).toInt()..(0.7 * u).toInt()) {
            for (sign in intArrayOf(-1, 1)) {
                val y = if (sign < 0) (top - dy).toInt() else (bot + dy).toInt()
                extRows++
                if ((a..b).any { dark(it, y) }) ext++
            }
        }
        return side + (if (extRows == 0) 0.0 else ext.toDouble() / extRows)
    }

    /** Колонки тактов системы по тактовым чертам нотоносца [staff] (верх/низ — по всем нотоносцам системы). */
    fun columns(page: GrayImage, staff: DetectedStaff, system: List<DetectedStaff>, angle: Double, expected: Int? = null): SysColumns {
        val top = system.minOf { it.topAt((it.minX + it.maxX) / 2.0) } - 0.8 * staff.unit
        val bottom = system.maxOf { it.bottomAt((it.minX + it.maxX) / 2.0) } + 0.8 * staff.unit
        return SysColumns(PageFrame(page.width, page.height, angle), top, bottom, boundaries(page, staff, expected, system))
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
     * Ноты партии на страницах — по порядку нот партии (ноты, которые начинают звук: продолжения лиг пропускаются,
     * как при сборке партии). [parsed] — результат разбора этих же [tokens].
     */
    fun notes(parsed: ru.partitrainer.core.omr.OmrParsed, columns: List<SysColumns?>, slots: List<Int>): List<ru.partitrainer.core.omr.NoteMark?> {
        val out = ArrayList<ru.partitrainer.core.omr.NoteMark?>()
        var prevTied = false
        for (e in parsed.measures.flatMap { it.events }) {
            if (e.midi == null) { prevTied = false; continue }
            if (!prevTied) {
                val cols = columns.getOrNull(e.staff)
                out += if (cols == null || e.x.isNaN() || e.y.isNaN()) null else {
                    val (sx, sy) = toSourcePoint(cols.frame, e.x, e.y)
                    ru.partitrainer.core.omr.NoteMark(slots.getOrElse(e.staff) { 0 }, (sx / cols.frame.width).toFloat().coerceIn(0f, 1f), (sy / cols.frame.height).toFloat().coerceIn(0f, 1f))
                }
            }
            prevTied = e.tie
        }
        return out
    }

    /** Точка выровненной страницы → исходная страница (обратный поворот вокруг центра, как в ImageOps.rotate). */
    fun toSourcePoint(f: PageFrame, px: Double, py: Double): Pair<Double, Double> {
        val cx = (f.width - 1) / 2.0; val cy = (f.height - 1) / 2.0
        val cs = Math.cos(f.angle); val sn = Math.sin(f.angle)
        val dx = px - cx; val dy = py - cy
        return (cx + dx * cs - dy * sn) to (cy + dx * sn + dy * cs)
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
