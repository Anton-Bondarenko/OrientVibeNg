package ru.bondarenko.orientvibe.ng.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Программируемый стерео-плеер для генерации тональных сигналов навигации.
 * Использует AudioTrack с PCM 16-bit для прямого контроля над частотой, длительностью
 * и балансировкой лево/право — позволяет направлять звуковой сигнал в нужный динамик.
 */
class StereoAudioPlayer {

    private var audioTrack: AudioTrack? = null
    private val lock = Any()
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE
    private val MAX_VOLUME: Float = 0.45f // громкость не громче — ориентирование на улице

    companion object {
        private const val TAG = "StereoAudioPlayer"
        private const val DEFAULT_SAMPLE_RATE = 44100

        fun create(): StereoAudioPlayer? {
            return try {
                StereoAudioPlayer()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create StereoAudioPlayer", e)
                null
            }
        }
    }

    private constructor() {
        audioTrack = initAudioTrack()
    }

    // ─── Public API ────────────────────────────────────────────────────────

    /**
     * Воспроизвести чистый тон (синусоида) с балансировкой лево/право.
     *
     * @param frequencyHz частота тона в Герцах (200–2000)
     * @param durationMs длительность в миллисекундах
     * @param volume общая громкость 0.0–1.0
     * @param leftChannelBalance баланс: 0.0 = только правый, 0.5 = оба равны, 1.0 = только левый
     */
    fun playTone(
        frequencyHz: Int = 440,
        durationMs: Long = 200,
        volume: Float = MAX_VOLUME,
        leftChannelBalance: Float = 0.5f,
    ) {
        if (frequencyHz < 100 || frequencyHz > 3000) return

        val clampedVolume = volume.coerceIn(0f, 1f) * MAX_VOLUME
        val balance = leftChannelBalance.coerceIn(0f, 1f)
        val frames = ((durationMs * sampleRate) / 1000).toInt().coerceAtLeast(1)

        // Моно-буфер семплов (потом интерлейвим в L/R)
        val monoBuffer = ShortArray(frames)
        val phaseInc = 2.0 * PI * frequencyHz / sampleRate

        for (i in monoBuffer.indices) {
            val sineVal = sin(i * phaseInc)
            monoBuffer[i] = (sineVal * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }

        // Интерлейвим в stereo (L, R, L, R...) с балансировкой
        val stereoBuffer = ShortArray(frames * 2)
        val leftVol = balance * clampedVolume
        val rightVol = (1f - balance) * clampedVolume
        for (i in monoBuffer.indices) {
            stereoBuffer[i * 2] =
                (monoBuffer[i].toFloat() * leftVol).toInt().coerceIn(-32768, 32767).toShort()
            stereoBuffer[i * 2 + 1] =
                (monoBuffer[i].toFloat() * rightVol).toInt().coerceIn(-32768, 32767).toShort()
        }

        playBuffer(stereoBuffer, frames)
    }

    /**
     * Воспроизвести звуковую трель (AM-модуляция синусоиды).
     * Используется для сигнала "прибыл на КП" (<20м).
     */
    fun playTrill(
        baseFreqHz: Int = 600,
        modulationFreqHz: Float = 8f,
        durationMs: Long = 500,
        volume: Float = MAX_VOLUME,
        leftChannelBalance: Float = 0.5f,
    ) {
        if (baseFreqHz < 100 || baseFreqHz > 3000) return

        val clampedVolume = volume.coerceIn(0f, 1f) * MAX_VOLUME
        val balance = leftChannelBalance.coerceIn(0f, 1f)
        val frames = ((durationMs * sampleRate) / 1000).toInt().coerceAtLeast(1)

        val monoBuffer = ShortArray(frames)
        val freqPhaseInc = 2.0 * PI * baseFreqHz / sampleRate
        val modPeriodFrames = (sampleRate / modulationFreqHz).toDouble()

        for (i in monoBuffer.indices) {
            // Огибающая: от 0 до 1 и обратно по синусу, частота модуляции
            val modPhase = ((i % modPeriodFrames.toInt()) / modPeriodFrames) * 2.0 * PI
            val envelope = 0.5f + 0.5f * sin(modPhase) // 0..1

            val sineVal = sin(i * freqPhaseInc)
            monoBuffer[i] = (sineVal * envelope * 32767f * clampedVolume)
                .toInt()
                .coerceIn(-32768, 32767)
                .toShort()
        }

        // Интерлейвим stereo
        val stereoBuffer = ShortArray(frames * 2)
        val leftVol = balance
        val rightVol = (1f - balance)
        for (i in monoBuffer.indices) {
            stereoBuffer[i * 2] =
                (monoBuffer[i].toFloat() * leftVol).toInt().coerceIn(-32768, 32767).toShort()
            stereoBuffer[i * 2 + 1] =
                (monoBuffer[i].toFloat() * rightVol).toInt().coerceIn(-32768, 32767).toShort()
        }

        playBuffer(stereoBuffer, frames)
    }

    /** Остановить текущее воспроизведение. */
    fun stop() {
        synchronized(lock) {
            audioTrack?.let {
                it.stop()
                it.release()
                audioTrack = null
            }
        }
    }

    /** Освободить все ресурсы. */
    fun release() {
        synchronized(lock) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {
            }
            audioTrack = null
        }
    }

    // ─── Internals ─────────────────────────────────────────────────────────

    private fun playBuffer(pcm: ShortArray, frameCount: Int) {
        synchronized(lock) {
            // Останавливаем предыдущее воспроизведение
            audioTrack?.let {
                try {
                    it.stop()
                } catch (_: Exception) {
                }
            }

            var track = audioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                track = initAudioTrack()
                if (track == null) return
                audioTrack = track
            }

            try {
                track.play()
                track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
            } catch (e: Exception) {
                Log.w(TAG, "Error playing audio", e)
                // Пересоздаём при ошибке
                track.release()
                audioTrack = initAudioTrack()
            }
        }
    }

    private fun initAudioTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0 || minBuf == AudioTrack.ERROR_BAD_VALUE || minBuf == AudioTrack.ERROR) {
            return null
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        return AudioTrack(
            audioAttributes,
            format,
            minBuf * 4, // 4 буфера для плавности
            AudioTrack.MODE_STREAM,
            0 // позиция старта = 0 (не автостарт — запускаем вручную)
        )
    }
}
