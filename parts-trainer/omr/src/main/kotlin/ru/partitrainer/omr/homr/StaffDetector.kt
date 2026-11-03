package ru.partitrainer.omr.homr

import ru.partitrainer.core.omr.StaffBox
import kotlin.math.max
import kotlin.math.min

/** Парабола y(x) = c0 + c1·t + c2·t², t = (x − x0)/1000 — форма линейки на скане (наклон и изгиб страницы). */
class Poly(val c0: Double, val c1: Double, val c2: Double, val x0: Double) {
    fun at(x: Double): Double { val t = (x - x0) / 1000.0; return c0 + c1 * t + c2 * t * t }

    companion object {
        /** МНК по точкам; при малом числе точек — прямая. */
        fun fit(xs: DoubleArray, ys: DoubleArray): Poly {
            val x0 = xs.average()
            val n = xs.size
            if (n < 4) {
                var sxx = 0.0; var sxy = 0.0; val my = ys.average()
                for (i in 0 until n) { val t = (xs[i] - x0) / 1000.0; sxx += t * t; sxy += t * (ys[i] - my) }
                return Poly(my, if (sxx > 1e-9) sxy / sxx else 0.0, 0.0, x0)
            }
            // нормальные уравнения 3×3
            val m = Array(3) { DoubleArray(4) }
            for (i in 0 until n) {
                val t = (xs[i] - x0) / 1000.0
                val row = doubleArrayOf(1.0, t, t * t)
                for (r in 0 until 3) { for (c in 0 until 3) m[r][c] += row[r] * row[c]; m[r][3] += row[r] * ys[i] }
            }
            for (col in 0 until 3) {
                var piv = col
                for (r in col + 1 until 3) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r
                val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
                if (Math.abs(m[col][col]) < 1e-12) return Poly(ys.average(), 0.0, 0.0, x0)
                for (r in 0 until 3) if (r != col) { val f = m[r][col] / m[col][col]; for (c in col until 4) m[r][c] -= f * m[col][c] }
            }
            return Poly(m[0][3] / m[0][0], m[1][3] / m[1][1], m[2][3] / m[2][2], x0)
        }
    }
}

/** Найденный нотоносец в координатах страницы (ширина 1920). [top]/[bottom] — кривые первой и пятой линеек. */
data class DetectedStaff(
    val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val unit: Double,
    val top: Poly? = null, val bottom: Poly? = null,
) {
    val box get() = StaffBox(minY, maxY)
    fun topAt(x: Double): Double = top?.at(x) ?: minY.toDouble()
    fun bottomAt(x: Double): Double = bottom?.at(x) ?: maxY.toDouble()
}

/**
 * Упрощённый поиск нотоносцев по маске класса «staff» от segnet (страницы PDF прямые).
 * В отличие от homr не строит повёрнутые рамки и не выпрямляет линейки: линия — это строка пикселей
 * с длинными горизонтальными пробегами; пятёрки линий с равным шагом образуют нотоносец.
 */
object StaffDetector {
    private const val MIN_RUN = 60          // минимальная длина пробега, пикселей на странице шириной 1920

    /** Линия (линейка) как кривая на участке [minX, maxX]. */
    class Line(val poly: Poly, val minX: Int, val maxX: Int) {
        fun y(x: Double) = poly.at(x)
    }

    private const val STRIP = 128

    fun detect(map: SegMap): List<DetectedStaff> = groupStaves(findLines(map), map.height)

