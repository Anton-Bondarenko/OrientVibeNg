package ru.bondarenko.orientvibe.ng.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlin.math.sin

class StereoAudioPlayer private constructor(context: Context) {

    companion object {
        @Volatile private var instance: StereoAudioPlayer? = null

        const val DEFAULT_SAMPLE_RATE = 48000
        const val MAX_BUFFER_FRAMES = DEFAULT_SAMPLE_RATE * 2
        const val FRAME_DURATION_MS = 13
        const val MIN_FADE_MS = 30
        const val CLIP_TONE = "tone"
        const val CLIP_TRILL = "trill"
        const val MAX_VOLUME = 1.0f

        @JvmStatic fun getInstance(context: Context): StereoAudioPlayer {
            return instance ?: synchronized(this) {
                instance ?: StereoAudioPlayer(context).also { instance = it }
            }
        }
    }

    private var audioTrack: AudioTrack? = null
    private val lock = Any()
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE
    private val samplesPerFrame = FRAME_DURATION_MS * sampleRate / 1000 // 624 стерео-сэмпла

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
        volume: Float = MAX_VOLUME,
        leftChannelBalance: Float = 0.5f
    ) {
        if (frequencyHz !in 100..3000 || durationMs <= 0) return

        val clampedVolume = volume.coerceIn(0.0f, 1.0f) * MAX_VOLUME
        val balance = leftChannelBalance.coerceIn(0.0f, 1.0f)
        val totalFrames = ((sampleRate.toLong() * durationMs) / 1000).toInt().coerceIn(1, MAX_BUFFER_FRAMES)
        val fadeFrames = if (durationMs < MIN_FADE_MS) 0 else ((sampleRate * 15L) / 1000).toInt().coerceAtMost(totalFrames / 2)

        synchronized(lock) {
            activeSounds.add(
                SoundClip(
                    type = CLIP_TONE,
                    frequencyHz = frequencyHz,
                    modulationFreqHz = 0.0,
                    fadeFrames = fadeFrames,
                    frames = totalFrames,
                    leftVol = balance * clampedVolume,
                    rightVol = (1.0f - balance) * clampedVolume,
                    sampleRateInv = 1.0 / sampleRate.toDouble()
                )
            )
            ensurePlayingLocked()
        }
    }

    fun playTrill(
        baseFreqHz: Int = 1800,
        modulationFreqHz: Float = 12.0f,
        durationMs: Long = 500,
        volume: Float = MAX_VOLUME,
        leftChannelBalance: Float = 0.5f
    ) {
        if (baseFreqHz !in 100..3000 || durationMs <= 0) return

        val clampedVolume = volume.coerceIn(0.0f, 1.0f) * MAX_VOLUME
        val balance = leftChannelBalance.coerceIn(0.0f, 1.0f)
        val totalFrames = ((sampleRate.toLong() * durationMs) / 1000).toInt().coerceIn(1, MAX_BUFFER_FRAMES)
        val fadeFrames = if (durationMs < MIN_FADE_MS) 0 else ((sampleRate * 15L) / 1000).toInt().coerceAtMost(totalFrames / 2)

        synchronized(lock) {
            activeSounds.add(
                SoundClip(
                    type = CLIP_TRILL,
                    frequencyHz = baseFreqHz,
                    modulationFreqHz = modulationFreqHz.toDouble(),
                    fadeFrames = fadeFrames,
                    frames = totalFrames,
                    leftVol = balance * clampedVolume,
                    rightVol = (1.0f - balance) * clampedVolume,
                    sampleRateInv = 1.0 / sampleRate.toDouble()
                )
            )
            ensurePlayingLocked()
        }
    }

    fun stop() {
        synchronized(lock) {
            activeSounds.clear()
        }
    }

    fun release() {
        synchronized(lock) {
            audioActive = false
            audioTrack?.apply {
                try { stop() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
            audioTrack = null
            audioThread?.interrupt()
            audioThread = null
            activeSounds.clear()
        }
    }

    private var audioThread: Thread? = null
    private val activeSounds = ArrayList<SoundClip>()
    private val stereoBuffer = ShortArray(samplesPerFrame * 2)
    private val silenceBuffer = ShortArray(samplesPerFrame * 2)
    @Volatile private var audioActive = true

    private fun ensurePlayingLocked() {
        if (audioTrack != null) return

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return

        audioActive = true
        audioTrack = AudioTrack.Builder().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setContext(appContext)
            }
            setAudioAttributes(audioAttributes)
            setAudioFormat(audioFormat)
            setBufferSizeInBytes(bufferSize * 2)
            setTransferMode(AudioTrack.MODE_STREAM)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
        }.build().also { track ->
            track.play()
            audioThread = object : Thread("StereoAudioPlayer-mixer") {
                init { isDaemon = true }
                override fun run() {
                    while (audioActive) {
                        val hasAudio: Boolean
                        synchronized(lock) {
                            hasAudio = mixFrameLocked()
                        }
                        val bufferToWrite = if (hasAudio) stereoBuffer else silenceBuffer
                        val written = track.write(bufferToWrite, 0, bufferToWrite.size)
                        if (written < 0) break
                    }
                }
            }.also { it.start() }
        }
    }

    private fun mixFrameLocked(): Boolean {
        if (activeSounds.isEmpty()) return false

        for (i in 0 until samplesPerFrame) {
            var leftAcc = 0.0
            var rightAcc = 0.0

            for (s in activeSounds) {
                val frameIdx = s.currentFrame
                if (frameIdx >= s.frames) continue

                val fadeMultiplier: Double = when {
                    s.fadeFrames > 0 && frameIdx < s.fadeFrames ->
                        frameIdx.toDouble() / s.fadeFrames
                    s.fadeFrames > 0 && frameIdx >= s.frames - s.fadeFrames ->
                        (s.frames - 1 - frameIdx).toDouble() / s.fadeFrames
                    else -> 1.0
                }

                // Непрерывная фаза от начала воспроизведения клипа
                val carrier = sin(frameIdx.toDouble() * s.phaseInc)

                // Тремоло применяем только для CLIP_TRILL
                val tremoloEnvelope = if (s.type == CLIP_TRILL) {
                    val tremoloPhase = frameIdx.toDouble() * s.modPhaseInc + s.trillPhaseOffset
                    0.575 + 0.425 * sin(tremoloPhase)
                } else {
                    1.0
                }

                val sample = carrier * tremoloEnvelope * fadeMultiplier
                leftAcc += sample * s.leftVol
                rightAcc += sample * s.rightVol

                s.currentFrame++
            }

            // Soft-limit до [-0.95, 0.95] (~31128 в 16-bit PCM)
            val leftFinal = (leftAcc.coerceIn(-0.95, 0.95) * 32767.0).toInt().toShort()
            val rightFinal = (rightAcc.coerceIn(-0.95, 0.95) * 32767.0).toInt().toShort()

            stereoBuffer[i * 2] = leftFinal
            stereoBuffer[i * 2 + 1] = rightFinal
        }

        activeSounds.removeAll { it.currentFrame >= it.frames }
        return true
    }

    private class SoundClip(
        val type: String,
        frequencyHz: Int,
        modulationFreqHz: Double,
        val fadeFrames: Int,
        val frames: Int,
        val leftVol: Float,
        val rightVol: Float,
        sampleRateInv: Double
    ) {
        val phaseInc = frequencyHz.toDouble() * 2.0 * Math.PI * sampleRateInv
        val modPhaseInc = modulationFreqHz * 2.0 * Math.PI * sampleRateInv
        val trillPhaseOffset = if (type == CLIP_TRILL) Math.random() * 2.0 * Math.PI else 0.0
        var currentFrame: Int = 0
    }
}
