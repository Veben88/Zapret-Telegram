package ru.partitrainer.core.music

import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Voice
import ru.partitrainer.core.model.VoiceKind

/** Ориентировочный диапазон голоса (включительно). */
data class MidiRange(val low: Int, val high: Int) {
    operator fun contains(midi: Int) = midi in low..high
    fun shifted(semitones: Int) = MidiRange(low + semitones, high + semitones)
}

object Ranges {
    fun of(kind: VoiceKind): MidiRange? = when (kind) {
        VoiceKind.SOPRANO -> MidiRange(60, 81) // C4–A5
        VoiceKind.ALTO -> MidiRange(55, 74)    // G3–D5
        VoiceKind.TENOR -> MidiRange(48, 69)   // C3–A4
        VoiceKind.BASS -> MidiRange(40, 62)    // E2–D4
        else -> null
    }

    /** Фактический диапазон нот голоса. */
    fun actual(notes: List<Note>): MidiRange? =
        if (notes.isEmpty()) null else MidiRange(notes.minOf { it.midi }, notes.maxOf { it.midi })

    fun outOfRange(note: Note, kind: VoiceKind): Boolean = of(kind)?.let { note.midi !in it } ?: false
}

object Transpose {
    const val MIN = -12
    const val MAX = 12

    fun notes(notes: List<Note>, semitones: Int): List<Note> {
        val s = semitones.coerceIn(MIN, MAX)
        return notes.map { it.copy(midi = (it.midi + s).coerceIn(0, 127)) }
    }

    fun voice(voice: Voice, semitones: Int): Voice = voice.copy(notes = notes(voice.notes, semitones))
}
