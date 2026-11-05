package ru.partitrainer.omr.homr

/**
 * Быстрая замена нейросети сегментации для поиска линеек: линейки — длинные тонкие горизонтальные тёмные линии.
 * Строит ту же [SegMap] (класс «staff»), которую дальше обрабатывает [StaffDetector]. Для сильно изогнутых страниц
 * (разворот книги) и плохих фото конвейер откатывается на нейросеть.
 */
object ClassicStaffMask {
    private const val WINDOW = 61          // окно локального порога
    private const val MIN_RUN = 100       // минимальная длина линии, пикселей (страница шириной 1920)
    private const val MAX_GAP = 16        // разрыв (пустые головки нот) внутри линии
    private const val MIN_FILL = 0.45     // доля тёмных пикселей в пробеге

    /** Тёмные пиксели: заметно темнее локального среднего (интегральное изображение). */
    fun dark(page: GrayImage): BooleanArray {
        val w = page.width; val h = page.height
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) { row += page.data[y * w + x].toInt() and 0xFF; integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row }
        }
        val r = WINDOW / 2
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r); val y1 = minOf(h, y + r + 1)
            for (x in 0 until w) {
                val g = page.data[y * w + x].toInt() and 0xFF
                if (g > 215) continue
                val x0 = maxOf(0, x - r); val x1 = minOf(w, x + r + 1)
                val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = sum.toDouble() / ((x1 - x0) * (y1 - y0))
                if (g < mean * 0.85) out[y * w + x] = true
            }
        }
        removeVertical(out, w, h)
        return out
    }

    /**
     * Убирает высокие вертикальные структуры (системная скобка, тактовые черты, штили): иначе они сливаются с линейками,
     * и левый край нотоносца «уезжает» на скобку. Разрывы в линиях на месте черт ≤ 4 px перекрываются MAX_GAP.
     */
    private fun removeVertical(dark: BooleanArray, w: Int, h: Int) {
        val minLen = 40
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!dark[y * w + x]) { y++; continue }
                var e = y
                while (e + 1 < h && dark[(e + 1) * w + x]) e++
                if (e - y + 1 >= minLen) for (k in y..e) dark[k * w + x] = false
                y = e + 1
            }
        }
    }

    /** Наклон линеек (dy/dx) по тёмным пикселям; диапазон ±6°. */
    fun estimateSlope(dark: BooleanArray, w: Int, h: Int): Double {
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (y in 0 until h step 2) for (x in 0 until w step 3) if (dark[y * w + x]) { xs += x; ys += y }
        if (xs.size < 2000) return 0.0
        val cx = w / 2.0
        val bins = h + w
        val off = w / 2
        fun score(t: Double): Double {
            val hist = IntArray(bins)
            for (i in xs.indices) {
                val yy = (ys[i] - (xs[i] - cx) * t).toInt() + off
                if (yy in 0 until bins) hist[yy]++
            }
            var s = 0.0
            for (v in hist) s += v.toDouble() * v
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

    /** Маска линеек: пробеги тёмных пикселей по строкам (с «заращиванием» разрывов), достаточно длинные и плотные. */
    fun mask(page: GrayImage, dark: BooleanArray = dark(page)): SegMap {
        val w = page.width; val h = page.height
        val cls = ByteArray(w * h)
        val staff = SegClass.STAFF.toByte()
        for (y in 0 until h) {
            val row = y * w
            var x = 0
            while (x < w) {
                if (!dark[row + x]) { x++; continue }
                val start = x; var last = x; var darkCount = 0; var gap = 0
                var cx = x
                while (cx < w) {
                    if (dark[row + cx]) { last = cx; darkCount++; gap = 0 } else { gap++; if (gap > MAX_GAP) break }
                    cx++
                }
                val span = last - start + 1
                if (span >= MIN_RUN && darkCount >= MIN_FILL * span) {
                    for (k in start..last) if (dark[row + k]) cls[row + k] = staff
                }
                x = last + 1
            }
        }
        return SegMap(w, h, cls)
    }
}
