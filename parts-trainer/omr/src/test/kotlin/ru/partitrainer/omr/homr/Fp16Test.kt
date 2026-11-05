package ru.partitrainer.omr.homr

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class Fp16Test {
    @Test fun halfConversionMatchesNumpy() {
        val cases = mapOf(1.0f to 0x3c00, -2.0f to 0xc000, 65504f to 0x7bff, 1e-8f to 0x0, 6e-8f to 0x1, 0.1f to 0x2e66,
            3.14159f to 0x4248, 1e5f to 0x7c00, -0.0f to 0x8000, 0.000123f to 0x808)
        for ((f, h) in cases) assertEquals("для $f", h, Fp16Converter.toHalf(f).toInt() and 0xFFFF)
    }

    /** HOMR_FP16=путь к float-энкодеру: преобразование и сверка выхода с float (на x86 fp16 считается медленно — тест долгий). */
    @Test fun encoderFp16CloseToFloat() {
        val path = System.getenv("HOMR_FP16")?.takeIf { it.isNotBlank() }
        assumeTrue(path != null)
        val t0 = System.currentTimeMillis()
        val fp16 = Fp16Converter.convert(File(path!!).readBytes())
        val out = File(System.getProperty("java.io.tmpdir"), "enc_fp16_kt.onnx").also { it.writeBytes(fp16) }
        println("FP16 convert ${System.currentTimeMillis() - t0} ms, ${File(path).length()} → ${out.length()} bytes")
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val canvas = GrayImage(1280, 256).also { java.util.Arrays.fill(it.data, 0xFF.toByte()) }
        for (i in 0..4) for (x in 40..1240) canvas[x, 100 + i * 12] = 0
        for (k in 0 until 30) for (y in 90..160) for (x in 60 + k * 38..64 + k * 38) canvas[x, y] = 30
        val int8 = System.getenv("HOMR_INT8_ENC") ?: path
        val r = EncoderBench.compare(env, listOf(EncoderVariant("int8", int8, {}), EncoderVariant("float", path, {}), EncoderVariant("fp16", out.path, {})), EncoderBench.sampleCanvas(), runs = 1)
        r.forEach { println("FP16 ${it.name}: ${it.ms} ms, cos=${"%.5f".format(it.similarity)}, ok=${it.ok}") }
        assertTrue(r[2].similarity > 0.98)
    }
}
