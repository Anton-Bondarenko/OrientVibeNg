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

    private val appContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.applicationContext.createAttributionContext("audioPlayback")
    } else {
        context.applicationContext
    }

    // Блок init и initAudioTrack() удалены — MODE_STATIC создаётся по факту воспроизведения

    fun playTone(
        frequencyHz: Int = 440,
        durationMs: Long = 200,
        volume: Float = maxVolume,
        leftChannelBalance: Float = 0.5f
    ) {
        if (frequencyHz < 100 || frequencyHz > 3000) return

        val clampedVolume = volume.coerceIn(0.0f, 1.0f) * maxVolume
        val balance = leftChannelBalance.coerceIn(0.0f, 1.0f)

        val frames = ((sampleRate.toLong() * durationMs) / 1000).toInt().coerceAtLeast(1)
        // Плавное нарастание и затухание по 15 мс, чтобы убрать щелчки
        val fadeFrames = ((sampleRate * 15L) / 1000).toInt().coerceAtMost(frames / 2)

        val stereoBuffer = ShortArray(frames * 2)
        val phaseInc = (frequencyHz.toDouble() * 2.0 * Math.PI) / sampleRate.toDouble()
        val leftVol = balance * clampedVolume
        val rightVol = (1.0f - balance) * clampedVolume

        for (i in 0 until frames) {
            val fadeMultiplier = when {
                i < fadeFrames -> i.toDouble() / fadeFrames
                i >= frames - fadeFrames -> (frames - 1 - i).toDouble() / fadeFrames
                else -> 1.0
            }

            val sample = sin(i.toDouble() * phaseInc) * fadeMultiplier * 32767.0
            stereoBuffer[i * 2] = (sample * leftVol).toInt().coerceIn(-32768, 32767).toShort()
            stereoBuffer[i * 2 + 1] = (sample * rightVol).toInt().coerceIn(-32768, 32767).toShort()
        }

        playBuffer(stereoBuffer, frames)
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

        val frames = ((sampleRate.toLong() * durationMs) / 1000).toInt().coerceAtLeast(1)
        val fadeFrames = ((sampleRate * 15L) / 1000).toInt().coerceAtMost(frames / 2)

        val stereoBuffer = ShortArray(frames * 2)
        val freqPhaseInc = (baseFreqHz.toDouble() * 2.0 * Math.PI) / sampleRate.toDouble()
        val modPhaseInc = (modulationFreqHz.toDouble() * 2.0 * Math.PI) / sampleRate.toDouble()

        val leftVol = balance * clampedVolume
        val rightVol = (1.0f - balance) * clampedVolume

        for (i in 0 until frames) {
            val fadeMultiplier = when {
                i < fadeFrames -> i.toDouble() / fadeFrames
                i >= frames - fadeFrames -> (frames - 1 - i).toDouble() / fadeFrames
                else -> 1.0
            }

            // Плавная модуляция амплитуды от 0.15 до 1.0 без резких ступенек
            val envelope = 0.575 + 0.425 * sin(i.toDouble() * modPhaseInc)
            val sample = sin(i.toDouble() * freqPhaseInc) * envelope * fadeMultiplier * 32767.0

            stereoBuffer[i * 2] = (sample * leftVol).toInt().coerceIn(-32768, 32767).toShort()
            stereoBuffer[i * 2 + 1] = (sample * rightVol).toInt().coerceIn(-32768, 32767).toShort()
        }

        playBuffer(stereoBuffer, frames)
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

    private fun playBuffer(pcm: ShortArray, frameCount: Int) {
        synchronized(lock) {
            val lengthBytes = pcm.size * 2
            val staticTrack = initAudioTrackStatic(lengthBytes.toLong()) ?: return

            staticTrack.write(pcm, 0, pcm.size)

            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {
            }

            audioTrack = staticTrack
            staticTrack.play()
        }
    }

    private fun initAudioTrackStatic(bufferSizeInBytes: Long): AudioTrack? {
        if (bufferSizeInBytes <= 0 || bufferSizeInBytes > 10485760) return null

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        return try {
            createAudioTrack(appContext, format, bufferSizeInBytes.toInt(), AudioTrack.MODE_STATIC)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "initAudioTrackStatic: buffer size $bufferSizeInBytes rejected", e)
            null
        }
    }

    private fun createAudioTrack(
        context: Context,
        format: AudioFormat,
        bufferSizeInBytes: Int,
        mode: Int
    ): AudioTrack {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val builder = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeInBytes)
            .setTransferMode(mode)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setContext(context)
        }

        return builder.build()
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 48000
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
