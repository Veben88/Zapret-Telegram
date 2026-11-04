package ru.partitrainer.omr.homr

/**
 * Партия, найденная на странице до полного распознавания, и правило, по которому её строка выбирается в каждой системе.
 *
 * [staffIndex] — номер строки в образцовой системе (сверху, с нуля) из [systemSize] строк; [clef]/[octave8]/[clefRank] —
 * ключ строки и её номер среди строк с таким же ключом (по ним партия находится в системах, где строк меньше, например
 * когда часть голосов опущена); [stemVoice]: 0 — вся строка (при дивизи — первый голос), 1 — верхний голос (штили вверх),
 * 2 — нижний (штили вниз).
 */
data class PartPick(
    val staffIndex: Int, val systemSize: Int, val clef: Char?, val octave8: Boolean, val clefRank: Int, val stemVoice: Int,
    val name: String, val kind: ChoirVoice?, val piano: Boolean = false,
    /** Звучит на октаву ниже написанного (тенор в скрипичном ключе, даже если «8» под ключом не распознана). */
    val lowerOctave: Boolean = false,
    /** Номер среди партий того же голоса в образцовой системе (Альт 1 → 0, Альт 2 → 1). */
    val kindRank: Int = 0,
) {
    /** Строка для передачи между экранами и фоновой задачей. */
    fun encode(): String = listOf(staffIndex, systemSize, clef ?: '-', if (octave8) 1 else 0, clefRank, stemVoice, kind?.name ?: "-", if (piano) 1 else 0,
        if (lowerOctave) 1 else 0, kindRank, name.replace('|', '/')).joinToString("|")

    companion object {
        fun decode(s: String): PartPick? = runCatching {
            val f = s.split('|')
            PartPick(f[0].toInt(), f[1].toInt(), f[2].first().takeIf { it != '-' }, f[3] == "1", f[4].toInt(), f[5].toInt(),
                f.drop(10).joinToString("|"), f[6].takeIf { it != "-" }?.let { ChoirVoice.valueOf(it) }, f[7] == "1", f[8] == "1", f[9].toInt())
        }.getOrNull()
    }
}

object Parts {
    private const val SOPRANO_MIN = 66      // медиана высоты верхних голосов в скрипичном ключе: сопрано ≥ ля♭4/ля4

    private fun medianOf(tokens: List<EncodedSymbol>): Int? =
        tokens.filter { it.rhythm.startsWith("note_") }.mapNotNull { TokenParser.midi(it.pitch, it.lift) }.sorted().let { it.getOrNull(it.size / 2) }

    private fun title(k: ChoirVoice) = when (k) { ChoirVoice.SOPRANO -> "Сопрано"; ChoirVoice.ALTO -> "Альт"; ChoirVoice.TENOR -> "Тенор"; ChoirVoice.BASS -> "Бас" }

    /** Голос строки: вся строка или один из двух голосов дивизи. */
    private class Lane(val staff: Int, val voice: Int, val median: Int?, var kind: ChoirVoice? = null, var lowerOctave: Boolean = false)

