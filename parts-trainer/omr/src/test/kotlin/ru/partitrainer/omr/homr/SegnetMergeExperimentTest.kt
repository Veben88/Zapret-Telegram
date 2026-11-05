package ru.partitrainer.omr.homr

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Сравнение способов склейки окон SegNet (Sprint 0). Запуск:
 * `HOMR_MODELS=<папка> HOMR_SEGMERGE="dir:p1,p2;dir2:p3" gradle -c settings-core.gradle.kts :omr:test --tests '*SegnetMergeExperiment*' -i`
 *
 * Для полосы перекрытия окон строится «эталон»: окна, сдвинутые так, что пиксели полосы лежат внутри окна (≥ 79 px от
 * края), — сеть видит их с контекстом со всех сторон. Стратегии сравниваются с ним; «невозможный» класс — такой, который
 * не выбрало ни одно из покрывающих пиксель окон.
 */
class SegnetMergeExperimentTest {
    private val models = System.getenv("HOMR_MODELS")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

    private fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }

    @Test fun compareStrategies() {
        val spec = System.getenv("HOMR_SEGMERGE")?.takeIf { it.isNotBlank() }
        assumeTrue(models != null && spec != null)
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        env.createSession(f("segnet").path).use { sess ->
            val segnet = Segnet(env, sess)
            val images = ArrayList<Pair<String, GrayImage>>()
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    val page = ImageOps.clahe(ImageOps.resizeToHomrWidth(TestImages.loadGray(File(dir, "$name.png"))))
                    images += name to page
                }
            }
            // размеры, не кратные 320 по обеим осям, и меньше окна
            images.firstOrNull()?.second?.let { p ->
                images += "crop1000x777" to p.crop(100, 300, 1100, 1077)
                images += "crop641x641" to p.crop(300, 400, 941, 1041)
                images += "crop319x500" to p.crop(200, 300, 519, 800)
                images += "crop200x150" to p.crop(400, 500, 600, 650)
            }
            val strategies = SegMerge.Strategy.entries
            val tot = HashMap<String, LongArray>()     // стратегия → [совпало, пикселей, совпало «чернила», «чернила», невозможных, мс]
            for ((name, page) in images) {
                val w = page.width; val h = page.height
                val ys = Segnet.starts(h); val xs = Segnet.starts(w)
                val merges = strategies.map { SegMerge(w, h, ys, xs, it) }
                val seen = ByteArray(w * h)                 // какие классы выбирали окна (битовая маска)
                val coords = segnet.tiles(page)
                val t0 = System.nanoTime()
                segnet.runTiles(page, coords) { y0, x0, sc, base, k ->
                    for (m in merges) m.add(y0, x0, sc, base, k)
                    val plane = Segnet.WIN * Segnet.WIN
                    for (p in 0 until plane) {
                        val sy = y0 + p / Segnet.WIN; val sx = x0 + p % Segnet.WIN
                        if (sy !in 0 until h || sx !in 0 until w) continue
                        var best = 0; var bv = sc.get(base + p)
                        for (c in 1 until k) { val v = sc.get(base + c * plane + p); if (v > bv) { bv = v; best = c } }
                        seen[sy * w + sx] = (seen[sy * w + sx].toInt() or (1 shl best)).toByte()
                    }
                }
                val runMs = (System.nanoTime() - t0) / 1_000_000
                val maps = strategies.zip(merges.map { it.result() }).toMap()
                // E: страница, дополненная белым до кратного 320 размера, — перекрытий нет
                val pw = (w + Segnet.WIN - 1) / Segnet.WIN * Segnet.WIN; val ph = (h + Segnet.WIN - 1) / Segnet.WIN * Segnet.WIN
                val padded = GrayImage(pw, ph).also { g -> java.util.Arrays.fill(g.data, 255.toByte()); for (y in 0 until h) System.arraycopy(page.data, y * w, g.data, y * pw, w) }
                val pm = segnet.segment(padded)
                val padMap = SegMap(w, h, ByteArray(w * h) { pm.cls[(it / w) * pw + it % w] })
                // эталон для полос
                fun band(starts: List<Int>, size: Int): IntRange? {
                    if (starts.size < 2) return null
                    val last = starts.last(); val prevEnd = minOf(size, starts[starts.size - 2] + Segnet.WIN)
                    return if (last < prevEnd) last until prevEnd else null
                }
                val rb = band(ys, h); val cb = band(xs, w)
                val ref = ByteArray(w * h) { -1 }
                fun refRun(coordsRef: List<IntArray>, keep: (Int, Int) -> Boolean) = segnet.runTiles(page, coordsRef) { y0, x0, sc, base, k ->
                    val plane = Segnet.WIN * Segnet.WIN
                    for (p in 0 until plane) {
                        val sy = y0 + p / Segnet.WIN; val sx = x0 + p % Segnet.WIN
                        if (sy !in 0 until h || sx !in 0 until w || !keep(sx, sy)) continue
                        var best = 0; var bv = sc.get(base + p)
                        for (c in 1 until k) { val v = sc.get(base + c * plane + p); if (v > bv) { bv = v; best = c } }
                        ref[sy * w + sx] = best.toByte()
                    }
                }
                if (rb != null) { val y0 = ((rb.first + rb.last) / 2 - Segnet.WIN / 2).coerceIn(0, maxOf(0, h - Segnet.WIN)); refRun(xs.map { intArrayOf(y0, it) }) { _, y -> y in rb } }
                if (cb != null) { val x0 = ((cb.first + cb.last) / 2 - Segnet.WIN / 2).coerceIn(0, maxOf(0, w - Segnet.WIN)); refRun(ys.map { intArrayOf(it, x0) }) { x, y -> x in cb && (rb == null || y !in rb) } }
                val all = maps + ("E_PAD" to padMap)
                val sb = StringBuilder("SEGMERGE $name ${w}x$h band rows=${rb ?: "-"} cols=${cb ?: "-"} tiles=${coords.size} run=${runMs}ms\n")
                for ((label, m) in all.mapKeys { it.key.toString() }) {
                    var same = 0L; var n = 0L; var inkSame = 0L; var ink = 0L; var impossible = 0L; var heads = 0L; var refHeads = 0L
                    for (k in 0 until w * h) {
                        val r = ref[k].toInt(); if (r < 0) continue
                        val c = m.cls[k].toInt()
                        n++; if (c == r) same++
                        if (c != 0 || r != 0) { ink++; if (c == r) inkSame++ }
                        if (seen[k].toInt() != 0 && (seen[k].toInt() shr c) and 1 == 0) impossible++
                        if (c == SegClass.NOTEHEAD) heads++; if (r == SegClass.NOTEHEAD) refHeads++
                    }
                    val staves = StaffDetector.detect(m)
                    sb.append("  %-10s band agree %6.2f%%  ink agree %6.2f%%  impossible %6d  noteheads %6d (ref %d)  staves %d %s\n".format(
                        label, if (n == 0L) 100.0 else 100.0 * same / n, if (ink == 0L) 100.0 else 100.0 * inkSame / ink, impossible, heads, refHeads,
                        staves.size, staves.map { "${it.minY}-${it.maxY}" }))
                    tot.getOrPut(label) { LongArray(5) }.let { it[0] += same; it[1] += n; it[2] += inkSame; it[3] += ink; it[4] += impossible }
                }
                println(sb)
            }
            for ((label, t) in tot) println("SEGMERGE TOTAL %-10s band agree %6.2f%%  ink agree %6.2f%%  impossible %d".format(
                label, 100.0 * t[0] / maxOf(1, t[1]), 100.0 * t[2] / maxOf(1, t[3]), t[4]))
        }
    }
}
