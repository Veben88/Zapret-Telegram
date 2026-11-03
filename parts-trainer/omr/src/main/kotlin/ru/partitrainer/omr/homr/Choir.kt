package ru.partitrainer.omr.homr

enum class ChoirVoice { SOPRANO, ALTO, TENOR, BASS }

/** Прочитанный нотоносец страницы вместе с признаками для определения голоса. */
class StaffRead(
    val staff: DetectedStaff,
    val system: Int,
    val indexInSystem: Int,
    val tokens: List<EncodedSymbol>,
    /** 'G', 'F', 'C' или null. */
    val clef: Char?,
    /** Скрипичный ключ с маленькой «8» (тенор): звучит на октаву ниже написанного. */
    val octave8: Boolean,
    val medianMidi: Int?,
    /** Доля тёмных пикселей в области «8» (диагностика). */
    val octaveScore: Double = 0.0,
    /** Строки текста, распознанные под нотоносцем (пусто, если распознавание текста выключено). */
    val lyricLines: List<String> = emptyList(),
    /** Колонки тактов системы по тактовым чертам этого нотоносца. */
    val columns: SysColumns? = null,
)

/**
 * Определение голоса хоровой партитуры по строкам системы. Число строк в системах бывает разным
 * (опущенные голоса, «solo»), поэтому номер строки не годится — используем ключ, «8» и регистр:
 * бас — басовый ключ; тенор — скрипичный с «8» (иначе — первый из двух басовых);
 * сопрано/альт — скрипичные без «8»: две строки — верхняя S, нижняя A; одна — по высоте.
 */
object ChoirAssigner {
    private const val SOPRANO_MIN_MEDIAN = 66

    fun assign(system: List<StaffRead>): Map<ChoirVoice, StaffRead> {
        val out = HashMap<ChoirVoice, StaffRead>()
        val bass = system.filter { it.clef == 'F' }
        val tenorTreble = system.firstOrNull { it.clef != 'F' && it.octave8 }
        val upper = system.filter { it.clef != 'F' && !it.octave8 }
        if (tenorTreble != null) {
            out[ChoirVoice.TENOR] = tenorTreble
            bass.lastOrNull()?.let { out[ChoirVoice.BASS] = it }
        } else if (bass.size >= 2) {
            out[ChoirVoice.TENOR] = bass.first(); out[ChoirVoice.BASS] = bass.last()
        } else bass.firstOrNull()?.let { out[ChoirVoice.BASS] = it }
        when {
            upper.size >= 2 -> { out[ChoirVoice.SOPRANO] = upper.first(); out[ChoirVoice.ALTO] = upper.last() }
            upper.size == 1 -> {
                val m = upper[0].medianMidi
                if (m == null || m >= SOPRANO_MIN_MEDIAN) out[ChoirVoice.SOPRANO] = upper[0] else out[ChoirVoice.ALTO] = upper[0]
            }
        }
        return out
    }

    /** Сдвиг высоты при чтении голоса: тенор в скрипичном ключе с «8» звучит на октаву ниже. */
    fun transposeFor(voice: ChoirVoice, read: StaffRead): Int = if (voice == ChoirVoice.TENOR && read.octave8 && read.clef != 'F') -12 else 0

    /**
     * Токены выбранного голоса по всем системам страницы. Если в системе голоса нет, вставляются паузы
     * на столько тактов, сколько в соседней строке (чтобы не сдвигать время). Сдвиг [transposeFor] учитывается позже по тенору.
     */
    fun tokensFor(voice: ChoirVoice, systems: List<List<StaffRead>>): Pair<List<List<EncodedSymbol>>, Int> =
        tokensWithLyrics(voice, systems).let { (r, shift) -> r.map { it.tokens } to shift }

    /** Токены нотоносца и строки текста под ним (пусто для вставленных пауз). */
    class StaffText(val tokens: List<EncodedSymbol>, val lyricLines: List<String>, val columns: SysColumns? = null)