    /**
     * Партии образцовой системы. Правила (хоровая партитура идёт сверху вниз S → A → T → B):
     *  — фортепиано — нижняя пара строк, если хотя бы одна из них похожа на рояль (много аккордов из 3+ нот);
     *  — строка с двумя голосами (дивизи, штили вверх/вниз) даёт две партии;
     *  — басовый ключ: дивизи — тенор (верхний) и бас (нижний); две басовые строки — тенор и бас; одна — бас;
     *  — скрипичный с «8» — тенор; если «8» не найдена, но скрипичных строк три и больше, нижняя стоит прямо над басовой
     *    и её ноты на письме выше, чем у строки над ней, — она тоже тенор (на октаву ниже написанного);
     *  — остальные скрипичные: одна — по высоте; две — сопрано и альт; три и больше — по высоте, но верхняя всегда сопрано,
     *    нижняя — альт (так получаются «Сопрано 1, Сопрано 2, Альт» или «Сопрано, Альт 1, Альт 2»).
     * Одинаковые партии нумеруются: «Альт 1», «Альт 2», «Альт 3».
     */
    fun describe(system: List<StaffRead>): List<PartPick> {
        val n = system.size
        val piano = BooleanArray(n)
        if (n >= 3 && (ChoirAssigner.isPianoStaff(system[n - 1]) || ChoirAssigner.isPianoStaff(system[n - 2]))) { piano[n - 1] = true; piano[n - 2] = true }
        else if (n >= 1 && n <= 2 && system.all { ChoirAssigner.isPianoStaff(it) }) system.indices.forEach { piano[it] = true }
        val lanes = ArrayList<Lane>()
        for ((i, r) in system.withIndex()) {
            if (piano[i]) continue
            if (Divisi.hasStemVoices(r.tokens)) {
                val sp = Divisi.split(r.tokens)
                lanes += Lane(i, 1, medianOf(sp.upper)); lanes += Lane(i, 2, medianOf(sp.lower))
            } else lanes += Lane(i, 0, r.medianMidi)
        }
        fun clef(l: Lane) = system[l.staff].clef
        fun oct(l: Lane) = system[l.staff].octave8
        // басовый ключ
        val fStaves = lanes.filter { clef(it) == 'F' }.map { it.staff }.distinct()
        for (l in lanes.filter { clef(it) == 'F' }) {
            l.kind = when {
                l.voice == 1 -> if ((l.median ?: 60) < 50 && (lanes.firstOrNull { it.staff == l.staff && it.voice == 2 }?.median ?: 0) < 50) ChoirVoice.BASS else ChoirVoice.TENOR
                l.voice == 2 -> ChoirVoice.BASS
                fStaves.size >= 2 && l.staff == fStaves.first() -> ChoirVoice.TENOR
                else -> ChoirVoice.BASS
            }
        }
        // скрипичный с «8»
        lanes.filter { clef(it) != 'F' && oct(it) }.forEach { it.kind = ChoirVoice.TENOR }
        // скрипичный без «8» прямо над басовой строкой при трёх и более скрипичных строках — тенор без распознанной «8»
        val gStaves = lanes.filter { clef(it) != 'F' && !oct(it) }.map { it.staff }.distinct()
        // (тенор пишется на октаву выше звучания, поэтому его ноты на письме выше, чем у строки над ним;
        //  у третьего альта — наоборот, так «Сопрано 1, 2, Альт 1, 2, 3, Бас» не превращается в тенор)
        val lastG = gStaves.lastOrNull(); val prevG = gStaves.getOrNull(gStaves.size - 2)
        val writtenHigher = lastG != null && prevG != null && (system[lastG].medianMidi ?: 0) > (system[prevG].medianMidi ?: 999) + 2
        if (lanes.none { it.kind == ChoirVoice.TENOR } && gStaves.size >= 3 && fStaves.isNotEmpty() && gStaves.last() + 1 == fStaves.first() && writtenHigher) {
            lanes.filter { it.staff == gStaves.last() }.forEach { it.kind = ChoirVoice.TENOR; it.lowerOctave = true }
        }
        // остальные скрипичные — сопрано/альт
        val rest = lanes.filter { clef(it) != 'F' && it.kind == null }
        when {
            rest.size == 1 -> rest[0].kind = if ((rest[0].median ?: SOPRANO_MIN) >= SOPRANO_MIN) ChoirVoice.SOPRANO else ChoirVoice.ALTO
            rest.size == 2 -> { rest[0].kind = ChoirVoice.SOPRANO; rest[1].kind = ChoirVoice.ALTO }
            rest.size >= 3 -> {
                rest.forEachIndexed { i, l -> l.kind = when { l.median == null -> if (i < rest.size / 2) ChoirVoice.SOPRANO else ChoirVoice.ALTO; l.median >= SOPRANO_MIN -> ChoirVoice.SOPRANO; else -> ChoirVoice.ALTO } }
                rest.first().kind = ChoirVoice.SOPRANO; rest.last().kind = ChoirVoice.ALTO
                // сверху вниз голоса не «поднимаются»: после первого альта — только альты
                var seenAlto = false
                for (l in rest) { if (l.kind == ChoirVoice.ALTO) seenAlto = true else if (seenAlto) l.kind = ChoirVoice.ALTO }
            }
        }
        fun rank(i: Int): Int = (0 until i).count { system[it].clef == system[i].clef && system[it].octave8 == system[i].octave8 }
        val raw = ArrayList<PartPick>()
        for (i in 0 until n) {
            val r = system[i]
            if (piano[i]) {
                val hand = if (i == n - 1 || (piano[i] && i + 1 < n && !piano[i + 1])) "левая рука" else "правая рука"
                raw += PartPick(i, n, r.clef, r.octave8, rank(i), 0, "Фортепиано, $hand", null, true)
                continue
            }
            for (l in lanes.filter { it.staff == i }) {
                val k = l.kind ?: ChoirVoice.SOPRANO
                raw += PartPick(i, n, r.clef, r.octave8, rank(i), l.voice, title(k), k, false, l.lowerOctave)
            }
        }
        // нумерация одинаковых названий сверху вниз и номер среди партий того же голоса
        val total = raw.groupingBy { it.name }.eachCount()
        val seen = HashMap<String, Int>()
        val rankOf = HashMap<String, Int>()
        return raw.map { p ->
            val group = if (p.piano) "piano" else p.kind?.name ?: "?"
            val kr = rankOf[group] ?: 0; rankOf[group] = kr + 1
            val named = if ((total[p.name] ?: 0) > 1 && !p.piano) { val k = (seen[p.name] ?: 0) + 1; seen[p.name] = k; p.copy(name = "${p.name} $k") } else p
            named.copy(kindRank = kr)
        }
    }

