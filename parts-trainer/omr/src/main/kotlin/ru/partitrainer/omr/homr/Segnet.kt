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
 * Сегментация нотной страницы нейросетью segnet (ONNX), окна 320×320 с шагом 320, как в homr. Последний ряд/столбец окон
 * сдвигается к краю страницы и перекрывает соседний, если размер не кратен 320.
 * Вход — «сырые» значения яркости 0..255, три одинаковых канала.
 */
class Segnet(private val env: OrtEnvironment, private val session: OrtSession, private val batchSize: Int = 8) {
    /** Сколько окон пропущено как пустые (для профилирования). */
    @Volatile var skippedTiles = 0

    companion object {
        const val WIN = 320
        /** Порог «чистой бумаги». */
        const val BLANK_LEVEL = 235

        /** Начала окон по одной оси (как в homr: шаг [WIN], последнее окно прижато к краю; меньше окна — одно окно с 0). */
        fun starts(size: Int): List<Int> = (0 until maxOf(size, WIN) step WIN).map { maxOf(0, minOf(it, size - WIN)) }.distinct()
    }

    private val inputName = session.inputNames.first()

    fun segment(page: GrayImage, strategy: SegMerge.Strategy = SegMerge.Strategy.DEFAULT, onProgress: (Int, Int) -> Unit = { _, _ -> }): SegMap {
        val merge = SegMerge(page.width, page.height, starts(page.height), starts(page.width), strategy)
        runTiles(page, tiles(page), onProgress) { y0, x0, scores, base, classes -> merge.add(y0, x0, scores, base, classes) }
        return merge.result()
    }

    /** Окна страницы без пустых (чистая бумага — нейросеть всё равно разметит её как фон). */
    fun tiles(page: GrayImage): List<IntArray> {
        val coords = ArrayList<IntArray>()
        for (y in starts(page.height)) for (x in starts(page.width)) {
            if (isBlank(page, x, y)) { skippedTiles++; continue }
            coords += intArrayOf(y, x)
        }
        return coords
    }

    /**
     * Прогоняет окна [coords] (y, x) через сеть. [onTile] получает выход сети для окна: оценки класса c в точке p окна
     * лежат в `scores[base + c * WIN * WIN + p]`. Части окна за краем страницы заполняются белым.
     */
    fun runTiles(
        page: GrayImage, coords: List<IntArray>, onProgress: (Int, Int) -> Unit = { _, _ -> },
        onTile: (y0: Int, x0: Int, scores: java.nio.FloatBuffer, base: Int, classes: Int) -> Unit,
    ) {
        val h = page.height; val w = page.width
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
                    val classes = out.info.shape[1].toInt()           // [n, C, 320, 320]
                    val buf = out.floatBuffer
                    for (b in 0 until n) onTile(coords[i + b][0], coords[i + b][1], buf, b * classes * WIN * WIN, classes)
                }
            }
            i += n; done += n
            onProgress(done, coords.size)
        }
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

/**
 * Склейка окон сегментации в карту классов страницы.
 *
 * Окна идут с шагом 320 без перекрытия, кроме последнего ряда/столбца, прижатого к краю: там пиксель покрыт двумя (в углу —
 * четырьмя) окнами. В homr (и в этом порте до ноября 2026) номера классов, выбранные в каждом окне, усреднялись — при
 * несогласии окон получался третий класс: фон 0 + линейка 4 → 2 «головка ноты», штиль 1 + линейка 4 → 2, головка 2 +
 * линейка 4 → 3 «ключ». Это [Strategy.ID_AVERAGE], оставлено для сравнения.
 *
 * Память: вне полос перекрытия класс пишется сразу (1 байт на пиксель), в полосах — суммы по классам.
 */
