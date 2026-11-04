package ru.partitrainer.omr.homr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Один символ, предсказанный трансформером (ветки декодера как в homr). */
data class EncodedSymbol(
    val rhythm: String, val pitch: String, val lift: String,
    val articulation: String, val slur: String, val position: String,
) {
    override fun toString() = "$rhythm $pitch $lift $articulation $slur $position"

    /**
     * Где символ на странице (пиксели выровненной страницы 1920 px): центр внимания декодера, перенесённый с холста строки
     * обратно на страницу. Не участвует в сравнении символов. NaN — неизвестно.
     */
    var x: Double = Double.NaN
    var y: Double = Double.NaN

    fun withPositionOf(o: EncodedSymbol): EncodedSymbol = also { x = o.x; y = o.y }
}

class HomrVocab private constructor(
    val rhythm: List<String>, val pitch: List<String>, val lift: List<String>,
    val articulation: List<String>, val slur: List<String>, val position: List<String>,
) {
    companion object {
        fun load(json: String): HomrVocab {
            val o = Json.parseToJsonElement(json) as JsonObject
            fun l(k: String) = (o.getValue(k) as JsonArray).map { (it as JsonPrimitive).content }
            return HomrVocab(l("rhythm"), l("pitch"), l("lift"), l("articulation"), l("slur"), l("position"))
        }

        fun fromResources(): HomrVocab =
            load(HomrVocab::class.java.getResourceAsStream("/homr/vocab.json")!!.readBytes().toString(Charsets.UTF_8))
    }
}

