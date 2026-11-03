package ru.partitrainer.omr.homr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Классы сегментации (порядок из homr). */
object SegClass { const val BACKGROUND = 0; const val STEMS_RESTS = 1; const val NOTEHEAD = 2; const val CLEFS_KEYS = 3; const val STAFF = 4; const val SYMBOLS = 5 }

/** Карта классов страницы: один байт на пиксель. */
class SegMap(val width: Int, val height: Int, val cls: ByteArray) {
    /** Поворот вокруг центра (ближайший сосед) — для выравнивания наклонной страницы без повторной сегментации. */
    fun rotated(radians: Double): SegMap {
        val out = ByteArray(width * height)
        val c = Math.cos(radians); val sn = Math.sin(radians)
        val cx = (width - 1) / 2.0; val cy = (height - 1) / 2.0
        for (y in 0 until height) {
            val dy = y - cy
            for (x in 0 until width) {
                val dx = x - cx
                val sx = Math.round(cx + dx * c - dy * sn).toInt(); val sy = Math.round(cy + dx * sn + dy * c).toInt()
                if (sx in 0 until width && sy in 0 until height) out[y * width + x] = cls[sy * width + sx]
            }
        }
        return SegMap(width, height, out)
    }

    fun count(c: Int): Int { var n = 0; val b = c.toByte(); for (v in cls) if (v == b) n++; return n }
}

/**
 * Сегментация нотной страницы нейросетью segnet (ONNX), без перекрытия окон (шаг = 320, как в homr).
 * Вход — «сырые» значения яркости 0..255, три одинаковых канала.
 */
class Segnet(private val env: OrtEnvironment, private val session: OrtSession, private val batchSize: Int = 8) {
    /** Сколько окон пропущено как пустые (для профилирования). */
    @Volatile var skippedTiles = 0

    companion object {
        const val WIN = 320
        /** Порог «чистой бумаги». */
        const val BLANK_LEVEL = 235
    }

    private val inputName = session.inputNames.first()

    fun segment(page: GrayImage, onProgress: (Int, Int) -> Unit = { _, _ -> }): SegMap {
        val h = page.height; val w = page.width
        // Координаты окон: как в inference() + merge_patches()
        val ys = (0 until maxOf(h, WIN) step WIN).map { minOf(it, h - WIN) }
        val xs = (0 until maxOf(w, WIN) step WIN).map { minOf(it, w - WIN) }
        val recon = FloatArray(w * h)
        val weight = ByteArray(w * h)
        val coords = ArrayList<IntArray>()
        for (y in ys) for (x in xs) {
            // Пустое окно (чистая бумага) нейросеть всё равно разметит как фон — не тратим на него время
            if (isBlank(page, x, y)) { skippedTiles++; continue }
            coords += intArrayOf(y, x)
        }

        var done = 0
        var i = 0
        if (coords.isEmpty()) onProgress(0, 0)
        while (i < coords.size) {
            val n = minOf(batchSize, coords.size - i)
            val input = FloatArray(n * 3 * WIN * WIN)
            for (b in 0 until n) {
                val (y0, x0) = coords[i + b].let { it[0] to it[1] }
                val base = b * 3 * WIN * WIN
                for (py in 0 until WIN) for (px in 0 until WIN) {
                    val sy = y0 + py; val sx = x0 + px
                    val v = if (sy in 0 until h && sx in 0 until w) page[sx, sy].toFloat() else 255f
                    val o = base + py * WIN + px
                    input[o] = v; input[o + WIN * WIN] = v; input[o + 2 * WIN * WIN] = v
                }
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(n.toLong(), 3, WIN.toLong(), WIN.toLong())).use { t ->
                session.run(mapOf(inputName to t)).use { res ->
                    val out = res[0] as OnnxTensor
                    val shape = out.info.shape            // [n, C, 320, 320]
                    val classes = shape[1].toInt()
                    val buf = out.floatBuffer
                    val plane = WIN * WIN
                    for (b in 0 until n) {
                        val (y0, x0) = coords[i + b].let { it[0] to it[1] }
                        val base = b * classes * plane
                        for (p in 0 until plane) {
                            var best = 0; var bv = buf.get(base + p)
                            for (c in 1 until classes) { val v = buf.get(base + c * plane + p); if (v > bv) { bv = v; best = c } }
                            val sy = y0 + p / WIN; val sx = x0 + p % WIN
                            if (sy in 0 until h && sx in 0 until w) { val o = sy * w + sx; recon[o] += best.toFloat(); weight[o]++ }
                        }
                    }
                }
            }
            i += n; done += n
            onProgress(done, coords.size)
        }
        val cls = ByteArray(w * h)
        for (k in cls.indices) {
            if (weight[k].toInt() == 0) continue   // не размечено (пропущенные пустые окна) — фон
            val wt = weight[k].toInt()
            cls[k] = (recon[k] / wt).toInt().toByte()   // усечение, как astype(int64) в homr
        }
        return SegMap(w, h, cls)
    }

    /** Окно пустое, если все его пиксели светлее порога (после CLAHE бумага ≈ 255). */
    private fun isBlank(page: GrayImage, x0: Int, y0: Int): Boolean {
        val xe = minOf(page.width, x0 + WIN); val ye = minOf(page.height, y0 + WIN)
        for (y in maxOf(0, y0) until ye) {
            val row = y * page.width
            for (x in maxOf(0, x0) until xe) if ((page.data[row + x].toInt() and 0xFF) < BLANK_LEVEL) return false
        }
        return true
    }
}
