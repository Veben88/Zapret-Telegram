package ru.partitrainer.core.music

enum class Notation { SOLFEGE, LATIN }

object NoteNames {
    private val latinSharp = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val solfegeSharp = arrayOf("до", "до♯", "ре", "ре♯", "ми", "фа", "фа♯", "соль", "соль♯", "ля", "ля♯", "си")
    private val letterPc = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
    private val solfegePc = mapOf("до" to 0, "ре" to 2, "ми" to 4, "фа" to 5, "соль" to 7, "ля" to 9, "си" to 11)

    /** Октава в научной нотации: C4 = 60. */
    fun octave(midi: Int): Int = Math.floorDiv(midi, 12) - 1

    fun pitchClass(midi: Int): Int = Math.floorMod(midi, 12)

    fun isBlack(midi: Int): Boolean = pitchClass(midi) in intArrayOf(1, 3, 6, 8, 10)

    /** Название без октавы. */
    fun name(midi: Int, notation: Notation): String {
        val pc = pitchClass(midi)
        return if (notation == Notation.SOLFEGE) solfegeSharp[pc] else latinSharp[pc]
    }

    fun nameWithOctave(midi: Int, notation: Notation): String = name(midi, notation) + octave(midi)

    /**
     * Разбор «F#4», «Bb4», «до#4», «Соль-бемоль 3». Возвращает null, если не удалось.
     * Регистр не важен; «♯»/«♭» допускаются.
     */
    fun parse(text: String): Int? {
        val s = text.trim().replace('♯', '#').replace('♭', 'b').lowercase()
        if (s.isEmpty()) return null
        var pc: Int? = null
        var rest = ""
        for ((k, v) in solfegePc.entries.sortedByDescending { it.key.length }) {
            if (s.startsWith(k)) { pc = v; rest = s.substring(k.length); break }
        }
        if (pc == null) {
            val v = letterPc[s[0].uppercaseChar()] ?: return null
            pc = v; rest = s.substring(1)
        }
        var alter = 0
        while (rest.isNotEmpty()) {
            when {
                rest[0] == '#' -> { alter++; rest = rest.substring(1) }
                rest[0] == 'b' && (rest.length == 1 || rest[1] == 'b' || rest[1].isDigit() || rest[1] == '-' || rest[1] == '#') -> { alter--; rest = rest.substring(1) }
                else -> break
            }
        }
        rest = rest.trim()
        val oct = if (rest.isEmpty()) 4 else rest.toIntOrNull() ?: return null
        val midi = (oct + 1) * 12 + pc!! + alter
        return if (midi in 0..127) midi else null
    }
}
