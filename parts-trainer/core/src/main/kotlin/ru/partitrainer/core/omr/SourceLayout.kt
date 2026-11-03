package ru.partitrainer.core.omr

import kotlinx.serialization.Serializable

/** Страница исходника: номер в файле и поворот, с которым её рисует приложение. */
@Serializable
data class PageRef(val index: Int, val rotation: Int = 0)

/** Область такта на странице исходника в долях ширины/высоты страницы (0..1). [page] — номер в [SourceLayout.pages]. */
@Serializable
data class MeasureRect(val page: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    fun contains(x: Float, y: Float) = x in x0..x1 && y in y0..y1
}

/** Где на страницах исходника находится каждый такт распознанной партии (по порядку тактов; null — неизвестно). */
@Serializable
data class SourceLayout(val pages: List<PageRef>, val rects: List<MeasureRect?>)
