package ru.partitrainer.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.partitrainer.R
import ru.partitrainer.core.audio.NoteOutcome
import ru.partitrainer.core.audio.Verdict
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.music.BeatsFormat
import ru.partitrainer.core.music.Notation
import ru.partitrainer.core.music.NoteNames
import ru.partitrainer.core.music.Phrase
import ru.partitrainer.core.music.Ranges
import ru.partitrainer.ui.appViewModel
import ru.partitrainer.ui.components.LocalNotation
import ru.partitrainer.ui.components.ScoreView
import ru.partitrainer.ui.components.SpellingState
import ru.partitrainer.ui.components.NoteChip
import ru.partitrainer.ui.components.PianoKeyboard
import ru.partitrainer.ui.components.RestChip
import ru.partitrainer.util.ExportFormat
import ru.partitrainer.util.Exporter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerScreen(projectId: Long, onBack: () -> Unit, onEdit: () -> Unit, onSettings: () -> Unit) {
    val vm = appViewModel(key = "trainer$projectId") { TrainerViewModel(it, projectId) }
    val ui by vm.ui.collectAsState()
    val d by vm.derived.collectAsState()
    val pos by vm.position.collectAsState()
    val ctx = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val notation = ui.settings.notation
    var exportMenu by remember { mutableStateOf(false) }

    // Экран не гаснет в режиме «Спой фразу»
    val view = LocalView.current
    DisposableEffect(ui.singing != null) { view.keepScreenOn = ui.singing != null; onDispose { view.keepScreenOn = false } }

    // Разрешение микрофона
    var pendingSing by remember { mutableStateOf<Int?>(null) }
    var showRationale by remember { mutableStateOf(false) }
    var showDenied by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { vm.setMic(true); pendingSing?.let { vm.singPhrase(it) } } else showDenied = true
        pendingSing = null
    }
    fun withMic(sing: Int?) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (sing != null) vm.singPhrase(sing) else vm.setMic(!ui.micOn)
        } else { pendingSing = sing; showRationale = true }
    }

    val voice = d.voice
    val notes = voice?.notes.orEmpty()
    val totalBeats = notes.maxOfOrNull { it.endBeat } ?: 0.0
    val active = ui.playing || ui.pausedByFocus
    // Текущая фраза под указателем (только пока играет): первая, чей конец ещё впереди
    val curPhrase = if (active && d.phrases.isNotEmpty()) d.phrases.indexOfFirst { pos.beat < it.endBeat - 1e-9 }.let { if (it < 0) d.phrases.lastIndex else it } else -1
    val listState = rememberLazyListState()
    LaunchedEffect(curPhrase) { if (curPhrase >= 0) listState.animateScrollToItem(curPhrase + 1) }
    val targets: Set<Int> = buildSet {
        pos.activeMain.forEach { notes.getOrNull(it)?.let { n -> add(n.midi) } }
        ui.selectedNote?.let { notes.getOrNull(it)?.let { n -> add(n.midi) } }
        ui.singing?.let { s -> notes.getOrNull(s.activeIndex)?.let { n -> add(n.midi) } }
    }
    val kbLow = (d.range?.low ?: 55) - 2
    val kbHigh = (d.range?.high ?: 76) + 2

    val transport: @Composable () -> Unit = {
        if (totalBeats > 0) PlayheadBar(pos.beat, pos.countInBeat, totalBeats, active, d, notes, curPhrase, { b -> vm.playFrom(b) }, vm::stop)
    }
    val keyboard: @Composable () -> Unit = {
        PianoKeyboard(kbLow, kbHigh, notation, targets, pos.activeMain.mapNotNull { notes.getOrNull(it)?.midi }.toSet(), vm::tapKey, Modifier.padding(vertical = 6.dp))
    }
    // Нотный стан с бегущей кареткой; касание такта — играть оттуда
    val scoreView: @Composable (Modifier) -> Unit = { m ->
        val spec = d.spec
        if (spec != null) ScoreView(spec, d.playMap, pos.beat, showCaret = active, onSeek = { ob -> vm.playFrom(d.playMap.expandedBeat(ob), false) }, modifier = m)
        else Box(m) {}
    }

    CompositionLocalProvider(LocalNotation provides notation) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(ui.project?.title ?: "", maxLines = 1) },
                    navigationIcon = { IconButton(onClick = { vm.stop(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                    actions = {
                        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.edit_notes)) }
                        Box {
                            IconButton(onClick = { exportMenu = true }) { Icon(Icons.Default.Share, stringResource(R.string.export)) }
                            DropdownMenu(exportMenu, { exportMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.export_midi)) }, onClick = { exportMenu = false; ui.project?.let { p -> Exporter.share(ctx, p.copy(expandRepeats = ui.expand), ui.voiceId, ExportFormat.MIDI) } })
                                DropdownMenuItem(text = { Text(stringResource(R.string.export_musicxml)) }, onClick = { exportMenu = false; ui.project?.let { p -> Exporter.share(ctx, p.copy(expandRepeats = ui.expand), ui.voiceId, ExportFormat.MUSICXML) } })
                            }
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                    },
                )
            },
            bottomBar = {
                // Планшет: управление позицией и клавиатура — внизу на всю ширину. Телефон: всё это в шторке.
                if (wide) Surface(tonalElevation = 3.dp) {
                    Column {
                        transport()
                        if (ui.micOn) MicPanel(ui, vm)
                        keyboard()
                    }
                }
            },
        ) { pad ->
            val controls: @Composable () -> Unit = { Controls(vm, ui, d, onMic = { withMic(null) }) }
            val list: @Composable (Modifier) -> Unit = { m ->
                LazyColumn(m, state = listState, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item { controls() }
                    itemsIndexed(d.phrases) { i, ph ->
                        PhraseCard(i, ph, notes.subList(ph.from, ph.to), ph.from, d.voice?.kind, notation, pos.activeMain,
                            singActive = ui.singing?.takeIf { it.phrase == i }?.activeIndex, selected = ui.selectedNote,
                            looping = ui.loopPhrase == i, playing = ui.playing,
                            progress = if (i == curPhrase) ((pos.beat - ph.startBeat) / (ph.endBeat - ph.startBeat).coerceAtLeast(1e-6)).toFloat().coerceIn(0f, 1f) else null,
                            onNote = vm::tapNote,
                            onPlay = { vm.playPhrase(i, false) }, onLoop = { vm.playPhrase(i, true) }, onStop = vm::stop,
                            onSing = { withMic(i) }, bpmBeats = d.bpm)
                    }
                }
            }
            if (wide) Row(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                scoreView(Modifier.weight(0.56f).fillMaxHeight())
                list(Modifier.weight(0.44f).fillMaxHeight())
            } else {
                val sheetMax = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
                BottomSheetScaffold(
                    modifier = Modifier.padding(pad),
                    sheetPeekHeight = 128.dp,
                    sheetContent = {
                        transport()
                        if (ui.micOn) MicPanel(ui, vm)
                        keyboard()
                        list(Modifier.heightIn(max = sheetMax).padding(horizontal = 12.dp))
                    },
                ) { inner -> scoreView(Modifier.padding(inner).fillMaxSize().padding(horizontal = 6.dp)) }
            }
        }
    }

    ui.singResult?.let { r ->
        AlertDialog(
            onDismissRequest = vm::dismissResult,
            title = { Text(stringResource(R.string.sing_result, r.percent)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (nr in r.results) {
                        val label = stringResource(when (nr.outcome) { NoteOutcome.HIT -> R.string.out_hit; NoteOutcome.HIGH -> R.string.out_high; NoteOutcome.LOW -> R.string.out_low; NoteOutcome.MISSED -> R.string.out_missed })
                        val cents = nr.avgCents?.let { " (${if (it >= 0) "+" else ""}${it.roundToInt()} ¢)" } ?: ""
                        Text("${NoteNames.nameWithOctave(nr.note.midi, notation, SpellingState.flats)} · ${BeatsFormat.format(nr.note.beats)} — $label$cents",
                            Modifier.padding(vertical = 4.dp), color = if (nr.outcome == NoteOutcome.HIT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissResult) { Text(stringResource(R.string.ok)) } },
            dismissButton = { ui.singResultPhrase?.let { p -> TextButton(onClick = { vm.dismissResult(); withMic(p) }) { Text(stringResource(R.string.retry)) } } },
        )
    }
    if (showRationale) AlertDialog(
        onDismissRequest = { showRationale = false },
        title = { Text(stringResource(R.string.mic_why_title)) }, text = { Text(stringResource(R.string.mic_why_text)) },
        confirmButton = { TextButton(onClick = { showRationale = false; permLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text(stringResource(R.string.mic_allow)) } },
        dismissButton = { TextButton(onClick = { showRationale = false; pendingSing = null }) { Text(stringResource(R.string.cancel)) } },
    )
    if (showDenied) AlertDialog(
        onDismissRequest = { showDenied = false },
        title = { Text(stringResource(R.string.mic_denied_title)) }, text = { Text(stringResource(R.string.mic_denied_text)) },
        confirmButton = {
            TextButton(onClick = {
                showDenied = false
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(stringResource(R.string.open_settings)) }
        },
        dismissButton = { TextButton(onClick = { showDenied = false }) { Text(stringResource(R.string.cancel)) } },
    )
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(vm: TrainerViewModel, ui: TrainerUi, d: Derived, onMic: () -> Unit) {
    val notation = ui.settings.notation
    val project = ui.project ?: return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Голос
            if (project.score.voices.size > 1) {
                Text(stringResource(R.string.voice), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in project.score.voices) FilterChip(v.id == (d.voice?.id), { vm.selectVoice(v.id) }, label = { Text(v.label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            // Диапазон
            val kind = d.voice?.kind
            val std = kind?.let { Ranges.of(it) }
            d.range?.let { r ->
                val rangeText = "${NoteNames.nameWithOctave(r.low, notation, SpellingState.flats)} – ${NoteNames.nameWithOctave(r.high, notation, SpellingState.flats)}"
                Text(stringResource(R.string.range_of_voice, rangeText), style = MaterialTheme.typography.bodyMedium)
                if (std != null) {
                    val out = d.voice!!.notes.count { it.midi !in std }
                    Text(
                        stringResource(R.string.standard_range, NoteNames.nameWithOctave(std.low, notation, SpellingState.flats), NoteNames.nameWithOctave(std.high, notation, SpellingState.flats)) +
                            if (out > 0) " · " + stringResource(R.string.notes_out_of_range, out) else "",
                        style = MaterialTheme.typography.bodySmall, color = if (out > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Воспроизведение
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::playAll, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.play_all)) }
                OutlinedButton(onClick = vm::stop, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.stop)) }
            }
            if (ui.pausedByFocus) Text(stringResource(R.string.paused_focus), color = MaterialTheme.colorScheme.tertiary)
            // Темп
            Text(stringResource(R.string.tempo_bpm, ui.tempo), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(ui.tempo.toFloat(), { vm.setTempo(it.roundToInt()) }, valueRange = 40f..200f, modifier = Modifier.weight(1f).semantics { contentDescription = "tempo" })
                TextButton(onClick = vm::resetTempo) { Text(stringResource(R.string.reset_tempo, project.score.tempo ?: 85)) }
            }
            // Транспонирование
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.transpose), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { vm.setTranspose(ui.transpose - 1) }) { Icon(Icons.Default.Remove, stringResource(R.string.transpose_down)) }
                Text(if (ui.transpose > 0) "+${ui.transpose}" else "${ui.transpose}", Modifier.widthIn(min = 36.dp))
                IconButton(onClick = { vm.setTranspose(ui.transpose + 1) }) { Icon(Icons.Default.Add, stringResource(R.string.transpose_up)) }
            }
            // Размер фразы
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.phrase_size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                for (m in listOf(1, 2, 4)) FilterChip(ui.measuresPerPhrase == m, { vm.setPhraseSize(m) }, label = { Text(stringResource(R.string.n_measures, m)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            // Повторы
            if (d.hasStructure) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.expand_repeats), Modifier.weight(1f))
                    Switch(ui.expand, vm::setExpand)
                }
                if (d.expansion.ambiguous) Text(stringResource(R.string.repeats_ambiguous, d.expansion.warnings.joinToString("; ")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider()
            // Остальные голоса
            if (project.score.voices.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.listen_others), Modifier.weight(1f))
                    Switch(ui.settings.playOthers, { v -> vm.updateSettings { it.copy(playOthers = v) } })
                }
                if (ui.settings.playOthers) {
                    Text(stringResource(R.string.others_volume), style = MaterialTheme.typography.labelMedium)
                    Slider(ui.settings.otherVoicesVolume, { v -> vm.updateSettings { it.copy(otherVoicesVolume = v) } })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.count_in), Modifier.weight(1f))
                Switch(ui.settings.countIn, { v -> vm.updateSettings { it.copy(countIn = v) } })
            }
            Text(stringResource(R.string.volume), style = MaterialTheme.typography.labelMedium)
            Slider(ui.settings.volume, { v -> vm.updateSettings { it.copy(volume = v) } })
            Text(stringResource(R.string.sustain_label, (ui.settings.sustain * 100).roundToInt()), style = MaterialTheme.typography.labelMedium)
            Slider(ui.settings.sustain, { v -> vm.updateSettings { it.copy(sustain = v) } }, modifier = Modifier.semantics { contentDescription = "sustain" })
            Text(stringResource(R.string.sustain_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            // Микрофон
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.mic_check), Modifier.weight(1f))
                Switch(ui.micOn, { onMic() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.match_octave), Modifier.weight(1f))
                Switch(ui.settings.matchOctave, { v -> vm.updateSettings { it.copy(matchOctave = v) } })
            }
            ui.micError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * Бегущий указатель: ползунок по всей партии, текущая фраза и такт. Пока звучит — движется сам;
 * можно потянуть в любое место (воспроизведение продолжится оттуда).
 */
@Composable
private fun PlayheadBar(
    beat: Double, countIn: Int, total: Double, active: Boolean, d: Derived, notes: List<Note>, curPhrase: Int,
    onSeek: (Double) -> Unit, onStop: () -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var lastSeek by remember { mutableStateOf(0f) }
    val live = if (active) (beat / total).toFloat().coerceIn(0f, 1f) else lastSeek
    val value = drag ?: live
    val shownBeat = (value * total)
    // Такт под указателем: ближайшая слева нота (с учётом повторов её номер — из нотного текста)
    val measure = notes.lastOrNull { it.startBeat <= shownBeat + 1e-6 }?.measure ?: notes.firstOrNull()?.measure ?: 1
    val phraseIdx = if (curPhrase >= 0) curPhrase else d.phrases.indexOfFirst { shownBeat < it.endBeat - 1e-9 }.let { if (it < 0) d.phrases.lastIndex else it }
    val label = if (countIn > 0 && active) stringResource(R.string.playhead_count_in, countIn)
    else stringResource(R.string.playhead_pos, phraseIdx + 1, d.phrases.size, measure)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            if (active) TextButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
        }
        Slider(
            value = value,
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { v -> lastSeek = v; onSeek(v * total) }; drag = null },
            modifier = Modifier.semantics { contentDescription = "playhead" },
        )
    }
}

