package ru.partitrainer.omr.homr

/**
 * Восстановление триолей, напечатанных без цифры «3» (многие издания ставят её только у первых триолей или не ставят
 * вовсе), — по алгоритму homr (`homr/triplet_restoration.py`, AGPL-3.0, авторы проекта homr), перенесено на Kotlin.
 *
 * Модель читает такую триоль как три обычные ноты, и такт перестаёт «сходиться»: голос не продолжается там, где закончилась
 * его предыдущая нота. Для каждого такта, который не сходится, три ноты подряд одной обычной длительности в одном голосе
 * пробуются как триоль; триоль берётся, если мест, где голос «рвётся», становится меньше. Подряд идущие тройки пробуются
 * вместе (до 4). Такт, который и так сходится, не трогается. Триоли данной длительности принимаются, только если на всём
 * отрывке они нашлись хотя бы в трёх местах (одна-две «триоли» — обычно другая ошибка чтения).
 */
object Triplets {
    private const val MAX_THREES_IN_A_ROW = 4
    private const val MIN_TRIPLETS = 3
    private val MAX_MEASURE_LENGTH = Frac(2, 1)          // в целых нотах: длиннее — тактовых черт нет или они не прочитаны
    private val PLAIN = Regex("(note|rest)_(4|8|16|32|64)$")

    /** Точная дробь (длительности в целых нотах). */
    data class Frac(val n: Long, val d: Long) : Comparable<Frac> {
        companion object {
            private fun gcd(a: Long, b: Long): Long = if (b == 0L) Math.abs(a) else gcd(b, a % b)
            fun of(n: Long, d: Long): Frac { val g = gcd(n, d).coerceAtLeast(1); val s = if (d < 0) -1 else 1; return Frac(s * n / g, s * d / g) }
            val ZERO = Frac(0, 1)
        }
        operator fun plus(o: Frac) = of(n * o.d + o.n * d, d * o.d)
        operator fun minus(o: Frac) = of(n * o.d - o.n * d, d * o.d)
        operator fun times(o: Frac) = of(n * o.n, d * o.d)
        override fun compareTo(other: Frac) = (n * other.d).compareTo(other.n * d)
        fun abs() = if (n < 0) Frac(-n, d) else this
    }

    /** Длительность символа в целых нотах (kern: 4 — четверть, 12 — восьмая триоли, точки). Форшлаг и многотактовые паузы — 0. */
    fun duration(rhythm: String): Frac {
        val m = Regex("(note|rest)_(\\d+)(G?)(\\.*)").matchEntire(rhythm) ?: return Frac.ZERO
        if (m.groupValues[3] == "G") return Frac.ZERO
        val k = m.groupValues[2].toLong()
        var d = if (k == 0L) Frac(2, 1) else Frac.of(1, k)
        var add = d * Frac(1, 2)
        repeat(m.groupValues[4].length) { d += add; add = add * Frac(1, 2) }
        return d
    }

    private fun asTriplet(s: EncodedSymbol): EncodedSymbol {
        val m = PLAIN.find(s.rhythm) ?: return s
        return s.copy(rhythm = "${m.groupValues[1]}_${m.groupValues[2].toInt() * 3 / 2}").withPositionOf(s)
    }

    /** Нота такта: голос, длительность, индекс символа. */
    private class N(val voice: String, val dur: Frac, val index: Int)
    private class Three(val indices: Set<Int>, val offBeat: Boolean)

