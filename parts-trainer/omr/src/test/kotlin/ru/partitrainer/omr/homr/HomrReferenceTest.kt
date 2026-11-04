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

    @Test fun profile() {
        val dir = System.getenv("HOMR_SURVEY")?.takeIf { it.isNotBlank() }?.let(::File)
        val pages = System.getenv("HOMR_PAGES")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        assumeTrue(models != null && dir != null && pages.isNotEmpty() && System.getenv("HOMR_PROFILE") == "1")
        val t0 = System.currentTimeMillis()
        HomrPipeline(modelFiles()).use { p ->
            println("PROF session init ${System.currentTimeMillis() - t0} ms")
            for (name in pages) {
                val gray = loadGray(File(dir, "$name.png"))
                val s = System.currentTimeMillis()
                val r = p.recognizeAll(gray)
                println("PROF $name total=${System.currentTimeMillis() - s} ms staves=${r.sumOf { it.size }} " + p.timings.entries.joinToString(" ") { "${it.key}=${it.value}" } + " enc=${p.lastS2s?.encoderMs} dec=${p.lastS2s?.decoderMs} steps=${p.lastS2s?.steps}")
            }
        }
    }

    @Test fun loadTimeExperiment() {
        assumeTrue(models != null && System.getenv("HOMR_LOAD") == "1")
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val mf = modelFiles()
        val tmp = File(System.getProperty("java.io.tmpdir"), "homr_opt").apply { mkdirs() }
        for (f in listOf(mf.segnet, mf.encoder, mf.decoder)) {
            var t = System.nanoTime()
            env.createSession(f.path, ai.onnxruntime.OrtSession.SessionOptions()).close()
            val plain = (System.nanoTime() - t) / 1_000_000
            val optFile = File(tmp, f.name)
            optFile.delete()
            t = System.nanoTime()
            ai.onnxruntime.OrtSession.SessionOptions().use { o ->
                o.setOptimizedModelFilePath(optFile.path)
                env.createSession(f.path, o).close()
            }
            val saveMs = (System.nanoTime() - t) / 1_000_000
            t = System.nanoTime()
            ai.onnxruntime.OrtSession.SessionOptions().use { o ->
                o.setOptimizationLevel(ai.onnxruntime.OrtSession.SessionOptions.OptLevel.NO_OPT)
                env.createSession(optFile.path, o).close()
            }
            val fast = (System.nanoTime() - t) / 1_000_000
            println("LOAD ${f.name.take(10)} plain=$plain ms, save=$saveMs ms, fromOptimized=$fast ms, size ${f.length() / 1_000_000}->${optFile.length() / 1_000_000} MB")
        }
    }

    /** Быстрый поиск линеек (без нейросети) против нейросетевого на множестве страниц: HOMR_FAST=dir:name,name;dir:name... */
    @Test fun fastVsAccurateLocate() {
        val spec = System.getenv("HOMR_FAST")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        var same = 0; var total = 0
        HomrPipeline(modelFiles()).use { p ->
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    val gray = loadGray(File(dir, "$name.png"))
                    val t0 = System.currentTimeMillis()
                    val fast = p.locate(gray, false, { _, _, _ -> }, { false })
                    val tf = System.currentTimeMillis() - t0
                    val usedClassic = p.usedClassic
                    val t1 = System.currentTimeMillis()
                    val acc = p.locate(gray, true, { _, _, _ -> }, { false })
                    val ta = System.currentTimeMillis() - t1
                    val fs = fast.systems.map { it.size }; val ac = acc.systems.map { it.size }
                    val posOk = fast.staves.size == acc.staves.size && fast.staves.zip(acc.staves).all { (a, b) -> Math.abs(a.minY - b.minY) <= 3 && Math.abs(a.maxY - b.maxY) <= 3 }
                    total++; if (fs == ac && posOk) same++
                    if (System.getenv("HOMR_FASTDBG") == name) { println("FASTDBG fast: " + fast.staves.map { "${it.minX}..${it.maxX} y=${it.minY}-${it.maxY} u=%.1f".format(it.unit) }); println("FASTDBG acc : " + acc.staves.map { "${it.minX}..${it.maxX} y=${it.minY}-${it.maxY} u=%.1f".format(it.unit) }) }
                    println("FAST $name: classic=$usedClassic fast=$fs (${tf} ms) accurate=$ac (${ta} ms) ${if (fs == ac && posOk) "OK" else "DIFF"}")
                }
            }
        }
        println("FAST TOTAL same $same of $total")
    }

    /** Итоговые ноты по голосам: быстрый режим против точного. HOMR_FULL=dir:name,name:two (two = хор на двух строках). */
    @Test fun fastVsAccurateNotes() {
        val spec = System.getenv("HOMR_FULL")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        var totalNotes = 0; var totalSame = 0
        HomrPipeline(modelFiles()).use { p ->
            for (part in spec!!.split(';')) {
                val (dir, names, mode) = part.split(':').let { Triple(it[0], it[1], it.getOrNull(2)) }
                for (name in names.split(',')) {
                    val gray = loadGray(File(dir, "$name.png"))
                    val t0 = System.currentTimeMillis(); val fast = p.recognizeAll(gray, accurate = false); val tf = System.currentTimeMillis() - t0
                    val t1 = System.currentTimeMillis(); val acc = p.recognizeAll(gray, accurate = true); val ta = System.currentTimeMillis() - t1
                    if (System.getenv("HOMR_SYS") == name) { for ((lab, sys) in listOf("fast" to fast, "acc " to acc)) for ((si, sy) in sys.withIndex()) println("SYS $lab sys $si: " + sy.joinToString { "${it.clef}${if (it.octave8) "8" else ""}/${it.medianMidi}/o=%.3f/x=${it.staff.minX}".format(it.octaveScore) }) }
                    val sb = StringBuilder("FULL $name: fast ${tf} ms, accurate ${ta} ms |")
                    for (v in ChoirVoice.values()) {
                        fun notes(sys: List<List<StaffRead>>): List<Pair<Int, Double>> {
                            val (tokens, shift) = if (mode == "two") Pair(ChoirAssigner.tokensForTwoStaff(v, sys), 0) else ChoirAssigner.tokensFor(v, sys)
                            return ru.partitrainer.core.omr.ClaudeOmr.assemble(TokenParser.parse(tokens, shift)).score.voices.first().notes.map { it.midi to it.beats }
                        }
                        val a = notes(acc); val f = notes(fast)
                        val same = a.zip(f).count { it.first == it.second }
                        sb.append(" ${v.name.take(1)} ${same}/${a.size}(fast ${f.size})")
                        totalNotes += a.size; totalSame += same
                    }
                    println(sb)
                }
            }
        }
        println("FULL TOTAL same $totalSame of $totalNotes")
    }

    /** Калибровка признака «8» по 4-строчным системам (тенор — третья строка). HOMR_CAL=dir:name,name;... */
    @Test fun calibrateOctave() {
        val spec = System.getenv("HOMR_CAL")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        val tenor = ArrayList<Double>(); val other = ArrayList<Double>()
        HomrPipeline(modelFiles()).use { p ->
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    val gray = loadGray(File(dir, "$name.png"))
                    for (accurate in listOf(false, true)) {
                        val loc = p.locate(gray, accurate, { _, _, _ -> }, { false })
                        for (sys in loc.systems.filter { it.size == 4 }) {
                            val left = sys.map { it.minX }.sorted()[2].toDouble()
                            for ((i, st) in sys.withIndex()) {
                                if (i == 3) continue   // басовый ключ
                                val sc = StaffDetector.octaveMarkScore(loc.page, st, sys.getOrNull(i + 1)?.minY, left)
                                if (i == 2) tenor += sc else other += sc
                            }
                        }
                    }
                }
            }
        }
        fun q(l: List<Double>, p: Double) = l.sorted().let { it[((it.size - 1) * p).toInt()] }
        println("CAL tenor n=${tenor.size}: min=%.3f p10=%.3f median=%.3f".format(tenor.min(), q(tenor, 0.1), q(tenor, 0.5)))
        println("CAL other n=${other.size}: median=%.3f p90=%.3f max=%.3f".format(q(other, 0.5), q(other, 0.9), other.max()))
    }

    /** int8-энкодер против float: HOMR_MODELS (float) и HOMR_MODELS_Q (int8) на страницах HOMR_FULL=dir:name,name[:two]. */
    @Test fun int8EncoderVsFloat() {
        val qdir = System.getenv("HOMR_MODELS_Q")?.takeIf { it.isNotBlank() }?.let(::File)
        val spec = System.getenv("HOMR_FULL")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && qdir != null && spec != null && System.getenv("HOMR_INT8") == "1")
        fun files(d: File): HomrModelFiles {
            fun f(pr: String): File = d.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
            return HomrModelFiles(f("segnet"), f("encoder"), f("decoder"))
        }
        var total = 0; var same = 0; var msF = 0L; var msQ = 0L
        HomrPipeline(files(models!!)).use { pf -> HomrPipeline(files(qdir!!)).use { pq ->
            for (part in spec!!.split(';')) {
                val (dir, names, mode) = part.split(':').let { Triple(it[0], it[1], it.getOrNull(2)) }
                for (name in names.split(',')) {
                    val gray = loadGray(File(dir, "$name.png"))
                    val t0 = System.currentTimeMillis(); val a = pf.recognizeAll(gray); val ta = System.currentTimeMillis() - t0
                    val t1 = System.currentTimeMillis(); val b = pq.recognizeAll(gray); val tb = System.currentTimeMillis() - t1
                    msF += ta; msQ += tb
                    val sb = StringBuilder("INT8 $name: float ${ta} ms, int8 ${tb} ms |")
                    for (v in ChoirVoice.values()) {
                        fun notes(sys: List<List<StaffRead>>): List<Pair<Int, Double>> {
                            val (tokens, shift) = if (mode == "two") Pair(ChoirAssigner.tokensForTwoStaff(v, sys), 0) else ChoirAssigner.tokensFor(v, sys)
                            return ru.partitrainer.core.omr.ClaudeOmr.assemble(TokenParser.parse(tokens, shift)).score.voices.first().notes.map { it.midi to it.beats }
                        }
                        val x = notes(a); val y = notes(b)
                        val eq = x.zip(y).count { it.first == it.second }
                        sb.append(" ${v.name.take(1)} ${eq}/${x.size}"); total += x.size; same += eq
                    }
                    println(sb)
                }
            }
        } }
        println("INT8 TOTAL same $same of $total; float ${msF} ms vs int8 ${msQ} ms")
    }

    /** Тактовые черты на реальных страницах: HOMR_BARS=dir:p1,p2;dir2:... — сверка с числом тактов по токенам, картинки *_bars.png. */
    @Test fun barlinesOnRealPages() {
        val spec = System.getenv("HOMR_BARS")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder"))).use { pl ->
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    val gray = loadGray(File(dir, "$name.png"))
                    val loc = pl.locate(gray, false, { _, _, _ -> }, { false })
                    val reads = pl.recognizeAll(gray)
                    var ok = 0; var tot = 0
                    val img = java.awt.image.BufferedImage(loc.page.width, loc.page.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                    for (y in 0 until loc.page.height) for (x in 0 until loc.page.width) { val v = loc.page[x, y]; img.setRGB(x, y, (v shl 16) or (v shl 8) or v) }
                    val g = img.createGraphics()
                    val sb = StringBuilder("BARS $name:")
                    for (sys in reads) for (r in sys) {
                        val c = TokenParser.parse(listOf(r.tokens)).measures.size
                        val xs = r.columns!!.xs
                        val k = xs.size - 1
                        tot++; if (k == c) ok++
                        sb.append(" ${k}/${c}")
                        g.color = if (k == c) java.awt.Color(0, 160, 0) else java.awt.Color(220, 0, 0)
                        g.stroke = java.awt.BasicStroke(3f)
                        for (x in xs) g.drawLine(x.toInt(), r.columns!!.top.toInt(), x.toInt(), r.columns!!.bottom.toInt())
                    }
                    g.dispose()
                    ImageIO.write(img, "png", File(dir, "${name}_bars.png"))
                    println("$sb  => совпало $ok из $tot")
                }
            }
        }
    }

    /** Скорость: HOMR_BENCH=dir:page; варианты «потоков энкодера/декодера/параллельных нотоносцев». */
    @Test fun benchThreads() {
        val spec = System.getenv("HOMR_BENCH")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        val (dir, name) = spec!!.split(':')
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        val gray = loadGray(File(dir, "$name.png"))
        val cores = Runtime.getRuntime().availableProcessors()
        var ref: List<List<EncodedSymbol>>? = null
        val cfgs = System.getenv("HOMR_CFG")?.split(";")?.map { c -> c.split(",").map { it.toInt() }.let { Triple(it[0], it[1], it[2]) } } ?: listOf(Triple(cores, cores, 1), Triple(1, 1, 2), Triple(1, 1, cores))
        for ((th, dec, w) in cfgs) {
            HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = cores, staffWorkers = w, decoderThreads = dec, encoderThreads = th, serialEncoder = System.getenv("HOMR_SERIAL") == "1").use { pl ->
                pl.recognizeAll(gray)   // прогрев
                val t0 = System.currentTimeMillis()
                val r = pl.recognizeAll(gray)
                val ms = System.currentTimeMillis() - t0
                val toks = r.flatten().map { it.tokens }
                if (ref == null) ref = toks
                println("BENCH threads=$th dec=$dec workers=$w: $ms ms, same=${toks == ref}, ${pl.timings} enc=${pl.lastS2s?.encoderMs} dec=${pl.lastS2s?.decoderMs} steps=${pl.lastS2s?.steps} peakRSS=${File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }} javaHeap=${Runtime.getRuntime().totalMemory() / 1048576}MB")
            }
        }
    }

    /** Точность: HOMR_ACC=dir:p1,p2;... — сколько нотоносцев с «битыми» тактами у разных вариантов вырезки строки. */
    @Test fun canvasVariants() {
        val spec = System.getenv("HOMR_ACC")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        val variants = listOf(4.0 to 2.0, 3.0 to 2.0, 5.0 to 2.0, 4.0 to 1.0, 4.0 to 3.0, 2.5 to 1.5)
        fun bad(t: List<EncodedSymbol>) = ru.partitrainer.core.omr.ClaudeOmr.badMeasures(TokenParser.parse(listOf(t))).size
        val totals = IntArray(variants.size); var bestTotal = 0; var staves = 0; var badStaves = 0; var fixedStaves = 0
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 1, staffWorkers = 4, decoderThreads = 1).use { pl ->
            val s2s = Staff2Score(ai.onnxruntime.OrtEnvironment.getEnvironment(),
                ai.onnxruntime.OrtEnvironment.getEnvironment().createSession(f("encoder").path), ai.onnxruntime.OrtEnvironment.getEnvironment().createSession(f("decoder").path), HomrVocab.fromResources())
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    val gray = loadGray(File(dir, "$name.png"))
                    val loc = pl.locate(gray, false, { _, _, _ -> }, { false })
                    for (st in loc.staves) {
                        staves++
                        val res = variants.map { (v, h) -> s2s.predict(HomrPipeline.staffCanvas(loc.page, st, loc.staves, v, h)) }
                        val b = res.map { bad(it) }
                        b.forEachIndexed { i, x -> totals[i] += x }
                        val best = if (b[0] == 0) 0 else b.min()
                        bestTotal += best
                        if (b[0] > 0) { badStaves++; if (best < b[0]) fixedStaves++ }
                    }
                    println("ACC $name done: totals=${totals.toList()} best=$bestTotal")
                }
            }
        }
        println("ACC FINAL variants=$variants totals=${totals.toList()} bestOf=$bestTotal staves=$staves bad=$badStaves improved=$fixedStaves")
    }

    /** Разбор «битых» тактов: HOMR_BAD=dir:p1,... — токены тактов, где сумма длительностей не сходится. */
    @Test fun badMeasureDump() {
        val spec = System.getenv("HOMR_BAD")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 1, staffWorkers = 4, decoderThreads = 1).use { pl ->
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    val reads = pl.recognizeAll(loadGray(File(dir, "$name.png")))
                    for ((si, sys) in reads.withIndex()) for (r in sys) {
                        val p = TokenParser.parse(listOf(r.tokens))
                        val bad = ru.partitrainer.core.omr.ClaudeOmr.badMeasures(p)
                        if (bad.isEmpty()) continue
                        // токены по тактам
                        val groups = ArrayList<MutableList<String>>(); var cur = ArrayList<String>()
                        for (t in r.tokens) { if (t.rhythm.contains("barline") || t.rhythm.startsWith("repeat")) { groups += cur; cur = ArrayList() } else cur += (t.rhythm + (if (t.pitch != "." && t.pitch != "_") "/" + t.pitch else "")) }
                        groups += cur
                        println("BAD $name sys$si staff${r.indexInSystem} meter=${p.timeNum}/${p.timeDen} bad=$bad totals=${p.measures.map { it.total }}")
                        for (b in bad) println("   m$b: ${groups.filter { g -> g.any { it.startsWith("note") || it.startsWith("rest") } }.getOrNull(b)}")
                    }
                }
            }
        }
    }

    /** Разрешение входа: HOMR_RES=dirA|dirB|name1,name2 — одна и та же страница в двух разрешениях. */
    @Test fun inputResolution() {
        val spec = System.getenv("HOMR_RES")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        val (da, db, names) = spec!!.split('|')
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        fun bad(t: List<EncodedSymbol>) = ru.partitrainer.core.omr.ClaudeOmr.badMeasures(TokenParser.parse(listOf(t))).size
        var badA = 0; var badB = 0; var same = 0; var tot = 0; var prepA = 0L; var prepB = 0L; var staffA = 0; var staffB = 0
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 4, staffWorkers = 4, decoderThreads = 1, encoderThreads = 1).use { pl ->
            for (n in names.split(',')) {
                val ra = pl.recognizeAll(loadGray(File(da, "$n.png"))); prepA += (pl.timings["prep"] ?: 0)
                val rb = pl.recognizeAll(loadGray(File(db, "$n.png"))); prepB += (pl.timings["prep"] ?: 0)
                val ta = ra.flatten().map { it.tokens }; val tb = rb.flatten().map { it.tokens }
                staffA += ta.size; staffB += tb.size
                badA += ta.sumOf { bad(it) }; badB += tb.sumOf { bad(it) }
                tot += minOf(ta.size, tb.size); same += ta.indices.count { ta[it] == tb.getOrNull(it) }
                println("RES $n: staves ${ta.size}/${tb.size}, bad ${ta.sumOf { bad(it) }}/${tb.sumOf { bad(it) }}, same ${ta.indices.count { ta[it] == tb.getOrNull(it) }}")
            }
        }
        println("RES TOTAL staves $staffA/$staffB bad $badA/$badB same $same/$tot prep $prepA/$prepB ms")
    }

    @Test fun locateCompare() {
        val spec = System.getenv("HOMR_LOC")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder"))).use { pl ->
            for (path in spec!!.split(',')) {
                val loc = pl.locate(loadGray(File(path)), false, { _, _, _ -> }, { false })
                println("LOC $path classic=${pl.usedClassic} angle=${loc.angle} staves=${loc.staves.size} systems=${loc.systems.map { it.size }}")
                loc.staves.forEach { println("   y ${it.minY}-${it.maxY} x ${it.minX}-${it.maxX} u=${"%.1f".format(it.unit)}") }
            }
        }
    }

    @Test fun linesNearY() {
        val spec = System.getenv("HOMR_LINES")?.takeIf { it.isNotBlank() }
        assumeTrue(spec != null)
        val (path, ys) = spec!!.split('@')
        val page = ImageOps.clahe(ImageOps.resizeToHomrWidth(loadGray(File(path))))
        val lines = StaffDetector.findLines(ClassicStaffMask.mask(page))
        val y0 = ys.toInt()
        for (l in lines) { val ym = l.y((l.minX + l.maxX) / 2.0); if (ym in (y0 - 20.0)..(y0 + 80.0)) println("LINE y=%.1f x=%d-%d".format(ym, l.minX, l.maxX)) }
    }

    /** Сколько строк без единой ноты (только паузы): HOMR_EMPTY=dir:p1,... */
    @Test fun emptyStaves() {
        val spec = System.getenv("HOMR_EMPTY")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        var total = 0; var empty = 0; var steps = 0; var emptySteps = 0
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 4, staffWorkers = 4, decoderThreads = 1, encoderThreads = 1).use { pl ->
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    val reads = pl.recognizeAll(loadGray(File(dir, "$name.png"))).flatten()
                    for (r in reads) {
                        total++; steps += r.tokens.size
                        if (r.tokens.none { it.rhythm.startsWith("note_") }) { empty++; emptySteps += r.tokens.size; println("EMPTY $name staff ${r.system}/${r.indexInSystem}: ${r.tokens.joinToString(" ") { it.rhythm }}") }
                    }
                }
            }
        }
        println("EMPTY TOTAL $empty of $total staves, tokens $emptySteps of $steps")
    }

    /** Подготовка следующей страницы параллельно с чтением текущей: HOMR_PREFETCH=dir:p1,p2,... */
    @Test fun prefetchPages() {
        val spec = System.getenv("HOMR_PREFETCH")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        val (dir, names) = spec!!.split(':')
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        val files = names.split(',').map { File(dir, "$it.png") }
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 4, staffWorkers = 4, decoderThreads = 1, encoderThreads = 1).use { pl ->
            pl.recognizeAll(loadGray(files[0]))
            val t0 = System.currentTimeMillis()
            val seq = files.map { pl.recognizeAll(loadGray(it)).flatten().map { r -> r.tokens } }
            val t1 = System.currentTimeMillis()
            val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
            fun prep(fl: File) = ex.submit<Pair<GrayImage, HomrPipeline.Located>> { val g = loadGray(fl); g to pl.locate(g, false, { _, _, _ -> }, { false }) }
            var next = prep(files[0])
            val par = files.indices.map { i ->
                val (g, loc) = next.get()
                if (i + 1 < files.size) next = prep(files[i + 1])
                pl.recognizeAll(g, located = loc).flatten().map { r -> r.tokens }
            }
            val t2 = System.currentTimeMillis(); ex.shutdown()
            println("PREFETCH sequential ${t1 - t0} ms, prefetch ${t2 - t1} ms, same=${seq == par}")
        }
    }

    /** Позиции (голоса по штилям): HOMR_POS=dir:p1,... — сколько нот upper/upper2/lower/lower2 и пример строки. */
    @Test fun positionsDump() {
        val spec = System.getenv("HOMR_POS")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 4, staffWorkers = 4, decoderThreads = 1, encoderThreads = 1).use { pl ->
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    val reads = pl.recognizeAll(loadGray(File(dir, "$name.png")))
                    val cnt = reads.flatten().flatMap { it.tokens }.filter { it.rhythm.startsWith("note_") }.groupingBy { it.position }.eachCount()
                    println("POS $name: $cnt")
                    val ex = reads.flatten().firstOrNull { r -> r.tokens.any { it.position == "upper2" } }
                    if (ex != null) println("POS   example sys${ex.system}/${ex.indexInSystem}: " + ex.tokens.take(40).joinToString(" ") { "${it.rhythm}:${it.pitch}:${it.position}" })
                }
            }
        }
    }

    /** Голоса по штилям против деления по высоте: HOMR_VOICES=dir:p1,... — «битые» такты в каждом голосе. */
    @Test fun stemVoicesVsPitch() {
        val spec = System.getenv("HOMR_VOICES")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        fun bad(t: List<EncodedSymbol>) = ru.partitrainer.core.omr.ClaudeOmr.badMeasures(TokenParser.parse(listOf(t))).size
        var oldBad = 0; var newBad = 0; var staves = 0; var notesOld = 0; var notesNew = 0
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 4, staffWorkers = 4, decoderThreads = 1, encoderThreads = 1).use { pl ->
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    for (r in pl.recognizeAll(loadGray(File(dir, "$name.png"))).flatten()) {
                        if (!Divisi.hasStemVoices(r.tokens)) continue
                        staves++
                        val a = Divisi.splitByPitch(r.tokens); val b = Divisi.split(r.tokens)
                        val ob = bad(a.upper) + bad(a.lower); val nb = bad(b.upper) + bad(b.lower)
                        oldBad += ob; newBad += nb
                        fun n(t: List<EncodedSymbol>) = t.count { it.rhythm.startsWith("note_") }
                        notesOld += n(a.upper) + n(a.lower); notesNew += n(b.upper) + n(b.lower)
                        if (ob != nb) println("VOICES $name sys${r.system}/${r.indexInSystem}: pitch bad $ob, stems bad $nb")
                    }
                }
            }
        }
        println("VOICES TOTAL staves with 2 voices $staves: bad measures pitch $oldBad -> stems $newBad; notes $notesOld / $notesNew")
    }

    /** Триоли без цифры: HOMR_TRIP=dir:p1,... — «битые» такты по голосам хора без и с восстановлением. */
    @Test fun tripletRestoration() {
        val spec = System.getenv("HOMR_TRIP")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        var before = 0; var after = 0; var changed = 0
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 4, staffWorkers = 4, decoderThreads = 1, encoderThreads = 1).use { pl ->
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                val two = part.endsWith(":two").also { }
                for (name in names.removeSuffix(":two").split(',')) {
                    val systems = pl.recognizeAll(loadGray(File(dir, "$name.png")))
                    for (v in ChoirVoice.values()) {
                        val toks = if (two) ChoirAssigner.tokensForTwoStaff(v, systems) else ChoirAssigner.tokensFor(v, systems).first
                        val b = ru.partitrainer.core.omr.ClaudeOmr.badMeasures(TokenParser.parse(toks, restoreTriplets = false)).size
                        val a = ru.partitrainer.core.omr.ClaudeOmr.badMeasures(TokenParser.parse(toks)).size
                        before += b; after += a
                        if (Triplets.restore(toks) != toks) { changed++; println("TRIP $name ${v.name}: bad $b -> $a") }
                    }
                }
            }
        }
        println("TRIP TOTAL bad $before -> $after, voices changed $changed")
    }

    /** Положения нот по вниманию декодера: HOMR_ATT=dir:page — картинка *_att.png с точками нот. */
    @Test fun attentionPositions() {
        val spec = System.getenv("HOMR_ATT")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        val (dir, name) = spec!!.split(':')
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 4, staffWorkers = 4, decoderThreads = 1, encoderThreads = 1).use { pl ->
            val gray = loadGray(File(dir, "$name.png"))
            val loc = pl.locate(gray, false, { _, _, _ -> }, { false })
            val reads = pl.recognizeAll(gray, located = loc)
            val img = java.awt.image.BufferedImage(loc.page.width, loc.page.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until loc.page.height) for (x in 0 until loc.page.width) { val v = loc.page[x, y]; img.setRGB(x, y, (v shl 16) or (v shl 8) or v) }
            val g = img.createGraphics()
            var n = 0; var inside = 0
            for (r in reads.flatten()) for (s in r.tokens) if (s.rhythm.startsWith("note_") && !s.x.isNaN()) {
                n++
                if (s.x in r.staff.minX - 30.0..r.staff.maxX + 30.0 && s.y in r.staff.minY - 6 * r.staff.unit..r.staff.maxY + 6 * r.staff.unit) inside++
                g.color = if (s.position.endsWith("2")) java.awt.Color(0, 90, 255) else java.awt.Color(230, 0, 0)
                g.fillOval(s.x.toInt() - 5, s.y.toInt() - 5, 10, 10)
            }
            g.dispose()
            ImageIO.write(img, "png", File(dir, "${name}_att.png"))
            println("ATT notes $n, inside their staff $inside")
        }
    }
}
