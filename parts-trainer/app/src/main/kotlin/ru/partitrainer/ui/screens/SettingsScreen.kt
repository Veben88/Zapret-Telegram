package ru.partitrainer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

    val backupMessage = MutableStateFlow<String?>(null)
    val tuneMessage = MutableStateFlow<String?>(null)
    val tuneVersion = MutableStateFlow(0)

    /** Полный подбор ускорителя энкодера (докачивает float-энкодер). */
    fun tuneEncoder() = viewModelScope.launch {
        tuneMessage.value = null
        tuneMessage.value = try {
            val threads = if (c.homrModels.bigMemoryDevice) 1 else 2.coerceAtMost(ru.partitrainer.omr.CpuTopology.performanceThreads())
            c.homrModels.tune(full = true, threads = threads)
            null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Не удалось: ${e.message}" }
        tuneVersion.value++
    }

    fun exportBackup(uri: android.net.Uri) = viewModelScope.launch {
        backupMessage.value = try {
            val n = c.context.contentResolver.openOutputStream(uri)?.use { c.backup.export(it) } ?: throw IllegalStateException("Не удалось создать файл")
            "Сохранено партий: $n"
        } catch (e: Exception) { "Не удалось сохранить: ${e.message}" }
    }

    fun importBackup(uri: android.net.Uri) = viewModelScope.launch {
        backupMessage.value = try {
            val r = c.context.contentResolver.openInputStream(uri)?.use { c.backup.restore(it) } ?: throw IllegalStateException("Не удалось открыть файл")
            "Восстановлено: ${r.restored}" + if (r.skipped > 0) ", уже были: ${r.skipped}" else ""
        } catch (e: Exception) { "Не удалось восстановить: ${e.message}" }
    }

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
    val backupMsg by vm.backupMessage.collectAsState()
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::exportBackup) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(vm::importBackup) }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.omr_accurate))
                    Text(stringResource(R.string.omr_accurate_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(s.omrAccurate, { v -> vm.update { it.copy(omrAccurate = v) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.omr_lyrics))
                    Text(stringResource(R.string.omr_lyrics_hint), style = MaterialTheme.typography.bodySmall)
                    if (s.omrLyrics && !hasKey) Text(stringResource(R.string.omr_lyrics_nokey), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Switch(s.omrLyrics, { v -> vm.update { it.copy(omrLyrics = v) } })
            }
            EncoderTuneCard(vm)
            OutlinedButton(onClick = vm::clearCache, Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (cleared) R.string.cache_cleared else R.string.clear_cache)) }

            HorizontalDivider()
            SectionTitle(R.string.set_backup)
            Text(stringResource(R.string.backup_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("partitrainer-backup.zip") }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_save)) }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.backup_restore)) }
            }
            backupMsg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            HorizontalDivider()
            SectionTitle(R.string.set_notation)
            Choice(listOf(Notation.SOLFEGE to R.string.notation_solfege, Notation.LATIN to R.string.notation_latin), s.notation) { v -> vm.update { it.copy(notation = v) } }

            SectionTitle(R.string.set_timbre)
            Choice(listOf(Timbre.PIANO to R.string.timbre_piano, Timbre.AAH to R.string.timbre_aah), s.timbre) { v -> vm.update { it.copy(timbre = v) } }

            SectionTitle(R.string.set_theme)
            Choice(listOf(ThemeMode.SYSTEM to R.string.theme_system, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.DARK to R.string.theme_dark), s.theme) { v -> vm.update { it.copy(theme = v) } }

            SectionTitle(R.string.set_caret_color)
            Text(stringResource(R.string.caret_color_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (c in ru.partitrainer.data.CaretColor.entries) {
                    val col = ru.partitrainer.ui.components.caretColorOf(c, MaterialTheme.colorScheme.primary)
                    val selected = c == s.caretColor
                    Box(
                        Modifier.size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(col)
                            .border(if (selected) 4.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, androidx.compose.foundation.shape.CircleShape)
                            .selectable(selected = selected, onClick = { vm.update { it.copy(caretColor = c) } }, role = Role.RadioButton)
                            .semantics { contentDescription = c.name },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.key_haptics), Modifier.weight(1f))
                Switch(s.keyHaptics, { v -> vm.update { it.copy(keyHaptics = v) } })
            }

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

/** Ускоритель распознавания: что выбрано, итоги замеров и полный подбор. */
@Composable
private fun EncoderTuneCard(vm: SettingsViewModel) {
    val status by vm.models.tuneStatus.collectAsState()
    val msg by vm.tuneMessage.collectAsState()
    val ver by vm.tuneVersion.collectAsState()
    val backend = remember(ver, status) { vm.models.encoderBackend }
    val report = remember(ver, status) { vm.models.benchReport }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.accel_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.accel_current, backend?.title ?: stringResource(R.string.accel_not_tuned)))
            if (report.isNotBlank()) Text(report, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.accel_hint, vm.models.floatEncoderSizeMb), style = MaterialTheme.typography.bodySmall)
            if (status != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(status!!, style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = vm::tuneEncoder, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.accel_tune)) }
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