@Composable
private fun MicPanel(ui: TrainerUi, vm: TrainerViewModel) {
    val notation = ui.settings.notation
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (ui.headphonesNeeded) Text(stringResource(R.string.headphones_warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        val h = ui.heard
        val text = if (h == null) stringResource(R.string.listening)
        else {
            val name = NoteNames.nameWithOctave(h.nearestMidi, notation, SpellingState.flats)
            val c = h.cents.roundToInt()
            val sign = if (c > 0) "+" else ""
            val verdict = when (h.verdict) {
                Verdict.IN_TUNE -> stringResource(R.string.verdict_ok); Verdict.HIGH -> stringResource(R.string.verdict_high)
                Verdict.LOW -> stringResource(R.string.verdict_low); null -> ""
            }
            val target = h.targetMidi?.let { " → ${NoteNames.nameWithOctave(it, notation, SpellingState.flats)}" } ?: ""
            stringResource(R.string.heard_fmt, name, "$sign$c", target) + if (verdict.isNotEmpty()) " · $verdict" else ""
        }
        val color = when (h?.verdict) { Verdict.IN_TUNE -> MaterialTheme.colorScheme.primary; null -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.tertiary }
        Text(text, style = MaterialTheme.typography.titleMedium, color = color, modifier = Modifier.semantics { contentDescription = text })
        ui.singing?.let { Text(if (it.countIn) stringResource(R.string.get_ready) else stringResource(R.string.sing_now), style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhraseCard(
    index: Int, phrase: Phrase, notes: List<Note>, firstIndex: Int, kind: ru.partitrainer.core.model.VoiceKind?, notation: Notation,
    activeMain: Set<Int>, singActive: Int?, selected: Int?, looping: Boolean, playing: Boolean, progress: Float?,
    onNote: (Int) -> Unit, onPlay: () -> Unit, onLoop: () -> Unit, onStop: () -> Unit, onSing: () -> Unit, bpmBeats: Double,
) {
    val m1 = notes.firstOrNull()?.measure ?: (Math.floor(phrase.startBeat / bpmBeats + 1e-9).toInt() + 1)
    val m2 = notes.lastOrNull()?.measure ?: m1
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.phrase_n, index + 1, m1, m2), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, stringResource(R.string.play_phrase, index + 1)) }
                FilledTonalIconToggleButton(checked = looping, onCheckedChange = { if (it) onLoop() else onStop() }) { Icon(Icons.Default.Repeat, stringResource(R.string.loop_phrase, index + 1)) }
                IconButton(onClick = onSing) { Icon(Icons.Default.Mic, stringResource(R.string.sing_phrase, index + 1)) }
            }
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().semantics { contentDescription = "phrase progress" })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                notes.forEachIndexed { k, n ->
                    val gi = firstIndex + k
                    if (k > 0) {
                        val gap = n.startBeat - notes[k - 1].endBeat
                        if (gap > 0.01) RestChip(gap, false) {}
                    }
                    NoteChip(n, notation, outOfRange = kind?.let { Ranges.outOfRange(n, it) } == true,
                        active = gi in activeMain || singActive == gi, selected = selected == gi, onClick = { onNote(gi) })
                }
            }
        }
    }
}
