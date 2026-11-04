package ru.partitrainer.omr.homr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Сверка с эталоном (Sprint 0).
 *
 * Метрики проверяются без моделей ([metricsOnSmallExample]). Полный прогон — по манифесту:
 * `HOMR_MODELS=<папка> HOMR_EVAL=<manifest.json> HOMR_EVAL_OUT=<папка> gradle -c settings-core.gradle.kts :omr:test --tests '*OmrEvalTest*' -i`
 * Формат манифеста — `tools/omr_eval/manifest.example.json`.
 */
class OmrEvalTest {
    private val xml = """<?xml version="1.0"?>
        <score-partwise><part-list/><part id="P1"><measure number="1">
          <attributes><divisions>2</divisions></attributes>
          <note><pitch><step>C</step><octave>4</octave></pitch><duration>2</duration></note>
          <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><tie type="start"/></note>
          <note><pitch><step>D</step><octave>4</octave></pitch><duration>2</duration><tie type="stop"/></note>
          <note><rest/><duration>2</duration></note>
        </measure></part></score-partwise>"""

    private fun n(r: String, p: String = ".", sl: String = "_") = EncodedSymbol(r, p, "_", "_", sl, "upper")

    @Test fun metricsOnSmallExample() {
        val f = File.createTempFile("omr-gt", ".xml").apply { writeText(xml); deleteOnExit() }
        val gt = OmrEval.readMusicXml(f).getValue("P1")
        val q = OmrEval.TICKS
        assertEquals(listOf(60 to q, 62 to 2 * q), gt.lines.top)                      // лига слита
        assertEquals(4, gt.symbols.size)                                              // C, D, D, пауза — без слияния
        // распознано: C верно, лига не прочитана (две D по четверти), пауза верно
        val ours = OmrEval.fromTokens(listOf(listOf(n("note_4", "C4"), n("note_4", "D4"), n("note_4", "D4"), n("rest_4"))))
        val s = OmrEval.score(gt, ours)
        assertEquals(4, s.notes)                // по 2 ноты в верхней и нижней линии
        assertEquals(2, s.pitchErr)             // лишняя D в каждой линии
        assertEquals(0, s.symErr)               // напечатанные символы совпали
        // та же лига прочитана — всё верно
        val tied = OmrEval.fromTokens(listOf(listOf(n("note_4", "C4"), n("note_4", "D4", "slurStart"), n("note_4", "D4", "slurStop"), n("rest_4"))))
        assertEquals(0, OmrEval.score(gt, tied).bothErr)
        assertEquals(3, OmrEval.editDistance("kitten".toList(), "sitting".toList()))
    }

    private class SetResult(
        val name: String, val kind: String, val pages: Int, val expected: Int?, val found: Int, val matched: Int,
        val score: OmrEval.Score?, val pageMs: List<Long>, val stages: Map<String, Long>, val peakRssKb: Long,
    )