    private fun measures(symbols: List<EncodedSymbol>): List<List<List<N>>> {
        val out = ArrayList<MutableList<List<N>>>(); out.add(ArrayList())
        val chords = ArrayList<List<Int>>()
        var inChord = false
        for ((k, s) in symbols.withIndex()) {
            if (s.rhythm == "chord") { inChord = true; continue }
            if (inChord && chords.isNotEmpty()) { chords[chords.lastIndex] = chords.last() + k; inChord = false } else chords += listOf(k)
        }
        for (c in chords) {
            val r = symbols[c[0]].rhythm
            when {
                r == STAFF_BREAK || r.contains("barline") || r.contains("repeat") -> out.add(ArrayList())
                (r.startsWith("note") || r.startsWith("rest")) && !r.endsWith("m") ->
                    out.last().add(c.filter { symbols[it].rhythm.startsWith("note") || symbols[it].rhythm.startsWith("rest") }
                        .map { N(symbols[it].position, duration(symbols[it].rhythm), it) })
            }
        }
        return out
    }

    /** Как генератор MusicXML homr ведёт время: аккорд начинается, когда кончается самая ранняя из звучащих нот. */
    private fun play(measure: List<List<N>>, triplets: Set<Int>): List<Pair<Frac, List<Pair<String, Frac>>>> {
        var clock = Frac.ZERO
        var sounding = ArrayList<Frac>()
        val res = ArrayList<Pair<Frac, List<Pair<String, Frac>>>>()
        for (chord in measure) {
            val notes = ArrayList<Pair<String, Frac>>()
            for (n in chord) {
                val played = if (n.index in triplets) n.dur * Frac(2, 3) else n.dur
                if (played > Frac.ZERO) notes += n.voice to clock + played
            }
            res += clock to notes
            if (notes.isNotEmpty()) {
                sounding.addAll(notes.map { it.second })
                val c = clock
                clock = sounding.filter { it > c }.minOrNull() ?: clock
                val nc = clock
                sounding = ArrayList(sounding.filter { it > nc })
            }
        }
        return res
    }

    /** Сколько мест, где голос не продолжается ровно там, где кончилась его прошлая нота (и их суммарный размер). */
    private fun breaks(measure: List<List<N>>, triplets: Set<Int> = emptySet()): Pair<Int, Frac> {
        val endOf = HashMap<String, Frac>()
        var count = 0; var size = Frac.ZERO
        for ((start, notes) in play(measure, triplets)) {
            val ends = HashMap<String, Frac>()
            for ((v, e) in notes) ends[v] = maxOf(ends[v] ?: e, e)
            for (v in ends.keys) { val prev = endOf[v]; if (prev != null && prev != start) { count++; size += (start - prev).abs() } }
            endOf.putAll(ends)
        }
        if (endOf.isNotEmpty()) {
            val end = endOf.values.max()
            for (e in endOf.values) if (e != end) { count++; size += end - e }
        }
        return count to size
    }

    private fun length(measure: List<List<N>>): Frac =
        play(measure, emptySet()).flatMap { it.second }.maxOfOrNull { it.second } ?: Frac.ZERO

    private fun singleThrees(measure: List<List<N>>, symbols: List<EncodedSymbol>): Map<Pair<List<Int>, String>, Three> {
        val found = LinkedHashMap<Pair<List<Int>, String>, Pair<MutableSet<Int>, Boolean>>()
        for (voice in measure.flatten().map { it.voice }.toSortedSet()) {
            val chords = measure.mapIndexed { place, c -> place to c.filter { it.voice == voice }.map { it.index } }.filter { it.second.isNotEmpty() }
            val values = chords.map { (_, notes) ->
                val ms = notes.map { PLAIN.find(symbols[it].rhythm) }
                val plain = ms.filterNotNull().map { it.groupValues[2] }.toSet()
                if (ms.all { it != null } && plain.size == 1) plain.first() else null
            }
            var runStart = 0
            for (i in values.indices) {
                val v = values[i]
                if (i > 0 && (v == null || v != values[i - 1])) runStart = i
                if (v == null || i + 3 > values.size || values.subList(i, i + 3) != List(3) { v }) continue
                val inVoice = chords.subList(i, i + 3).flatMap { it.second }
                if (inVoice.none { symbols[it].rhythm.startsWith("note") }) continue
                val key = chords.subList(i, i + 3).map { it.first } to v
                val (set, off) = found[key] ?: (HashSet<Int>() to false)
                set.addAll(inVoice)
                found[key] = set to (off || (i - runStart) % 3 != 0)
            }
        }
        return found.mapValues { Three(it.value.first, it.value.second) }
    }

