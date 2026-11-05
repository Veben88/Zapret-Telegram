package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Test

class OmrProfileTest {
    @Test fun stagesTimesMemoryAndMerge() {
        val p = OmrProfile("page 1")
        p.stage("resize") { Thread.sleep(20) }
        p.stage("resize") { Thread.sleep(5) }
        val big = p.stage("segnet") { ByteArray(32 shl 20).also { it[it.size - 1] = 1 } }     // 32 МБ
        assertEquals(1, big[big.size - 1].toInt())
        assertTrue(p.ms().getValue("resize") >= 25)
        assertEquals(2, p.calls("resize"))
        assertEquals(listOf("resize", "segnet"), p.ms().keys.toList().sorted())
        // на Linux/Android размер процесса читается из /proc/self/status
        if (java.io.File("/proc/self/status").isFile) {
            assertTrue(MemoryWatch.rssKb() > 0)
            assertTrue(p.peakRssKb.get() > 0)
            assertTrue(p.stagePeakRssKb().getValue("segnet") > 0)
        }
        val doc = OmrProfile("total").also { it.merge(p); it.merge(p) }
        assertEquals(4, doc.calls("resize"))
        val json = doc.toJson()
        assertTrue(json.startsWith("{\"label\":\"total\",\"stages\":{"))
        assertTrue(json.contains("\"segnet\":{\"ms\":"))
    }

    @Test fun parallelAddsAreNotLost() {
        val p = OmrProfile()
        val threads = (0 until 8).map { Thread { repeat(10_000) { p.add("decoder", 1_000_000) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(80_000L, p.ms()["decoder"])
        assertEquals(80_000L, p.calls("decoder"))
    }
}