    /**
     * Поиск линеек полосами по 128 пикселей: в каждой полосе — строки, почти целиком занятые маской «staff»;
     * фрагменты соседних полос связываются в цепочки (допуск на наклон), цепочка аппроксимируется параболой.
     */
    fun findLines(map: SegMap): List<Line> {
        val w = map.width; val h = map.height
        val staffCls = SegClass.STAFF.toByte()
        val nStrips = (w + STRIP - 1) / STRIP
        class Frag(val y: Double, val strip: Int) { var next: Frag? = null; var prev: Frag? = null }
        val frags = Array(nStrips) { ArrayList<Frag>() }
        val maxThick = 12
        for (k in 0 until nStrips) {
            val x0 = k * STRIP; val x1 = min(w, x0 + STRIP)
            if (x1 - x0 < STRIP / 2) continue
            val cover = IntArray(h)
            for (y in 0 until h) {
                // считаем покрытие, «заращивая» дыры до 5 пикселей (ноты, штили и значки перекрывают линейку)
                var c = 0; var last = -100; val row = y * w
                for (x in x0 until x1) if (map.cls[row + x] == staffCls) { c += if (x - last in 2..6 && last >= x0) x - last else 1; last = x }
                cover[y] = c
            }
            val need = 0.5 * (x1 - x0)
            var y = 0
            while (y < h) {
                if (cover[y] < need) { y++; continue }
                var e = y
                while (e + 1 < h && cover[e + 1] >= need) e++
                if (e - y + 1 <= maxThick) {
                    var wy = 0.0; var ws = 0.0
                    for (r in y..e) { wy += r * cover[r].toDouble(); ws += cover[r] }
                    frags[k] += Frag(wy / ws, k)
                }
                y = e + 1
            }
        }
        // связывание соседних полос (с возможностью пропустить одну). Предсказание по уже найденному наклону цепочки;
        // допуск меньше половины расстояния между линейками, чтобы не перескочить на соседнюю линейку.
        for (k in 0 until nStrips - 1) for (f in frags[k]) {
            if (f.next != null) continue
            val drift = f.prev?.let { f.y - it.y } ?: 0.0
            var best: Frag? = null; var bestDiff = Double.MAX_VALUE
            for (look in 1..2) {
                if (k + look >= nStrips) break
                val predicted = f.y + drift * look
                val tol = if (f.prev != null) 2.5 * look else 4.0 * look
                for (g in frags[k + look]) {
                    if (g.prev != null) continue
                    val diff = Math.abs(g.y - predicted)
                    if (diff <= tol && diff < bestDiff) { bestDiff = diff; best = g }
                }
                if (best != null) break
            }
            if (best != null) { f.next = best; best.prev = f }
        }
        val lines = ArrayList<Line>()
        for (k in 0 until nStrips) for (f in frags[k]) {
            if (f.prev != null) continue
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            var c: Frag? = f
            while (c != null) { xs += (c.strip * STRIP + STRIP / 2.0); ys += c.y; c = c.next }
            if (xs.size < 3) continue
            val poly = Poly.fit(xs.toDoubleArray(), ys.toDoubleArray())
            // реальный размах цепочки: от начала первой до конца последней полосы
            var endStrip = f.strip; var cc: Frag? = f
            while (cc?.next != null) { cc = cc.next; endStrip = cc!!.strip }
            // границы уточняем по маске вдоль кривой: полоса может начинаться левее/правее целой полосы
            val left = extent(map, poly, f.strip * STRIP, -1)
            val right = extent(map, poly, min(w, (endStrip + 1) * STRIP) - 1, +1)
            lines += Line(poly, left, right)
        }
        return lines.sortedBy { it.y((it.minX + it.maxX) / 2.0) }
    }

    /**
     * Уточняет край линейки. Сначала от границы полосы идёт внутрь до первого пикселя линейки (линейка может начинаться
     * посреди полосы), затем наружу, пока вдоль кривой есть пиксели (пропуски до 6 px допустимы).
     */
    private fun extent(map: SegMap, poly: Poly, start: Int, dir: Int): Int {
        val staffCls = SegClass.STAFF.toByte()
        fun hit(x: Int): Boolean {
            if (x !in 0 until map.width) return false
            val yc = Math.round(poly.at(x.toDouble())).toInt()
            for (y in yc - 2..yc + 2) if (y in 0 until map.height && map.cls[y * map.width + x] == staffCls) return true
            return false
        }
        var x = start
        var steps = 0
        while (!hit(x) && steps < STRIP) { x -= dir; steps++ }      // внутрь
        var last = x; var misses = 0
        while (x in 0 until map.width && misses <= 6) {
            if (hit(x)) { last = x; misses = 0 } else misses++
            x += dir
        }
        return last
    }

