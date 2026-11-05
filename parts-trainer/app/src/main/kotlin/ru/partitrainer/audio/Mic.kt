package ru.partitrainer.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import ru.partitrainer.core.audio.PitchSmoother
import ru.partitrainer.core.audio.YinPitchDetector

/** Кадр анализа: [frequency] == null — тишина или тон не найден. */
data class MicFrame(val elapsedNanos: Long, val frequency: Double?, val rms: Double, val clarity: Double)

/**
 * Микрофон → YIN. Источник UNPROCESSED (если поддерживается) или VOICE_RECOGNITION;
 * эхо- и шумоподавление не подключаются. Звук нигде не сохраняется и не отправляется.
 */
class MicPitchSource(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val WINDOW = 4096
        const val HOP = 1024
    }

    private fun chooseSource(): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    /** Требует выданного разрешения RECORD_AUDIO (проверяется вызывающим). */
    @SuppressLint("MissingPermission")
    fun frames(): Flow<MicFrame> = callbackFlow {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(chooseSource(), SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, WINDOW * 4))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            close(IllegalStateException("Микрофон недоступен"))
            return@callbackFlow
        }
        val worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val detector = YinPitchDetector(SAMPLE_RATE)
            val smoother = PitchSmoother(3)
            val ring = FloatArray(WINDOW)
            val shorts = ShortArray(HOP)
            val window = FloatArray(WINDOW)
            var filled = 0
            rec.startRecording()
            val start = SystemClock.elapsedRealtimeNanos()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = rec.read(shorts, 0, HOP)
                    if (n <= 0) break
                    // сдвигаем кольцо
                    System.arraycopy(ring, n, ring, 0, WINDOW - n)
                    for (i in 0 until n) ring[WINDOW - n + i] = shorts[i] / 32768f
                    filled = minOf(WINDOW, filled + n)
                    if (filled < WINDOW) continue
                    System.arraycopy(ring, 0, window, 0, WINDOW)
                    val r = detector.detect(window)
                    val f = smoother.push(r?.frequency)
                    trySend(MicFrame(SystemClock.elapsedRealtimeNanos() - start, if (r != null) f else null, r?.rms ?: 0.0, r?.probability ?: 0.0))
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }, "mic-yin")
        worker.start()
        awaitClose { worker.interrupt() }
    }.flowOn(Dispatchers.Default)
}
