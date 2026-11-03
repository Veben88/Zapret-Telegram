package ru.partitrainer.omr.homr

import ru.partitrainer.core.omr.StaffBox
import kotlin.math.max
import kotlin.math.min

/** Найденный нотоносец в координатах страницы (ширина 1920). */
data class DetectedStaff(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int, val unit: Double) {
    val box get() = StaffBox(minY, maxY)
}

/**
 * Упрощённый поиск нотоносцев по маске класса «staff» от segnet (страницы PDF прямые).
 * В отличие от homr не строит повёрнутые рамки и не выпрямляет линейки: линия — это строка пикселей
 * с длинными горизонтальными пробегами; пятёрки линий с равным шагом образуют нотоносец.
 */
object StaffDetector {
    private const val MIN_RUN = 60          // минимальная длина пробега, пикселей на странице шириной 1920
    private const val MAX_GAP = 4           // «дыры» в линии (нота поверх линейки), пикселей

    fun detect(map: SegMap): List<DetectedStaff> {
        val w = map.width; val h = map.height
        val staffCls = SegClass.STAFF.toByte()
        // 1) длина пробегов по строкам, начало/конец крайних пробегов
        val longSum = IntArray(h)
        val minX = IntArray(h) { Int.MAX_VALUE }
        val maxX = IntArray(h) { -1 }
        for (y in 0 until h) {
            var x = 0
            val row = y * w
            while (x < w) {
                if (map.cls[row + x] != staffCls) { x++; continue }
                val start = x
                var end = x
                var gap = 0
                var cx = x
                while (cx < w) {
                    if (map.cls[row + cx] == staffCls) { end = cx; gap = 0 } else { gap++; if (gap > MAX_GAP) break }
                    cx++
                }
                if (end - start + 1 >= MIN_RUN) {
                    longSum[y] += end - start + 1
                    minX[y] = min(minX[y], start); maxX[y] = max(maxX[y], end)
                }
                x = end + 1
            }
        }
        // 2) линии = связные группы строк
        class Line(val y: Double, val minX: Int, val maxX: Int)
        val lines = ArrayList<Line>()
        var y = 0
        val maxThickness = max(6, h / 300)
        while (y < h) {
            if (longSum[y] == 0) { y++; continue }
            var e = y
            while (e + 1 < h && longSum[e + 1] > 0) e++
            if (e - y + 1 <= maxThickness) {
                var wy = 0.0; var ws = 0.0; var mn = Int.MAX_VALUE; var mx = -1
                for (r in y..e) { wy += r * longSum[r].toDouble(); ws += longSum[r]; mn = min(mn, minX[r]); mx = max(mx, maxX[r]) }
                lines += Line(wy / ws, mn, mx)
            }
            y = e + 1
        }
        // 3) пятёрки линий с одинаковым шагом и пересекающимися по X
        val staves = ArrayList<DetectedStaff>()
        var i = 0
        while (i + 4 < lines.size) {
            val gaps = DoubleArray(4) { lines[i + it + 1].y - lines[i + it].y }
            val mn = gaps.min(); val mx = gaps.max()
            val xMin = (0..4).maxOf { lines[i + it].minX }; val xMax = (0..4).minOf { lines[i + it].maxX }
            if (mn >= 4.0 && mx / mn <= 1.3 && mx < 60.0 && xMax - xMin > MIN_RUN) {
                val u = gaps.average()
                val x0 = (0..4).minOf { lines[i + it].minX }; val x1 = (0..4).maxOf { lines[i + it].maxX }
                staves += DetectedStaff(x0, x1, lines[i].y.toInt(), lines[i + 4].y.toInt(), u)
                i += 5
            } else i++
        }
        return staves
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
        val y0 = upper.maxY + 3; val y1 = lower.minY - 3
        if (y1 - y0 < 4) return true
        val xc = min(upper.minX, lower.minX)
        val xa = max(0, xc - 5); val xb = min(page.width - 1, xc + 5)
        var covered = 0
        for (y in y0 until y1) {
            var dark = false
            for (x in xa..xb) if (page[x, y] < 140) { dark = true; break }
            if (dark) covered++
        }
        return covered >= 0.9 * (y1 - y0)
    }

    /** Индекс нужного нотоносца внутри системы; null — такого нет. */
    fun indexInSystem(sel: Staff, size: Int): Int? = when (sel) {
        Staff.VOCAL_OR_TOP -> 0
        Staff.RIGHT_HAND -> if (size >= 3) 1 else 0
        Staff.LEFT_HAND -> if (size >= 3) 2 else if (size == 2) 1 else null
    }.takeIf { it == null || it < size }

    enum class Staff { VOCAL_OR_TOP, RIGHT_HAND, LEFT_HAND }
}
