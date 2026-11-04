package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Test
import java.nio.FloatBuffer

/** Склейка окон сегментации без нейросети: окна задаются «уверенностями» по классам. */
class SegnetMergeTest {
    private val k = 6
    private val win = Segnet.WIN
    private val plane = win * win

    /** Окно, где во всех точках выбран класс [c] с перевесом [margin]. */
    private fun tile(c: Int, margin: Float = 2f): FloatBuffer {
        val a = FloatArray(k * plane)
        for (p in 0 until plane) a[c * plane + p] = margin
        return FloatBuffer.wrap(a)
    }

    private fun merge(w: Int, h: Int, strategy: SegMerge.Strategy, classOf: (y: Int, x: Int) -> Pair<Int, Float>): SegMap {
        val m = SegMerge(w, h, Segnet.starts(h), Segnet.starts(w), strategy)
        for (y in Segnet.starts(h)) for (x in Segnet.starts(w)) { val (c, mg) = classOf(y, x); m.add(y, x, tile(c, mg), 0, k) }
        return m.result()
    }

    @Test fun windowStartsCoverNonMultipleSizes() {
        assertEquals(listOf(0, 320, 640, 960, 1280, 1600), Segnet.starts(1920))
        assertEquals(listOf(0, 320, 457), Segnet.starts(777))
        assertEquals(listOf(0), Segnet.starts(319))
        assertEquals(listOf(0), Segnet.starts(320))
        assertEquals(listOf(0, 320, 321), Segnet.starts(641))     // как в homr: последнее окно прижато к краю
    }

    @Test fun disagreeingWindowsNeverGiveAThirdClass() {
        // нижний ряд окон говорит «линейка» (4), верхние — «фон» (0); ID_AVERAGE даёт в перекрытии 2 («головка ноты»)
        for (s in SegMerge.Strategy.entries) {
            val map = merge(641, 777, s) { y, _ -> if (y == Segnet.starts(777).last()) SegClass.STAFF to 3f else SegClass.BACKGROUND to 1f }
            val classes = map.cls.map { it.toInt() }.toSet()
            if (s == SegMerge.Strategy.ID_AVERAGE) assertTrue(SegClass.NOTEHEAD in classes)
            else assertEquals("$s", setOf(SegClass.BACKGROUND, SegClass.STAFF), classes)
        }
    }

    @Test fun moreConfidentWindowWinsInOverlap() {
        val h = 777; val last = Segnet.starts(h).last()
        val map = merge(400, h, SegMerge.Strategy.DEFAULT) { y, _ -> if (y == last) SegClass.STAFF to 5f else SegClass.STEMS_RESTS to 1f }
        assertEquals(SegClass.STAFF, map.cls[500 * 400 + 10].toInt())    // строка 500 — в обоих окнах (320..640 и 457..777)
        assertEquals(SegClass.STEMS_RESTS, map.cls[400 * 400 + 10].toInt())
    }

    @Test fun everyPixelIsCoveredForOddSizes() {
        for ((w, h) in listOf(1000 to 777, 641 to 641, 319 to 500, 200 to 150, 1920 to 2718)) {
            val map = merge(w, h, SegMerge.Strategy.DEFAULT) { _, _ -> SegClass.SYMBOLS to 1f }
            assertEquals(w * h, map.cls.size)
            assertTrue("$w×$h", map.cls.all { it.toInt() == SegClass.SYMBOLS })
        }
    }

    @Test fun agreeingWindowsGiveSameResultAsOldMerge() {
        val a = merge(1000, 777, SegMerge.Strategy.ID_AVERAGE) { y, x -> ((y + x) / 320 % k) to 1f }
        val b = merge(1000, 777, SegMerge.Strategy.DEFAULT) { y, x -> ((y + x) / 320 % k) to 1f }
        // вне полос перекрытия карты совпадают
        val ys = Segnet.starts(777); val xs = Segnet.starts(1000)
        val rowBand = ys.last() until ys[ys.size - 2] + 320; val colBand = xs.last() until xs[xs.size - 2] + 320
        for (y in 0 until 777) for (x in 0 until 1000) if (y !in rowBand && x !in colBand) assertEquals(a.cls[y * 1000 + x], b.cls[y * 1000 + x])
    }

    @Test fun skippedBlankWindowStaysBackgroundAndHalfCoveredBandUsesTheOther() {
        val w = 400; val h = 777
        val m = SegMerge(w, h, Segnet.starts(h), Segnet.starts(w))
        m.add(Segnet.starts(h).last(), 0, tile(SegClass.NOTEHEAD), 0, k)     // только нижнее окно; остальные «пустые»
        val map = m.result()
        assertEquals(SegClass.BACKGROUND, map.cls[100 * w].toInt())
        assertEquals(SegClass.NOTEHEAD, map.cls[500 * w].toInt())          // полоса перекрытия, покрыта одним окном
        assertEquals(SegClass.NOTEHEAD, map.cls[700 * w].toInt())
    }
}
