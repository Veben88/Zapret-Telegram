package ru.partitrainer.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ru.partitrainer.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF2B3A67), onPrimary = Color.White, secondary = Color(0xFF7A5900), tertiary = Color(0xFF00696B),
    primaryContainer = Color(0xFFDCE2FF), secondaryContainer = Color(0xFFFFDF9A),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB4C5FF), onPrimary = Color(0xFF0F1F4D), secondary = Color(0xFFFFC857), tertiary = Color(0xFF4DDADC),
    primaryContainer = Color(0xFF28385F), secondaryContainer = Color(0xFF5A4300),
)

@Composable
fun PartiTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
