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

    /** Настоящая хоровая партитура (S/A/T/B): сверка с выводом Python-homr. Нужны real/p1.png и real/p1.musicxml. */
    @Test fun realChoirPage() {
        val real = System.getenv("HOMR_REAL")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(models != null && real != null && File(real, "p1.png").exists())
        val gray = loadGray(File(real, "p1.png"))
        val py = ru.partitrainer.core.io.MusicXmlParser.parse(File(real, "p1.musicxml").inputStream()).score
        println("python voices: " + py.voices.map { it.label + ":" + it.notes.size })
        HomrPipeline(modelFiles()).use { p ->
            for (idx in 0..3) {
                val r = p.recognizePage(gray, StaffDetector.Staff.VOCAL_OR_TOP, staffInSystem = idx)
                val parsed = TokenParser.parse(r.staffs)
                val notes = ru.partitrainer.core.omr.ClaudeOmr.assemble(parsed).score.voices.first().notes
                println("idx=$idx staves=${r.stavesFound} selected=${r.selected.size} key=${parsed.keyFifths} time=${parsed.timeNum}/${parsed.timeDen} notes=${notes.size}")
                println("  " + notes.take(14).joinToString(" ") { "${ru.partitrainer.core.music.NoteNames.nameWithOctave(it.midi, ru.partitrainer.core.music.Notation.LATIN)}:${it.beats}" })
                val pv = py.voices.getOrNull(idx)
                if (pv != null) println("  py " + pv.notes.take(14).joinToString(" ") { "${ru.partitrainer.core.music.NoteNames.nameWithOctave(it.midi, ru.partitrainer.core.music.Notation.LATIN)}:${it.beats}" })
            }
        }
    }

    /** Все страницы хоровой партитуры: сохраняет ноты каждого голоса в HOMR_REAL/kt_pN_vK.txt. */
    @Test fun realChoirAllPages() {
        val real = System.getenv("HOMR_REAL")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(models != null && real != null && File(real, "p10.png").exists() && System.getenv("HOMR_ALL") == "1")
        HomrPipeline(modelFiles()).use { p ->
            for (page in 1..10) {
                val gray = loadGray(File(real, "p$page.png"))
                val t0 = System.currentTimeMillis()
                for (idx in 0..3) {
                    val r = p.recognizePage(gray, StaffDetector.Staff.VOCAL_OR_TOP, staffInSystem = idx)
                    val parsed = TokenParser.parse(r.staffs)
                    val notes = ru.partitrainer.core.omr.ClaudeOmr.assemble(parsed).score.voices.first().notes
                    File(real, "kt_p${page}_v$idx.txt").writeText(notes.joinToString("\n") { "${it.midi} ${it.beats}" } + "\n")
                    println("page $page voice $idx: staves=${r.stavesFound} selected=${r.selected.size} notes=${notes.size}")
                }
                println("page $page time ${System.currentTimeMillis() - t0} ms")
            }
        }
    }

    @Test fun octaveMarkExperiment() {
        val real = System.getenv("HOMR_REAL")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(models != null && real != null && System.getenv("HOMR_OCT") == "1")
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val sess = env.createSession(modelFiles().segnet.path)
        for (pg in listOf(1, 5)) {
            val page = ImageOps.clahe(ImageOps.resizeToHomrWidth(loadGray(File(real, "p$pg.png"))))
            val seg = Segnet(env, sess).segment(page)
            val staves = StaffDetector.detect(seg)
            val systems = StaffDetector.systems(staves, page)
            for ((si, sys) in systems.withIndex()) {
                val scores = sys.mapIndexed { i, st -> "%.3f".format(StaffDetector.octaveMarkScore(page, st, sys.getOrNull(i + 1)?.minY)) }
                println("page $pg system $si: $scores  " + sys.map { "x=${it.minX}..${it.maxX} u=%.1f y=${it.minY}".format(it.unit) })
            }
        }
    }

    /** Хор: голоса по ключу/«8»/регистру на страницах 1, 2, 5; сверка с Python-homr (части по номеру строки). */
    @Test fun choirVoices() {
        val real = System.getenv("HOMR_REAL")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(models != null && real != null && System.getenv("HOMR_CHOIR") == "1")
        fun nm(m: Int) = ru.partitrainer.core.music.NoteNames.nameWithOctave(m, ru.partitrainer.core.music.Notation.LATIN)
        HomrPipeline(modelFiles()).use { p ->
            for (pg in listOf(1, 5)) {
                val systems = p.recognizeAll(loadGray(File(real, "p$pg.png")))
                for ((si, sys) in systems.withIndex()) println("page $pg system $si: " + sys.joinToString { "${it.clef}${if (it.octave8) "8" else ""}/${it.medianMidi}" } + "  -> " + ChoirAssigner.assign(sys).entries.sortedBy { it.key }.joinToString { "${it.key.name.take(1)}=${it.value.indexInSystem}" })
                for (v in ChoirVoice.values()) {
                    val (tokens, shift) = ChoirAssigner.tokensFor(v, systems)
                    val parsed = TokenParser.parse(tokens, shift)
                    val notes = ru.partitrainer.core.omr.ClaudeOmr.assemble(parsed).score.voices.first().notes
                    println("page $pg $v: measures=${parsed.measures.size} notes=${notes.size} shift=$shift first=" + notes.take(8).joinToString(" ") { nm(it.midi) })
                }
            }
        }
    }

    /** Сравнение хорового разбора Kotlin и Python-homr по всем страницам (там, где во всех системах по 4 строки). */
    @Test fun choirVsPythonAllPages() {
        val real = System.getenv("HOMR_REAL")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(models != null && real != null && System.getenv("HOMR_CMP") == "1")
        HomrPipeline(modelFiles()).use { p ->
            var totalNotes = 0; var totalSame = 0
            for (pg in 1..10) {
                val systems = p.recognizeAll(loadGray(File(real, "p$pg.png")))
                val sizes = systems.map { it.size }
                val py = File(real, "p$pg.musicxml").takeIf { it.exists() }?.let { ru.partitrainer.core.io.MusicXmlParser.parse(it.inputStream()).score }
                val line = StringBuilder("page $pg systems=$sizes")
                for ((vi, v) in ChoirVoice.values().withIndex()) {
                    val (tokens, shift) = ChoirAssigner.tokensFor(v, systems)
                    val notes = ru.partitrainer.core.omr.ClaudeOmr.assemble(TokenParser.parse(tokens, 0)).score.voices.first().notes
                    line.append(" | ${v.name.take(1)}:${notes.size}")
                    if (py != null && sizes.all { it == 4 } && py.voices.size == 4) {
                        val pn = py.voices[vi].notes
                        val same = notes.zip(pn).count { it.first.midi == it.second.midi && it.first.beats == it.second.beats }
                        line.append("(py ${pn.size}, same $same)")
                        totalNotes += pn.size; totalSame += same
                    }
                }
                println(line)
            }
            println("TOTAL same $totalSame of $totalNotes on uniform pages")
        }
    }

    @Test fun debugLines() {
        val real = System.getenv("HOMR_REAL")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(models != null && real != null && System.getenv("HOMR_DBG") == "1")
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val sess = env.createSession(modelFiles().segnet.path)
        val page = ImageOps.clahe(ImageOps.resizeToHomrWidth(loadGray(File(System.getenv("HOMR_DIR") ?: real!!.path, "${System.getenv("HOMR_PAGE") ?: "p8"}.png"))))
        val seg = Segnet(env, sess).segment(page)
        run {
            // картинка сегментации: красный — линейки, синий — ноты, зелёный — ключи/знаки, серый — остальное
            val out = java.awt.image.BufferedImage(page.width, page.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until page.height) for (x in 0 until page.width) {
                val g = page[x, y]; val c = seg.cls[y * page.width + x].toInt()
                val rgb = when (c) { 4 -> 0xFF0000; 2 -> 0x0000FF; 3 -> 0x00AA00; 1 -> 0xFF8800; 5 -> 0xAA00AA; else -> (g shl 16) or (g shl 8) or g }
                out.setRGB(x, y, rgb)
            }
            javax.imageio.ImageIO.write(out, "png", File(System.getenv("HOMR_DIR") ?: real!!.path, (System.getenv("HOMR_PAGE") ?: "p8") + "_seg.png"))
        }
        val lines = StaffDetector.findLines(seg)
        println("lines=" + lines.size)
        var prev = 0.0
        for (l in lines.filter { it.y((it.minX + it.maxX) / 2.0) in 1200.0..1320.0 }) { val ym = l.y((l.minX + l.maxX) / 2.0); println("  y=%.1f gap=%.1f x=%d..%d slope=%.3f".format(ym, ym - prev, l.minX, l.maxX, l.poly.c1)); prev = ym }
        println("STAVES " + StaffDetector.groupStaves(lines, page.height).map { "${it.minY}-${it.maxY}(u=%.1f)".format(it.unit) })
    }

    @Test fun exportChoirXml() {
        val real = System.getenv("HOMR_REAL")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(models != null && real != null && System.getenv("HOMR_XML") == "1")
        HomrPipeline(modelFiles()).use { p ->
            val systems = p.recognizeAll(loadGray(File(real, "p1.png")))
            var score: ru.partitrainer.core.model.Score? = null
            val voices = ArrayList<ru.partitrainer.core.model.Voice>()
            for (v in ChoirVoice.values()) {
                val (tokens, shift) = ChoirAssigner.tokensFor(v, systems)
                val asm = ru.partitrainer.core.omr.ClaudeOmr.assemble(TokenParser.parse(tokens, shift), v.name)
                if (score == null) score = asm.score
                // для проверки на глаз возвращаем тенор на написанную высоту (+12): так он совпадёт с картинкой
                val notes = asm.score.voices.first().notes.map { if (v == ChoirVoice.TENOR) it.copy(midi = it.midi - shift) else it }
                voices += asm.score.voices.first().copy(id = v.name, notes = notes)
            }
            File(real, "kt_p1.musicxml").writeText(ru.partitrainer.core.io.MusicXmlWriter.write(score!!.copy(voices = voices), voices))
        }
    }

    /** Обзор произвольных страниц: HOMR_SURVEY=папка, HOMR_PAGES=a1,a2,b1 . Пишет <имя>_kt.musicxml. */
    @Test fun survey() {
        val dir = System.getenv("HOMR_SURVEY")?.takeIf { it.isNotBlank() }?.let(::File)
        val pages = System.getenv("HOMR_PAGES")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        assumeTrue(models != null && dir != null && pages.isNotEmpty())
        fun nm(m: Int) = ru.partitrainer.core.music.NoteNames.nameWithOctave(m, ru.partitrainer.core.music.Notation.LATIN, true)
        HomrPipeline(modelFiles()).use { p ->
            for (name in pages) {
                val t0 = System.currentTimeMillis()
                val systems = try { p.recognizeAll(loadGray(File(dir, "$name.png"))) } catch (e: Exception) { println("PAGE $name: ERROR $e"); continue }
                println("PAGE $name: ${System.currentTimeMillis() - t0} ms; systems=" + systems.map { it.size })
                for ((si, sys) in systems.withIndex()) println("  sys $si: " + sys.joinToString { "${it.clef}${if (it.octave8) "8" else ""}/${it.medianMidi}/${it.tokens.count { t -> t.rhythm.startsWith("note_") }}n" })
                var score: ru.partitrainer.core.model.Score? = null
                val voices = ArrayList<ru.partitrainer.core.model.Voice>()
                for (v in ChoirVoice.values()) {
                    val two = System.getenv("HOMR_TWO") == "1"
                    val (tokens, shift) = if (two) ChoirAssigner.tokensForTwoStaff(v, systems) to 0 else ChoirAssigner.tokensFor(v, systems)
                    if (tokens.isEmpty()) continue
                    val parsed = TokenParser.parse(tokens, shift)
                    val asm = ru.partitrainer.core.omr.ClaudeOmr.assemble(parsed, v.name)
                    if (score == null) score = asm.score
                    val notes = asm.score.voices.first().notes.map { if (v == ChoirVoice.TENOR) it.copy(midi = it.midi - shift) else it }
                    voices += asm.score.voices.first().copy(id = v.name, notes = notes)
                    val bad = ru.partitrainer.core.omr.ClaudeOmr.badMeasures(parsed)
                    if (System.getenv("HOMR_VERBOSE") == v.name) println("  TOK " + tokens.flatten().joinToString(" ") { it.rhythm.replace("note_", "n").replace("barline", "|").replace("timeSignature/", "TS") })
                    if (System.getenv("HOMR_VERBOSE") == v.name) println("  MEAS " + parsed.measures.joinToString(" ") { "[${it.timeNum}/${it.timeDen}:${"%.2f".format(it.total)}${if (it.pickup) "p" else ""}]" })
                    println("  $v: measures=${parsed.measures.size} notes=${notes.size} key=${parsed.keyFifths} time=${parsed.timeNum}/${parsed.timeDen} badMeasures=${bad.size} first=" + notes.take(6).joinToString(" ") { nm(it.midi) })
                }
                if (score != null) File(dir, "${name}_kt.musicxml").writeText(ru.partitrainer.core.io.MusicXmlWriter.write(score.copy(voices = voices), voices))
            }
        }
    }

    @Test fun dumpStaffTokens() {
        val dir = System.getenv("HOMR_SURVEY")?.takeIf { it.isNotBlank() }?.let(::File)
        val page = System.getenv("HOMR_PAGE1")
        assumeTrue(models != null && dir != null && page != null)
        HomrPipeline(modelFiles()).use { p ->
            val systems = p.recognizeAll(loadGray(File(dir, "$page.png")))
            for ((si, sys) in systems.withIndex()) for (st in sys) {
                println("TOKS sys=$si idx=${st.indexInSystem} clef=${st.clef} y=${st.staff.minY} :: " + st.tokens.joinToString(" ") { t ->
                    val r = t.rhythm.replace("note_", "n").replace("rest_", "r").replace("barline", "|")
                    if (t.rhythm.startsWith("note")) "$r:${t.pitch}${if (t.lift in listOf("#", "b", "N")) t.lift else ""}" else r
                })
            }
        }
    }

    @Test fun textExperiment() {
        val dir = System.getenv("HOMR_SURVEY")?.takeIf { it.isNotBlank() }?.let(::File)
        val pages = System.getenv("HOMR_PAGES")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        assumeTrue(models != null && dir != null && pages.isNotEmpty() && System.getenv("HOMR_TEXT") == "1")
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val sess = env.createSession(modelFiles().segnet.path)
        for (name in pages) {
            val page = ImageOps.clahe(ImageOps.resizeToHomrWidth(loadGray(File(dir, "$name.png"))))
            val seg = Segnet(env, sess).segment(page)
            val staves = StaffDetector.detect(seg)
            val systems = StaffDetector.systems(staves, page)
            for ((si, sys) in systems.withIndex()) {
                println("TEXT $name sys $si: " + sys.mapIndexed { i, st -> "%.3f".format(StaffDetector.textBelowScore(page, seg, st, sys.getOrNull(i + 1)?.topAt(((st.minX + st.maxX) / 2.0)))) })
            }
        }
    }
}
