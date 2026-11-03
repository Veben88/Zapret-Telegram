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

    fun split(tokens: List<EncodedSymbol>): Split {
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
