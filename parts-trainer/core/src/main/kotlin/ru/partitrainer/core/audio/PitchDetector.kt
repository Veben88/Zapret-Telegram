package ru.partitrainer.core.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Результат определения основного тона. */
data class PitchResult(val frequency: Double, val probability: Double, val rms: Double)

/**
 * Собственная реализация алгоритма YIN (de Cheveigné & Kawahara, 2002):
 * разностная функция, нормировка кумулятивным средним, абсолютный порог,
 * параболическая интерполяция. Диапазон 70–1000 Гц, отсечка по громкости.
 */
class YinPitchDetector(
    private val sampleRate: Int,
    private val minFreq: Double = 70.0,
    private val maxFreq: Double = 1000.0,
    private val threshold: Double = 0.15,
    private val minRms: Double = 0.01,
) {
    private var diff = FloatArray(0)

    /** @return null, если звук тише порога или тон не найден. */
    fun detect(buffer: FloatArray, length: Int = buffer.size): PitchResult? {
        val maxTau = (sampleRate / minFreq).toInt()
        val minTau = (sampleRate / maxFreq).toInt().coerceAtLeast(2)
        val window = length / 2
        if (window <= maxTau + 1) return null

        var sum = 0.0
        for (i in 0 until length) sum += buffer[i].toDouble() * buffer[i]
        val rms = sqrt(sum / length)
        if (rms < minRms) return null

        if (diff.size < maxTau + 2) diff = FloatArray(maxTau + 2)
        val d = diff
        // 1) разностная функция
        for (tau in 1..maxTau + 1) {
            var s = 0.0
            for (i in 0 until window) {
                val x = buffer[i] - buffer[i + tau]
                s += x * x
            }
            d[tau] = s.toFloat()
        }
        // 2) кумулятивная нормированная
        d[0] = 1f
        var running = 0.0
        for (tau in 1..maxTau + 1) {
            running += d[tau]
            d[tau] = if (running == 0.0) 1f else (d[tau] * tau / running).toFloat()
        }
        // 3) абсолютный порог: первый минимум ниже порога
        var tauEst = -1
        var tau = minTau
        while (tau <= maxTau) {
            if (d[tau] < threshold) {
                while (tau + 1 <= maxTau && d[tau + 1] < d[tau]) tau++
                tauEst = tau
                break
            }
            tau++
        }
        if (tauEst == -1) {
            // запасной вариант: глобальный минимум, если он достаточно мал
            var best = minTau
            for (t in minTau..maxTau) if (d[t] < d[best]) best = t
            if (d[best] > 0.3f) return null
            tauEst = best
        }
        // 4) параболическая интерполяция
        val x0 = if (tauEst > 1) tauEst - 1 else tauEst
        val x2 = if (tauEst + 1 <= maxTau + 1) tauEst + 1 else tauEst
        val better: Double = if (x0 == tauEst) {
            if (d[tauEst] <= d[x2]) tauEst.toDouble() else x2.toDouble()
        } else if (x2 == tauEst) {
            if (d[tauEst] <= d[x0]) tauEst.toDouble() else x0.toDouble()
        } else {
            val s0 = d[x0].toDouble(); val s1 = d[tauEst].toDouble(); val s2 = d[x2].toDouble()
            val den = 2 * (2 * s1 - s2 - s0)
            if (den == 0.0) tauEst.toDouble() else tauEst + (s2 - s0) / den
        }
        val f = sampleRate / better
        if (f < minFreq || f > maxFreq) return null
        return PitchResult(f, (1.0 - d[tauEst]).coerceIn(0.0, 1.0), rms)
    }
}

object PitchMath {
    fun freqToMidiFloat(f: Double): Double = 69.0 + 12.0 * ln(f / 440.0) / ln(2.0)
    fun midiToFreq(m: Double): Double = 440.0 * 2.0.pow((m - 69.0) / 12.0)
    fun centsBetween(f: Double, targetMidi: Int): Double = (freqToMidiFloat(f) - targetMidi) * 100.0

    /**
     * Отклонение в центах от целевой ноты. Если [matchOctave] == false, октава не учитывается:
     * отклонение приводится к диапазону (−600, 600].
     */
    fun deviationCents(f: Double, targetMidi: Int, matchOctave: Boolean): Double {
        val c = centsBetween(f, targetMidi)
        if (matchOctave) return c
        var r = c % 1200.0
        if (r > 600.0) r -= 1200.0
        if (r <= -600.0) r += 1200.0
        return r
    }
}

enum class Verdict { IN_TUNE, HIGH, LOW }

object Judge {
    fun verdict(cents: Double, toleranceCents: Double = 40.0): Verdict = when {
        abs(cents) <= toleranceCents -> Verdict.IN_TUNE
        cents > 0 -> Verdict.HIGH
        else -> Verdict.LOW
    }
}

/** Медианный фильтр по последним N оценкам тона — гасит выбросы (скачки на октаву). */
class PitchSmoother(private val size: Int = 5) {
    private val window = ArrayDeque<Double>()
    fun push(freq: Double?): Double? {
        if (freq == null) { if (window.isNotEmpty()) window.removeFirst(); return window.sorted().getOrNull(window.size / 2) }
        window.addLast(freq); if (window.size > size) window.removeFirst()
        return window.sorted()[window.size / 2]
    }
    fun reset() = window.clear()
}
