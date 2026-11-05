package ru.partitrainer.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.partitrainer.AppContainer
import ru.partitrainer.audio.PlayTrack
import ru.partitrainer.core.edit.EditHistory
import ru.partitrainer.core.edit.MeasureLayout
import ru.partitrainer.core.edit.MeasureView
import ru.partitrainer.core.edit.NoteEditor
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Voice
import ru.partitrainer.core.model.VoiceKind
import ru.partitrainer.core.music.NoteNames
import ru.partitrainer.core.structure.MeasureMark
import ru.partitrainer.core.voices.VoiceClassifier
import ru.partitrainer.data.Project

data class EditState(val voices: List<Voice>, val marks: List<MeasureMark>, val uncertain: Set<Int>)

data class EditUi(
    val project: Project? = null,
    val state: EditState = EditState(emptyList(), emptyList(), emptySet()),
    val voiceIndex: Int = 0,
    val layout: List<MeasureView> = emptyList(),
    val selectedNote: Int? = null,
    val selectedRest: Pair<Double, Double>? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val playing: Boolean = false,
    val message: String? = null,
) {
    val voice: Voice? get() = state.voices.getOrNull(voiceIndex)
}

class EditorViewModel(private val c: AppContainer, private val projectId: Long) : ViewModel() {
    private val _ui = MutableStateFlow(EditUi())
    val ui: StateFlow<EditUi> = _ui
    private var history: EditHistory<EditState>? = null
    private var editor = NoteEditor { 1 }

    init {
        viewModelScope.launch {
            val p = c.projects.load(projectId) ?: return@launch
            val st = EditState(p.score.voices, p.marks, p.uncertain)
            history = EditHistory(st)
            val idx = p.score.voices.indexOfFirst { it.id == p.selectedVoiceId }.coerceAtLeast(0)
            ru.partitrainer.ui.components.SpellingState.flats = p.score.keyFifths < 0
            editor = NoteEditor.forMarks(p.marks, p.score.beatsPerMeasure)
            _ui.value = EditUi(project = p, state = st, voiceIndex = idx).relayout()
        }
        c.playback.bind(viewModelScope)
    }

    private fun EditUi.relayout(): EditUi {
        val p = project ?: return this
        val v = voice
        return copy(
            layout = MeasureLayout.build(v?.notes.orEmpty(), state.marks, p.score.beatsPerMeasure),
            canUndo = history?.canUndo == true, canRedo = history?.canRedo == true,
        )
    }

    private fun commit(next: EditState, selNote: Int? = _ui.value.selectedNote, selRest: Pair<Double, Double>? = null) {
        history?.push(next)
        editor = NoteEditor.forMarks(next.marks, _ui.value.project!!.score.beatsPerMeasure)
        _ui.value = _ui.value.copy(state = next, selectedNote = selNote, selectedRest = selRest).relayout()
        persist()
    }

    private fun persist(reviewed: Boolean? = null) {
        val u = _ui.value
        val p = u.project ?: return
        val np = p.copy(
            score = p.score.copy(voices = u.state.voices), marks = u.state.marks, uncertain = u.state.uncertain,
            selectedVoiceId = u.voice?.id, reviewed = reviewed ?: p.reviewed,
        )
        _ui.value = u.copy(project = np)
        viewModelScope.launch { c.projects.save(np) }
    }

    private fun mutateNotes(selAfter: Int? = _ui.value.selectedNote, f: (List<Note>) -> List<Note>) {
        val u = _ui.value
        val v = u.voice ?: return
        val notes = f(v.notes)
        if (notes == v.notes) return
        val voices = u.state.voices.mapIndexed { i, x -> if (i == u.voiceIndex) x.copy(notes = notes) else x }
        commit(u.state.copy(voices = voices), selAfter?.coerceAtMost(notes.lastIndex))
    }

    fun selectVoice(i: Int) { _ui.value = _ui.value.copy(voiceIndex = i, selectedNote = null, selectedRest = null).relayout(); persist() }
    fun selectNote(i: Int) { _ui.value = _ui.value.copy(selectedNote = i, selectedRest = null) }
    fun selectRest(start: Double, beats: Double) { _ui.value = _ui.value.copy(selectedNote = null, selectedRest = start to beats) }
    fun clearSelection() { _ui.value = _ui.value.copy(selectedNote = null, selectedRest = null) }

