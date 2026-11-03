package ru.partitrainer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import ru.partitrainer.data.Timbre
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Собственный синтезатор на AudioTrack (без сэмплов и внешних библиотек).
 * Тембры: «пианино» (две таблицы с разным затуханием), «ааа» (формантный спектр + вибрато), щелчок метронома.
 * Каждая нота — отдельный голос с плавной атакой и релизом, поэтому быстрые нажатия не дают щелчков:
 * повторная нота гасит предыдущую короткой (≈15 мс) рампой, а не обрывает её.
 */
class Synth {
    enum class Kind { PIANO, AAH, CLICK }

    private sealed interface Cmd {
        data class On(val handle: Long, val midi: Int, val gain: Float, val kind: Kind) : Cmd
        data class Off(val handle: Long) : Cmd
        data object AllOff : Cmd
    }

    private class Voice(
        val handle: Long, val key: Int, val kind: Kind, val gain: Float, val sampleRate: Int,
        val low: FloatArray, val high: FloatArray?, val inc: Double, val sustain: Float,
    ) {
        var phase = 0.0
        var t = 0L                    // сэмплов с начала
        var level = 0f                // огибающая атаки/релиза 0..1
        var releasing = false
        var relInc = 0f               // шаг прогресса релиза 0..1 за сэмпл
        var relPos = 0f
        var relStart = 1f
        val attackStep = when (kind) { Kind.AAH -> 1f / (0.05f * sampleRate); Kind.PIANO -> 1f / (0.006f * sampleRate); Kind.CLICK -> 1f }
        // Сустейн удлиняет «хвост» после конца ноты (как педаль): звук не обрывается, а плавно затухает в паузе.
        val releaseSamples = (when (kind) { Kind.AAH -> 0.12f + 0.55f * sustain; Kind.PIANO -> 0.09f + 1.8f * sustain; Kind.CLICK -> 0.01f }) * sampleRate
        val tauLow = when (kind) { Kind.PIANO -> (2.4 - 0.025 * (key - 48)).coerceIn(0.5, 3.0) * (1.0 + 1.2 * sustain) * sampleRate; else -> 1e12 }
        val tauHigh = tauLow * 0.22
        var done = false

        fun release(fast: Boolean = false) {
            if (releasing) return
            releasing = true
            val samples = if (fast) 0.015f * sampleRate else releaseSamples
            relStart = level.coerceAtLeast(0.001f)
            relPos = 0f
            relInc = 1f / samples
        }
    }

    private val cmds = ConcurrentLinkedQueue<Cmd>()
    private val handles = AtomicLong(1)
    private val lock = Object()
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile var masterVolume: Float = 0.8f
    @Volatile var duck: Float = 1f
    /** Сустейн 0..1: длина затухания после конца ноты. */
    @Volatile var sustain: Float = 0.5f

    private val sampleRate = 44100
    private val tables = HashMap<Long, FloatArray>()

    fun noteOn(midi: Int, gain: Float = 1f, kind: Kind = Kind.PIANO): Long {
        val h = handles.getAndIncrement()
        cmds.add(Cmd.On(h, midi, gain, kind))
        wake()
        return h
    }

    fun noteOff(handle: Long) { cmds.add(Cmd.Off(handle)); wake() }
    fun allOff() { cmds.add(Cmd.AllOff); wake() }

    fun kindFor(t: Timbre) = if (t == Timbre.AAH) Kind.AAH else Kind.PIANO

    private fun wake() {
        if (thread == null) startThread()
        synchronized(lock) { lock.notifyAll() }
    }