    private fun threes(measure: List<List<N>>, symbols: List<EncodedSymbol>): List<Three> {
        val single = singleThrees(measure, symbols)
        val all = LinkedHashMap<Set<Int>, Boolean>()
        single.values.forEach { all.putIfAbsent(it.indices, it.offBeat) }
        var inARow = single
        repeat(MAX_THREES_IN_A_ROW - 1) {
            val longer = LinkedHashMap<Pair<List<Int>, String>, Three>()
            for ((key, t) in inARow) {
                val (places, value) = key
                val voices = t.indices.map { symbols[it].position }.toSet()
                val next = (places.last() + 1 until measure.size).firstOrNull { p -> measure[p].any { it.voice in voices } }
                for ((nk, nt) in single) {
                    if (nk.second == value && nk.first.first() == next && t.indices.intersect(nt.indices).isEmpty() &&
                        nt.indices.map { symbols[it].position }.toSet() == voices
                    ) longer[(places + nk.first) to value] = Three(t.indices + nt.indices, t.offBeat)
                }
            }
            inARow = longer
            longer.values.forEach { all.putIfAbsent(it.indices, it.offBeat) }
        }
        return all.entries.sortedBy { e -> e.key.sorted().joinToString(",") { "%08d".format(it) } }.map { Three(it.key, it.value) }
    }

    private fun choose(measure: List<List<N>>, symbols: List<EncodedSymbol>): List<Set<Int>> {
        var br = breaks(measure)
        if (br.first == 0 || length(measure) > MAX_MEASURE_LENGTH) return emptyList()
        val ts = threes(measure, symbols)
        val chosen = ArrayList<Set<Int>>()
        var trip: Set<Int> = emptySet()
        while (true) {
            val trials = ts.filter { it.indices.intersect(trip).isEmpty() }.map { Triple(breaks(measure, trip + it.indices), it.offBeat, it.indices) }
            if (trials.isEmpty()) break
            val best = trials.minWithOrNull(
                compareBy<Triple<Pair<Int, Frac>, Boolean, Set<Int>>> { it.first.first }.thenBy { it.first.second }
                    .thenBy { it.second }.thenBy { it.third.sorted().joinToString(",") { i -> "%08d".format(i) } },
            )!!
            if (best.first.first >= br.first) break
            chosen += best.third
            trip = trip + best.third; br = best.first
        }
        return chosen
    }

    private const val STAFF_BREAK = "__staff_break__"

    /**
     * Возвращает нотоносцы одного голоса с восстановленными триолями. Граница нотоносца считается границей такта.
     */
    fun restore(staffs: List<List<EncodedSymbol>>): List<List<EncodedSymbol>> {
        if (staffs.all { it.isEmpty() }) return staffs
        val flat = ArrayList<EncodedSymbol>()
        val owner = ArrayList<Int>()
        for ((si, st) in staffs.withIndex()) {
            if (si > 0) { flat += EncodedSymbol(STAFF_BREAK, ".", ".", ".", ".", "."); owner += -1 }
            for (s in st) { flat += s; owner += si }
        }
        val chosen = measures(flat).map { choose(it, flat) }
        fun value(ix: Set<Int>) = flat[ix.min()].rhythm.substringAfter('_')
        val shown = chosen.flatten().groupingBy { value(it) }.eachCount()
        val trip = chosen.flatten().filter { (shown[value(it)] ?: 0) >= MIN_TRIPLETS }.flatten().toSet()
        if (trip.isEmpty()) return staffs
        val out = staffs.map { ArrayList<EncodedSymbol>() }
        for ((i, s) in flat.withIndex()) { val o = owner[i]; if (o >= 0) out[o] += if (i in trip) asTriplet(s) else s }
        return out
    }
}