    /**
     * Пятёрки линий с равным шагом. Посторонние «линии» (куски лиг, вилок, титров) между линейками
     * пропускаются: шаг берётся от крайних линий, три средние ищутся там, где им положено быть.
     */
    fun groupStaves(lines: List<Line>, h: Int): List<DetectedStaff> {
        val used = BooleanArray(lines.size)
        val staves = ArrayList<DetectedStaff>()
        fun overlap(a: Line, b: Line): Pair<Int, Int> = max(a.minX, b.minX) to min(a.maxX, b.maxX)
        for (i in lines.indices) {
            if (used[i]) continue
            for (m in i + 1 until lines.size) {
                if (used[m]) continue
                val (ox0, ox1) = overlap(lines[i], lines[m])
                if (ox1 - ox0 < MIN_RUN) continue
                val xc = (ox0 + ox1) / 2.0
                val span = lines[m].y(xc) - lines[i].y(xc)
                if (span > 4 * 40.0) continue
                if (span < 4 * 6.0) continue
                val d = span / 4.0
                val chain = IntArray(5); chain[0] = i; chain[4] = m
                var ok = true
                for (k in 1..3) {
                    val target = lines[i].y(xc) + k * d
                    var best = -1; var bestDiff = Double.MAX_VALUE
                    for (q in i + 1 until m) {
                        if (q >= lines.size) break
                        val (qa, qb) = overlap(lines[i], lines[q])
                        if (xc < qa || xc > qb) continue
                        val diff = Math.abs(lines[q].y(xc) - target)
                        if (diff < bestDiff) { bestDiff = diff; best = q }
                    }
                    if (best < 0 || used[best] || bestDiff > 0.12 * d) { ok = false; break }
                    chain[k] = best
                }
                if (!ok) continue
                // наклоны линеек одной пятёрки должны быть близки
                val slopes = chain.map { lines[it].poly.c1 }
                if (slopes.max() - slopes.min() > 30.0) continue   // c1 — пикселей на 1000 пикселей ширины
                val x0 = chain.maxOf { lines[it].minX }; val x1 = chain.minOf { lines[it].maxX }
                if (x1 - x0 <= MIN_RUN) continue
                val gx0 = chain.minOf { lines[it].minX }; val gx1 = chain.maxOf { lines[it].maxX }
                val xm = (gx0 + gx1) / 2.0
                staves += DetectedStaff(gx0, gx1, lines[i].y(xm).toInt(), lines[m].y(xm).toInt(), d, lines[i].poly, lines[m].poly)
                chain.forEach { used[it] = true }
                break
            }
        }
        staves += inferFourLineStaves(lines, used, staves)
        return staves.sortedBy { (it.minY + it.maxY) / 2 }
    }

