package ru.bondarenko.orientvibe.ng.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.sin

class StereoAudioPlayer private constructor() {

    private var audioTrack: AudioTrack? = null
    private val lock = Any()
    private val maxVolume = 0.45f
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE

    init {
        this.audioTrack = initAudioTrack()
    }

    fun playTone(
        frequencyHz: Int = 440,
        durationMs: Long = 200,
        volume: Float = maxVolume,
        leftChannelBalance: Float = 0.5f
    ) {
        if (frequencyHz < 100 || frequencyHz > 3000) return

        Log.d(TAG, "playTone START f=${frequencyHz}Hz dur=${durationMs}ms vol=$volume bal=$leftChannelBalance")

        val clampedVolume = volume.coerceIn(0.0f, 1.0f) * maxVolume
        val balance = leftChannelBalance.coerceIn(0.0f, 1.0f)

        val frames = ((sampleRate.toLong() * durationMs) / 1000).toInt().coerceAtLeast(1)
        val monoBuffer = ShortArray(frames)
        val phaseInc = (frequencyHz.toDouble() * 2.0 * Math.PI) / sampleRate.toDouble()

        for (i in monoBuffer.indices) {
            val sineVal = sin(i.toDouble() * phaseInc)
            monoBuffer[i] = (sineVal * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
        }

        val stereoBuffer = ShortArray(frames * 2)
        val leftVol = balance * clampedVolume
        val rightVol = (1.0f - balance) * clampedVolume

        for (i in monoBuffer.indices) {
            stereoBuffer[i * 2] = (monoBuffer[i] * leftVol).toInt().coerceIn(-32768, 32767).toShort()
            stereoBuffer[i * 2 + 1] = (monoBuffer[i] * rightVol).toInt().coerceIn(-32768, 32767).toShort()
        }

        Log.d(TAG, "playTone writing ${stereoBuffer.size} samples")
        playBuffer(stereoBuffer, frames)
        Log.d(TAG, "playTone DONE f=${frequencyHz}Hz")
    }

    fun playTrill(
        baseFreqHz: Int = 1800,
        modulationFreqHz: Float = 12.0f,
        durationMs: Long = 500,
        volume: Float = maxVolume,
        leftChannelBalance: Float = 0.5f
    ) {
        if (baseFreqHz < 100 || baseFreqHz > 3000) return

        Log.d(TAG, "playTrill START f=${baseFreqHz}Hz mod=${modulationFreqHz}Hz dur=${durationMs}ms")

        val clampedVolume = volume.coerceIn(0.0f, 1.0f) * maxVolume
        val balance = leftChannelBalance.coerceIn(0.0f, 1.0f)

        val frames = ((sampleRate.toLong() * durationMs) / 1000).toInt().coerceAtLeast(1)
        val monoBuffer = ShortArray(frames)

        val freqPhaseInc = (baseFreqHz.toDouble() * 2.0 * Math.PI) / sampleRate.toDouble()
        val modPeriodFrames = sampleRate / modulationFreqHz.toDouble()

        for (i in monoBuffer.indices) {
            val modPhase = (((i % modPeriodFrames.toInt()).toDouble()) / modPeriodFrames) * 2.0 * Math.PI
            val envelope = 0.5 + (sin(modPhase) * 0.5)
            val sharpEnv = if (envelope > 0.3) (envelope - 0.3) / 0.7 else 0.0
            val sineVal = sin(i.toDouble() * freqPhaseInc)

            monoBuffer[i] = (clampedVolume.toDouble() * sineVal * sharpEnv * 32767.0).toInt()
                .coerceIn(-32768, 32767).toShort()
        }

        val stereoBuffer = ShortArray(frames * 2)
        val rightVol = 1.0f - balance

        for (i in monoBuffer.indices) {
            stereoBuffer[i * 2] = (monoBuffer[i] * balance).toInt().coerceIn(-32768, 32767).toShort()
            stereoBuffer[i * 2 + 1] = (monoBuffer[i] * rightVol).toInt().coerceIn(-32768, 32767).toShort()
        }

        Log.d(TAG, "playTrill writing ${stereoBuffer.size} samples")
        playBuffer(stereoBuffer, frames)
        Log.d(TAG, "playTrill DONE f=${baseFreqHz}Hz")
    }

    fun stop() {
        synchronized(lock) {
            val isInitialized = audioTrack?.state == AudioTrack.STATE_INITIALIZED
            Log.d(TAG, "stop() called — track initialized: $isInitialized")

            audioTrack?.apply {
                stop()
                release()
            }
            audioTrack = null
        }
    }

    fun release() {
        Log.d(TAG, "release() called")
        synchronized(lock) {
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                // Игнорируем ошибки при освобождении
            }
            audioTrack = null
        }
        Log.d(TAG, "release() done — track now " + (if (audioTrack != null) "alive" else "null"))
    }

    private fun playBuffer(pcm: ShortArray, frameCount: Int) {
        Log.d(TAG, "playBuffer ENTER frames=$frameCount pcmSize=${pcm.size}")
        synchronized(lock) {
            val lengthBytes = pcm.size * 2
            val staticTrack = initAudioTrackStatic(lengthBytes.toLong())
            if (staticTrack == null) {
                Log.e(TAG, "playBuffer: failed to create static track for ${pcm.size} samples")
                return
            }

            val written = staticTrack.write(pcm, 0, pcm.size)
            Log.d(TAG, "playBufferStatic: wrote $written/${pcm.size} bytes for $lengthBytes B buffer")

            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                // Игнорируем ошибки остановки предыдущего трека
            }

            audioTrack = staticTrack
            staticTrack.play()
            Log.d(TAG, "playBuffer EXIT")
        }
    }

    private fun initAudioTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        return AudioTrack(audioAttributes, format, minBuf * 4, AudioTrack.MODE_STREAM, 0)
    }

    private fun initAudioTrackStatic(bufferSizeInBytes: Long): AudioTrack? {
        if (bufferSizeInBytes <= 0 || bufferSizeInBytes > 10485760) return null

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        return try {
            AudioTrack(audioAttributes, format, bufferSizeInBytes.toInt(), AudioTrack.MODE_STATIC, 0)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "initAudioTrackStatic: buffer size $bufferSizeInBytes rejected, falling back to MODE_STREAM", e)
            null
        }
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 44100
        private const val TAG = "StereoAudioPlayer"

        @JvmStatic
        fun create(): StereoAudioPlayer? {
            return try {
                StereoAudioPlayer()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create StereoAudioPlayer", e)
                null
            }
        }
    }
}