    @Synchronized private fun startThread() {
        if (thread != null) return
        running = true
        thread = Thread({ loop() }, "synth").also { it.isDaemon = true; it.start() }
    }

    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frames = 256
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minBuf, frames * 4 * 2))
            .build()
        val voices = ArrayList<Voice>()
        val buf = FloatArray(frames)
        var idleBlocks = 0
        var playing = false
        try {
            while (running) {
                // команды
                while (true) {
                    when (val c = cmds.poll() ?: break) {
                        is Cmd.On -> {
                            voices.filter { it.key == c.midi && !it.releasing && it.kind == c.kind }.forEach { it.release(fast = true) }
                            if (voices.size >= 16) voices.firstOrNull { !it.releasing }?.release(fast = true)
                            voices += makeVoice(c)
                        }
                        is Cmd.Off -> voices.firstOrNull { it.handle == c.handle }?.release()
                        Cmd.AllOff -> voices.forEach { it.release(fast = true) }
                    }
                }
                if (voices.isEmpty()) {
                    idleBlocks++
                    if (idleBlocks > sampleRate / frames) { // ~1 c тишины — засыпаем
                        if (playing) { track.pause(); track.flush(); playing = false }
                        synchronized(lock) { if (cmds.isEmpty() && running) lock.wait(2000) }
                        continue
                    }
                } else idleBlocks = 0
                if (!playing) { track.play(); playing = true }

                java.util.Arrays.fill(buf, 0f)
                val vol = masterVolume * duck
                for (v in voices) render(v, buf)
                for (i in 0 until frames) buf[i] = softClip(buf[i] * vol * 0.6f)
                track.write(buf, 0, frames, AudioTrack.WRITE_BLOCKING)
                voices.removeAll { it.done }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun softClip(x: Float): Float = tanh(x.toDouble()).toFloat()

    private fun render(v: Voice, out: FloatArray) {
        val n = out.size
        val tableSize = v.low.size
        val vibrato = v.kind == Kind.AAH
        for (i in 0 until n) {
            // огибающая
            if (v.releasing) {
                v.relPos += v.relInc
                if (v.relPos >= 1f) { v.done = true; return }
                val k = 1f - v.relPos
                v.level = v.relStart * k * k      // квадратичный спад: тихий длинный хвост без «ступеньки»
            } else if (v.level < 1f) {
                v.level = (v.level + v.attackStep).coerceAtMost(1f)
            }
            var inc = v.inc
            if (vibrato) {
                val sec = v.t.toDouble() / v.sampleRate
                val depth = 0.004 * (sec - 0.25).coerceIn(0.0, 0.5) * 2 // вибрато нарастает после 250 мс
                inc *= 1.0 + depth * sin(2 * PI * 5.5 * sec)
            }
            v.phase += inc
            if (v.phase >= 1.0) v.phase -= 1.0
            val pos = v.phase * tableSize
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val i1 = if (i0 + 1 == tableSize) 0 else i0 + 1
            var s = v.low[i0] + (v.low[i1] - v.low[i0]) * frac
            when (v.kind) {
                Kind.PIANO -> {
                    val h = v.high!!
                    val hs = (h[i0] + (h[i1] - h[i0]) * frac) * 0.5f
                    val tt = v.t.toDouble()
                    s = s * exp(-tt / v.tauLow).toFloat() + hs * exp(-tt / v.tauHigh).toFloat()
                }
                Kind.CLICK -> s *= exp(-v.t.toDouble() / (0.006 * v.sampleRate)).toFloat()
                Kind.AAH -> {}
            }
            out[i] += s * v.level * v.gain
            v.t++
            if (v.kind == Kind.CLICK && v.t > 0.05 * v.sampleRate) { v.done = true; return }
        }
    }

    private fun makeVoice(c: Cmd.On): Voice {
        val freq = 440.0 * Math.pow(2.0, (c.midi - 69) / 12.0)
        val (low, high) = tablesFor(c.kind, c.midi, freq)
        val f = if (c.kind == Kind.CLICK) 1500.0 else freq
        return Voice(c.handle, c.midi, c.kind, c.gain, sampleRate, low, high, f / sampleRate, sustain.coerceIn(0f, 1f))
    }

    private fun tablesFor(kind: Kind, midi: Int, freq: Double): Pair<FloatArray, FloatArray?> {
        val key = kind.ordinal * 1000L + (if (kind == Kind.CLICK) 0 else midi)
        val maxH = ((sampleRate * 0.45) / (if (kind == Kind.CLICK) 1500.0 else freq)).toInt().coerceIn(1, 40)
        return when (kind) {
            Kind.PIANO -> {
                val lo = tables.getOrPut(key) { build(maxH) { k -> if (k <= 3) 1.0 / k else 0.0 } }
                val hi = tables.getOrPut(key + 500) { build(maxH) { k -> if (k > 3) 0.55 / Math.pow(k.toDouble(), 1.3) else 0.0 } }
                lo to hi
            }
            Kind.AAH -> {
                val t = tables.getOrPut(key) {
                    build(maxH) { k ->
                        val f = k * freq
                        // форманты гласного «а»: ~800, 1200, 2800 Гц
                        val g = gauss(f, 800.0, 200.0) + 0.55 * gauss(f, 1200.0, 250.0) + 0.2 * gauss(f, 2800.0, 400.0)
                        (g + 0.02) / Math.pow(k.toDouble(), 0.6)
                    }
                }
                t to null
            }
            Kind.CLICK -> tables.getOrPut(key) { build(1) { 1.0 } } to null
        }
    }

    private fun gauss(x: Double, mu: Double, sigma: Double) = exp(-0.5 * ((x - mu) / sigma) * ((x - mu) / sigma))

    /** Одна таблица периода с суммой гармоник; нормируется по пику. */
    private fun build(maxH: Int, amp: (Int) -> Double): FloatArray {
        val size = 2048
        val t = DoubleArray(size)
        for (k in 1..maxH) {
            val a = amp(k)
            if (a == 0.0) continue
            for (i in 0 until size) t[i] += a * sin(2 * PI * k * i / size)
        }
        val peak = t.maxOf { Math.abs(it) }.coerceAtLeast(1e-9)
        return FloatArray(size) { (t[it] / peak * 0.5).toFloat() }
    }
}
