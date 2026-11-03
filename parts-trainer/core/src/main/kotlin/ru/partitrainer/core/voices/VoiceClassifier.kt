package ru.partitrainer.core.voices

import ru.partitrainer.core.io.RawNote
import ru.partitrainer.core.io.RawPart
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Voice
import ru.partitrainer.core.model.VoiceKind

/**
 * Определение голосов при импорте:
 * 1) по названию партии/инструмента (сопрано, soprano, S, vocals…),
 * 2) по `<voice>`/`<staff>`,
 * 3) аккорды в одном нотоносце делятся по высоте,
 * 4) если тип определить не удалось — сортировка по средней высоте и имена «Голос N».
 */
object VoiceClassifier {

    fun kindFromName(raw: String): VoiceKind? {
        val s = raw.lowercase().trim().trim('.', ':')
        if (s.isEmpty()) return null
        val compact = s.replace(Regex("[\\s._-]+"), "")
        // Одиночные буквы (с возможным номером): S, A, T, B, S1, T2, SI…
        Regex("^([satb])(i{1,3}|\\d)?$").matchEntire(compact)?.let {
            return when (it.groupValues[1]) { "s" -> VoiceKind.SOPRANO; "a" -> VoiceKind.ALTO; "t" -> VoiceKind.TENOR; else -> VoiceKind.BASS }
        }
        return when {
            "сопран" in s || "soprano" in s || "treble" in s || "descant" in s -> VoiceKind.SOPRANO
            "альт" in s || "contralto" in s || Regex("\\balto\\b|\\balt\\b").containsMatchIn(s) -> VoiceKind.ALTO
            "тенор" in s || "tenor" in s -> VoiceKind.TENOR
            "бас" in s || "bass" in s || "баритон" in s || "baritone" in s -> VoiceKind.BASS
            "vocal" in s || "voice" in s || "voc" == compact || "голос" in s || "вокал" in s ||
                "melody" in s || "мелод" in s || "solo" in s || "соло" in s || "lead" in s || "vox" in s -> VoiceKind.MELODY
            else -> null
        }
    }

    private val order = listOf(VoiceKind.SOPRANO, VoiceKind.ALTO, VoiceKind.TENOR, VoiceKind.BASS)

    fun label(kind: VoiceKind): String = when (kind) {
        VoiceKind.SOPRANO -> "Сопрано"; VoiceKind.ALTO -> "Альт"; VoiceKind.TENOR -> "Тенор"
        VoiceKind.BASS -> "Бас"; VoiceKind.MELODY -> "Мелодия"; VoiceKind.OTHER -> "Другое"
    }

    private class Cand(val partName: String, val notes: List<Note>, var kind: VoiceKind?, var label: String?, val partIndex: Int)

    fun classify(parts: List<RawPart>): List<Voice> {
        val cands = ArrayList<Cand>()
        parts.forEachIndexed { pi, part ->
            val named = kindFromName(part.name) ?: kindFromName(part.instrument)
            val display = part.name.ifBlank { part.instrument }
            // Группы по (staff, voice).
            val groups = part.notes.groupBy { it.staff to it.voice }
                .toSortedMap(compareBy({ it.first }, { it.second }))
                .values.map { g -> g.map { it.note }.sortedBy { it.startBeat } }
                .filter { it.isNotEmpty() }
            val lines: List<List<Note>> = if (groups.size > 1) groups else groups.firstOrNull()?.let { splitChords(it) } ?: emptyList()
            if (lines.isEmpty()) return@forEachIndexed

            // Составные названия «Soprano/Alto», «S/A».
            val tokens = display.split('/', '&', ',').map { it.trim() }.filter { it.isNotEmpty() }
            val tokenKinds = tokens.map { kindFromName(it) }
            if (tokens.size > 1 && tokens.size == lines.size && tokenKinds.all { it != null }) {
                val byPitch = lines.sortedByDescending { avg(it) }
                val kindsSorted = tokenKinds.map { it!! }.sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
                byPitch.forEachIndexed { i, ln -> cands += Cand(display, ln, kindsSorted[i], label(kindsSorted[i]), pi) }
                return@forEachIndexed
            }
            if (lines.size == 1) {
                cands += Cand(display, lines[0], named, if (named != null && display.isNotBlank()) display else display.ifBlank { null }, pi)
            } else {
                // Несколько линий в одной партии: сверху вниз.
                val sorted = lines.sortedByDescending { avg(it) }
                sorted.forEachIndexed { i, ln ->
                    val k = named
                    cands += Cand(display, ln, k, if (k != null) "${display.ifBlank { label(k) }} ${i + 1}" else null, pi)
                }
            }
        }

        // Распределение имён «Голос N» для неопределённых.
        val unnamed = cands.filter { it.kind == null }
        val sortedUnnamed = unnamed.sortedByDescending { avg(it.notes) }
        val result = ArrayList<Voice>()
        var idx = 0
        for (c in cands) {
            idx++
            val (kind, label) = if (c.kind != null) {
                c.kind!! to (c.label ?: label(c.kind!!))
            } else if (c.partName.isNotBlank() && unnamed.size == 1) {
                VoiceKind.OTHER to c.partName
            } else {
                VoiceKind.OTHER to "Голос ${sortedUnnamed.indexOf(c) + 1}"
            }
            result += Voice("v$idx", label, kind, c.notes)
        }
        return result
    }

    private fun avg(n: List<Note>) = if (n.isEmpty()) 0.0 else n.sumOf { it.midi }.toDouble() / n.size

    /**
     * Делит одноголосый список с аккордами на линии: верхняя нота аккорда — линия 0, и т. д.
     * Если аккордов нет — возвращает одну линию.
     */
    fun splitChords(notes: List<Note>): List<List<Note>> {
        val buckets = notes.groupBy { Math.round(it.startBeat * 1000) }
        val k = buckets.values.maxOfOrNull { it.size } ?: 1
        if (k <= 1) return listOf(notes)
        val lines = List(k) { ArrayList<Note>() }
        for (key in buckets.keys.sorted()) {
            val chord = buckets.getValue(key).sortedByDescending { it.midi }
            chord.forEachIndexed { i, n -> lines[i] += n }
        }
        return lines.filter { it.isNotEmpty() }
    }

    /** Ручное назначение типа. */
    fun withKind(voice: Voice, kind: VoiceKind): Voice =
        voice.copy(kind = kind, label = if (voice.kind == VoiceKind.OTHER || voice.label.startsWith("Голос")) label(kind) else voice.label)

}
