package ru.partitrainer.omr.homr

/**
 * Разделение двух голосов, записанных на одной строке (дивизи: S/A или T/B).
 *
 * Модель пишет такие места аккордами, у которых у каждой ноты своя длительность (`n4:C5 chord n1:F4` — верхний голос
 * четвертью, нижний целой). Время идёт по самой короткой ноте аккорда (как в homr); одиночная нота принадлежит тому
 * голосу, который в этот момент свободен, а если свободны оба — звучит в обоих (унисон). На тактовой черте голоса
 * синхронизируются, чтобы ошибка не копилась.
 */
object Divisi {
    class Split(val upper: List<EncodedSymbol>, val lower: List<EncodedSymbol>)

    private const val EPS = 1e-6

    fun groups(symbols: List<EncodedSymbol>): List<List<EncodedSymbol>> {
        val out = ArrayList<MutableList<EncodedSymbol>>()
        var inChord = false
        for (s in symbols) {
            if (s.rhythm == "chord") inChord = true
            else if (inChord && out.isNotEmpty()) { out.last() += s; inChord = false }
            else out += mutableListOf(s)
        }
        return out
    }

    /** Голос ноты по направлению штиля (поле position модели homr): 1 — штиль вверх (upper/lower), 2 — вниз (upper2/lower2). */
    private fun voiceOf(s: EncodedSymbol): Int = if (s.position.endsWith("2")) 2 else 1

    /** Есть ли на строке второй голос, размеченный моделью (ноты со штилем вниз). */
    fun hasStemVoices(tokens: List<EncodedSymbol>) = tokens.any { (it.rhythm.startsWith("note_") || it.rhythm.startsWith("rest_")) && it.position.endsWith("2") }

    /**
     * Строка действительно записана двумя голосами (дивизи), а не изредка встречается нота со штилем вниз:
     * второй голос — не меньше четверти нот и не меньше 4 нот.
     */
    fun isTwoVoiceStaff(tokens: List<EncodedSymbol>): Boolean {
        val notes = tokens.filter { it.rhythm.startsWith("note_") }
        val second = notes.count { it.position.endsWith("2") }
        return second >= 4 && second >= 0.25 * notes.size
    }

    /**
     * Первый (верхний) голос строки. Если модель разметила голоса штилями — по ним, иначе строка как есть.
     * Нужен для партии, у которой на своей строке изредка встречается второй голос (дивизи внутри партии).
     */
    fun primary(tokens: List<EncodedSymbol>): List<EncodedSymbol> = if (hasStemVoices(tokens)) split(tokens).upper else tokens

    /**
     * Разделение двух голосов строки. Если модель разметила голоса направлением штилей (homr ≥ #156: `upper` — штиль
     * вверх, `upper2` — вниз), голос определяется по нему — так пишут хор на двух строках (S/A, T/B). Иначе — по высоте
     * внутри аккорда. Время в обоих случаях идёт по самой короткой ноте аккорда; нота, которой нет у второго голоса,
     * достаётся и ему, если он в этот момент свободен (унисон, общий штиль).
     */
    fun split(tokens: List<EncodedSymbol>): Split {
        if (hasStemVoices(tokens)) return splitByStems(tokens)
        return splitByPitch(tokens)
    }

    private fun splitByStems(tokens: List<EncodedSymbol>): Split {
        val up = ArrayList<EncodedSymbol>(); val lo = ArrayList<EncodedSymbol>()
        var t = 0.0; var endU = 0.0; var endL = 0.0
        for (g in groups(tokens)) {
            val first = g[0].rhythm
            val isEvent = first.startsWith("note_") || first.startsWith("rest_")
            if (!isEvent) {
                if (first.startsWith("dynamic")) continue
                up += g[0]; lo += g[0]
                if (first.contains("barline") || first.startsWith("repeat")) { endU = minOf(endU, t); endL = minOf(endL, t) }
                continue
            }
            val items = g.filter { (it.rhythm.startsWith("note_") || it.rhythm.startsWith("rest_")) && TokenParser.beats(it.rhythm) != null }
            if (items.isEmpty()) continue
            fun best(v: Int): EncodedSymbol? {
                val mine = items.filter { voiceOf(it) == v }
                val notes = mine.filter { it.rhythm.startsWith("note_") }
                    .mapNotNull { n -> TokenParser.midi(n.pitch, n.lift)?.let { it to n } }
                // в своём голосе аккорд — верхняя нота у первого голоса, нижняя у второго
                return (if (v == 1) notes.maxByOrNull { it.first } else notes.minByOrNull { it.first })?.second ?: mine.firstOrNull()
            }
            val u = best(1); val l = best(2)
            val upperFree = endU <= t + EPS; val lowerFree = endL <= t + EPS
            if (u != null) { up += u; endU = t + TokenParser.beats(u.rhythm)!! }
            if (l != null) { lo += l; endL = t + TokenParser.beats(l.rhythm)!! }
            if (u == null && l != null && upperFree) { up += l; endU = endL }      // общая нота — у обоих голосов
            if (l == null && u != null && lowerFree) { lo += u; endL = endU }
            t += items.mapNotNull { TokenParser.beats(it.rhythm) }.min()
        }
        return Split(up, lo)
    }

    internal fun splitByPitch(tokens: List<EncodedSymbol>): Split {
        val up = ArrayList<EncodedSymbol>(); val lo = ArrayList<EncodedSymbol>()
        var t = 0.0; var endU = 0.0; var endL = 0.0
        for (g in groups(tokens)) {
            val first = g[0].rhythm
            val isEvent = first.startsWith("note_") || first.startsWith("rest_")
            if (!isEvent) {
                if (first.startsWith("dynamic")) continue
                up += g[0]; lo += g[0]
                if (first.contains("barline") || first.startsWith("repeat")) { endU = minOf(endU, t); endL = minOf(endL, t) }
                continue
            }
            // ноты по убыванию высоты, затем паузы
            val notes = g.filter { it.rhythm.startsWith("note_") }
                .mapNotNull { n -> TokenParser.midi(n.pitch, n.lift)?.let { it to n } }.sortedByDescending { it.first }.map { it.second }
            val rests = g.filter { it.rhythm.startsWith("rest_") }
            val items = notes + rests
            val durs = items.map { TokenParser.beats(it.rhythm) }
            if (items.isEmpty() || durs.any { it == null }) continue // форшлаги и нераспознанное пропускаем
            val d = durs.map { it!! }
            if (items.size >= 2) {
                up += items.first(); lo += items.last()
                endU = t + d.first(); endL = t + d.last()
                t += d.min()
            } else {
                val item = items[0]; val dur = d[0]
                val upperFree = endU <= t + EPS; val lowerFree = endL <= t + EPS
                when {
                    upperFree && lowerFree -> { up += item; lo += item; endU = t + dur; endL = t + dur }
                    lowerFree -> { lo += item; endL = t + dur }
                    else -> { up += item; endU = t + dur }
                }
                t += dur
            }
        }
        return Split(up, lo)
    }
}
