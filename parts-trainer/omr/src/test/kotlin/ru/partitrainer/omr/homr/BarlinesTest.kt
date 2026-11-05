package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Test

class BarlinesTest {
    /** Страница 800×300: стан из 5 линеек (интервал 10 px) с тактовыми чертами и «штилями» разной длины. */
    private fun page(bars: List<Int>, stems: List<Int> = emptyList()): GrayImage {
        val g = GrayImage(800, 300); java.util.Arrays.fill(g.data, 255.toByte())
        for (i in 0..4) for (x in 50..750) g[x, 100 + i * 10] = 0
        for (b in bars) for (y in 100..140) { g[b, y] = 0; g[b + 1, y] = 0 }
        for (s in stems) for (y in 85..130) g[s, y] = 0           // штиль не доходит до нижней линейки
        return g
    }
    private val staff = DetectedStaff(50, 750, 100, 140, 10.0)

    @Test fun findsBarlinesAndIgnoresStems() {
        val b = Barlines.boundaries(page(listOf(250, 450, 750), listOf(150, 350, 550)), staff)
        assertEquals(listOf(50.0, 250.5, 450.5, 750.0), b)
    }

    @Test fun doubleBarMerges() {
        val g = page(listOf(300, 306, 750))
        val b = Barlines.boundaries(g, staff)
        assertEquals(3, b.size)
        assertEquals(303.5, b[1], 1.0)
    }

    @Test fun noBarlinesGivesWholeStaff() {
        assertEquals(listOf(50.0, 750.0), Barlines.boundaries(page(emptyList()), staff))
    }

    @Test fun rectsMatchOrSplitEvenly() {
        val cols = Barlines.columns(page(listOf(250, 450, 750)), staff, listOf(staff), 0.0)
        val r = LayoutBuilder.rects(cols, 3, 0)
        assertEquals(3, r.size)
        assertEquals(50f / 800f, r[0].x0, 1e-3f); assertEquals(250.5f / 800f, r[0].x1, 1e-3f)
        assertTrue(r[0].y0 < 100f / 300f && r[0].y1 > 140f / 300f)
        // число тактов не совпало с числом черт — деление поровну
        val even = LayoutBuilder.rects(cols, 4, 1)
        assertEquals(4, even.size)
        assertEquals(1, even[0].page)
        assertEquals(even[0].x1, even[1].x0, 1e-6f)
        assertEquals(750f / 800f, even.last().x1, 1e-3f)
    }

    @Test fun rotationMapsBack() {
        val cols = SysColumns(PageFrame(800, 300, 0.0), 100.0, 140.0, listOf(0.0, 400.0))
        val tilt = SysColumns(PageFrame(800, 300, 0.02), 100.0, 140.0, listOf(0.0, 400.0))
        val a = LayoutBuilder.rects(cols, 1, 0)[0]; val b = LayoutBuilder.rects(tilt, 1, 0)[0]
        assertTrue(b.y1 - b.y0 > a.y1 - a.y0)      // повёрнутая рамка шире по вертикали
        assertTrue(Math.abs(b.x0 - a.x0) < 0.02f)
    }

    @Test fun layoutFollowsTokenMeasureCounts() {
        fun ev(r: String, p: String = ".") = EncodedSymbol(r, p, "_", ".", "_", ".")
        val t1 = listOf(ev("timeSignature/4"), ev("note_4", "C4"), ev("barline"), ev("note_4", "D4"), ev("barline"))
        val t2 = listOf(ev("note_4", "E4"), ev("barline"))
        val cols = Barlines.columns(page(listOf(400, 750)), staff, listOf(staff), 0.0)
        val layout = LayoutBuilder.build(listOf(t1, t2), listOf(cols, null), listOf(0, 0), listOf(ru.partitrainer.core.omr.PageRef(3, 90)))
        assertEquals(3, layout.rects.size)                    // 2 такта на первом нотоносце + 1 на втором
        assertNotNull(layout.rects[0]); assertNotNull(layout.rects[1]); assertNull(layout.rects[2])
        assertEquals(3, layout.pages[0].index)
    }
}
