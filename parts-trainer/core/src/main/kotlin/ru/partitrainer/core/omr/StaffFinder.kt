package ru.partitrainer.core.omr

/** Нотоносец: y первой и последней линеек (в пикселях анализируемого изображения). */
data class StaffBox(val top: Int, val bottom: Int) {
    val height get() = bottom - top
}

/**
 * Грубый поиск нотоносцев по горизонтальной проекции тёмных пикселей. Нужен только для подсветки
 * выбранной строки на миниатюре (приблизительно); на распознавание не влияет.
 */
object StaffFinder {
    /** @param lum яркость 0..255, построчно, размер [w]×[h]. */
    fun find(lum: IntArray, w: Int, h: Int): List<StaffBox> {
        if (w <= 0 || h <= 0 || lum.size < w * h) return emptyList()
        val dark = DoubleArray(h)
        for (y in 0 until h) {
            var s = 0L
            val o = y * w
            for (x in 0 until w) s += 255 - lum[o + x]
            dark[y] = s / (255.0 * w)
        }
        val max = dark.max()
        if (max < 0.05) return emptyList()
        val thr = max * 0.45
        // Кластеры подряд идущих «линейных» строк → центры линеек.
        val lines = ArrayList<Double>()
        var y = 0
        while (y < h) {
            if (dark[y] >= thr) {
                var e = y
                while (e + 1 < h && dark[e + 1] >= thr) e++
                if (e - y + 1 <= maxOf(4, h / 150)) lines += (y + e) / 2.0
                y = e + 1
            } else y++
        }
        // Пятёрки линеек с почти равным шагом.
        val staves = ArrayList<StaffBox>()
        var i = 0
        while (i + 4 < lines.size) {
            val gaps = (0 until 4).map { lines[i + it + 1] - lines[i + it] }
            val mn = gaps.min(); val mx = gaps.max()
            if (mn >= 2 && mx / mn <= 1.35 && mx < h / 12.0) {
                staves += StaffBox(lines[i].toInt(), lines[i + 4].toInt()); i += 5
            } else i++
        }
        return staves
    }

    /** Сколько нотоносцев в системе: 3 для «вокал + фортепиано», иначе неизвестно (0). */
    fun perSystem(count: Int): Int = when {
        count == 0 -> 0
        count % 3 == 0 -> 3
        count % 2 == 0 -> 2
        else -> 1
    }

    /** Индекс нотоносца в системе: 0 — вокал/верх, 1 — правая рука, 2 — левая рука; null — все. */
    fun select(staves: List<StaffBox>, indexInSystem: Int?): List<StaffBox> {
        val per = perSystem(staves.size)
        if (per == 0 || indexInSystem == null) return staves
        return staves.filterIndexed { i, _ -> i % per == indexInSystem }
    }
}
