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

    /** Доля событий строки, которые аккорды из 2+ нот. */
    private fun chordShare(r: StaffRead): Double {
        val g = Divisi.groups(r.tokens).filter { gr -> gr.any { it.rhythm.startsWith("note_") } }
        return if (g.isEmpty()) 0.0 else g.count { gr -> gr.count { it.rhythm.startsWith("note_") } >= 2 }.toDouble() / g.size
    }

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
     *  — скрипичный с «8» — тенор (но «8» выше строки без «8» считается ошибкой: теноры ниже сопрано и альтов); если «8» не найдена, но скрипичных строк три и больше, нижняя стоит прямо над басовой
     *    и её ноты на письме выше, чем у строки над ней, — она тоже тенор (на октаву ниже написанного);
     *  — остальные скрипичные: одна — по высоте; две — сопрано и альт; три и больше — по высоте, но верхняя всегда сопрано,
     *    нижняя — альт (так получаются «Сопрано 1, Сопрано 2, Альт» или «Сопрано, Альт 1, Альт 2»).
     * Одинаковые партии нумеруются: «Альт 1», «Альт 2», «Альт 3».
     */
    fun describe(system: List<StaffRead>): List<PartPick> {
        val n = system.size
        // фортепиано — только сплошной блок строк внизу системы, похожих на рояль; пара «скрипичный над басовым» внизу
        // достраивается, если нижняя басовая — рояль, а скрипичная над ней тоже аккордовая
        val pianoLike = BooleanArray(n) { ChoirAssigner.isPianoStaff(system[it]) }
        val piano = BooleanArray(n)
        var k = n - 1
        while (k >= 0 && pianoLike[k]) { piano[k] = true; k-- }
        if (n >= 3 && piano[n - 1] && !piano[n - 2] && system[n - 1].clef == 'F' && system[n - 2].clef != 'F' && chordShare(system[n - 2]) >= 0.3) piano[n - 2] = true
        if (piano.all { it } && n > 2) piano.fill(false)                // вся система «рояль» — значит, ошиблись: это хор
        val lanes = ArrayList<Lane>()
        for ((i, r) in system.withIndex()) {
            if (piano[i]) continue
            if (Divisi.isTwoVoiceStaff(r.tokens)) {
                val sp = Divisi.split(r.tokens)
                lanes += Lane(i, 1, medianOf(sp.upper)); lanes += Lane(i, 2, medianOf(sp.lower))
            } else lanes += Lane(i, 0, r.medianMidi)
        }
        fun clef(l: Lane) = system[l.staff].clef
        // «8» под ключом выше строки без «8» — ошибка распознавания: в хоре теноры ниже сопрано и альтов
        val lastPlainG = lanes.filter { system[it.staff].clef != 'F' && !system[it.staff].octave8 }.maxOfOrNull { it.staff } ?: -1
        fun oct(l: Lane) = system[l.staff].octave8 && l.staff > lastPlainG
        // скрипичный с «8»
        lanes.filter { clef(it) != 'F' && oct(it) }.forEach { it.kind = ChoirVoice.TENOR }
        // Старинная музыка в современных изданиях: «соль с 8» бывает у альта, тенора и баса сразу (Superius, Altus,
        // Tenor, Bassus), басового ключа нет. Тогда голоса — по порядку сверху вниз; все строки с «8» звучат октавой ниже.
        val octLanes = lanes.filter { clef(it) != 'F' && oct(it) }
        if (lanes.none { clef(it) == 'F' } && octLanes.size >= 2 && octLanes.none { it.voice != 0 }) {
            val order = listOf(ChoirVoice.SOPRANO, ChoirVoice.ALTO, ChoirVoice.TENOR, ChoirVoice.BASS)
            when {
                lanes.size == 4 -> lanes.forEachIndexed { i, l -> l.kind = order[i] }
                octLanes.size == 3 -> octLanes.forEachIndexed { i, l -> l.kind = order[i + 1] }
            }
            octLanes.forEach { it.lowerOctave = true }
        }
        // скрипичный без «8» прямо над басовой строкой при трёх и более скрипичных строках — тенор без распознанной «8»
        // (тенор пишется на октаву выше звучания, поэтому его ноты на письме выше, чем у строки над ним;
        //  у третьего альта — наоборот, так «Сопрано 1, 2, Альт 1, 2, 3, Бас» не превращается в тенор)
        val fStaves = lanes.filter { clef(it) == 'F' }.map { it.staff }.distinct()
        val gStaves = lanes.filter { clef(it) != 'F' && !oct(it) }.map { it.staff }.distinct()
        val lastG = gStaves.lastOrNull(); val prevG = gStaves.getOrNull(gStaves.size - 2)
        val writtenHigher = lastG != null && prevG != null && (system[lastG].medianMidi ?: 0) > (system[prevG].medianMidi ?: 999) + 2
        if (lanes.none { it.kind == ChoirVoice.TENOR } && gStaves.size >= 3 && fStaves.isNotEmpty() && gStaves.last() + 1 == fStaves.first() && writtenHigher) {
            lanes.filter { it.staff == gStaves.last() }.forEach { it.kind = ChoirVoice.TENOR; it.lowerOctave = true }
        }
        val hasTenor = lanes.any { it.kind == ChoirVoice.TENOR }
        // басовый ключ: если теноры уже есть (скрипичный с «8») — все басовые строки басы; иначе дивизи — тенор/бас,
        // две басовые строки — тенор и бас, одна — бас
        for (l in lanes.filter { clef(it) == 'F' }) {
            l.kind = when {
                hasTenor -> ChoirVoice.BASS
                l.voice == 1 -> if ((l.median ?: 60) < 50 && (lanes.firstOrNull { it.staff == l.staff && it.voice == 2 }?.median ?: 0) < 50) ChoirVoice.BASS else ChoirVoice.TENOR
                l.voice == 2 -> ChoirVoice.BASS
                fStaves.size >= 2 && l.staff == fStaves.first() -> ChoirVoice.TENOR
                else -> ChoirVoice.BASS
            }
        }
        // остальные скрипичные — сопрано/альт: делим ряд сверху вниз на «сопрано» и «альты». По умолчанию пополам
        // (S1 S2 A1 A2, S1 S2 S3 A1 A2 A3), сдвигаем границу, если высоты явно говорят иначе.
        val rest = lanes.filter { clef(it) != 'F' && it.kind == null }
        when {
            rest.size == 1 -> rest[0].kind = if ((rest[0].median ?: SOPRANO_MIN) >= SOPRANO_MIN) ChoirVoice.SOPRANO else ChoirVoice.ALTO
            rest.size >= 2 -> {
                var bestK = 1; var bestCost = Double.MAX_VALUE
                for (kk in 1 until rest.size) {
                    var cost = Math.abs(kk - rest.size / 2.0) * 3.0
                    rest.forEachIndexed { i, l ->
                        val m = l.median ?: return@forEachIndexed
                        cost += if (i < kk) maxOf(0, SOPRANO_MIN - 1 - m) else maxOf(0, m - SOPRANO_MIN - 1)
                    }
                    if (cost < bestCost) { bestCost = cost; bestK = kk }
                }
                rest.forEachIndexed { i, l -> l.kind = if (i < bestK) ChoirVoice.SOPRANO else ChoirVoice.ALTO }
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
     * Пользователь сменил голос партии: новое название и октава (тенор в скрипичном ключе звучит на октаву ниже);
     * одинаковые названия заново нумеруются.
     */
    fun withKind(parts: List<PartPick>, index: Int, kind: ChoirVoice?): List<PartPick> {
        val changed = parts.mapIndexed { i, p ->
            if (i != index) p
            else if (kind == null) p.copy(kind = null, piano = true, name = "Фортепиано", lowerOctave = false)
            else p.copy(kind = kind, piano = false, name = title(kind), lowerOctave = p.clef != 'F' && (kind == ChoirVoice.TENOR || p.octave8 && p.lowerOctave))
        }
        // снять старые номера и пронумеровать заново (фортепиано не нумеруем)
        val base = changed.map { if (it.piano) it else it.copy(name = it.name.replace(Regex(" \\d+$"), "")) }
        val total = base.filter { !it.piano }.groupingBy { it.name }.eachCount()
        val seen = HashMap<String, Int>()
        return base.map { p -> if (!p.piano && (total[p.name] ?: 0) > 1) { val k = (seen[p.name] ?: 0) + 1; seen[p.name] = k; p.copy(name = "${p.name} $k") } else p }
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
                if (st.clef != 'F' && (pick.lowerOctave || pick.kind == ChoirVoice.TENOR && st.octave8)) shift = -12
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
