package ru.partitrainer.omr.homr

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Координаты распознанных нот на исходной странице (Sprint 0): точка ноты — так, как её получает приложение (холст строки →
 * выровненная страница → обратный поворот [LayoutBuilder.toSourcePoint] → доля ширины/высоты), переносится на исходную
 * картинку, и там проверяется, есть ли рядом «толстое» чёрное (головка ноты, а не линейка). Для сравнения — те же точки,
 * сдвинутые на 2 интервала вправо (случайные места строки).
 *
 * `HOMR_MODELS=<папка> HOMR_POS="dir:p1,p2;dir2:p3" gradle -c settings-core.gradle.kts :omr:test --tests '*NotePosition*' -i`
 */
class NotePositionTest {
    @Test fun notesLandOnNoteheads() {
        val spec = System.getenv("HOMR_POS")?.takeIf { it.isNotBlank() }
        val models = System.getenv("HOMR_MODELS")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(spec != null && models != null && models.isDirectory)
        fun f(pr: String) = models!!.listFiles()!!.first { it.name.startsWith(pr) && it.name.endsWith(".onnx") }
        HomrPipeline(HomrModelFiles(f("segnet"), f("encoder"), f("decoder")), threads = 4, staffWorkers = 4, decoderThreads = 1, encoderThreads = 1).use { pl ->
            for (part in spec!!.split(';')) {
                val (dir, names) = part.split(':')
                for (name in names.split(',')) {
                    val src = TestImages.loadGray(File(dir, "$name.png"))
                    val loc = pl.locate(src, false, { _, _, _ -> }, { false })
                    val reads = pl.recognizeAll(src, located = loc)
                    val frame = PageFrame(loc.page.width, loc.page.height, loc.angle)
                    val scale = src.width.toDouble() / loc.page.width
                    var n = 0; var hit = 0; var ctrl = 0; var off = 0.0
                    for (r in reads.flatten()) {
                        val u = r.staff.unit * scale
                        for (t in r.tokens) {
                            if (!t.rhythm.startsWith("note_") || t.x.isNaN()) continue
                            val (sx, sy) = LayoutBuilder.toSourcePoint(frame, t.x, t.y)
                            val px = sx / frame.width * src.width; val py = sy / frame.height * src.height   // как NoteMark (доли) → пиксели исходника
                            n++
                            val d = nearestHead(src, px, py, u)
                            if (d != null) { hit++; off += d }
                            if (nearestHead(src, px + 2 * u, py, u) != null) ctrl++
                        }
                    }
                    println("POS $name angle=%.2f° notes=$n onHead=%.1f%% control=%.1f%% meanOffset=%.2f interval".format(
                        Math.toDegrees(loc.angle), 100.0 * hit / maxOf(1, n), 100.0 * ctrl / maxOf(1, n), off / maxOf(1, hit)))
                }
            }
        }
    }

    /**
     * Расстояние (в интервалах) от точки до ближайшего пикселя головки ноты в радиусе 0,8 интервала или null.
     * «Головка» — тёмный пиксель, у которого тёмный столбик по вертикали не короче 0,35 интервала (линейки тоньше).
     */
    private fun nearestHead(img: GrayImage, x: Double, y: Double, u: Double): Double? {
        val r = (0.8 * u).toInt(); val need = maxOf(3, (0.35 * u).toInt())
        var best: Double? = null
        for (yy in (y - r).toInt()..(y + r).toInt()) for (xx in (x - r).toInt()..(x + r).toInt()) {
            if (xx !in 0 until img.width || yy !in 0 until img.height || img[xx, yy] >= 128) continue
            var run = 1
            var k = yy - 1; while (k >= 0 && img[xx, k] < 128 && run < need) { run++; k-- }
            k = yy + 1; while (k < img.height && img[xx, k] < 128 && run < need) { run++; k++ }
            if (run < need) continue
            val d = Math.hypot(xx - x, yy - y) / u
            if (d <= 0.8 && (best == null || d < best)) best = d
        }
        return best
    }
}
