package ru.partitrainer.core

import org.junit.Assert.*
import org.junit.Test
import ru.partitrainer.core.audio.*
import ru.partitrainer.core.model.Note
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PitchTest {
    private val sr = 44100
    private fun sine(f: Double, n: Int = 4096, amp: Double = 0.5, harmonics: Int = 1): FloatArray =
        FloatArray(n) { i ->
            var v = 0.0
            for (h in 1..harmonics) v += sin(2 * PI * f * h * i / sr) / h
            (v * amp / (if (harmonics > 1) 1.5 else 1.0)).toFloat()
        }

    @Test fun detectsSines() {
        val d = YinPitchDetector(sr)
        for (f in listOf(82.41, 110.0, 196.0, 261.63, 440.0, 659.25, 880.0)) {
            val r = d.detect(sine(f))
            assertNotNull("f=$f", r)
            val cents = 1200 * Math.log(r!!.frequency / f) / Math.log(2.0)
            assertTrue("f=$f got ${r.frequency} (${cents}c)", abs(cents) < 5)
        }
    }

    @Test fun detectsVoiceLikeHarmonics() {
        val d = YinPitchDetector(sr)
        for (f in listOf(130.81, 220.0, 349.23)) {
            val r = d.detect(sine(f, harmonics = 5))!!
            assertEquals(f, r.frequency, f * 0.005)
        }
    }

    @Test fun smallWindow2048() {
        val d = YinPitchDetector(sr)
        val r = d.detect(sine(440.0, 2048))!!
        assertEquals(440.0, r.frequency, 2.0)
    }

    @Test fun silenceAndNoise() {
        val d = YinPitchDetector(sr)
        assertNull(d.detect(FloatArray(4096)))
        assertNull(d.detect(sine(440.0, amp = 0.001)))
        val rnd = java.util.Random(1)
        val noise = FloatArray(4096) { (rnd.nextGaussian() * 0.2).toFloat() }
        assertNull(d.detect(noise))
    }

    @Test fun outOfRangeIgnored() {
        val d = YinPitchDetector(sr)
        assertNull(d.detect(sine(40.0, 8192)))
    }

    @Test fun centsAndOctave() {
        assertEquals(0.0, PitchMath.centsBetween(440.0, 69), 1e-9)
        assertEquals(100.0, PitchMath.centsBetween(466.1638, 69), 0.01)
        // На октаву выше без учёта октавы — 0 центов; с учётом — 1200
        assertEquals(0.0, PitchMath.deviationCents(880.0, 69, false), 1e-6)
        assertEquals(1200.0, PitchMath.deviationCents(880.0, 69, true), 1e-6)
        assertEquals(-30.0, PitchMath.deviationCents(PitchMath.midiToFreq(68.7 + 12 - 12), 69, false), 0.01)
        // На октаву ниже и 20 центов высоко
        assertEquals(20.0, PitchMath.deviationCents(PitchMath.midiToFreq(57.2), 69, false), 0.01)
    }

    @Test fun verdicts() {
        assertEquals(Verdict.IN_TUNE, Judge.verdict(39.0))
        assertEquals(Verdict.IN_TUNE, Judge.verdict(-40.0))
        assertEquals(Verdict.HIGH, Judge.verdict(41.0))
        assertEquals(Verdict.LOW, Judge.verdict(-80.0))
        assertEquals(Verdict.IN_TUNE, Judge.verdict(60.0, 70.0))
    }

    @Test fun smootherRemovesOutlier() {
        val s = PitchSmoother(5)
        var out: Double? = null
        for (f in listOf(440.0, 441.0, 880.0, 440.0, 439.0)) out = s.push(f)
        assertEquals(440.0, out!!, 1.0)
    }
}

class SingEvaluatorTest {
    private val notes = listOf(Note(69, 0.0, 1.0, 1), Note(71, 1.0, 1.0, 1), Note(72, 2.0, 1.0, 1), Note(74, 3.0, 1.0, 1))

    private fun samples(vararg f: Double?): List<PitchSample> =
        f.flatMapIndexed { beat, fr -> (0 until 10).map { PitchSample(beat + it / 10.0, fr) } }

    @Test fun outcomes() {
        val a = PitchMath.midiToFreq(69.0)
        val s = samples(a, PitchMath.midiToFreq(71.8), PitchMath.midiToFreq(71.2), null)
        val r = SingEvaluator.evaluate(notes, s)
        assertEquals(listOf(NoteOutcome.HIT, NoteOutcome.HIGH, NoteOutcome.LOW, NoteOutcome.MISSED), r.results.map { it.outcome })
        assertEquals(25, r.percent)
    }

    @Test fun octaveShiftAllowedByDefault() {
        val s = samples(PitchMath.midiToFreq(57.0), PitchMath.midiToFreq(83.0), PitchMath.midiToFreq(72.0), PitchMath.midiToFreq(62.0))
        assertEquals(100, SingEvaluator.evaluate(notes, s).percent)
        assertEquals(25, SingEvaluator.evaluate(notes, s, matchOctave = true).percent)
    }

    @Test fun edgesIgnored() {
        // Начало ноты (первые 20%) «мимо», основная часть чисто — всё равно «попал»
        val a = PitchMath.midiToFreq(69.0)
        val s = (0 until 10).map { PitchSample(it / 10.0, if (it < 2) PitchMath.midiToFreq(65.0) else a) }
        assertEquals(NoteOutcome.HIT, SingEvaluator.evaluate(listOf(notes[0]), s).results[0].outcome)
    }
}
