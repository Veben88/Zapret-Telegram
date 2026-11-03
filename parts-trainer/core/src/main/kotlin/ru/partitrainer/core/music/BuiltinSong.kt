package ru.partitrainer.core.music

import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.model.Voice
import ru.partitrainer.core.model.VoiceKind

/**
 * Встроенный пример «Верхом на звезде» (вокальная партия, соль мажор, 4/4, ~100).
 * Данные введены вручную и могут содержать неточности.
 */
object BuiltinSong {
    const val TITLE = "Верхом на звезде"
    const val BPM = 100

    val verse1 = "B4:1 A4:.5 C5:1 B4:1.5 G4:2"
    val verse2 = "B4:.5 A4:.5 A4:.5 C5:1 B4:2 F#4:3"
    val verse3 = "B4:.5 B4:1 A4:.5 B4:1 A4:.5 C5:.5 B4:.5 C5:2 r:.5 D4:.5 D5:5"
    val verse6 = verse3.replace("D5:5", "D5:15")

    val chorus1 = "B4:.5 B4:.5 B4:.5 B4:.5 G4:1.5 E4:1"
    val chorus2 = "B4:.5 B4:.5 B4:.5 B4:.5 A4:1.5 C5:1 C5:.5 B4:.5 G4:2"
    val chorus3 = "$chorus2 r:.5 E4:.5 E4:2 D4:1"
    val chorus6 = "$chorus2 E4:1"

    /** Финал: строка 2 припева, где последние четыре ноты заменены на новые. */
    val finale = chorus2.substringBeforeLast(" C5:1 C5:.5 B4:.5 G4:2") + " G4:5 A4:3 A4:1 Bb4:6"

    /** Строки текста (каждая — отдельная фраза). */
    val lines = listOf(
        verse1, verse2, verse3, verse1, "B4:.5 B4:.5 B4:.5 A4:.5 C5:.5 B4:2 F#4:3", verse6,
        chorus1, chorus2, chorus3, chorus1, chorus2, chorus6,
        finale,
    )

    class Built(val score: Score, val lineStarts: Set<Int>)

    fun build(): Built {
        val bpm = 4.0
        var t = 0.0
        val notes = ArrayList<Note>()
        val starts = HashSet<Int>()
        for (line in lines) {
            starts += notes.size
            val (n, end) = NoteDsl.build(NoteDsl.parseEvents(line), t, bpm)
            notes += n
            t = end
        }
        val score = Score(
            title = TITLE, keyFifths = 1, timeNum = 4, timeDen = 4, tempo = BPM,
            voices = listOf(Voice("sample", "Вокал", VoiceKind.MELODY, notes)),
        )
        return Built(score, starts - 0)
    }
}
