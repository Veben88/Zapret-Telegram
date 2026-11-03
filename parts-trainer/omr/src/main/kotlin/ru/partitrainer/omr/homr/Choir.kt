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
    fun tokensFor(voice: ChoirVoice, systems: List<List<StaffRead>>): Pair<List<List<EncodedSymbol>>, Int> {
        val result = ArrayList<List<EncodedSymbol>>()
        var shift = 0
        for (sys in systems) {
            val a = assign(sys)
            val mine = a[voice]
            if (mine != null) {
                result += mine.tokens
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
                    result += filler
                }
            }
        }
        return result to shift
    }
}
