package ru.partitrainer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.partitrainer.data.EngineId
import ru.partitrainer.omr.OmrJob
import ru.partitrainer.omr.StaffSelection
import ru.partitrainer.util.FileKinds

class AppUnitTest {
    @Test fun detectsFileKindsByContent() {
        assertEquals(FileKinds.Kind.PDF, FileKinds.detect("x", null, "%PDF-1.7".toByteArray()))
        assertEquals(FileKinds.Kind.MIDI, FileKinds.detect("song", null, "MThd\u0000\u0000\u0000\u0006".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(FileKinds.Kind.MXL, FileKinds.detect("a.mxl", null, byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0, 0)))
        assertEquals(FileKinds.Kind.MUSICXML, FileKinds.detect("a.musicxml", null, "<?xml ve".toByteArray()))
        assertEquals(FileKinds.Kind.IMAGE, FileKinds.detect("p", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)))
        assertEquals(FileKinds.Kind.UNKNOWN, FileKinds.detect("a.bin", null, ByteArray(8)))
    }

    @Test fun cacheKeyDependsOnEverythingThatChangesTheResult() {
        val base = OmrJob("/p", "abc", true, "t", listOf(0, 1), listOf(0, 0), StaffSelection.VOCAL_TOP, EngineId.CLAUDE_VISION)
        assertEquals(base.cacheKey(), base.copy(title = "другое имя", filePath = "/q").cacheKey())
        assertNotEquals(base.cacheKey(), base.copy(staff = StaffSelection.RIGHT_HAND).cacheKey())
        assertNotEquals(base.cacheKey(), base.copy(rotations = listOf(90, 0)).cacheKey())
        assertNotEquals(base.cacheKey(), base.copy(pages = listOf(0)).cacheKey())
        assertNotEquals(base.cacheKey(), base.copy(engine = EngineId.ON_DEVICE).cacheKey())
    }

    @Test fun allStaffExpandsToThreeLines() {
        assertEquals(3, StaffSelection.ALL.expand().size)
        assertEquals(listOf(StaffSelection.LEFT_HAND), StaffSelection.LEFT_HAND.expand())
    }
}
