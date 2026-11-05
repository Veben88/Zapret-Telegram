package ru.partitrainer.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.partitrainer.R
import ru.partitrainer.core.edit.MeasureView
import ru.partitrainer.core.edit.NoteCell
import ru.partitrainer.core.edit.RestCell
import ru.partitrainer.core.model.VoiceKind
import ru.partitrainer.core.music.BeatsFormat
import ru.partitrainer.core.music.Notation
import ru.partitrainer.core.music.NoteNames
import ru.partitrainer.core.structure.Jump
import ru.partitrainer.core.structure.MeasureMark
import ru.partitrainer.omr.PageRenderer
import ru.partitrainer.ui.appViewModel
import ru.partitrainer.ui.components.NoteChip
import ru.partitrainer.ui.components.RestChip
import ru.partitrainer.ui.components.SpellingState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(projectId: Long, onBack: () -> Unit, onDone: (Long) -> Unit) {
    val vm = appViewModel(key = "edit$projectId") { EditorViewModel(it, projectId) }
    val ui by vm.ui.collectAsState()
    val project = ui.project
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    var range by remember { mutableStateOf(1 to 1) }
    var markDialog by remember { mutableStateOf<Int?>(null) }
    var kindMenu by remember { mutableStateOf(false) }
    var bigThumb by remember { mutableStateOf(false) }

    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(project?.sourcePath, project?.pageIndex, project?.rotation) {
        thumb = loadSourceThumb(project)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.title ?: "", maxLines = 1) },
                navigationIcon = { IconButton(onClick = { vm.stop(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = vm::undo, enabled = ui.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    IconButton(onClick = vm::redo, enabled = ui.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    if (ui.selectedNote != null || ui.selectedRest != null) NotePanel(vm, ui)
                    Button(onClick = { vm.stop(); vm.finish(onDone) }, Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 52.dp)) {
                        Icon(Icons.Default.Check, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.all_correct))
                    }
                }
            }
        },
    ) { pad ->
        val list: @Composable (Modifier) -> Unit = { mod ->
            Column(mod) {
                // Голос и диапазон проигрывания
                if (ui.state.voices.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.state.voices.forEachIndexed { i, v ->
                            FilterChip(i == ui.voiceIndex, { vm.selectVoice(i) }, label = { Text(v.label) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.measures_range), style = MaterialTheme.typography.labelLarge)
                    Stepper(range.first, 1, ui.layout.size.coerceAtLeast(1)) { range = it to maxOf(it, range.second) }
                    Text("–")
                    Stepper(range.second, range.first, ui.layout.size.coerceAtLeast(1)) { range = range.first to it }
                    FilledIconButton(onClick = { if (ui.playing) vm.stop() else vm.playMeasures(range.first - 1, range.second - 1) }, Modifier.size(48.dp)) {
                        Icon(if (ui.playing) Icons.Default.Stop else Icons.Default.PlayArrow, stringResource(if (ui.playing) R.string.stop else R.string.play))
                    }
                    Box {
                        TextButton(onClick = { kindMenu = true }) { Text(stringResource(R.string.voice_type)) }
                        DropdownMenu(kindMenu, { kindMenu = false }) {
                            for (k in VoiceKind.entries) DropdownMenuItem(text = { Text(kindName(k)) }, onClick = { kindMenu = false; vm.setKind(k) })
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    itemsIndexed(ui.layout, key = { _, m -> m.index }) { _, m ->
                        MeasureCard(m, ui.selectedNote, ui.selectedRest, uncertain = m.index in ui.state.uncertain,
                            voiceKind = ui.voice?.kind ?: VoiceKind.OTHER,
                            onNote = vm::selectNote, onRest = vm::selectRest,
                            onPlay = { vm.playMeasures(m.index, m.index) }, onMarks = { markDialog = m.index },
                            onChecked = { vm.markChecked(m.index) })
                    }
                    item {
                        OutlinedButton(onClick = vm::addAtEnd, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_note_end)) }
                    }
                }
            }
        }
        val thumbBox: @Composable (Modifier) -> Unit = { mod ->
            val b = thumb
            if (b != null) {
                Image(b.asImageBitmap(), stringResource(R.string.source_page), mod.clickable { bigThumb = true }, contentScale = ContentScale.Fit)
            } else if (project?.sourcePath != null) Box(mod, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (wide) Row(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (project?.sourcePath != null) thumbBox(Modifier.weight(0.4f).fillMaxHeight())
            list(Modifier.weight(0.6f).fillMaxHeight())
        } else Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp)) {
            if (project?.sourcePath != null) thumbBox(Modifier.fillMaxWidth().height(150.dp))
            list(Modifier.weight(1f).fillMaxWidth())
        }
    }

    if (bigThumb && thumb != null) Dialog(onDismiss = { bigThumb = false }) {
        var scale by remember { mutableFloatStateOf(1f) }
        var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val st = rememberTransformableState { z, pan, _ -> scale = (scale * z).coerceIn(1f, 6f); off += pan }
        Image(thumb!!.asImageBitmap(), stringResource(R.string.source_page), Modifier.fillMaxSize().transformable(st).graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y })
    }

    markDialog?.let { mi ->
        val mark = ui.state.marks.getOrNull(mi)
        if (mark != null) MarkDialog(mi + 1, mark, onDismiss = { markDialog = null }, onSave = { vm.setMark(mi, it); markDialog = null })
        else { markDialog = null }
    }
}

