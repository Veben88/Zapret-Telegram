package ru.partitrainer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import ru.partitrainer.data.Settings
import ru.partitrainer.ui.PartiNavHost
import ru.partitrainer.ui.PartiTheme

/** Хранит внешний URI («Открыть с помощью»/«Поделиться»), пока UI его не обработает. */
class MainViewModel : ViewModel() {
    val incoming = MutableStateFlow<Uri?>(null)
    val error = MutableStateFlow<String?>(null)
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            // Подписываемся только на тему и систему записи нот: прочие настройки (ползунки громкости и т.п.) не трогают корень
            val theme by remember { container.settings.flow.map { it.theme }.distinctUntilChanged() }.collectAsState(initial = Settings().theme)
            val notation by remember { container.settings.flow.map { it.notation }.distinctUntilChanged() }.collectAsState(initial = Settings().notation)
            PartiTheme(theme) {
                androidx.compose.runtime.CompositionLocalProvider(ru.partitrainer.ui.components.LocalNotation provides notation) { PartiNavHost(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    @Suppress("DEPRECATION")
    private fun handle(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) vm.incoming.value = uri
    }
}
