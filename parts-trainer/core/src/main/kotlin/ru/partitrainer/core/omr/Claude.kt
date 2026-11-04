package ru.partitrainer.core.omr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Score
import ru.partitrainer.core.model.Voice
import ru.partitrainer.core.model.VoiceKind
import ru.partitrainer.core.music.NoteNames
import ru.partitrainer.core.structure.Jump
import ru.partitrainer.core.structure.MeasureMark

/** Один такт в ответе модели (после разбора). */
data class OmrEvent(
    val midi: Int?, val beats: Double, val tie: Boolean, val syllable: String?,
    /** Где нота на странице (пиксели выровненной страницы) и номер строки во входных данных разбора; NaN/−1 — неизвестно. */
    val x: Double = Double.NaN, val y: Double = Double.NaN, val staff: Int = -1,
)

data class OmrMeasure(
    val n: Int,
    val chord: String?,
    val pickup: Boolean,
    val events: List<OmrEvent>,
    val repeatStart: Boolean,
    val repeatEnd: Boolean,
    val ending: Int?,
    val marks: List<String>,
    val uncertain: Boolean = false,
    val uncertainReason: String? = null,
    /** Собственный размер такта (если в пьесе меняется метр); null — размер всей пьесы. */
    val timeNum: Int? = null,
    val timeDen: Int? = null,
) {
    val total: Double get() = events.sumOf { it.beats }
}

data class OmrParsed(
    val title: String?, val keyFifths: Int, val timeNum: Int, val timeDen: Int, val tempo: Int?,
    val measures: List<OmrMeasure>,
    /** Где такты находятся на страницах исходника (только движок «На устройстве»). */
    val layout: SourceLayout? = null,
)

class OmrFormatException(message: String) : Exception(message)

object ClaudeOmr {
    const val MODEL = "claude-sonnet-5-5"
    const val ANTHROPIC_VERSION = "2023-06-01"

