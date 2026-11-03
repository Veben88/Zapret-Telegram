package ru.partitrainer.ui.screens

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.partitrainer.AppContainer
import ru.partitrainer.audio.MicFrame
import ru.partitrainer.audio.MicPitchSource
import ru.partitrainer.audio.PlayState
import ru.partitrainer.audio.PlayTrack
import ru.partitrainer.audio.Synth
import ru.partitrainer.core.audio.Judge
import ru.partitrainer.core.audio.PhraseResult
import ru.partitrainer.core.audio.PitchMath
import ru.partitrainer.core.audio.PitchSample
import ru.partitrainer.core.audio.SingEvaluator
import ru.partitrainer.core.audio.Verdict
import ru.partitrainer.core.model.Note
import ru.partitrainer.core.model.Voice
import ru.partitrainer.core.model.VoiceKind
import ru.partitrainer.core.music.MidiRange
import ru.partitrainer.core.notation.Clef
import ru.partitrainer.core.notation.PlayMap
import ru.partitrainer.ui.components.ScoreSpec
import ru.partitrainer.core.music.Phrase
import ru.partitrainer.core.music.PhraseSplitter
import ru.partitrainer.core.music.Ranges
import ru.partitrainer.core.music.Transpose
import ru.partitrainer.core.structure.Expansion
import ru.partitrainer.core.structure.Repeats
import ru.partitrainer.data.Project
import ru.partitrainer.data.Settings
import kotlin.math.roundToInt

data class Heard(val frequency: Double, val nearestMidi: Int, val cents: Double, val verdict: Verdict?, val targetMidi: Int?)

data class SingState(val phrase: Int, val activeIndex: Int, val countIn: Boolean)

/** Всё, что вычисляется из проекта и настроек (нотный текст после раскрытия повторов и транспонирования). */
class Derived(
    val voice: Voice?, val others: List<Voice>, val phrases: List<Phrase>, val range: MidiRange?,
    val baseRange: MidiRange?, val expansion: Expansion, val hasStructure: Boolean, val bpm: Double,
    /** Нотный стан выбранного голоса «как напечатано» и карта повторов для каретки. */
    val spec: ScoreSpec? = null,
    val playMap: PlayMap = PlayMap(emptyList(), null),
)

data class TrainerUi(
    val project: Project? = null,
    val settings: Settings = Settings(),
    val voiceId: String? = null,
    val transpose: Int = 0,
    val tempo: Int = 85,
    val expand: Boolean = false,
    val measuresPerPhrase: Int = 2,
    val selectedNote: Int? = null,
    val playing: Boolean = false,
    val loopPhrase: Int? = null,
    val micOn: Boolean = false,
    val micError: String? = null,
    val heard: Heard? = null,
    val singing: SingState? = null,
    val singResult: PhraseResult? = null,
    val singResultPhrase: Int? = null,
    val headphonesNeeded: Boolean = false,
    val pausedByFocus: Boolean = false,
)

class TrainerViewModel(private val c: AppContainer, private val projectId: Long) : ViewModel() {
    private val _ui = MutableStateFlow(TrainerUi())
    val ui: StateFlow<TrainerUi> = _ui
    private val _derived = MutableStateFlow(Derived(null, emptyList(), emptyList(), null, null, Expansion(emptyList(), false, emptyList()), false, 4.0))
    val derived: StateFlow<Derived> = _derived
    val position = c.playback.position

    private var micJob: Job? = null
    private var singJob: Job? = null
    private val singSamples = ArrayList<PitchSample>()
    @Volatile private var singStartNs = 0L
    @Volatile private var singStartBeat = 0.0
    @Volatile private var singSecPerBeat = 0.6
    @Volatile private var singActive = false

    init {
        c.playback.bind(viewModelScope)
        viewModelScope.launch {
            val p = c.projects.load(projectId) ?: return@launch
            val s = c.settings.flow.first()
            _ui.value = TrainerUi(project = p, settings = s, voiceId = p.selectedVoiceId ?: p.score.voices.firstOrNull()?.id,
                tempo = p.score.tempo ?: 85, expand = p.expandRepeats, measuresPerPhrase = s.measuresPerPhrase)
            ru.partitrainer.ui.components.SpellingState.flats = p.score.keyFifths < 0
            recompute()
            c.settings.flow.collect { ns -> _ui.value = _ui.value.copy(settings = ns); c.synth.masterVolume = ns.volume; c.synth.sustain = ns.sustain; recompute() }
        }
        viewModelScope.launch { c.playback.state.collect { st -> _ui.value = _ui.value.copy(playing = st == PlayState.PLAYING, pausedByFocus = st == PlayState.PAUSED_BY_FOCUS) } }
    }

