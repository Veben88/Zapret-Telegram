package ru.partitrainer.omr.homr

/**
 * Уточнение положения ноты на странице: декодер даёт точку внимания, у пустых (половинных, целых) нот она в среднем на
 * четверть интервала в стороне. Точка переносится в ближайшее «пятно» головки ноты: в окне ±0,45 интервала доля тёмных
 * пикселей (без линеек) наибольшая и не меньше [MIN_SCORE] — закрашенная головка ≈ 0,75, пустая ≈ 0,4, штиль ≈ 0,15.
 * Ищется в радиусе 0,8 интервала; ничего похожего — точка остаётся. Меняются только координаты, не ноты.
 */
object NoteSnap {
    private const val MIN_SCORE = 0.3
    private const val RADIUS = 0.8
    private const val HALF_BOX = 0.45

    /** Тёмное без линеек: маска линеек — та же, что у быстрого поиска нотоносцев. */
    fun inkWithoutLines(page: GrayImage): BooleanArray {
        val dark = ClassicStaffMask.dark(page)
        val lines = ClassicStaffMask.mask(page, dark)
        val ink = BooleanArray(page.width * page.height)
        for (i in ink.indices) ink[i] = page.data[i].toInt() and 0xFF < 160 && lines.cls[i].toInt() != SegClass.STAFF
        return ink
    }

    class Integral(private val w: Int, h: Int, ink: BooleanArray) {
        private val s = IntArray((w + 1) * (h + 1))
        init {
            for (y in 0 until h) { var row = 0; for (x in 0 until w) { if (ink[y * w + x]) row++; s[(y + 1) * (w + 1) + x + 1] = s[y * (w + 1) + x + 1] + row } }
        }
        fun count(x0: Int, y0: Int, x1: Int, y1: Int): Int = s[y1 * (w + 1) + x1] - s[y0 * (w + 1) + x1] - s[y1 * (w + 1) + x0] + s[y0 * (w + 1) + x0]
    }

    /**
     * Новое положение точки (x, y) или null, если головки рядом не видно. По вертикали сдвиг не больше 0,35 интервала:
     * соседние головки аккорда (секунда) стоят на половину интервала выше/ниже — точку нельзя «перетянуть» на них.
     */
    fun snap(page: GrayImage, integral: Integral, x: Double, y: Double, unit: Double): Pair<Double, Double>? {
        val rx = (RADIUS * unit).toInt(); val ry = (0.35 * unit).toInt(); val hb = maxOf(2, (HALF_BOX * unit).toInt())
        val cx = Math.round(x).toInt(); val cy = Math.round(y).toInt()
        fun score(xx: Int, yy: Int): Double {
            val x0 = xx - hb; val y0 = yy - hb; val x1 = xx + hb + 1; val y1 = yy + hb + 1
            if (x0 < 0 || y0 < 0 || x1 > page.width || y1 > page.height) return -1.0
            return integral.count(x0, y0, x1, y1).toDouble() / ((x1 - x0) * (y1 - y0))
        }
        var max = -1.0
        for (yy in cy - ry..cy + ry) for (xx in cx - rx..cx + rx) max = maxOf(max, score(xx, yy))
        if (max < MIN_SCORE) return null
        // среди почти лучших — ближайшая к исходной точке
        var bx = cx; var by = cy; var bd = Double.MAX_VALUE
        for (yy in cy - ry..cy + ry) for (xx in cx - rx..cx + rx) {
            if (score(xx, yy) < max - 0.03) continue
            val d = Math.hypot(xx - x, yy - y); if (d < bd) { bd = d; bx = xx; by = yy }
        }
        return bx.toDouble() to by.toDouble()
    }

    /** Уточнить все ноты строки (координаты — на выровненной странице [page]). */
    fun snapAll(page: GrayImage, integral: Integral, symbols: List<EncodedSymbol>, unit: Double) {
        for (s in symbols) {
            if (!s.rhythm.startsWith("note_") || s.x.isNaN() || s.y.isNaN()) continue
            snap(page, integral, s.x, s.y, unit)?.let { (nx, ny) -> s.x = nx; s.y = ny }
        }
    }
}
