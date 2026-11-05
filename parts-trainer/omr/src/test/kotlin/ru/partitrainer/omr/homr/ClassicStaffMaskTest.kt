package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Test

/** Быстрый поиск линеек без нейросети: синтетические страницы (модели не нужны). */
class ClassicStaffMaskTest {
    private fun page(slope: Double = 0.0, staves: List<Int> = listOf(200, 500), noise: Boolean = false): GrayImage {
        val w = 1920; val h = 800
        val img = GrayImage(w, h)
        java.util.Arrays.fill(img.data, 0xFF.toByte())
        val rnd = java.util.Random(7)
        for (top in staves) for (line in 0 until 5) for (x in 150 until 1800) {
            val y = (top + line * 14 + (x - 960) * slope).toInt()
            for (t in 0..1) if (y + t in 0 until h) img[x, y + t] = if (noise) 60 + rnd.nextInt(60) else 0
        }
        // «штили» и «ноты» пересекают линейки — линии не должны рваться
        for (top in staves) for (k in 0 until 20) {
            val x = 300 + k * 70
            for (y in top - 30 until top + 90) if (y in 0 until h) img[x, (y + ((x - 960) * slope).toInt()).coerceIn(0, h - 1)] = 0
        }
        // левая системная черта
        for (y in staves.first() until staves.last() + 57) img[145, y] = 0
        return img
    }

    @Test fun findsFlatStaves() {
        val p = page()
        val staves = StaffDetector.detect(ClassicStaffMask.mask(p))
        assertEquals(2, staves.size)
        assertEquals(14.0, staves[0].unit, 0.6)
        assertTrue(Math.abs(staves[0].minY - 200) <= 3)
        assertTrue("начало строки не должно уезжать на системную черту", staves[0].minX >= 150 - 4)
    }

    @Test fun estimatesAndRemovesTilt() {
        val p = page(slope = 0.02)   // ≈ 1,1°
        val dark = ClassicStaffMask.dark(p)
        val slope = ClassicStaffMask.estimateSlope(dark, p.width, p.height)
        assertEquals(0.02, slope, 0.004)
        val flat = ImageOps.rotate(p, Math.atan(slope))
        assertEquals(2, StaffDetector.detect(ClassicStaffMask.mask(flat)).size)
    }

    @Test fun toleratesLightNoisyLines() {
        val staves = StaffDetector.detect(ClassicStaffMask.mask(page(noise = true)))
        assertEquals(2, staves.size)
    }

    @Test fun emptyPageHasNoStaves() {
        val blank = GrayImage(1920, 800).also { java.util.Arrays.fill(it.data, 0xFF.toByte()) }
        assertTrue(StaffDetector.detect(ClassicStaffMask.mask(blank)).isEmpty())
    }

    @Test fun systemsGroupedByLeftLine() {
        val p = page()
        val staves = StaffDetector.detect(ClassicStaffMask.mask(p))
        // линия слева соединяет оба нотоносца — одна система
        assertEquals(listOf(2), StaffDetector.systems(staves, p).map { it.size })
    }
}