class SegMerge(
    val width: Int, val height: Int, ys: List<Int>, xs: List<Int>,
    val strategy: Strategy = Strategy.DEFAULT,
) {
    enum class Strategy {
        /** Среднее номеров классов с усечением (как в homr) — ошибочно, только для сравнения. */
        ID_AVERAGE,
        /** Голосование окон за класс; ничья — у кого выше вероятность. */
        VOTE,
        /** Сумма вероятностей (softmax) по окнам, затем лучший класс. */
        PROB_SUM,
        /** Класс окна, которое увереннее всех. */
        MAX_PROB;

        companion object { val DEFAULT = MAX_PROB }
    }

    private val cls = ByteArray(width * height)
    /** Полоса перекрытия по строкам [rLo, rHi) на всю ширину и по столбцам [cLo, cHi) на всю высоту. */
    private val rLo: Int; private val rHi: Int; private val cLo: Int; private val cHi: Int
    private val rowBand: Int; private val bandSize: Int
    private var k = 0
    private var acc: FloatArray? = null
    private var cnt: ByteArray? = null

    init {
        fun band(starts: List<Int>, size: Int): Pair<Int, Int> {
            if (starts.size < 2) return 0 to 0
            val last = starts.last(); val prevEnd = minOf(size, starts[starts.size - 2] + Segnet.WIN)
            return if (last < prevEnd) last to prevEnd else 0 to 0
        }
        val (a, b) = band(ys, height); rLo = a; rHi = b
        val (c, d) = band(xs, width); cLo = c; cHi = d
        rowBand = (rHi - rLo) * width
        bandSize = rowBand + height * (cHi - cLo)
    }

    /** Номер пикселя в полосах перекрытия или -1. */
    private fun bandIndex(x: Int, y: Int): Int = when {
        y in rLo until rHi -> (y - rLo) * width + x
        x in cLo until cHi -> rowBand + y * (cHi - cLo) + (x - cLo)
        else -> -1
    }

    /** Добавить окно с левым верхним углом (x0, y0): оценки класса c в точке p окна — `scores[base + c * WIN² + p]`. */
    fun add(y0: Int, x0: Int, scores: java.nio.FloatBuffer, base: Int, classes: Int) {
        val win = Segnet.WIN; val plane = win * win
        if (acc == null && bandSize > 0) { k = classes; acc = FloatArray(bandSize * classes); cnt = ByteArray(bandSize) }
        val prob = FloatArray(classes)
        for (p in 0 until plane) {
            val sy = y0 + p / win; val sx = x0 + p % win
            if (sy !in 0 until height || sx !in 0 until width) continue
            var best = 0; var bv = scores.get(base + p)
            for (c in 1 until classes) { val v = scores.get(base + c * plane + p); if (v > bv) { bv = v; best = c } }
            val bi = bandIndex(sx, sy)
            if (bi < 0) { cls[sy * width + sx] = best.toByte(); continue }
            val a = acc!!; val o = bi * k
            cnt!![bi] = (cnt!![bi] + 1).toByte()
            when (strategy) {
                Strategy.ID_AVERAGE -> a[o] += best.toFloat()
                Strategy.VOTE -> a[o + best] += 1f + 0.001f * softmax(scores, base, plane, p, classes, prob)[best]
                Strategy.PROB_SUM -> { softmax(scores, base, plane, p, classes, prob); for (c in 0 until classes) a[o + c] += prob[c] }
                Strategy.MAX_PROB -> {
                    val pb = softmax(scores, base, plane, p, classes, prob)[best]
                    if (pb > a[o]) { a[o] = pb; a[o + 1] = best.toFloat() }
                }
            }
        }
    }

    private fun softmax(scores: java.nio.FloatBuffer, base: Int, plane: Int, p: Int, classes: Int, out: FloatArray): FloatArray {
        var m = Float.NEGATIVE_INFINITY
        for (c in 0 until classes) { val v = scores.get(base + c * plane + p); out[c] = v; if (v > m) m = v }
        var sum = 0f
        for (c in 0 until classes) { val e = Math.exp((out[c] - m).toDouble()).toFloat(); out[c] = e; sum += e }
        for (c in 0 until classes) out[c] /= sum
        return out
    }

    fun result(): SegMap {
        val a = acc; val n = cnt
        if (a != null && n != null) for (y in 0 until height) for (x in 0 until width) {
            val bi = bandIndex(x, y); if (bi < 0 || n[bi].toInt() == 0) continue
            val o = bi * k
            val c = when (strategy) {
                Strategy.ID_AVERAGE -> (a[o] / n[bi]).toInt()      // усечение, как astype(int64) в homr
                Strategy.MAX_PROB -> a[o + 1].toInt()
                else -> { var best = 0; for (j in 1 until k) if (a[o + j] > a[o + best]) best = j; best }
            }
            cls[y * width + x] = c.toByte()
        }
        return SegMap(width, height, cls)
    }
}
