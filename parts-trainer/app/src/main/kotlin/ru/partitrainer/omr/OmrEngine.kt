package ru.partitrainer.omr

import android.graphics.Bitmap
import ru.partitrainer.core.omr.OmrParsed
import ru.partitrainer.data.EngineId

/** Какую строку брать из нот. */
enum class StaffSelection(val key: String, val promptRu: String, val voiceLabel: String, val kind: ru.partitrainer.core.model.VoiceKind = ru.partitrainer.core.model.VoiceKind.MELODY) {
    VOCAL_TOP("vocal", "Распознай только ВЕРХНЮЮ строку каждой системы — вокальную партию (строка, под которой напечатан текст песни). Партию фортепиано пропусти. Если над нотами есть аккорды (буквенные обозначения), верни их в поле chord.", "Вокал"),
    RIGHT_HAND("right", "Распознай только ПРАВУЮ РУКУ фортепиано (верхний нотоносец фортепианной партии, скрипичный ключ). Вокальную строку пропусти. Если звучит аккорд — верни его верхнюю ноту.", "Правая рука"),
    LEFT_HAND("left", "Распознай только ЛЕВУЮ РУКУ фортепиано (нижний нотоносец фортепианной партии, обычно басовый ключ). Вокальную строку пропусти. Если звучит аккорд — верни его верхнюю ноту.", "Левая рука"),
    ALL("all", "", "Все"),
    SOPRANO("sop", "Распознай только партию СОПРАНО хоровой партитуры (верхняя строка системы, подпись S.). Если систему сопрано в ней нет — верни такты с паузами. Тенор в скрипичном ключе с «8» звучит на октаву ниже, но здесь он не нужен.", "Сопрано", ru.partitrainer.core.model.VoiceKind.SOPRANO),
    ALTO("alt", "Распознай только партию АЛЬТ хоровой партитуры (вторая строка системы, подпись A.). Если строки альта в системе нет — верни такты с паузами.", "Альт", ru.partitrainer.core.model.VoiceKind.ALTO),
    TENOR("ten", "Распознай только партию ТЕНОР хоровой партитуры (подпись T.). Если ключ скрипичный с маленькой «8» под ним, верни высоты на октаву НИЖЕ написанных. Если строки в системе нет — верни такты с паузами.", "Тенор", ru.partitrainer.core.model.VoiceKind.TENOR),
    BASS("bas", "Распознай только партию БАС хоровой партитуры (басовый ключ, подпись B.). Если строки в системе нет — верни такты с паузами.", "Бас", ru.partitrainer.core.model.VoiceKind.BASS),
    CHOIR_ALL("choir", "", "Хор");

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: VOCAL_TOP
    }

    val isChoirVoice: Boolean get() = this == SOPRANO || this == ALTO || this == TENOR || this == BASS

    /** Какие строки реально распознаются. */
    fun expand(): List<StaffSelection> = when (this) {
        ALL -> listOf(VOCAL_TOP, RIGHT_HAND, LEFT_HAND)
        CHOIR_ALL -> listOf(SOPRANO, ALTO, TENOR, BASS)
        else -> listOf(this)
    }
}

/** Поставщик страниц: рендерит по одной, чтобы не держать всё в памяти. Движок обязан вызвать recycle(). */
fun interface PageProvider { suspend fun render(page: Int): Bitmap }

class OmrRequest(val pages: List<Int>, val staff: StaffSelection, val provider: PageProvider)

data class OmrProgress(val done: Int, val total: Int, val message: String)

sealed interface Availability {
    data object Ready : Availability
    data class Unavailable(val reason: String) : Availability
}

/** Ошибка распознавания с понятным пользователю сообщением. [offerManual] — предложить ручной ввод. */
class OmrException(val userMessage: String, val offerManual: Boolean = true, cause: Throwable? = null) : Exception(userMessage, cause)

interface OmrEngine {
    val id: EngineId
    suspend fun availability(): Availability

    /** Распознаёт одну строку (см. [OmrRequest.staff]) по всем страницам. Отмена — через отмену корутины. */
    suspend fun recognize(request: OmrRequest, onProgress: (OmrProgress) -> Unit): OmrParsed
}
