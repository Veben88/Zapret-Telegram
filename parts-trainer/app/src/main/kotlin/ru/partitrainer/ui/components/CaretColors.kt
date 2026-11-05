package ru.partitrainer.ui.components

import androidx.compose.ui.graphics.Color
import ru.partitrainer.data.CaretColor

/** Цвет каретки: ACCENT берёт цвет темы, остальные — яркие, читаемые и на светлом, и на тёмном фоне. */
fun caretColorOf(c: CaretColor, accent: Color): Color = when (c) {
    CaretColor.ACCENT -> accent
    CaretColor.GREEN -> Color(0xFF2E9E4F)
    CaretColor.RED -> Color(0xFFD93025)
    CaretColor.ORANGE -> Color(0xFFF08A00)
    CaretColor.BLUE -> Color(0xFF1A73E8)
    CaretColor.PURPLE -> Color(0xFF9334E6)
    CaretColor.YELLOW -> Color(0xFFF2C200)
}
