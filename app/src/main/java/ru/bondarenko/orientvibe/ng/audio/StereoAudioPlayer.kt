package ru.bondarenko.orientvibe.ng.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.sin

class StereoAudioPlayer private constructor(context: Context) {

    private var audioTrack: AudioTrack? = null
    private val lock = Any()
    private val maxVolume = 0.45f
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE

    // Переиспользуемый буфер на 2 секунды стереозвука (~384 КБ выделяется один раз при старте)
    private val reusableStereoBuffer = ShortArray(MAX_BUFFER_FRAMES * 2)

    // Кэшируем неизменяемые конфигурации аудиопотока
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val audioFormat: AudioFormat = AudioFormat.Builder()
        .setSampleRate(sampleRate)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()

    private val appContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.applicationContext.createAttributionContext("audioPlayback")
    } else {
        context.applicationContext
    }

    fun playTone(
        frequencyHz: Int = 440,
        durationMs: Long = 200,
        volume: Float = maxVolume,
        leftChannelBalance: Float = 0.5f
    ) {
        if (frequencyHz < 100 || frequencyHz > 3000) return

        val clampedVolume = volume.coerceIn(0.0f, 1.0f) * maxVolume
        val balance = leftChannelBalance.coerceIn(0.0f, 1.0f)

        val frames = ((sampleRate.toLong() * durationMs) / 1000)
            .toInt()
            .coerceIn(1, MAX_BUFFER_FRAMES)
        val fadeFrames = ((sampleRate * 15L) / 1000).toInt().coerceAtMost(frames / 2)

        val phaseInc = (frequencyHz.toDouble() * 2.0 * Math.PI) / sampleRate.toDouble()
        val leftVol = balance * clampedVolume
        val rightVol = (1.0f - balance) * clampedVolume

        synchronized(lock) {
            for (i in 0 until frames) {
                val fadeMultiplier = when {
                    i < fadeFrames -> i.toDouble() / fadeFrames
                    i >= frames - fadeFrames -> (frames - 1 - i).toDouble() / fadeFrames
                    else -> 1.0
                }

                val sample = sin(i.toDouble() * phaseInc) * fadeMultiplier * 32767.0
                reusableStereoBuffer[i * 2] = (sample * leftVol).toInt().coerceIn(-32768, 32767).toShort()
                reusableStereoBuffer[i * 2 + 1] = (sample * rightVol).toInt().coerceIn(-32768, 32767).toShort()
            }

            playBufferLocked(frames * 2)
        }
    }

    fun playTrill(
        baseFreqHz: Int = 1800,
        modulationFreqHz: Float = 12.0f,
        durationMs: Long = 500,
        volume: Float = maxVolume,
        leftChannelBalance: Float = 0.5f
    ) {
        if (baseFreqHz < 100 || baseFreqHz > 3000) return

        val clampedVolume = volume.coerceIn(0.0f, 1.0f) * maxVolume
        val balance = leftChannelBalance.coerceIn(0.0f, 1.0f)

        val frames = ((sampleRate.toLong() * durationMs) / 1000)
            .toInt()
            .coerceIn(1, MAX_BUFFER_FRAMES)
        val fadeFrames = ((sampleRate * 15L) / 1000).toInt().coerceAtMost(frames / 2)

        val freqPhaseInc = (baseFreqHz.toDouble() * 2.0 * Math.PI) / sampleRate.toDouble()
        val modPhaseInc = (modulationFreqHz.toDouble() * 2.0 * Math.PI) / sampleRate.toDouble()

        val leftVol = balance * clampedVolume
        val rightVol = (1.0f - balance) * clampedVolume

        synchronized(lock) {
            for (i in 0 until frames) {
                val fadeMultiplier = when {
                    i < fadeFrames -> i.toDouble() / fadeFrames
                    i >= frames - fadeFrames -> (frames - 1 - i).toDouble() / fadeFrames
                    else -> 1.0
                }

                val envelope = 0.575 + 0.425 * sin(i.toDouble() * modPhaseInc)
                val sample = sin(i.toDouble() * freqPhaseInc) * envelope * fadeMultiplier * 32767.0

                reusableStereoBuffer[i * 2] = (sample * leftVol).toInt().coerceIn(-32768, 32767).toShort()
                reusableStereoBuffer[i * 2 + 1] = (sample * rightVol).toInt().coerceIn(-32768, 32767).toShort()
            }

            playBufferLocked(frames * 2)
        }
    }

    fun stop() {
        synchronized(lock) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {
            }
            audioTrack = null
        }
    }

    fun release() {
        stop()
    }

    // Вызывается строго внутри synchronized(lock)
    private fun playBufferLocked(sampleCount: Int) {
        val lengthBytes = sampleCount * 2
        val staticTrack = initAudioTrackStatic(lengthBytes) ?: return

        staticTrack.write(reusableStereoBuffer, 0, sampleCount)

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = staticTrack
        staticTrack.play()
    }

    private fun initAudioTrackStatic(bufferSizeInBytes: Int): AudioTrack? {
        if (bufferSizeInBytes <= 0 || bufferSizeInBytes > MAX_BUFFER_FRAMES * 4) return null

        return try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSizeInBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                builder.setContext(appContext)
            }

            builder.build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "initAudioTrackStatic: buffer size $bufferSizeInBytes rejected", e)
            null
        }
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 48000
        // Максимум 2 секунды на один сигнал (48000 * 2)
        private const val MAX_BUFFER_FRAMES = DEFAULT_SAMPLE_RATE * 2
        private const val TAG = "StereoAudioPlayer"

        @Volatile
        private var instance: StereoAudioPlayer? = null

        @JvmStatic
        fun getInstance(context: Context): StereoAudioPlayer {
            return instance ?: synchronized(this) {
                instance ?: StereoAudioPlayer(context).also { instance = it }
            }
        }
    }
}
