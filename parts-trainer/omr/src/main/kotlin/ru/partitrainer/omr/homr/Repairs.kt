package ru.partitrainer.omr.homr

/**
 * Исправления после чтения строки, проверяемые по длине такта.
 *
 * Лишняя точка: модель иногда ставит точку у ноты, которой её нет. Такой такт длиннее остальных ровно на величину
 * точки (половину основной длительности). Если в такте ровно одна точечная нота (или аккорд), снятие точки у которой
 * выравнивает такт, — точка снимается. Если кандидатов несколько или ни одного — такт не трогаем.
 * Сверено с эталонным MusicXML (печатная партитура «Отче наш»).
 */
object Repairs {
    private val DOTTED = Regex("(note|rest)_(\\d+)(G?)(\\.+)$")

    private fun groups(tokens: List<EncodedSymbol>): List<List<Int>> {
        val out = ArrayList<MutableList<Int>>()
        var inChord = false
        for ((i, s) in tokens.withIndex()) {
            if (s.rhythm == "chord") { inChord = true; continue }
            if (inChord && out.isNotEmpty()) { out.last() += i; inChord = false } else out += mutableListOf(i)
        }
        return out
    }

    private fun advance(tokens: List<EncodedSymbol>, g: List<Int>, undot: Boolean = false): Triplets.Frac? =
        g.filter { tokens[it].rhythm.startsWith("note_") || tokens[it].rhythm.startsWith("rest_") }
            .map { Triplets.duration(if (undot) tokens[it].rhythm.trimEnd('.') else tokens[it].rhythm) }
            .filter { it > Triplets.Frac.ZERO }.minOrNull()

    fun fixDots(tokens: List<EncodedSymbol>): List<EncodedSymbol> {
        val gs = groups(tokens)
        // такты: группы между тактовыми чертами
        val measures = ArrayList<MutableList<List<Int>>>().apply { add(ArrayList()) }
        for (g in gs) {
            val r = tokens[g[0]].rhythm
            if (r.contains("barline") || r.startsWith("repeat")) measures.add(ArrayList()) else measures.last() += g
        }
        val totals = measures.map { m -> m.mapNotNull { advance(tokens, it) }.fold(Triplets.Frac.ZERO) { a, b -> a + b } }
        // обычная длина такта — самая частая среди тактов строки (кроме первого и последнего: затакт, неполный конец)
        val inner = totals.drop(1).dropLast(1).filter { it > Triplets.Frac.ZERO }
        val usual = inner.groupingBy { it }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key ?: return tokens
        val out = tokens.toMutableList()
        var changed = false
        for ((mi, m) in measures.withIndex()) {
            if (mi == 0 || mi == measures.lastIndex) continue
            val over = totals[mi] - usual
            if (over <= Triplets.Frac.ZERO) continue
            val fixes = m.filter { g ->
                val dotted = g.any { DOTTED.matches(tokens[it].rhythm) }
                val a = advance(tokens, g) ?: return@filter false
                val b = advance(tokens, g, undot = true) ?: return@filter false
                dotted && a - b == over
            }
            if (fixes.size != 1) continue
            for (i in fixes[0]) if (DOTTED.matches(out[i].rhythm)) { out[i] = out[i].copy(rhythm = out[i].rhythm.trimEnd('.')).withPositionOf(out[i]); changed = true }
        }
        return if (changed) out else tokens
    }
}
