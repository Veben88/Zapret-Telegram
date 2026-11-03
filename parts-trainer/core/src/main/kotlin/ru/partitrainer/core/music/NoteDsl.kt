package ru.partitrainer.core.music

import ru.partitrainer.core.model.Note

/**
 * Формат «Имя+октава:доли», например `B4:1 A4:.5 r:.5 F#4:3`. `r` — пауза.
 * Пробелы разделяют события. Номер такта вычисляется от [beatsPerMeasure].
 */
object NoteDsl {
    data class Event(val midi: Int?, val beats: Double)

    fun parseEvents(text: String): List<Event> =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.map { token ->
            val i = token.lastIndexOf(':')
            require(i > 0) { "Ожидалось Имя:доли, получено «$token»" }
            val name = token.substring(0, i)
            val beats = token.substring(i + 1).toDoubleOrNull()
                ?: throw IllegalArgumentException("Неверная длительность в «$token»")
            require(beats > 0) { "Длительность должна быть > 0: «$token»" }
            if (name == "r") Event(null, beats)
            else Event(NoteNames.parse(name) ?: throw IllegalArgumentException("Неверная нота «$name»"), beats)
        }

    /** Строит ноты подряд, начиная с [startBeat]; возвращает ноты и конец (в долях). */
    fun build(events: List<Event>, startBeat: Double, beatsPerMeasure: Double): Pair<List<Note>, Double> {
        var t = startBeat
        val out = ArrayList<Note>()
        for (e in events) {
            if (e.midi != null) {
                out += Note(e.midi, t, e.beats, measureOf(t, beatsPerMeasure))
            }
            t += e.beats
        }
        return out to t
    }

    fun measureOf(beat: Double, beatsPerMeasure: Double): Int =
        (Math.floor(beat / beatsPerMeasure + 1e-9)).toInt() + 1
}
