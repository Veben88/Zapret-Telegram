package ru.partitrainer.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Написание чёрных клавиш: бемоли в тональностях с бемолями (A♭, а не G♯). Выставляется при открытии партии. */
object SpellingState {
    var flats by mutableStateOf(false)
}