    private fun recompute() {
        val u = _ui.value
        val p = u.project ?: return
        val marks = p.marks
        val hasStructure = Repeats.hasStructure(marks)
        val exp = Repeats.expandRepeats(marks)
        val doExpand = u.expand && hasStructure
        fun prep(v: Voice): Voice {
            val n = if (doExpand) Repeats.applyToNotes(v.notes, marks, exp.order) else v.notes
            return v.copy(notes = Transpose.notes(n, u.transpose))
        }
        val voices = p.score.voices.map(::prep)
        val voice = voices.firstOrNull { it.id == u.voiceId } ?: voices.firstOrNull()
        val bpm = p.score.beatsPerMeasure
        val phrases = voice?.let { PhraseSplitter.split(it.notes, bpm, u.measuresPerPhrase, if (doExpand) emptySet() else p.lineBreaks) }.orEmpty()
        val base = p.score.voices.firstOrNull { it.id == voice?.id }?.notes?.let { Ranges.actual(it) }
        val printed = p.score.voices.firstOrNull { it.id == voice?.id }
        val spec = printed?.let {
            ScoreSpec(
                notes = Transpose.notes(it.notes, u.transpose), marks = marks, fifths = p.score.keyFifths, timeNum = p.score.timeNum, timeDen = p.score.timeDen,
                beatsPerMeasure = bpm, clef = if (it.kind == VoiceKind.BASS) Clef.BASS else Clef.TREBLE,
                writtenShift = if (it.kind == VoiceKind.TENOR) 12 else 0,
            )
        }
        _derived.value = Derived(voice, voices.filter { it.id != voice?.id }, phrases, voice?.notes?.let { Ranges.actual(it) }, base, exp, hasStructure, bpm,
            spec, PlayMap(marks, if (doExpand) exp.order else null))
    }

    private fun persist() {
        val u = _ui.value
        val p = u.project ?: return
        val np = p.copy(selectedVoiceId = u.voiceId, expandRepeats = u.expand)
        _ui.value = u.copy(project = np)
        viewModelScope.launch { c.projects.save(np) }
    }

    private inline fun change(f: (TrainerUi) -> TrainerUi) { stop(); _ui.value = f(_ui.value); recompute() }

    fun selectVoice(id: String) { change { it.copy(voiceId = id, selectedNote = null) }; persist() }
    fun setTranspose(v: Int) = change { it.copy(transpose = v.coerceIn(Transpose.MIN, Transpose.MAX)) }
    fun setTempo(v: Int) { _ui.value = _ui.value.copy(tempo = v.coerceIn(40, 200)) }
    fun resetTempo() { _ui.value = _ui.value.copy(tempo = _ui.value.project?.score?.tempo ?: 85) }
    fun setExpand(v: Boolean) { change { it.copy(expand = v, selectedNote = null) }; persist() }
    fun setPhraseSize(m: Int) = change { it.copy(measuresPerPhrase = m) }

    fun updateSettings(f: (Settings) -> Settings) = viewModelScope.launch { c.settings.update(f) }

    // ---- Воспроизведение ----
    private fun kind() = c.synth.kindFor(_ui.value.settings.timbre)
    private fun tracks(): List<PlayTrack> {
        val d = _derived.value; val s = _ui.value.settings
        val main = d.voice?.let { PlayTrack(it.notes, 1f, true) }
        val others = if (s.playOthers) d.others.map { PlayTrack(it.notes, s.otherVoicesVolume, false) } else emptyList()
        return listOfNotNull(main) + others
    }

    private fun refreshHeadphones() {
        _ui.value = _ui.value.copy(headphonesNeeded = _ui.value.micOn && !headphonesConnected())
    }

    fun playAll() = playFrom(0.0)

    /** Играет от позиции [beat] (в долях партии после раскрытия повторов) до конца; без отсчёта, если это перемотка. */
    fun playFrom(beat: Double, withCountIn: Boolean = beat <= 0.0) {
        val d = _derived.value
        val end = d.voice?.notes?.maxOfOrNull { it.endBeat } ?: return
        if (beat >= end) return
        c.synth.masterVolume = _ui.value.settings.volume; c.synth.sustain = _ui.value.settings.sustain
        _ui.value = _ui.value.copy(loopPhrase = null)
        refreshHeadphones()
        c.playback.play(tracks(), beat.coerceAtLeast(0.0), end, _ui.value.tempo, kind(), false, if (withCountIn) countIn() else 0)
    }

    fun playPhrase(i: Int, loop: Boolean) {
        val ph = _derived.value.phrases.getOrNull(i) ?: return
        c.synth.masterVolume = _ui.value.settings.volume; c.synth.sustain = _ui.value.settings.sustain
        _ui.value = _ui.value.copy(loopPhrase = if (loop) i else null)
        refreshHeadphones()
        c.playback.play(tracks(), ph.startBeat, ph.endBeat, _ui.value.tempo, kind(), loop, if (loop) 0 else countIn())
    }

    private fun countIn() = if (_ui.value.settings.countIn) (_ui.value.project?.score?.timeNum ?: 4) else 0