    fun shiftPitch(semi: Int) { val i = _ui.value.selectedNote ?: return; mutateNotes { editor.shiftPitch(it, i, semi) } }
    fun setPitchByName(text: String): Boolean {
        val i = _ui.value.selectedNote ?: return false
        val m = NoteNames.parse(text) ?: return false
        mutateNotes { editor.setPitch(it, i, m) }; return true
    }
    fun setDuration(b: Double) { val i = _ui.value.selectedNote ?: return; mutateNotes { editor.setDuration(it, i, b) } }
    fun toRest() { val i = _ui.value.selectedNote ?: return; val n = _ui.value.voice!!.notes[i]; mutateNotes(null) { editor.toRest(it, i) }; _ui.value = _ui.value.copy(selectedRest = n.startBeat to n.beats) }
    fun delete() { val i = _ui.value.selectedNote ?: return; mutateNotes(null) { editor.delete(it, i) } }
    fun split() { val i = _ui.value.selectedNote ?: return; mutateNotes { editor.split(it, i) } }
    fun tieWithNext() { val i = _ui.value.selectedNote ?: return; mutateNotes { editor.mergeWithNext(it, i) } }
    fun setLyric(t: String) { val i = _ui.value.selectedNote ?: return; mutateNotes { editor.setLyric(it, i, t) } }

    fun insertAtRest(midi: Int = 67) {
        val (start, beats) = _ui.value.selectedRest ?: return
        val before = _ui.value.voice!!.notes
        mutateNotes(null) { editor.insert(it, midi, start, minOf(beats, 1.0).coerceAtLeast(0.25)) }
        val idx = _ui.value.voice!!.notes.indexOfFirst { Math.abs(it.startBeat - start) < 1e-6 }
        if (idx >= 0 && _ui.value.voice!!.notes != before) _ui.value = _ui.value.copy(selectedNote = idx, selectedRest = null)
    }

    fun addAtEnd() {
        val v = _ui.value.voice ?: return
        val last = v.notes.lastOrNull()
        val start = last?.endBeat ?: 0.0
        mutateNotes(null) { editor.insert(it, last?.midi ?: 67, start, 1.0) }
        _ui.value = _ui.value.copy(selectedNote = _ui.value.voice!!.notes.lastIndex)
    }

    fun setKind(kind: VoiceKind) {
        val u = _ui.value
        val voices = u.state.voices.mapIndexed { i, v -> if (i == u.voiceIndex) VoiceClassifier.withKind(v, kind) else v }
        commit(u.state.copy(voices = voices))
    }

    fun setMark(measureIndex: Int, mark: MeasureMark) {
        val u = _ui.value
        if (measureIndex !in u.state.marks.indices) return
        commit(u.state.copy(marks = u.state.marks.mapIndexed { i, m -> if (i == measureIndex) mark else m }))
    }

    fun markChecked(measureIndex: Int) { val u = _ui.value; commit(u.state.copy(uncertain = u.state.uncertain - measureIndex)) }

    fun undo() { history?.let { val s = it.undo(); editor = NoteEditor.forMarks(s.marks, _ui.value.project!!.score.beatsPerMeasure); _ui.value = _ui.value.copy(state = s, selectedNote = null, selectedRest = null).relayout(); persist() } }
    fun redo() { history?.let { val s = it.redo(); editor = NoteEditor.forMarks(s.marks, _ui.value.project!!.score.beatsPerMeasure); _ui.value = _ui.value.copy(state = s, selectedNote = null, selectedRest = null).relayout(); persist() } }

    // --- Проигрывание для сверки на слух ---
    fun playMeasures(from: Int, to: Int) {
        val u = _ui.value
        val v = u.voice ?: return
        val l = u.layout
        if (l.isEmpty()) return
        val a = l[from.coerceIn(0, l.lastIndex)].start
        val b = l[to.coerceIn(0, l.lastIndex)].let { it.start + it.length }
        viewModelScope.launch {
            val s = c.settings.flow.first()
            c.synth.masterVolume = s.volume; c.synth.sustain = s.sustain
            _ui.value = _ui.value.copy(playing = true)
            c.playback.play(listOf(PlayTrack(v.notes, 1f, true)), a, b, u.project?.score?.tempo ?: 85, c.synth.kindFor(s.timbre)) { _ui.value = _ui.value.copy(playing = false) }
        }
    }

    fun playSelected() {
        val u = _ui.value
        val n = u.selectedNote?.let { u.voice?.notes?.getOrNull(it) } ?: return
        viewModelScope.launch { val s = c.settings.flow.first(); c.synth.masterVolume = s.volume; c.synth.sustain = s.sustain; c.playback.tap(n.midi, c.synth.kindFor(s.timbre)) }
    }

    fun stop() { c.playback.stop(); _ui.value = _ui.value.copy(playing = false) }

    fun finish(onDone: (Long) -> Unit) { persist(reviewed = true); onDone(projectId) }

    override fun onCleared() { c.playback.stop() }
}