    /**
     * Запасной проход: сегментация иногда «теряет» одну линейку (её закрывает лига). Четыре равноотстоящие
     * линии на всю ширину достраиваются до нотоносца; недостающий край выбирается так, чтобы промежуток до соседней
     * строки был ближе к типичному на странице.
     */
    private fun inferFourLineStaves(lines: List<Line>, used: BooleanArray, found: List<DetectedStaff>): List<DetectedStaff> {
        val ordered = found.sortedBy { it.minY }
        val gaps = (0 until ordered.size - 1).map { ordered[it + 1].minY - ordered[it].maxY }.filter { it in 40..400 }.sorted()
        val typicalGap = gaps.getOrNull(gaps.size / 2)?.toDouble()
        val out = ArrayList<DetectedStaff>()
        val free = lines.indices.filter { !used[it] }.sortedBy { lines[it].y((lines[it].minX + lines[it].maxX) / 2.0) }
        var i = 0
        while (i + 3 < free.size) {
            val c = intArrayOf(free[i], free[i + 1], free[i + 2], free[i + 3]).map { lines[it] }
            val x0 = c.maxOf { it.minX }; val x1 = c.minOf { it.maxX }
            if (x1 - x0 < 600) { i++; continue }
            val xc = (x0 + x1) / 2.0
            val ys = c.map { it.y(xc) }
            val g = (0 until 3).map { ys[it + 1] - ys[it] }
            val d = g.average()
            if (d < 6 || d > 40 || g.max() - g.min() > 0.2 * d) { i++; continue }
            val gx0 = c.minOf { it.minX }; val gx1 = c.maxOf { it.maxX }
            val xm = (gx0 + gx1) / 2.0
            val firstP = c.first().poly; val lastP = c.last().poly
            // кандидат: пятая линия снизу или сверху
            val bottomCand = Triple(firstP, Poly(lastP.c0 + d, lastP.c1, lastP.c2, lastP.x0), lastP.at(xm) + d - firstP.at(xm))
            val topCand = Triple(Poly(firstP.c0 - d, firstP.c1, firstP.c2, firstP.x0), lastP, lastP.at(xm) - (firstP.at(xm) - d))
            val above = found.filter { it.maxY < ys.first() }.maxOfOrNull { it.maxY.toDouble() }
            val below = found.filter { it.minY > ys.last() }.minOfOrNull { it.minY.toDouble() }
            fun cost(top: Double, bottom: Double): Double {
                if (typicalGap == null) return 0.0
                var cst = 0.0
                if (above != null) cst += Math.abs((top - above) - typicalGap)
                if (below != null) cst += Math.abs((below - bottom) - typicalGap)
                return cst
            }
            val bottomCost = cost(firstP.at(xm), lastP.at(xm) + d)
            val topCost = cost(firstP.at(xm) - d, lastP.at(xm))
            val chosen = if (topCost < bottomCost) Triple(topCand.first, topCand.second, d) else Triple(bottomCand.first, bottomCand.second, d)
            val (tp, bp, _) = chosen
            out += DetectedStaff(gx0, gx1, tp.at(xm).toInt(), bp.at(xm).toInt(), d, tp, bp)
            i += 4
        }
        return out
    }

    /**
     * Наклон линеек (dy/dx) по маске нотоносцев: ищем наклон, при котором проекция пикселей на вертикаль
     * наиболее «острая» (линейки сливаются в узкие пики). Диапазон ±6°.
     */
    fun estimateSlope(map: SegMap): Double {
        val staffCls = SegClass.STAFF.toByte()
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (y in 0 until map.height step 2) for (x in 0 until map.width step 2) if (map.cls[y * map.width + x] == staffCls) { xs += x; ys += y }
        if (xs.size < 500) return 0.0
        val cx = map.width / 2.0
        val bins = map.height + map.width
        fun score(t: Double): Double {
            val h = IntArray(bins)
            val off = map.width / 2
            for (i in xs.indices) {
                val yy = (ys[i] - (xs[i] - cx) * t).toInt() + off
                if (yy in 0 until bins) h[yy]++
            }
            var s = 0.0
            for (v in h) s += v.toDouble() * v
            return s
        }
        var best = 0.0; var bestScore = score(0.0)
        var deg = -6.0
        while (deg <= 6.0) { val t = Math.tan(Math.toRadians(deg)); val sc = score(t); if (sc > bestScore * 1.0001) { bestScore = sc; best = t }; deg += 0.25 }
        val base = Math.toDegrees(Math.atan(best))
        var d = base - 0.2
        while (d <= base + 0.2) { val t = Math.tan(Math.toRadians(d)); val sc = score(t); if (sc > bestScore) { bestScore = sc; best = t }; d += 0.05 }
        return best
    }

    /**
     * Группировка нотоносцев в системы: два соседних нотоносца входят в одну систему, если слева
     * их соединяет сплошная вертикальная линия (системная тактовая черта/скобка).
     */
    fun systems(staves: List<DetectedStaff>, page: GrayImage): List<List<DetectedStaff>> {
        if (staves.isEmpty()) return emptyList()
        val out = ArrayList<MutableList<DetectedStaff>>()
        out += mutableListOf(staves[0])
        for (k in 1 until staves.size) {
            if (connected(page, staves[k - 1], staves[k])) out.last() += staves[k] else out += mutableListOf(staves[k])
        }
        return out
    }

