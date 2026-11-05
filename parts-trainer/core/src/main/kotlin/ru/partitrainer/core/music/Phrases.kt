package ru.partitrainer.core.music

import ru.partitrainer.core.model.Note

/** Фраза — полуинтервал индексов нот [from, to). */
data class Phrase(val from: Int, val to: Int, val startBeat: Double, val endBeat: Double)

object PhraseSplitter {
    /**
     * Делит ноты на фразы:
     * 1) по паузам длиннее одной доли;
     * 2) по заданным разрывам [lineBreaks] (индексы нот — начала строк текста);
     * 3) если [measuresPerPhrase] > 0 — фраза не длиннее этого числа тактов (считая от её начала), даже если пауз и строк текста нет;
     *    0 — «авто»: только по паузам и строкам, а если их во всей партии нет — каждые 2 такта.
     */
    fun split(
        notes: List<Note>,
        beatsPerMeasure: Double,
        measuresPerPhrase: Int = 2,
        lineBreaks: Set<Int> = emptySet(),
    ): List<Phrase> {
        if (notes.isEmpty()) return emptyList()
        val cap = measuresPerPhrase.coerceAtLeast(0)
        val limit = (if (cap > 0) cap else 2) * beatsPerMeasure
        val result = ArrayList<Phrase>()
        var from = 0
        fun close(toExclusive: Int) {
            if (toExclusive > from) {
                result += Phrase(from, toExclusive, notes[from].startBeat, notes[toExclusive - 1].endBeat)
                from = toExclusive
            }
        }
        val hasNatural = lineBreaks.isNotEmpty() ||
            (1 until notes.size).any { notes[it].startBeat - notes[it - 1].endBeat > 1.0 + 1e-9 }
        fun measureOf(beat: Double) = Math.floor(beat / beatsPerMeasure + 1e-9).toInt()
        fun cell(beat: Double) = Math.floor(beat / limit + 1e-9).toInt()
        for (i in 1 until notes.size) {
            val gap = notes[i].startBeat - notes[i - 1].endBeat
            val byRest = gap > 1.0 + 1e-9
            val byLine = i in lineBreaks
            val byCap = cap > 0 && measureOf(notes[i].startBeat) - measureOf(notes[from].startBeat) >= cap
            val byGrid = cap == 0 && !hasNatural && cell(notes[i].startBeat) > cell(notes[from].startBeat)
            if (byRest || byLine || byCap || byGrid) close(i)
        }
        close(notes.size)
        return result
    }
}
