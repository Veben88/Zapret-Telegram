package ru.partitrainer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.partitrainer.AppContainer
import ru.partitrainer.R
import ru.partitrainer.core.music.Notation
import ru.partitrainer.data.EngineId
import ru.partitrainer.data.Settings
import ru.partitrainer.data.ThemeMode
import ru.partitrainer.data.Timbre
import ru.partitrainer.ui.appViewModel

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings> = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val hasKey = MutableStateFlow(c.keyStore.hasKey())
    val cacheCleared = MutableStateFlow(false)
    fun update(f: (Settings) -> Settings) = viewModelScope.launch { c.settings.update(f) }
    fun saveKey(k: String) { if (k.isNotBlank()) { c.keyStore.save(k); hasKey.value = true } }
    fun deleteKey() { c.keyStore.delete(); hasKey.value = false }
    val models get() = c.homrModels
    fun clearCache() = viewModelScope.launch { c.projects.clearCache(); cacheCleared.value = true }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val s by vm.settings.collectAsState()
    val hasKey by vm.hasKey.collectAsState()
    val cleared by vm.cacheCleared.collectAsState()
    val models by vm.models.state.collectAsState()
    var keyInput by remember { mutableStateOf("") }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(R.string.set_recognition)
            Column(Modifier.selectableGroup()) {
                EngineOption(s.engine == EngineId.ON_DEVICE, R.string.engine_ondevice, R.string.engine_ondevice_desc) { vm.update { it.copy(engine = EngineId.ON_DEVICE) } }
                EngineOption(s.engine == EngineId.CLAUDE_VISION, R.string.engine_claude, R.string.engine_claude_desc) { vm.update { it.copy(engine = EngineId.CLAUDE_VISION) } }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.models_title), style = MaterialTheme.typography.titleSmall)
                    ru.partitrainer.ui.components.ModelsCard(models, vm.models.downloadSizeMb, vm.models::startDownload, vm.models::cancel, vm.models::delete)
                }
            }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.api_key), style = MaterialTheme.typography.titleSmall)
                    if (hasKey) {
                        Text(stringResource(R.string.key_saved))
                        OutlinedButton(onClick = vm::deleteKey, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.delete_key)) }
                    } else {
                        OutlinedTextField(keyInput, { keyInput = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.api_key_hint)) },
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                        Button(onClick = { vm.saveKey(keyInput); keyInput = "" }, enabled = keyInput.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) }
                    }
                    Text(stringResource(R.string.key_note), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = vm::clearCache, Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (cleared) R.string.cache_cleared else R.string.clear_cache)) }

            HorizontalDivider()
            SectionTitle(R.string.set_notation)
            Choice(listOf(Notation.SOLFEGE to R.string.notation_solfege, Notation.LATIN to R.string.notation_latin), s.notation) { v -> vm.update { it.copy(notation = v) } }

            SectionTitle(R.string.set_timbre)
            Choice(listOf(Timbre.PIANO to R.string.timbre_piano, Timbre.AAH to R.string.timbre_aah), s.timbre) { v -> vm.update { it.copy(timbre = v) } }

            SectionTitle(R.string.set_theme)
            Choice(listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark), s.theme) { v -> vm.update { it.copy(theme = v) } }

            SectionTitle(R.string.set_tolerance)
            Text(stringResource(R.string.tolerance_value, s.toleranceCents))
            Slider(s.toleranceCents.toFloat(), { v -> vm.update { it.copy(toleranceCents = v.toInt()) } }, valueRange = 20f..80f, steps = 11)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.match_octave), Modifier.weight(1f))
                Switch(s.matchOctave, { v -> vm.update { it.copy(matchOctave = v) } })
            }
        }
    }
}

@Composable private fun SectionTitle(res: Int) = Text(stringResource(res), style = MaterialTheme.typography.titleMedium)

@Composable
private fun EngineOption(selected: Boolean, title: Int, desc: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        RadioButton(selected, onClick = null, modifier = Modifier.padding(top = 4.dp)); Spacer(Modifier.width(12.dp))
        Column { Text(stringResource(title), style = MaterialTheme.typography.titleSmall); Text(stringResource(desc), style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(selected == v, { onSelect(v) }, SegmentedButtonDefaults.itemShape(i, options.size), Modifier.heightIn(min = 48.dp)) { Text(stringResource(label)) }
        }
    }
}