    /**
     * Строка (и голос на ней) партии в системе. Если в системе столько же строк, сколько в образцовой, — по номеру строки.
     * Иначе (часть голосов опущена или, наоборот, расписана подробнее) партии системы определяются заново, и берётся
     * партия того же голоса с тем же номером; если таких меньше — последняя (например, «Сопрано 2» там, где сопрано
     * записаны одной строкой, поёт её).
     */
    fun staffFor(pick: PartPick, system: List<StaffRead>): Pair<StaffRead, Int>? {
        if (system.size == pick.systemSize) return system.getOrNull(pick.staffIndex)?.let { it to pick.stemVoice }
        val here = describe(system)
        val same = here.filter { if (pick.piano) it.piano else !it.piano && it.kind == pick.kind }
        val p = same.getOrNull(pick.kindRank) ?: same.lastOrNull() ?: return null
        return system.getOrNull(p.staffIndex)?.let { it to p.stemVoice }
    }

    /**
     * Токены партии по всем системам страниц (с текстом и колонками тактов). В системе без этой строки — паузы на длину
     * соседней строки, чтобы время не сдвигалось. Второе значение — сдвиг высоты (тенор с «8» — на октаву ниже).
     */
    fun tokensFor(pick: PartPick, systems: List<List<StaffRead>>): Pair<List<ChoirAssigner.StaffText>, Int> {
        val out = ArrayList<ChoirAssigner.StaffText>()
        var shift = 0
        for (sys in systems) {
            val found = staffFor(pick, sys)
            if (found != null) {
                val (st, stemVoice) = found
                val toks = when (stemVoice) {
                    1 -> Divisi.split(st.tokens).upper
                    2 -> Divisi.split(st.tokens).lower
                    else -> Divisi.primary(st.tokens)
                }
                val line = if (stemVoice == 2) 1 else 0
                val lines = st.lyricLines.let { l -> (l.getOrNull(line) ?: l.getOrNull(0))?.let { listOf(it) }.orEmpty() }
                val syls = st.lyricSyls.let { l -> (l.getOrNull(line) ?: l.getOrNull(0))?.let { listOf(it) }.orEmpty() }
                out += ChoirAssigner.StaffText(toks, lines, st.columns, syls)
                if ((st.octave8 || pick.lowerOctave) && st.clef != 'F') shift = -12
            } else {
                val ref = sys.firstOrNull { it.tokens.isNotEmpty() } ?: continue
                val n = TokenParser.parse(listOf(ref.tokens)).measures.size
                if (n == 0) continue
                val filler = ArrayList<EncodedSymbol>()
                repeat(n) {
                    filler += EncodedSymbol(TokenParser.REST_MEASURE, ".", ".", ".", ".", ".")
                    filler += EncodedSymbol("barline", ".", ".", ".", ".", ".")
                }
                out += ChoirAssigner.StaffText(filler, emptyList(), ref.columns)
            }
        }
        return out to shift
    }
}
