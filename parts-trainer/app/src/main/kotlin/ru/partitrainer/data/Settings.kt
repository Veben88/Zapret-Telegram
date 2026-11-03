package ru.partitrainer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.partitrainer.core.music.Notation

enum class EngineId { ON_DEVICE, CLAUDE_VISION }
enum class Timbre { PIANO, AAH }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val engine: EngineId = EngineId.ON_DEVICE,
    val notation: Notation = Notation.SOLFEGE,
    val timbre: Timbre = Timbre.PIANO,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val toleranceCents: Int = 40,
    val matchOctave: Boolean = false,
    val volume: Float = 0.8f,
    val otherVoicesVolume: Float = 0.4f,
    val playOthers: Boolean = false,
    val countIn: Boolean = true,
    val measuresPerPhrase: Int = 2,
    val claudeWarningAccepted: Boolean = false,
    /** Точный режим распознавания на устройстве: нейросеть сегментации вместо быстрого поиска линеек (медленнее, надёжнее на изогнутых фото). */
    val omrAccurate: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val engine = stringPreferencesKey("engine")
        val notation = stringPreferencesKey("notation")
        val timbre = stringPreferencesKey("timbre")
        val theme = stringPreferencesKey("theme")
        val tolerance = intPreferencesKey("tolerance")
        val octave = booleanPreferencesKey("match_octave")
        val volume = floatPreferencesKey("volume")
        val other = floatPreferencesKey("other_volume")
        val playOthers = booleanPreferencesKey("play_others")
        val countIn = booleanPreferencesKey("count_in")
        val phrase = intPreferencesKey("measures_per_phrase")
        val warned = booleanPreferencesKey("claude_warned")
        val accurate = booleanPreferencesKey("omr_accurate")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { snapshot(it) }

    suspend fun update(block: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val cur = snapshot(p)
            val n = block(cur)
            p[K.engine] = n.engine.name; p[K.notation] = n.notation.name; p[K.timbre] = n.timbre.name
            p[K.theme] = n.theme.name; p[K.tolerance] = n.toleranceCents; p[K.octave] = n.matchOctave
            p[K.volume] = n.volume; p[K.other] = n.otherVoicesVolume; p[K.playOthers] = n.playOthers
            p[K.countIn] = n.countIn; p[K.phrase] = n.measuresPerPhrase; p[K.warned] = n.claudeWarningAccepted; p[K.accurate] = n.omrAccurate
        }
    }

    private fun snapshot(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            engine = p[K.engine]?.let { runCatching { EngineId.valueOf(it) }.getOrNull() } ?: d.engine,
            notation = p[K.notation]?.let { runCatching { Notation.valueOf(it) }.getOrNull() } ?: d.notation,
            timbre = p[K.timbre]?.let { runCatching { Timbre.valueOf(it) }.getOrNull() } ?: d.timbre,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            toleranceCents = p[K.tolerance] ?: d.toleranceCents,
            matchOctave = p[K.octave] ?: d.matchOctave,
            volume = p[K.volume] ?: d.volume,
            otherVoicesVolume = p[K.other] ?: d.otherVoicesVolume,
            playOthers = p[K.playOthers] ?: d.playOthers,
            countIn = p[K.countIn] ?: d.countIn,
            measuresPerPhrase = p[K.phrase] ?: d.measuresPerPhrase,
            claudeWarningAccepted = p[K.warned] ?: d.claudeWarningAccepted,
            omrAccurate = p[K.accurate] ?: d.omrAccurate,
        )
    }
}
