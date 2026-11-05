package ru.partitrainer.omr.homr

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Уточнение положения нот ([NoteSnap]) против независимого эталона — головок нот, найденных SegNet (класс «notehead»):
 * расстояние от точки ноты до центра ближайшей головки (в интервалах) до и после уточнения.
 * `HOMR_MODELS=<папка> HOMR_SNAP="dir:p1,p2" gradle -c settings-core.gradle.kts :omr:test --tests '*NoteSnapExperiment*' -i`
 */
class NoteSnapExperimentTest {
    @Test fun snapAgainstSegnetHeads() {
        val spec = System.getenv("HOMR_SNAP")?.takeIf { it.isNotBlank() }
        val models = System.getenv("HOMR_MODELS")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(spec != null && models != null && models.isDirectory)
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val segSess = env.createSession(f("segnet").path)
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 4, staffWorkers = 4, decoderThreads = 1, encoderThreads = 1).use { pl ->
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    val loc = pl.locate(TestImages.loadGray(File(dir, "$name.png")), false, { _, _, _ -> }, { false })
                    val reads = pl.recognizeAll(loc.page, located = loc)
                    val seg = Segnet(env, segSess).segment(loc.page)
                    val heads = centroids(seg, loc.page.width, loc.page.height)
                    val integral = NoteSnap.Integral(loc.page.width, loc.page.height, NoteSnap.inkWithoutLines(loc.page))
                    var n = 0; var rawD = 0.0; var snapD = 0.0; var rawIn = 0; var snapIn = 0; var moved = 0; var worse = 0
                    for (r in reads.flatten()) {
                        val u = r.staff.unit
                        for (t in r.tokens) {
                            if (!t.rhythm.startsWith("note_") || t.x.isNaN()) continue
                            val d0 = nearest(heads, t.x, t.y) / u
                            val s = NoteSnap.snap(loc.page, integral, t.x, t.y, u)
                            val d1 = if (s == null) d0 else nearest(heads, s.first, s.second) / u
                            n++; rawD += minOf(d0, 1.0); snapD += minOf(d1, 1.0)
                            if (d0 <= 0.25) rawIn++; if (d1 <= 0.25) snapIn++
                            if (s != null) moved++; if (d1 > d0 + 0.15) worse++
                        }
                    }
                    println("SNAP $name notes=$n moved=$moved  mean dist %.2f → %.2f  within 0.25 sp %.1f%% → %.1f%%  worse=$worse".format(
                        rawD / n, snapD / n, 100.0 * rawIn / n, 100.0 * snapIn / n))
                }
            }
        }
    }

    /** Центры связных областей класса «головка ноты». */
    private fun centroids(seg: SegMap, w: Int, h: Int): List<Pair<Double, Double>> {
        val lab = IntArray(w * h); val out = ArrayList<Pair<Double, Double>>(); val stack = IntArray(w * h); var next = 1
        for (i in 0 until w * h) {
            if (seg.cls[i].toInt() != SegClass.NOTEHEAD || lab[i] != 0) continue
            var sp = 0; stack[sp++] = i; lab[i] = next; var sx = 0.0; var sy = 0.0; var c = 0
            while (sp > 0) {
                val q = stack[--sp]; val x = q % w; val y = q / w; sx += x; sy += y; c++
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) { val k = ny * w + nx; if (lab[k] == 0 && seg.cls[k].toInt() == SegClass.NOTEHEAD) { lab[k] = next; stack[sp++] = k } }
                }
            }
            next++
            if (c >= 12) out += sx / c to sy / c
        }
        return out
    }

    private fun nearest(heads: List<Pair<Double, Double>>, x: Double, y: Double) = heads.minOfOrNull { Math.hypot(it.first - x, it.second - y) } ?: 1e9
}
