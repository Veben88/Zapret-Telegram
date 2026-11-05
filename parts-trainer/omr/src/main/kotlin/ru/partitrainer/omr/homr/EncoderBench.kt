package ru.partitrainer.omr.homr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Вариант запуска энкодера: файл модели и настройка сессии (ускоритель, потоки). */
class EncoderVariant(val name: String, val modelPath: String, val configure: (OrtSession.SessionOptions) -> Unit)

/** Результат замера: время одного прогона, сходство выхода с эталоном (косинус), годен ли вариант. */
class EncoderBenchResult(val name: String, val ms: Long, val similarity: Double, val ok: Boolean, val error: String? = null)

/**
 * Подбор самого быстрого способа запускать энкодер на этом устройстве. Первый вариант — эталон: его выход сравнивается
 * с остальными; вариант, который ошибается (например, неточный драйвер ускорителя), отбрасывается.
 */
object EncoderBench {
    const val MIN_SIMILARITY = 0.985

    fun compare(env: OrtEnvironment, variants: List<EncoderVariant>, canvas: GrayImage, runs: Int = 2, isCancelled: () -> Boolean = { false }): List<EncoderBenchResult> {
        val x = FloatArray(Staff2Score.HEIGHT * Staff2Score.WIDTH) { (((canvas.data[it].toInt() and 0xFF) / 255.0 - Staff2Score.MEAN) / Staff2Score.STD).toFloat() }
        var reference: FloatArray? = null
        val out = ArrayList<EncoderBenchResult>()
        for (v in variants) {
            if (isCancelled()) throw InterruptedException("Отменено")
            try {
                OrtSession.SessionOptions().use { opts ->
                    v.configure(opts)
                    env.createSession(v.modelPath, opts).use { s ->
                        fun run(): FloatArray = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 1, Staff2Score.HEIGHT.toLong(), Staff2Score.WIDTH.toLong())).use { t ->
                            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                                val o = r[0] as OnnxTensor
                                FloatArray(o.floatBuffer.remaining()).also { a -> o.floatBuffer.duplicate().get(a) }
                            }
                        }
                        var y = run()                                   // прогрев
                        var best = Long.MAX_VALUE
                        repeat(runs) { val t0 = System.nanoTime(); y = run(); best = minOf(best, (System.nanoTime() - t0) / 1_000_000) }
                        val ref = reference ?: y.also { reference = it }
                        val sim = cosine(ref, y)
                        out += EncoderBenchResult(v.name, best, sim, sim >= MIN_SIMILARITY && y.all { it.isFinite() })
                    }
                }
            } catch (e: InterruptedException) { throw e } catch (e: Throwable) {
                out += EncoderBenchResult(v.name, Long.MAX_VALUE, 0.0, false, e.message ?: e.javaClass.simpleName)
            }
        }
        return out
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa == 0.0 || bb == 0.0) 0.0 else ab / Math.sqrt(aa * bb)
    }

    /** Тестовая строка для замеров: пять линеек и «ноты» — содержимое на скорость энкодера не влияет. */
    fun sampleCanvas(): GrayImage {
        val c = GrayImage(Staff2Score.WIDTH, Staff2Score.HEIGHT).also { java.util.Arrays.fill(it.data, 0xFF.toByte()) }
        for (i in 0..4) for (x in 40..1240) c[x, 100 + i * 12] = 0
        for (k in 0 until 28) for (y in 88 + (k % 7) * 4..96 + (k % 7) * 4) for (x in 70 + k * 40..80 + k * 40) c[x, y] = 20
        return c
    }
}

/**
 * Способы запустить энкодер. Модель: int8 (встроена в APK), float (оригинал homr, докачивается), fp16 (делается из float
 * на устройстве). Ускоритель: обычные ядра процессора, XNNPACK (оптимизированные ядра ARM/x86) или NNAPI (нейропроцессор,
 * DSP или видеочип через драйвер производителя).
 */
enum class EncoderBackend(val title: String, val model: Model, private val accel: Accel) {
    INT8_CPU("int8 · процессор", Model.INT8, Accel.CPU),
    INT8_XNNPACK("int8 · XNNPACK", Model.INT8, Accel.XNNPACK),
    FLOAT_CPU("float · процессор", Model.FLOAT, Accel.CPU),
    FLOAT_XNNPACK("float · XNNPACK", Model.FLOAT, Accel.XNNPACK),
    FP16_CPU("fp16 · процессор", Model.FP16, Accel.CPU),
    FP16_XNNPACK("fp16 · XNNPACK", Model.FP16, Accel.XNNPACK),
    FLOAT_NNAPI("float · NNAPI", Model.FLOAT, Accel.NNAPI),
    FLOAT_NNAPI_FP16("float→fp16 · NNAPI", Model.FLOAT, Accel.NNAPI_FP16);

    enum class Model { INT8, FLOAT, FP16 }
    private enum class Accel { CPU, XNNPACK, NNAPI, NNAPI_FP16 }

    val needsNnapi get() = accel == Accel.NNAPI || accel == Accel.NNAPI_FP16

    /** Настраивает сессию. [threads] — потоков на один прогон энкодера. */
    fun configure(opts: OrtSession.SessionOptions, threads: Int) {
        when (accel) {
            Accel.CPU -> {}
            Accel.XNNPACK -> {
                opts.addConfigEntry("session.intra_op.allow_spinning", "0")
                opts.addXnnpack(mapOf("intra_op_num_threads" to threads.coerceAtLeast(1).toString()))
            }
            Accel.NNAPI -> opts.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
            Accel.NNAPI_FP16 -> opts.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED, ai.onnxruntime.providers.NNAPIFlags.USE_FP16))
        }
    }

    companion object {
        fun parse(s: String?): EncoderBackend? = entries.firstOrNull { it.name == s }
    }
}
