package ru.partitrainer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.partitrainer.core.model.Note

/** Дорожка для воспроизведения. [main] — выбранный голос (подсветка в интерфейсе). */
data class PlayTrack(val notes: List<Note>, val gain: Float, val main: Boolean)

data class PlayPosition(val beat: Double = 0.0, val activeMain: Set<Int> = emptySet(), val countInBeat: Int = 0)

enum class PlayState { IDLE, PLAYING, PAUSED, PAUSED_BY_FOCUS }

/**
 * Воспроизведение по таймлайну + аудиофокус.
 * Пауза при звонке/потере фокуса и возобновление при возврате.
 */
class PlaybackEngine(context: Context, private val synth: Synth) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var job: Job? = null
    private var scope: CoroutineScope? = null

    private val _position = MutableStateFlow(PlayPosition())
    val position: StateFlow<PlayPosition> = _position.asStateFlow()
    private val _state = MutableStateFlow(PlayState.IDLE)
    val state: StateFlow<PlayState> = _state.asStateFlow()

    @Volatile private var pausedByFocus = false
    private var lastRequest: Request? = null
    @Volatile private var pausedBeat = 0.0

    private class Request(
        val tracks: List<PlayTrack>, val fromBeat: Double, val toBeat: Double, val bpm: Int,
        val loop: Boolean, val countInBeats: Int, val kind: Synth.Kind, val onFinished: () -> Unit,
    )

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { pausedByFocus = false; stop() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (_state.value == PlayState.PLAYING) {
                pausedByFocus = true
                pausedBeat = _position.value.beat
                job?.cancel(); synth.allOff()
                _state.value = PlayState.PAUSED_BY_FOCUS
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> synth.duck = 0.25f
            AudioManager.AUDIOFOCUS_GAIN -> {
                synth.duck = 1f
                if (pausedByFocus) { pausedByFocus = false; resumeFromPaused() }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() { focusRequest?.let { audio.abandonAudioFocusRequest(it) }; focusRequest = null }

    fun bind(scope: CoroutineScope) { this.scope = scope }

    /** Короткий звук ноты при нажатии на чип/клавишу. */
    fun tap(midi: Int, kind: Synth.Kind) {
        val h = synth.noteOn(midi, 1f, kind)
        scope?.launch { delay(if (kind == Synth.Kind.AAH) 700 else 900); synth.noteOff(h) }
    }

    /** Клавиша нажата: нота звучит, пока её не отпустят ([keyUp]). */
    fun keyDown(midi: Int, kind: Synth.Kind): Long = synth.noteOn(midi, 1f, kind)

    fun keyUp(handle: Long) = synth.noteOff(handle)

    fun play(
        tracks: List<PlayTrack>, fromBeat: Double, toBeat: Double, bpm: Int, kind: Synth.Kind,
        loop: Boolean = false, countInBeats: Int = 0, onFinished: () -> Unit = {},
    ) {
        stop()
        val r = Request(tracks, fromBeat, toBeat, bpm, loop, countInBeats, kind, onFinished)
        lastRequest = r
        if (!requestFocus()) return
        resume(r)
    }

    /** Пауза: звук стихает, позиция и каретка остаются на месте; [resumePlayback] продолжит с этого места. */
    fun pause() {
        if (_state.value != PlayState.PLAYING) return
        pausedBeat = _position.value.beat
        job?.cancel(); job = null
        synth.allOff()
        _state.value = PlayState.PAUSED
        abandonFocus()
    }

    fun resumePlayback() {
        if (_state.value != PlayState.PAUSED) return
        if (!requestFocus()) return
        resumeFromPaused()
    }

    /** Продолжить с места паузы (повторяющуюся фразу — сначала); отсчёт не повторяется. */
    private fun resumeFromPaused() {
        val r = lastRequest ?: return
        resume(if (r.loop) r else Request(r.tracks, pausedBeat.coerceIn(r.fromBeat, r.toBeat), r.toBeat, r.bpm, false, 0, r.kind, r.onFinished))
    }

    private fun resume(r: Request) {
        val sc = scope ?: return
        job?.cancel()
        _state.value = PlayState.PLAYING
        job = sc.launch { run(r) }
    }

    fun stop() {
        pausedByFocus = false
        job?.cancel(); job = null
        synth.allOff()
        _position.value = PlayPosition()
        _state.value = PlayState.IDLE
        abandonFocus()
    }

    private suspend fun run(r: Request) {
        val secPerBeat = 60.0 / r.bpm.coerceIn(30, 240)
        // Событие: (старт, конец, трек, индекс)
        data class Ev(val start: Double, val end: Double, val track: Int, val idx: Int)
        val evs = ArrayList<Ev>()
        r.tracks.forEachIndexed { ti, t ->
            t.notes.forEachIndexed { ni, n ->
                if (n.endBeat > r.fromBeat && n.startBeat < r.toBeat) evs += Ev(maxOf(n.startBeat, r.fromBeat), minOf(n.endBeat, r.toBeat), ti, ni)
            }
        }
        evs.sortBy { it.start }
        try {
            do {
                // Отсчёт
                for (b in 0 until r.countInBeats) {
                    _position.value = PlayPosition(r.fromBeat, emptySet(), b + 1)
                    synth.noteOn(if (b == 0) 88 else 84, 0.8f, Synth.Kind.CLICK)
                    delay((secPerBeat * 1000).toLong())
                }
                val startNs = SystemClock.elapsedRealtimeNanos()
                val handles = HashMap<Int, Long>()      // ключ = индекс события
                var next = 0
                val active = HashMap<Int, Ev>()
                var activeSet: Set<Int> = emptySet()   // подсвеченные ноты главного голоса; пересобирается только при изменении
                var lastEmitNs = 0L
                while (currentCoroutineContext().isActive) {
                    val nowBeat = r.fromBeat + (SystemClock.elapsedRealtimeNanos() - startNs) / 1e9 / secPerBeat
                    var changed = false
                    while (next < evs.size && evs[next].start <= nowBeat) {
                        val e = evs[next]
                        val tr = r.tracks[e.track]
                        handles[next] = synth.noteOn(tr.notes[e.idx].midi, tr.gain, r.kind)
                        active[next] = e
                        next++; changed = true
                    }
                    val iter = active.entries.iterator()
                    while (iter.hasNext()) {
                        val (k, e) = iter.next()
                        if (e.end <= nowBeat) { handles.remove(k)?.let { h -> synth.noteOff(h) }; iter.remove(); changed = true }
                    }
                    if (changed) activeSet = active.values.filter { r.tracks[it.track].main }.map { it.idx }.toSet()
                    // Интерфейсу хватает ~40 кадров/с; звук при этом планируется каждые 4 мс
                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    if (changed || nowNs - lastEmitNs >= 25_000_000L) {
                        _position.value = PlayPosition(nowBeat, activeSet)
                        lastEmitNs = nowNs
                    }
                    if (nowBeat >= r.toBeat && active.isEmpty()) break
                    delay(4)
                }
                // Штатный конец: все ноты уже отпущены, их хвосты (сустейн) дозвучивают сами — allOff() оборвал бы их.
            } while (r.loop && currentCoroutineContext().isActive)
            _position.value = PlayPosition(); _state.value = PlayState.IDLE
            abandonFocus()
            r.onFinished()
        } catch (e: CancellationException) {
            synth.allOff()
            throw e
        }
    }
}