    @Test fun evaluate() {
        val manifest = System.getenv("HOMR_EVAL")?.takeIf { it.isNotBlank() }?.let(::File)
        val models = System.getenv("HOMR_MODELS")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(manifest != null && models != null && models.isDirectory)
        val out = File(System.getenv("HOMR_EVAL_OUT") ?: manifest!!.parentFile.path).apply { mkdirs() }
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        val root = Json.parseToJsonElement(manifest!!.readText()).jsonObject
        val results = ArrayList<SetResult>()
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 4, staffWorkers = 4, decoderThreads = 1, encoderThreads = 1).use { pl ->
            var warmed = false
            for (set in root.getValue("sets").jsonArray.map { it.jsonObject }) {
                val name = set.str("name"); val kind = set.str("kind"); val dir = File(set.str("dir"))
                val pages = set.getValue("pages").jsonArray.map { it.jsonPrimitive.content }
                val accurate = set["accurate"]?.jsonPrimitive?.content == "true"
                val expected = (set["staves"] as? JsonArray)?.map { it.jsonPrimitive.int }
                if (!warmed) { pl.recognizeAll(TestImages.loadGray(File(dir, "${pages[0]}.png"))); warmed = true }   // загрузка моделей — вне замера
                val systemsByPage = ArrayList<List<List<StaffRead>>>()
                val pageMs = ArrayList<Long>(); val stages = LinkedHashMap<String, Long>(); var peak = 0L
                var found = 0; var matched = 0
                for ((i, p) in pages.withIndex()) {
                    val prof = OmrProfile(p)
                    val gray = prof.stage("decode") { TestImages.loadGray(File(dir, "$p.png")) }
                    val t0 = System.nanoTime()
                    val loc = pl.locate(gray, accurate, { _, _, _ -> }, { false }, prof)
                    val sys = pl.recognizeAll(gray, accurate, located = loc)
                    pageMs += (System.nanoTime() - t0) / 1_000_000
                    for ((k, v) in prof.ms()) stages[k] = (stages[k] ?: 0) + v
                    peak = maxOf(peak, prof.peakRssKb.get())
                    systemsByPage += sys
                    val n = sys.sumOf { it.size }; found += n
                    expected?.getOrNull(i)?.let { matched += minOf(it, n) }
                }
                val score = set["musicxml"]?.jsonPrimitive?.content?.let { path ->
                    val gt = OmrEval.readMusicXml(File(path))
                    val total = OmrEval.Score()
                    for (m in set.str("map").split(',')) {
                        val parts = m.split(':'); val idx = parts[0].toInt(); val pid = parts[1]; val shift = parts.getOrNull(2)?.toInt() ?: 0
                        val staffs = systemsByPage.flatten().mapNotNull { it.getOrNull(idx)?.tokens }
                        val s = OmrEval.score(gt.getValue(pid), OmrEval.fromTokens(staffs, shift))
                        println("EVAL $name $pid: notes ${s.notes} pitch %.1f%% dur %.1f%% both %.1f%% SER %.1f%%".format(s.pitchAcc, s.durAcc, s.bothAcc, s.ser))
                        total.add(s)
                    }
                    total
                }
                results += SetResult(name, kind, pages.size, expected?.sum(), found, matched, score, pageMs, stages, peak)
            }
        }
        val md = report(results)
        println(md)
        File(out, "report.md").writeText(md)
        File(out, "report.json").writeText(json(results))
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    private fun report(rs: List<SetResult>): String = buildString {
        fun pct(v: Double?) = v?.let { "%.1f".format(it) } ?: "—"
        append("| Набор | Тип | Стр. | Нотоносцы: найдено/ожидалось | Recall | Нот | Высоты % | Длит. % | Выс.+длит. % | SER % | мс/стр. (медиана) | Пик RSS, МБ |\n")
        append("|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        fun row(label: String, kind: String, list: List<SetResult>) {
            val sc = list.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.let { l -> OmrEval.Score().also { t -> l.forEach(t::add) } }
            val exp = list.mapNotNull { it.expected }.takeIf { it.isNotEmpty() }?.sum()
            val matched = list.filter { it.expected != null }.sumOf { it.matched }
            val ms = list.flatMap { it.pageMs }.sorted()
            append("| $label | $kind | ${list.sumOf { it.pages }} | ${list.sumOf { it.found }}/${exp ?: "—"} | ${exp?.let { pct(100.0 * matched / it) } ?: "—"} | ${sc?.notes ?: "—"} | ")
            append("${pct(sc?.pitchAcc)} | ${pct(sc?.durAcc)} | ${pct(sc?.bothAcc)} | ${pct(sc?.ser)} | ${ms.getOrNull(ms.size / 2) ?: "—"} | ${list.maxOf { it.peakRssKb } / 1024} |\n")
        }
        for (r in rs) row(r.name, r.kind, listOf(r))
        for ((kind, list) in rs.groupBy { it.kind }) row("**Всего: $kind**", kind, list)
        append("\nВремя этапов, мс на страницу (среднее):\n\n")
        val stages = rs.flatMap { it.stages.keys }.distinct()
        append("| Набор | ${stages.joinToString(" | ")} |\n|---|${stages.joinToString("") { "---|" }}\n")
        for (r in rs) append("| ${r.name} | ${stages.joinToString(" | ") { s -> ((r.stages[s] ?: 0) / maxOf(1, r.pages)).toString() }} |\n")
        append("\nПик памяти процесса за прогон (VmHWM): ${MemoryWatch.processPeakRssKb() / 1024} МБ; ядер: ${Runtime.getRuntime().availableProcessors()}.\n")
    }

    private fun json(rs: List<SetResult>): String = rs.joinToString(",\n", "[\n", "\n]") { r ->
        val s = r.score
        "{\"name\":\"${r.name}\",\"kind\":\"${r.kind}\",\"pages\":${r.pages},\"stavesExpected\":${r.expected},\"stavesFound\":${r.found},\"stavesMatched\":${r.matched}," +
            (if (s != null) "\"notes\":${s.notes},\"pitchAcc\":${"%.2f".format(s.pitchAcc)},\"durAcc\":${"%.2f".format(s.durAcc)},\"pitchDurAcc\":${"%.2f".format(s.bothAcc)},\"ser\":${"%.2f".format(s.ser)}," else "") +
            "\"pageMs\":${r.pageMs},\"stagesMs\":{${r.stages.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}},\"peakRssKb\":${r.peakRssKb}}"
    }
}