    /** То же, что [tokensFor], но вместе со строками текста под нотоносцами голоса. */
    fun tokensWithLyrics(voice: ChoirVoice, systems: List<List<StaffRead>>): Pair<List<StaffText>, Int> {
        val result = ArrayList<StaffText>()
        var shift = 0
        for (sys in systems) {
            val a = assign(sys)
            val mine = a[voice]
            if (mine != null) {
                result += StaffText(mine.tokens, mine.lyricLines, mine.columns)
                if (transposeFor(voice, mine) != 0) shift = transposeFor(voice, mine)
            } else {
                val ref = sys.firstOrNull { it.tokens.isNotEmpty() } ?: continue
                val n = TokenParser.parse(listOf(ref.tokens)).measures.size
                if (n > 0) {
                    // каждая пауза — отдельный такт
                    val filler = ArrayList<EncodedSymbol>()
                    repeat(n) {
                        filler += EncodedSymbol(TokenParser.REST_MEASURE, ".", ".", ".", ".", ".")
                        filler += EncodedSymbol("barline", ".", ".", ".", ".", ".")
                    }
                    result += StaffText(filler, emptyList(), ref.columns)
                }
            }
        }
        return result to shift
    }

    // ---------- Хор на двух строках (SA / TB, дивизи) + фортепиано ----------

    /** Фортепианная строка: много аккордов из 3+ нот или очень низкий басовый ключ. */
    fun isPianoStaff(read: StaffRead): Boolean {
        val groups = Divisi.groups(read.tokens).filter { g -> g.any { it.rhythm.startsWith("note_") } }
        if (groups.isEmpty()) return false
        val big = groups.count { g -> g.count { it.rhythm.startsWith("note_") } >= 3 }
        if (big >= 0.15 * groups.size && big >= 2) return true
        return read.clef == 'F' && (read.medianMidi ?: 99) <= 47
    }

    class TwoStaff(val sa: StaffRead?, val tb: StaffRead?)

    /**
     * Хоровые строки системы. В системе из 4+ строк фортепиано — две нижние (роль определяет позиция: у хора в джазовой
     * гармонии аккорды из 3–4 нот на строку, по содержанию его не отличить от рояля). В системе из 2–3 строк — по
     * содержанию ([isPianoStaff]). Среди хоровых: TB — последняя басовая, SA — скрипичная строка прямо над ней.
     */
    fun assignTwoStaff(system: List<StaffRead>): TwoStaff {
        val choir = if (system.size >= 4) system.dropLast(2) else system.filter { !isPianoStaff(it) }
        val tb = choir.lastOrNull { it.clef == 'F' }
        val above = if (tb != null) choir.filter { it.indexInSystem < tb.indexInSystem && it.clef != 'F' } else choir.filter { it.clef != 'F' }
        val sa = above.lastOrNull()
        return TwoStaff(sa, tb)
    }

    /**
     * Токены голоса S/A/T/B для раскладки «две хоровые строки». Системы без хоровых строк (только фортепиано)
     * заполняются паузами на их длину, чтобы не сдвигать время.
     */
    fun tokensForTwoStaff(voice: ChoirVoice, systems: List<List<StaffRead>>): List<List<EncodedSymbol>> =
        tokensWithLyricsTwoStaff(voice, systems).map { it.tokens }

    /**
     * То же, что [tokensForTwoStaff], вместе с текстом. Строк под нотоносцем бывает две (текст верхнего и нижнего голоса):
     * верхнему голосу — первая, нижнему — вторая; если строка одна — она общая для обоих.
     */
    fun tokensWithLyricsTwoStaff(voice: ChoirVoice, systems: List<List<StaffRead>>): List<StaffText> {
        val result = ArrayList<StaffText>()
        for (sys in systems) {
            val a = assignTwoStaff(sys)
            val staff = when (voice) { ChoirVoice.SOPRANO, ChoirVoice.ALTO -> a.sa; else -> a.tb }
            if (staff != null) {
                val sp = Divisi.split(staff.tokens)
                val upper = voice == ChoirVoice.SOPRANO || voice == ChoirVoice.TENOR
                val lines = staff.lyricLines
                val mine = if (upper) lines.take(1) else (lines.getOrNull(1) ?: lines.getOrNull(0))?.let { listOf(it) }.orEmpty()
                result += StaffText(if (upper) sp.upper else sp.lower, mine, staff.columns)
            } else {
                val ref = sys.firstOrNull { it.tokens.isNotEmpty() } ?: continue
                val n = TokenParser.parse(listOf(ref.tokens)).measures.size
                val filler = ArrayList<EncodedSymbol>()
                repeat(n) {
                    filler += EncodedSymbol(TokenParser.REST_MEASURE, ".", ".", ".", ".", ".")
                    filler += EncodedSymbol("barline", ".", ".", ".", ".", ".")
                }
                if (n > 0) result += StaffText(filler, emptyList(), ref.columns)
            }
        }
        return result
    }
}
