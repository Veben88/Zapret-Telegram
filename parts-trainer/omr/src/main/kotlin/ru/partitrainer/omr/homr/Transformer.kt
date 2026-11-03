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

    private fun longTensor(v: Long) = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(v)), longArrayOf(1, 1))

    /** [canvas] — изображение строки 256×1280 (белый фон). */
    fun predict(canvas: GrayImage, isCancelled: () -> Boolean = { false }): List<EncodedSymbol> {
        require(canvas.width == WIDTH && canvas.height == HEIGHT)
        val x = FloatArray(HEIGHT * WIDTH) { (((canvas.data[it].toInt() and 0xFF) / 255.0 - MEAN) / STD).toFloat() }
        val ctxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 1, HEIGHT.toLong(), WIDTH.toLong()))
        val te = System.nanoTime()
        val ctxResult = ctxTensor.use { encoder.run(mapOf(encoder.inputNames.first() to it)) }
        encoderMs += (System.nanoTime() - te) / 1_000_000
        ctxResult.use { encRes ->
            val ctx = encRes[0] as OnnxTensor
            val ctxShape = ctx.info.shape                      // [1, N, 512]
            val full = FloatArray(ctx.floatBuffer.remaining()).also { ctx.floatBuffer.duplicate().get(it) }
            val dim = ctxShape[2].toInt()
            val reduced = full.copyOfRange(0, dim)            // context[:, :1]
            val fullTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(full), ctxShape)
            val redTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(reduced), longArrayOf(1, 1, dim.toLong()))
            val td = System.nanoTime()
            try {
                return decode(fullTensor, redTensor, isCancelled)
            } finally {
                decoderMs += (System.nanoTime() - td) / 1_000_000
                fullTensor.close(); redTensor.close()
            }
        }
    }

    private fun decode(ctxFull: OnnxTensor, ctxReduced: OnnxTensor, isCancelled: () -> Boolean): List<EncodedSymbol> {
        val symbols = ArrayList<EncodedSymbol>()
        var rhythm = 1L; var pitch = 0L; var lift = 0L; var art = 0L; var slur = 0L
        var cache: ai.onnxruntime.OrtSession.Result? = null
        var emptyCache: List<OnnxTensor> = (0 until DEPTH * 4).map {
            OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, HEADS.toLong(), 0, HEAD_DIM.toLong()))
        }
        try {
            for (step in 0 until MAX_SEQ_LEN) {
                if (isCancelled()) throw InterruptedException("Отменено")
                val inputs = HashMap<String, OnnxTensor>()
                val toClose = ArrayList<OnnxTensor>()
                fun t(v: Long) = longTensor(v).also { toClose += it }
                inputs["rhythms"] = t(rhythm); inputs["pitchs"] = t(pitch); inputs["lifts"] = t(lift)
                inputs["articulations"] = t(art); inputs["slurs"] = t(slur)
                inputs["context"] = if (step == 0) ctxFull else ctxReduced
                inputs["cache_len"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(step.toLong())), longArrayOf(1)).also { toClose += it }
                for ((k, name) in cacheIn.withIndex()) inputs[name] = if (cache == null) emptyCache[k] else cache!!.get(cacheOut[k]).get() as OnnxTensor
                val result = decoder.run(inputs, outNames.toSet())
                toClose.forEach { it.close() }
                cache?.close()
                cache = result

                fun argmaxLast(idx: Int): Int {
                    val o = result.get(outNames[idx]).get() as OnnxTensor
                    val shape = o.info.shape                    // [1, T, V]
                    val vSize = shape[2].toInt(); val tSize = shape[1].toInt()
                    val buf = o.floatBuffer
                    val base = (tSize - 1) * vSize
                    var best = 0; var bv = buf.get(base)
                    for (i in 1 until vSize) { val v = buf.get(base + i); if (v > bv) { bv = v; best = i } }
                    return best
                }
                val r = argmaxLast(0); val p = argmaxLast(1); val l = argmaxLast(2)
                val pos = argmaxLast(3); val a = argmaxLast(4); val s = argmaxLast(5)
                if (r == EOS) break
                steps++
                symbols += EncodedSymbol(vocab.rhythm[r], vocab.pitch[p], vocab.lift[l], vocab.articulation[a], vocab.slur[s], vocab.position[pos])
                rhythm = r.toLong(); pitch = p.toLong(); lift = l.toLong(); art = a.toLong(); slur = s.toLong()
            }
        } finally {
            cache?.close()
            emptyCache.forEach { it.close() }
        }
        return symbols
    }
}
