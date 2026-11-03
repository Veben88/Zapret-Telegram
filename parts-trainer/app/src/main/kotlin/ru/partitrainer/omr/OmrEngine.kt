package ru.partitrainer.omr

import android.graphics.Bitmap
import ru.partitrainer.core.omr.OmrParsed
import ru.partitrainer.data.EngineId

/** Какую строку брать из нот. */
enum class StaffSelection(val key: String, val promptRu: String, val voiceLabel: String) {
    VOCAL_TOP("vocal", "Распознай только ВЕРХНЮЮ строку каждой системы — вокальную партию (строка, под которой напечатан текст песни). Партию фортепиано пропусти. Если над нотами есть аккорды (буквенные обозначения), верни их в поле chord.", "Вокал"),
    RIGHT_HAND("right", "Распознай только ПРАВУЮ РУКУ фортепиано (верхний нотоносец фортепианной партии, скрипичный ключ). Вокальную строку пропусти. Если звучит аккорд — верни его верхнюю ноту.", "Правая рука"),
    LEFT_HAND("left", "Распознай только ЛЕВУЮ РУКУ фортепиано (нижний нотоносец фортепианной партии, обычно басовый ключ). Вокальную строку пропусти. Если звучит аккорд — верни его верхнюю ноту.", "Левая рука"),
    ALL("all", "", "Все");

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: VOCAL_TOP
    }
    /** Какие строки реально распознаются. */
    fun expand(): List<StaffSelection> = if (this == ALL) listOf(VOCAL_TOP, RIGHT_HAND, LEFT_HAND) else listOf(this)
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
