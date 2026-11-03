package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Сверка с эталонным Python-homr. Нужны модели и эталонные файлы:
 *   HOMR_MODELS=/path/to/models HOMR_REF=/path/to/ref gradle -c settings-core.gradle.kts :omr:test
 * Без них тесты пропускаются (в CI моделей нет).
 */
class HomrReferenceTest {
    private val models = System.getenv("HOMR_MODELS")?.takeIf { it.isNotBlank() }?.let(::File)
    private val ref = System.getenv("HOMR_REF")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun modelFiles(): HomrModelFiles {
        val d = models!!
        fun f(prefix: String) = d.listFiles()!!.first { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
        return HomrModelFiles(f("segnet"), f("encoder"), f("decoder"))
    }

    private fun loadGray(f: File): GrayImage {
        val img = ImageIO.read(f)
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        return GrayImage.fromArgb(img.width, img.height, px)
    }

    private fun readGrayPng(f: File) = loadGray(f)

    @Test fun preprocessingMatchesOpenCvAndPillow() {
        assumeTrue(ref != null)
        val src = loadGray(File(ref, "page.png"))
        val resized = ImageOps.resizeToHomrWidth(src)
        val refResized = readGrayPng(File(ref, "page_resized_gray.png"))
        assertEquals(refResized.width, resized.width); assertEquals(refResized.height, resized.height)
        val d1 = meanAbsDiff(resized, refResized)
        println("resize mean abs diff = $d1")
        assertTrue("resize diff $d1", d1 < 1.0)
        val clahe = ImageOps.clahe(resized)
        val refClahe = readGrayPng(File(ref, "page_clahe.png"))
        val d2 = meanAbsDiff(clahe, refClahe)
        println("clahe mean abs diff = $d2")
        assertTrue("clahe diff $d2", d2 < 1.5)
    }

    private fun meanAbsDiff(a: GrayImage, b: GrayImage): Double {
        var s = 0L
        for (i in a.data.indices) s += Math.abs((a.data[i].toInt() and 0xFF) - (b.data[i].toInt() and 0xFF))
        return s.toDouble() / a.data.size
    }

    @Test fun pipelineMatchesPythonTokens() {
        assumeTrue(models != null && ref != null)
        val gray = loadGray(File(ref, "page.png"))
        HomrPipeline(modelFiles()).use { p ->
            val t0 = System.currentTimeMillis()
            val r = p.recognizePage(gray, StaffDetector.Staff.VOCAL_OR_TOP, onProgress = { d, t, m -> println("[$d/$t] $m") })
            println("time ${System.currentTimeMillis() - t0} ms; staves=${r.stavesFound}")
            // homr убирает повторные ключ/тональность/размер в начале следующих строк
            val seen = HashSet<String>()
            val ours = r.staffs.flatMap { it }.filter { s ->
                val key = if (s.rhythm.startsWith("clef") || s.rhythm.startsWith("keySignature") || s.rhythm.startsWith("timeSignature")) s.rhythm.substringBefore('_').substringBefore('/') else null
                key == null || seen.add(key)
            }.map { it.toString() }
            val py = File(ref, "page_tokens.lst").readLines().filter { it.isNotBlank() && !it.startsWith("newline") }
            println("ours=${ours.size} python=${py.size}")
            assertEquals(2, r.stavesFound)
            assertEquals(py, ours)

            // Итоговые ноты: сравнение с MusicXML от Python-homr
            val parsed = TokenParser.parse(r.staffs)
            val asm = ru.partitrainer.core.omr.ClaudeOmr.assemble(parsed)
            val xml = ru.partitrainer.core.io.MusicXmlParser.parse(File(ref, "page.musicxml").inputStream()).score.voices.first().notes
            val mine = asm.score.voices.first().notes
            println("notes ours=${mine.size} python=${xml.size}; key=${parsed.keyFifths} time=${parsed.timeNum}/${parsed.timeDen}")
            assertEquals(xml.map { it.midi }, mine.map { it.midi })
            assertEquals(xml.map { it.beats }, mine.map { it.beats })
            assertEquals(xml.map { it.startBeat }, mine.map { it.startBeat })
            assertEquals(1, parsed.keyFifths); assertEquals(4, parsed.timeNum); assertEquals(4, parsed.timeDen)
        }
    }

    @Test fun voiceAndPianoSelection() {
        assumeTrue(models != null && ref != null && File(ref, "vp.png").exists())
        val gray = loadGray(File(ref, "vp.png"))
        val voice = "E4:1 G4:1 C5:2 D5:1 C5:1 B4:2 A4:1 C5:1 E5:2 D5:2 C5:2 G4:1 A4:1 B4:2 C5:4"
        val rh = "C5:2 E5:2 D5:2 G5:2 C5:1 E5:1 A5:2 B4:2 D5:2 D5:2 G5:2 E5:4"
        HomrPipeline(modelFiles()).use { p ->
            for ((sel, expected) in listOf(StaffDetector.Staff.VOCAL_OR_TOP to voice, StaffDetector.Staff.RIGHT_HAND to rh)) {
                val r = p.recognizePage(gray, sel)
                println("$sel: staves=${r.stavesFound} selected=${r.selected.size}")
                assertEquals(3, r.stavesFound)
                assertEquals(1, r.selected.size)
                val notes = ru.partitrainer.core.omr.ClaudeOmr.assemble(TokenParser.parse(r.staffs)).score.voices.first().notes
                val exp = ru.partitrainer.core.music.NoteDsl.build(ru.partitrainer.core.music.NoteDsl.parseEvents(expected), 0.0, 4.0).first
                println("  got ${notes.map { it.midi }}\n  exp ${exp.map { it.midi }}")
                assertEquals(exp.map { it.midi }, notes.map { it.midi })
                assertEquals(exp.map { it.beats }, notes.map { it.beats })
            }
        }
    }

    @Test fun accidentalsTiesRepeats() {
        assumeTrue(models != null && ref != null && File(ref, "cx.png").exists())
        val gray = loadGray(File(ref, "cx.png"))
        val truth = ru.partitrainer.core.io.MusicXmlParser.parse(File(ref, "cx.musicxml").inputStream())
        HomrPipeline(modelFiles()).use { p ->
            val r = p.recognizePage(gray, StaffDetector.Staff.VOCAL_OR_TOP)
            println(r.staffs.flatten().joinToString(" | "))
            val parsed = TokenParser.parse(r.staffs)
            val asm = ru.partitrainer.core.omr.ClaudeOmr.assemble(parsed)
            val notes = asm.score.voices.first().notes
            val exp = truth.score.voices.first().notes
            println("got ${notes.map { it.midi to it.beats }}\nexp ${exp.map { it.midi to it.beats }}")
            println("marks got ${asm.marks.map { listOf(it.repeatStart, it.repeatEnd, it.volta) }}")
            println("marks exp ${truth.marks.map { listOf(it.repeatStart, it.repeatEnd, it.volta) }}")
            assertEquals(exp.map { it.midi }, notes.map { it.midi })
            assertEquals(exp.map { it.beats }, notes.map { it.beats })
            assertEquals(truth.marks.map { Triple(it.repeatStart, it.repeatEnd, it.volta) }, asm.marks.map { Triple(it.repeatStart, it.repeatEnd, it.volta) })
            // раскрытие повторов на распознанных данных
            val order = ru.partitrainer.core.structure.Repeats.expandRepeats(asm.marks)
            println("expand ${order.order} ambiguous=${order.ambiguous} ${order.warnings}")
            assertEquals(ru.partitrainer.core.structure.Repeats.expandRepeats(truth.marks).order, order.order)
        }
    }

    @Test fun skewedNoisyPhoto() {
        assumeTrue(models != null && ref != null && File(ref, "photo.jpg").exists())
        val gray = loadGray(File(ref, "photo.jpg"))
        val truth = ru.partitrainer.core.io.MusicXmlParser.parse(File(ref, "page.musicxml").inputStream()).score.voices.first().notes
        HomrPipeline(modelFiles()).use { p ->
            val r = p.recognizePage(gray, StaffDetector.Staff.VOCAL_OR_TOP, onProgress = { _, _, m -> println(m) })
            val notes = ru.partitrainer.core.omr.ClaudeOmr.assemble(TokenParser.parse(r.staffs)).score.voices.first().notes
            val same = notes.zip(truth).count { it.first.midi == it.second.midi && it.first.beats == it.second.beats }
            println("photo: staves=${r.stavesFound} notes=${notes.size}/${truth.size} correct=$same")
            assertEquals(2, r.stavesFound)
            assertTrue("correct $same of ${truth.size}", notes.size == truth.size && same >= truth.size * 0.9)
        }
    }
}
