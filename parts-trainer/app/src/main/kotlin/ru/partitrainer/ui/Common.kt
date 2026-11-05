package ru.partitrainer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ru.partitrainer.AppContainer
import ru.partitrainer.container

/** Создаёт ViewModel с доступом к контейнеру зависимостей. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContext.current.applicationContext.container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}