@Composable
private fun Dialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun kindName(k: VoiceKind) = stringResource(when (k) {
    VoiceKind.SOPRANO -> R.string.kind_soprano; VoiceKind.ALTO -> R.string.kind_alto; VoiceKind.TENOR -> R.string.kind_tenor
    VoiceKind.BASS -> R.string.kind_bass; VoiceKind.MELODY -> R.string.kind_melody; VoiceKind.OTHER -> R.string.kind_other
})

@Composable
private fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }, Modifier.size(40.dp)) { Icon(Icons.Default.Remove, stringResource(R.string.decrease)) }
        Text("$value", Modifier.widthIn(min = 24.dp))
        IconButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, Modifier.size(40.dp)) { Icon(Icons.Default.Add, stringResource(R.string.increase)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeasureCard(
    m: MeasureView, selNote: Int?, selRest: Pair<Double, Double>?, uncertain: Boolean, voiceKind: VoiceKind,
    onNote: (Int) -> Unit, onRest: (Double, Double) -> Unit, onPlay: () -> Unit, onMarks: () -> Unit, onChecked: () -> Unit,
) {
    val warn = !m.balanced
    val border = when { uncertain -> BorderStroke(2.dp, MaterialTheme.colorScheme.error); warn -> BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary); else -> null }
    OutlinedCard(Modifier.fillMaxWidth(), border = border ?: CardDefaults.outlinedCardBorder()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.measure_n, m.number), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (uncertain) AssistChip(onClick = onChecked, label = { Text(stringResource(R.string.uncertain_mark)) }, leadingIcon = { Icon(Icons.Default.Warning, null) })
                else if (warn) Text(stringResource(R.string.sum_mismatch, BeatsFormat.format(m.filled), BeatsFormat.format(m.length)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, stringResource(R.string.play_measure, m.number)) }
                IconButton(onClick = onMarks) { Icon(Icons.Default.Repeat, stringResource(R.string.measure_marks, m.number)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in m.cells) when (c) {
                    is NoteCell -> NoteChip(c.note, ru.partitrainer.ui.components.LocalNotation.current, outOfRange = ru.partitrainer.core.music.Ranges.outOfRange(c.note, voiceKind), active = false, selected = selNote == c.index, onClick = { onNote(c.index) })
                    is RestCell -> RestChip(c.beats, selected = selRest != null && Math.abs(selRest.first - c.start) < 1e-6, onClick = { onRest(c.start, c.beats) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotePanel(vm: EditorViewModel, ui: EditUi) {
    val notation = ru.partitrainer.ui.components.LocalNotation.current
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val ni = ui.selectedNote
        val note = ni?.let { ui.voice?.notes?.getOrNull(it) }
        if (note == null) {
            val r = ui.selectedRest ?: return
            Text(stringResource(R.string.rest_selected, BeatsFormat.format(r.second)), style = MaterialTheme.typography.titleSmall)
            Button(onClick = { vm.insertAtRest() }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.make_note)) }
            return
        }
        var name by remember(ni, note.midi) { mutableStateOf(NoteNames.nameWithOctave(note.midi, Notation.LATIN, SpellingState.flats)) }
        var lyric by remember(ni, note.lyric) { mutableStateOf(note.lyric ?: "") }
        var bad by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(NoteNames.nameWithOctave(note.midi, notation, SpellingState.flats), style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 64.dp))
            for ((label, d) in listOf("−2" to -2, "−1" to -1, "+1" to 1, "+2" to 2)) {
                OutlinedButton(onClick = { vm.shiftPitch(d) }, Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp), contentPadding = PaddingValues(4.dp)) { Text(label) }
            }
            OutlinedTextField(name, { name = it; bad = false }, Modifier.width(110.dp), singleLine = true, isError = bad, label = { Text(stringResource(R.string.pitch_name)) },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { bad = !vm.setPitchByName(name) }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
            IconButton(onClick = vm::playSelected) { Icon(Icons.Default.VolumeUp, stringResource(R.string.play_note)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0)) {
                FilterChip(Math.abs(note.beats - d) < 1e-6, { vm.setDuration(d) }, label = { Text(BeatsFormat.format(d)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = vm::toRest, label = { Text(stringResource(R.string.to_rest)) }, modifier = Modifier.heightIn(min = 48.dp))
            AssistChip(onClick = vm::tieWithNext, label = { Text(stringResource(R.string.tie_next)) }, modifier = Modifier.heightIn(min = 48.dp))
            AssistChip(onClick = vm::split, label = { Text(stringResource(R.string.split)) }, modifier = Modifier.heightIn(min = 48.dp))
            AssistChip(onClick = vm::delete, label = { Text(stringResource(R.string.delete)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
        OutlinedTextField(lyric, { lyric = it; vm.setLyric(it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.syllable)) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkDialog(number: Int, mark: MeasureMark, onDismiss: () -> Unit, onSave: (MeasureMark) -> Unit) {
    var m by remember(mark) { mutableStateOf(mark) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.measure_marks_title, number)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                @Composable fun check(label: Int, v: Boolean, set: (Boolean) -> Unit) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { set(!v) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(v, null); Spacer(Modifier.width(8.dp)); Text(stringResource(label))
                    }
                }
                check(R.string.mark_repeat_start, m.repeatStart) { m = m.copy(repeatStart = it) }
                check(R.string.mark_repeat_end, m.repeatEnd) { m = m.copy(repeatEnd = it) }
                check(R.string.mark_segno, m.segno) { m = m.copy(segno = it) }
                check(R.string.mark_coda, m.coda) { m = m.copy(coda = it) }
                check(R.string.mark_to_coda, m.toCoda) { m = m.copy(toCoda = it) }
                check(R.string.mark_fine, m.fine) { m = m.copy(fine = it) }
                Text(stringResource(R.string.mark_volta), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in 1..3) FilterChip(n in m.volta, { m = m.copy(volta = if (n in m.volta) m.volta - n else (m.volta + n).sorted()) }, label = { Text("$n.") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                Text(stringResource(R.string.mark_jump), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(m.jump == null, { m = m.copy(jump = null) }, label = { Text(stringResource(R.string.none)) }, modifier = Modifier.heightIn(min = 48.dp))
                    for (j in Jump.entries) FilterChip(m.jump == j, { m = m.copy(jump = j) }, label = { Text(j.label) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(m) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Миниатюра страницы-источника (PDF или фото) для сверки с нотами; null, если источника нет или он не читается. */
private suspend fun loadSourceThumb(p: ru.partitrainer.data.Project?): Bitmap? {
    val path = p?.sourcePath ?: return null
    val renderer = PageRenderer(File(path), p.sourceType == "PDF")
    return try {
        runCatching { renderer.thumbnail(p.pageIndex, 1100, p.rotation) }.getOrNull()
    } finally {
        renderer.close()
    }
}