/** Энкодер + декодер TrOMR (ONNX) с жадным декодированием и KV-кэшем. */
class Staff2Score(
    private val env: OrtEnvironment, private val encoder: OrtSession, private val decoder: OrtSession,
    private val vocab: HomrVocab,
    /** Если задан — прогоны энкодера идут по одному (он тяжёлый по памяти), а декодеры нескольких строк — параллельно. */
    private val encoderLock: Any? = null,
) {
    companion object {
        const val HEIGHT = 256
        const val WIDTH = 1280
        const val MAX_SEQ_LEN = 608
        const val EOS = 2
        const val DEPTH = 8
        const val HEADS = 8
        const val HEAD_DIM = 64
        const val MEAN = 0.7931
        const val STD = 0.1738
    }

    /** Профилирование: суммарное время энкодера/декодера (мс) и число шагов декодера. */
    @Volatile var encoderMs = 0L; @Volatile var decoderMs = 0L; @Volatile var steps = 0

    private val cacheIn = (0 until DEPTH * 4).map { "cache_in$it" }
    private val cacheOut = (0 until DEPTH * 4).map { "cache_out$it" }
    private val outNames = listOf("out_rhythms", "out_pitchs", "out_lifts", "out_positions", "out_articulations", "out_slurs", "attention") + cacheOut


    /** [canvas] — изображение строки 256×1280 (белый фон). */
    fun predict(canvas: GrayImage, isCancelled: () -> Boolean = { false }, maxSteps: Int = MAX_SEQ_LEN): List<EncodedSymbol> = predictBatch(listOf(canvas), isCancelled, maxSteps)[0]

    /** Контекст энкодера [1, N, 512] для одной строки. */
    private fun encode(canvas: GrayImage): Pair<FloatArray, LongArray> {
        require(canvas.width == WIDTH && canvas.height == HEIGHT)
        val x = FloatArray(HEIGHT * WIDTH) { (((canvas.data[it].toInt() and 0xFF) / 255.0 - MEAN) / STD).toFloat() }
        val te = System.nanoTime()
        try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 1, HEIGHT.toLong(), WIDTH.toLong())).use { t ->
                val res = if (encoderLock != null) synchronized(encoderLock) { encoder.run(mapOf(encoder.inputNames.first() to t)) } else encoder.run(mapOf(encoder.inputNames.first() to t))
                res.use {
                    val ctx = it[0] as OnnxTensor
                    return FloatArray(ctx.floatBuffer.remaining()).also { a -> ctx.floatBuffer.duplicate().get(a) } to ctx.info.shape
                }
            }
        } finally { encoderMs += (System.nanoTime() - te) / 1_000_000 }
    }

    /** Чтение строк; батч (>1 строки за прогон) возможен только с декодером, у которого размер батча не зашит в модель. */
    fun predictBatch(canvases: List<GrayImage>, isCancelled: () -> Boolean = { false }, maxSteps: Int = MAX_SEQ_LEN): List<List<EncodedSymbol>> {
        if (canvases.isEmpty()) return emptyList()
        val encoded = canvases.map { if (isCancelled()) throw InterruptedException("Отменено"); encode(it) }
        val b = canvases.size
        val n = encoded[0].second[1].toInt(); val dim = encoded[0].second[2].toInt()
        val full = FloatArray(b * n * dim); val reduced = FloatArray(b * dim)
        for ((i, e) in encoded.withIndex()) {
            System.arraycopy(e.first, 0, full, i * n * dim, n * dim)
            System.arraycopy(e.first, 0, reduced, i * dim, dim)        // context[:, :1]
        }
        val fullT = OnnxTensor.createTensor(env, FloatBuffer.wrap(full), longArrayOf(b.toLong(), n.toLong(), dim.toLong()))
        val redT = OnnxTensor.createTensor(env, FloatBuffer.wrap(reduced), longArrayOf(b.toLong(), 1, dim.toLong()))
        val td = System.nanoTime()
        try {
            return decode(b, fullT, redT, isCancelled, maxSteps)
        } finally {
            decoderMs += (System.nanoTime() - td) / 1_000_000
            fullT.close(); redT.close()
        }
    }

    private fun decode(b: Int, ctxFull: OnnxTensor, ctxReduced: OnnxTensor, isCancelled: () -> Boolean, maxSteps: Int): List<List<EncodedSymbol>> {
        val symbols = List(b) { ArrayList<EncodedSymbol>() }
        val done = BooleanArray(b)
        val rhythm = LongArray(b) { 1L }; val pitch = LongArray(b); val lift = LongArray(b); val art = LongArray(b); val slur = LongArray(b)
        var cache: ai.onnxruntime.OrtSession.Result? = null
        val emptyCache: List<OnnxTensor> = (0 until DEPTH * 4).map {
            OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(b.toLong(), HEADS.toLong(), 0, HEAD_DIM.toLong()))
        }
        fun longs(v: LongArray) = OnnxTensor.createTensor(env, LongBuffer.wrap(v.copyOf()), longArrayOf(b.toLong(), 1))
        try {
            for (step in 0 until minOf(MAX_SEQ_LEN, maxSteps)) {
                if (isCancelled()) throw InterruptedException("Отменено")
                val inputs = HashMap<String, OnnxTensor>()
                val toClose = ArrayList<OnnxTensor>()
                fun t(v: LongArray) = longs(v).also { toClose += it }
                inputs["rhythms"] = t(rhythm); inputs["pitchs"] = t(pitch); inputs["lifts"] = t(lift)
                inputs["articulations"] = t(art); inputs["slurs"] = t(slur)
                inputs["context"] = if (step == 0) ctxFull else ctxReduced
                inputs["cache_len"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(step.toLong())), longArrayOf(1)).also { toClose += it }
                for ((k, name) in cacheIn.withIndex()) inputs[name] = if (cache == null) emptyCache[k] else cache!!.get(cacheOut[k]).get() as OnnxTensor
                val result = decoder.run(inputs, outNames.toSet())
                toClose.forEach { it.close() }
                cache?.close()
                cache = result

                fun argmaxLast(idx: Int, row: Int): Int {
                    val o = result.get(outNames[idx]).get() as OnnxTensor
                    val shape = o.info.shape                    // [B, T, V]
                    val vSize = shape[2].toInt(); val tSize = shape[1].toInt()
                    val buf = o.floatBuffer
                    val base = (row * tSize + tSize - 1) * vSize
                    var best = 0; var bv = buf.get(base)
                    for (i in 1 until vSize) { val v = buf.get(base + i); if (v > bv) { bv = v; best = i } }
                    return best
                }
                for (row in 0 until b) {
                    if (done[row]) continue
                    val r = argmaxLast(0, row)
                    if (r == EOS) { done[row] = true; continue }
                    val p = argmaxLast(1, row); val l = argmaxLast(2, row)
                    val pos = argmaxLast(3, row); val a = argmaxLast(4, row); val s = argmaxLast(5, row)
                    steps++
                    val sym = EncodedSymbol(vocab.rhythm[r], vocab.pitch[p], vocab.lift[l], vocab.articulation[a], vocab.slur[s], vocab.position[pos])
                    // центр внимания на холсте строки (x, y) — где декодер «смотрел», когда выдал этот символ
                    (result.get("attention").orElse(null) as? OnnxTensor)?.floatBuffer?.let { fb ->
                        if (fb.remaining() >= 2 * (row + 1)) { sym.x = fb.get(2 * row).toDouble(); sym.y = fb.get(2 * row + 1).toDouble() }
                    }
                    symbols[row] += sym
                    rhythm[row] = r.toLong(); pitch[row] = p.toLong(); lift[row] = l.toLong(); art[row] = a.toLong(); slur[row] = s.toLong()
                }
                if (done.all { it }) break
            }
        } finally {
            cache?.close()
            emptyCache.forEach { it.close() }
        }
        return symbols
    }
}