    val SYSTEM_PROMPT = """
Ты — система оптического распознавания нот (OMR). Тебе дают изображение нотной страницы или системы.
Распознай ТОЛЬКО запрошенную строку (по умолчанию — верхняя вокальная строка с текстом) и верни СТРОГО один JSON-объект, без пояснений и без markdown.
Схема:
{"title":"","keyFifths":0,"timeSig":[4,4],"tempo":null,
 "measures":[{"n":1,"chord":"","pickup":false,
   "events":[{"p":"B4","d":1},{"p":"r","d":0.5},{"p":"A4","d":0.5,"tie":true,"syl":""}],
   "repeat":{"start":false,"end":false,"ending":null},"marks":[]}],
 "uncertain":[{"measure":3,"reason":""}]}
Правила: p — научная высота ("C4"=до первой октавы, "F#4", "Bb4") или "r" для паузы; d — длительность в долях (четвертная = 1, пунктирная учитывается: 1.5);
tie=true, если нота залигована со следующей; syl — слог (необязательно); marks — подписи "segno","coda","to coda","fine","D.S.","D.C.","D.S. al Coda","D.C. al Fine" и т. п.;
ending — номер вольты (1, 2) или null. Сумма d в каждом такте должна равняться длине такта (кроме затакта и последнего такта). Если не уверен — добавь такт в "uncertain". Только JSON.
""".trim()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Достаёт JSON из ответа модели (допускает ```json ... ``` и лишний текст вокруг). */
    fun extractJson(text: String): String {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) throw OmrFormatException("В ответе нет JSON")
        return text.substring(a, b + 1)
    }

    fun parse(text: String): OmrParsed {
        val root = try { json.parseToJsonElement(extractJson(text)) } catch (e: Exception) { throw OmrFormatException("Некорректный JSON: ${e.message}") }
        if (root !is JsonObject) throw OmrFormatException("Ожидался объект JSON")
        val ts = (root["timeSig"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        val timeNum = ts?.getOrNull(0)?.takeIf { it > 0 } ?: 4
        val timeDen = ts?.getOrNull(1)?.takeIf { it > 0 } ?: 4
        val uncertainMap = HashMap<Int, String>()
        (root["uncertain"] as? JsonArray)?.forEach { u ->
            val o = u as? JsonObject ?: return@forEach
            val m = (o["measure"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            uncertainMap[m] = (o["reason"] as? JsonPrimitive)?.contentOrNull ?: ""
        }
        val measuresJson = root["measures"] as? JsonArray ?: throw OmrFormatException("Нет поля measures")
        val measures = measuresJson.mapIndexed { i, el ->
            val o = el as? JsonObject ?: throw OmrFormatException("Такт ${i + 1}: ожидался объект")
            val n = (o["n"] as? JsonPrimitive)?.intOrNull ?: (i + 1)
            val events = (o["events"] as? JsonArray ?: JsonArray(emptyList())).map { e ->
                val eo = e as? JsonObject ?: throw OmrFormatException("Такт $n: событие не объект")
                val p = (eo["p"] as? JsonPrimitive)?.contentOrNull ?: throw OmrFormatException("Такт $n: нет p")
                val d = (eo["d"] as? JsonPrimitive)?.doubleOrNull ?: throw OmrFormatException("Такт $n: нет d")
                if (d <= 0) throw OmrFormatException("Такт $n: d ≤ 0")
                val midi = if (p.trim().lowercase() == "r") null else NoteNames.parse(p) ?: throw OmrFormatException("Такт $n: неверная нота «$p»")
                OmrEvent(midi, d, (eo["tie"] as? JsonPrimitive)?.booleanOrNull ?: false, (eo["syl"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null })
            }
            val rep = o["repeat"] as? JsonObject
            OmrMeasure(
                n = n,
                chord = (o["chord"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null },
                pickup = (o["pickup"] as? JsonPrimitive)?.booleanOrNull ?: false,
                events = events,
                repeatStart = (rep?.get("start") as? JsonPrimitive)?.booleanOrNull ?: false,
                repeatEnd = (rep?.get("end") as? JsonPrimitive)?.booleanOrNull ?: false,
                ending = (rep?.get("ending") as? JsonPrimitive)?.intOrNull,
                marks = (o["marks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                uncertain = n in uncertainMap,
                uncertainReason = uncertainMap[n],
            )
        }
        if (measures.isEmpty()) throw OmrFormatException("В ответе нет тактов")
        return OmrParsed(
            title = (root["title"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null },
            keyFifths = (root["keyFifths"] as? JsonPrimitive)?.intOrNull ?: 0,
            timeNum = timeNum, timeDen = timeDen,
            tempo = (root["tempo"] as? JsonPrimitive)?.intOrNull,
            measures = measures,
        )
    }

    /** Номера (индексы в списке) тактов, у которых сумма длительностей не сходится. Затакт и последний такт не проверяются. */
    fun nominalLength(p: OmrParsed, m: OmrMeasure): Double =
        if (m.timeNum != null && m.timeDen != null) m.timeNum * 4.0 / m.timeDen else p.timeNum * 4.0 / p.timeDen

    fun badMeasures(p: OmrParsed): List<Int> {
        return p.measures.indices.filter { i ->
            val m = p.measures[i]
            !m.pickup && i != p.measures.lastIndex &&
                Math.abs(m.total - nominalLength(p, m)) > 0.02
        }
    }

    fun withUncertain(p: OmrParsed, indices: Collection<Int>, reason: String = "Сумма длительностей не равна длине такта"): OmrParsed =
        p.copy(measures = p.measures.mapIndexed { i, m -> if (i in indices) m.copy(uncertain = true, uncertainReason = m.uncertainReason ?: reason) else m })

    /** Подменяет такты с индексами из [fixed] (индекс → новый такт). */
    fun replaceMeasures(p: OmrParsed, fixed: Map<Int, OmrMeasure>): OmrParsed =
        p.copy(measures = p.measures.mapIndexed { i, m -> fixed[i] ?: m })

    class Assembled(val score: Score, val marks: List<MeasureMark>, val uncertainMeasures: List<Int>)

    /** Склеивает такты в голос: затакт идёт раньше нулевого бита, залигованные ноты сливаются. */
    fun assemble(p: OmrParsed, voiceLabel: String = "Вокал"): Assembled {
        val notes = ArrayList<Note>()
        val marks = ArrayList<MeasureMark>()
        var t = 0.0
        var openTie = -1
        for ((idx, m) in p.measures.withIndex()) {
            val mStart = t
            var cur = t
            for (e in m.events) {
                if (e.midi == null) { cur += e.beats; openTie = -1; continue }
                if (openTie >= 0 && notes[openTie].midi == e.midi && cur == notes[openTie].endBeat) {
                    val prev = notes[openTie]
                    notes[openTie] = prev.copy(beats = prev.beats + e.beats)
                } else {
                    notes += Note(e.midi, cur, e.beats, idx + 1, e.syllable)
                    openTie = notes.lastIndex
                }
                if (!e.tie) openTie = -1
                cur += e.beats
            }
            val nominal = nominalLength(p, m)
            // короче номинала — держим сетку тактов; длиннее (смена метра, лишние доли) — такт растягивается, ноты не накладываются
            val mLen = if (m.pickup || (idx == p.measures.lastIndex && m.total < nominal)) m.total.coerceAtLeast(0.25) else maxOf(nominal, m.total)
            val lowerMarks = m.marks.map { it.lowercase().replace(Regex("[\\s.]+"), " ").trim() }
            val jump = lowerMarks.firstNotNullOfOrNull { parseJump(it) }
            marks += MeasureMark(
                startBeat = mStart, lengthBeats = mLen,
                repeatStart = m.repeatStart, repeatEnd = m.repeatEnd,
                volta = m.ending?.let { listOf(it) } ?: emptyList(),
                segno = "segno" in lowerMarks || "сеньо" in lowerMarks,
                coda = "coda" in lowerMarks || "кода" in lowerMarks,
                toCoda = lowerMarks.any { it.startsWith("to coda") },
                fine = "fine" in lowerMarks,
                jump = jump,
            )
            t = mStart + mLen
        }
        val score = Score(p.title, p.keyFifths, p.timeNum, p.timeDen, p.tempo, listOf(Voice("omr", voiceLabel, VoiceKind.MELODY, notes)))
        return Assembled(score, fixVoltaSpans(marks), p.measures.withIndex().filter { it.value.uncertain }.map { it.index })
    }

    /** Если вольта указана только на первом такте группы, а второй такт идёт без метки — оставляем как есть. */
    private fun fixVoltaSpans(marks: List<MeasureMark>): List<MeasureMark> = marks

    internal fun parseJump(s: String): Jump? = when {
        s.startsWith("d s") && "coda" in s -> Jump.DS_AL_CODA
        s.startsWith("d s") && "fine" in s -> Jump.DS_AL_FINE
        s.startsWith("d s") -> Jump.DS
        s.startsWith("d c") && "coda" in s -> Jump.DC_AL_CODA
        s.startsWith("d c") && "fine" in s -> Jump.DC_AL_FINE
        s.startsWith("d c") -> Jump.DC
        else -> null
    }

    /** Подсказка с уточнением для повторного запроса по сомнительным тактам. */
    fun retryHint(badMeasureNumbers: List<Int>, p: OmrParsed): String =
        "В тактах ${badMeasureNumbers.joinToString()} сумма длительностей не равна ${p.timeNum}/${p.timeDen}. " +
            "Пересмотри эти такты внимательно и верни полный JSON снова."

    // ---- Оценка стоимости ----
    data class Cost(val inputTokens: Long, val outputTokens: Long, val usd: Double)

    /** Грубая оценка: изображение ≈ w*h/750 токенов (предел ~1600), ответ ≈ 60 токенов на такт. */
    fun estimate(pages: Int, imageWidth: Int, imageHeight: Int, usdPerMtokIn: Double = 3.0, usdPerMtokOut: Double = 15.0, measuresPerPage: Int = 16): Cost {
        val perImage = minOf(1600L, imageWidth.toLong() * imageHeight / 750)
        val input = pages * (perImage + 500)
        val output = pages.toLong() * measuresPerPage * 60 + 200
        return Cost(input, output, input / 1e6 * usdPerMtokIn + output / 1e6 * usdPerMtokOut)
    }
}