    fun stop() {
        c.playback.stop()
        singJob?.cancel(); singJob = null; singActive = false
        _ui.value = _ui.value.copy(loopPhrase = null, singing = null)
    }

    fun tapNote(index: Int) {
        val n = _derived.value.voice?.notes?.getOrNull(index) ?: return
        _ui.value = _ui.value.copy(selectedNote = index)
        playMidi(n.midi)
    }

    fun tapKey(midi: Int) { _ui.value = _ui.value.copy(selectedNote = null); playMidi(midi) }

    private fun playMidi(midi: Int) {
        c.synth.masterVolume = _ui.value.settings.volume; c.synth.sustain = _ui.value.settings.sustain
        refreshHeadphones()
        c.playback.tap(midi, kind())
    }

    // ---- Микрофон ----
    fun setMic(on: Boolean) {
        micJob?.cancel(); micJob = null
        if (!on) { singJob?.cancel(); singActive = false; _ui.value = _ui.value.copy(micOn = false, heard = null, singing = null, headphonesNeeded = false); return }
        _ui.value = _ui.value.copy(micOn = true, micError = null)
        refreshHeadphones()
        micJob = viewModelScope.launch {
            try {
                MicPitchSource(c.context).frames().collect { onFrame(it) }
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(micOn = false, micError = e.message ?: "Микрофон недоступен")
            }
        }
    }

    private fun targetMidi(): Int? {
        val u = _ui.value
        val notes = _derived.value.voice?.notes ?: return null
        u.singing?.let { s -> if (!s.countIn) return notes.getOrNull(s.activeIndex)?.midi }
        position.value.activeMain.firstOrNull()?.let { return notes.getOrNull(it)?.midi }
        return u.selectedNote?.let { notes.getOrNull(it)?.midi }
    }

    private fun onFrame(f: MicFrame) {
        val u = _ui.value
        val now = SystemClock.elapsedRealtimeNanos()
        val freq = f.frequency
        if (singActive) {
            val beat = singStartBeat + (now - singStartNs) / 1e9 / singSecPerBeat - 0.046 / singSecPerBeat
            singSamples += PitchSample(beat, freq)
        }
        if (freq == null) { if (u.heard != null) _ui.value = u.copy(heard = null); return }
        val nearest = PitchMath.freqToMidiFloat(freq).roundToInt()
        val target = targetMidi()
        val cents = if (target != null) PitchMath.deviationCents(freq, target, u.settings.matchOctave) else PitchMath.centsBetween(freq, nearest)
        val verdict = target?.let { Judge.verdict(cents, u.settings.toleranceCents.toDouble()) }
        _ui.value = u.copy(heard = Heard(freq, nearest, cents, verdict, target))
    }

    fun singPhrase(i: Int) {
        val d = _derived.value
        val ph = d.phrases.getOrNull(i) ?: return
        val notes = d.voice!!.notes.subList(ph.from, ph.to)
        if (!_ui.value.micOn) setMic(true)
        c.playback.stop(); singJob?.cancel()
        val s = _ui.value.settings
        val secPerBeat = 60.0 / _ui.value.tempo
        _ui.value = _ui.value.copy(singResult = null, singing = SingState(i, -1, true), loopPhrase = null)
        singJob = viewModelScope.launch {
            c.synth.masterVolume = s.volume
            if (s.countIn) {
                val beats = _ui.value.project?.score?.timeNum ?: 4
                for (b in 0 until beats) { c.synth.noteOn(if (b == 0) 88 else 84, 0.8f, Synth.Kind.CLICK); delay((secPerBeat * 1000).toLong()) }
            }
            singSamples.clear()
            singSecPerBeat = secPerBeat; singStartBeat = ph.startBeat; singStartNs = SystemClock.elapsedRealtimeNanos(); singActive = true
            while (true) {
                val beat = ph.startBeat + (SystemClock.elapsedRealtimeNanos() - singStartNs) / 1e9 / secPerBeat
                if (beat >= ph.endBeat) break
                val idx = notes.indexOfFirst { beat >= it.startBeat && beat < it.endBeat }
                _ui.value = _ui.value.copy(singing = SingState(i, if (idx >= 0) ph.from + idx else -1, false))
                delay(16)
            }
            delay(350) // хвост: последние кадры микрофона
            singActive = false
            val result = SingEvaluator.evaluate(notes, singSamples.toList(), s.toleranceCents.toDouble(), s.matchOctave)
            _ui.value = _ui.value.copy(singing = null, singResult = result, singResultPhrase = i)
        }
    }

    fun dismissResult() { _ui.value = _ui.value.copy(singResult = null) }

    private fun headphonesConnected(): Boolean {
        val am = c.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            when (it.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* TYPE_BLE_HEADSET */ -> true
                else -> false
            }
        }
    }

    override fun onCleared() {
        micJob?.cancel(); singJob?.cancel(); c.playback.stop()
    }
}