    private fun connected(page: GrayImage, upper: DetectedStaff, lower: DetectedStaff): Boolean {
        val xl = min(upper.minX, lower.minX).toDouble() + 4.0
        val ys0 = upper.bottomAt(xl).toInt() + 3; val ys1 = lower.topAt(xl).toInt() - 3
        if (ys1 - ys0 < 4) return true
        // вертикаль (системная черта / скобка) может быть чуть левее или правее начала линеек и слегка наклонена
        var best = 0.0
        for (xc in (xl.toInt() - 18)..(xl.toInt() + 14)) {
            if (xc < 1 || xc >= page.width - 1) continue
            var covered = 0
            for (y in ys0 until ys1) {
                if (page[xc - 1, y] < 160 || page[xc, y] < 160 || page[xc + 1, y] < 160) covered++
            }
            best = max(best, covered.toDouble() / (ys1 - ys0))
        }
        return best >= 0.88
    }

    /**
     * Доля тёмных пикселей под началом нотоносца, где в «теноровом» скрипичном ключе стоит маленькая «8».
     * Область: сразу под нижней линейкой, справа от ключа, выше следующего нотоносца.
     */
    fun octaveMarkScore(page: GrayImage, staff: DetectedStaff, nextStaffTop: Int?): Double {
        val u = staff.unit
        val x0 = (staff.minX + 0.3 * u).toInt(); val x1 = (staff.minX + 3.8 * u).toInt()
        val xl = staff.minX + 2 * u                         // линейки в начале строки (с учётом наклона)
        val y0 = (staff.bottomAt(xl) + 1.5 * u).toInt()
        var y1 = (staff.bottomAt(xl) + 3.2 * u).toInt()
        if (nextStaffTop != null) y1 = min(y1, (nextStaffTop - 0.4 * u).toInt())
        if (y1 <= y0 || x1 <= x0) return 0.0
        var dark = 0; var total = 0
        for (y in max(0, y0) until min(page.height, y1)) for (x in max(0, x0) until min(page.width, x1)) { total++; if (page[x, y] < 110) dark++ }
        return if (total == 0) 0.0 else dark.toDouble() / total
    }

    /**
     * Доля «текстовых» тёмных пикселей под нотоносцем (между ним и следующей строкой системы): так находится текст песни.
     * Учитываются только тёмные пиксели, не отнесённые сегментацией к нотам/линейкам/штилям.
     */
    fun textBelowScore(page: GrayImage, seg: SegMap, staff: DetectedStaff, nextTop: Double?): Double {
        val u = staff.unit
        val xm = (staff.minX + staff.maxX) / 2.0
        val y0 = (staff.bottomAt(xm) + 1.3 * u).toInt()
        var y1 = (staff.bottomAt(xm) + 5.0 * u).toInt()
        if (nextTop != null) y1 = min(y1, (nextTop - 1.0 * u).toInt())
        val x0 = (staff.minX + 8 * u).toInt(); val x1 = staff.maxX
        if (y1 <= y0 || x1 <= x0) return 0.0
        var dark = 0; var total = 0
        for (y in max(0, y0) until min(page.height, y1)) for (x in max(0, x0) until min(page.width, x1)) {
            total++
            if (page[x, y] < 120 && seg.cls[y * page.width + x].toInt() == SegClass.BACKGROUND) dark++
        }
        return if (total == 0) 0.0 else dark.toDouble() / total
    }

    /** Индекс нужного нотоносца внутри системы; null — такого нет. */
    fun indexInSystem(sel: Staff, size: Int): Int? = when (sel) {
        Staff.VOCAL_OR_TOP -> 0
        Staff.RIGHT_HAND -> if (size >= 3) 1 else 0
        Staff.LEFT_HAND -> if (size >= 3) 2 else if (size == 2) 1 else null
    }.takeIf { it == null || it < size }

    enum class Staff { VOCAL_OR_TOP, RIGHT_HAND, LEFT_HAND }
}
