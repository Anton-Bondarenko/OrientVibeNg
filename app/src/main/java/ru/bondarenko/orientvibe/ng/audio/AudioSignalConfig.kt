package ru.bondarenko.orientvibe.ng.audio

import android.content.Context
import android.util.Log
import ru.bondarenko.orientvibe.ng.auto.ApproachEvent
import ru.bondarenko.orientvibe.ng.auto.ApproachEvents
import ru.bondarenko.orientvibe.ng.model.AudioSignalState
import ru.bondarenko.orientvibe.ng.model.DeviationDirection
import ru.bondarenko.orientvibe.ng.model.NavigationData
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

enum class AudioSignalType {
    NONE,
    ARRIVED,
    DEVIATION,
    APPROACH_SINGLE,
    APPROACH_DOUBLE
}

/** Состояние подхода — хранит флаги пересечения радиусов между кадрами */
class ApproachState(
    var controlNum: Int = -1,
    val events: ApproachEvents = ApproachEvents(),
    var wasInSearchRadius: Boolean = false,   // < 40 м
    var wasInApproachDouble: Boolean = false, // < 50 м
    var wasInApproachSingle: Boolean = false  // < 100 м
) {
    fun resetForControl(newControlNum: Int) {
        controlNum = newControlNum
        wasInSearchRadius = false
        wasInApproachDouble = false
        wasInApproachSingle = false
        events.clear()
    }
}

/** Состояние arrived-трели */
class ArrivedState(
    var playedControlNum: Int = -1
)

/** Параметры и состояние между вызовами */
data class AudioPlayConfig(
    val audioPlayer: StereoAudioPlayer,
    val context: Context,

    // Состояние между вызовами
    var approachState: ApproachState = ApproachState(),
    var arrivedState: ArrivedState = ArrivedState(),
    var lastPlayedSignalType: AudioSignalType = AudioSignalType.NONE,
    var lastAudioPlayTimestampMs: Long = 0L,

    // Режим звука и текущий GPS fix
    val soundMode: String = "errors",
    val fixSpeedKmH: Float = 0f,

    // Данные навигации
    val confidenceProduct: Double = 1.0,
    val hasCalibrationPointB: Boolean = false,
    val navData: NavigationData? = null
) {
    /** Результат для UI / MovementState */
    var audioSignalState: AudioSignalState = AudioSignalState(type = AudioSignalType.NONE)
}

object AudioSignalConfig {

    // ── Дистанционные пороги (при confidenceProduct = 1.0) ────────────────────
    private const val BASE_SILENT_RADIUS_M = 40.0
    private const val BASE_APPROACH_DOUBLE_M = 50.0
    private const val BASE_APPROACH_SINGLE_M = 100.0

    // ── Мёртвая зона (угол) ───────────────────────────────────────────────────
    private const val BASE_DEAD_ZONE_DEG = 10.0

    // ── Диапазон частот DEVIATION ─────────────────────────────────────────────
    const val DEVIATION_MIN_FREQ_HZ = 300
    const val DEVIATION_MAX_FREQ_HZ = 800
    private const val DEVIATION_CENTER_FREQ = 800.0
    private const val DEVIATION_FREQ_SPAN = 500.0
    private const val DEVIATION_MAX_DEG = 60.0
    private const val DEVIATION_BALANCE_SPAN = 70.0

    // ── Интервалы DEVIATION (по углу) ────────────────────────────────────────
    private const val DEVIATION_INTERVAL_FAST_MS = 4000L   // > 60°
    private const val DEVIATION_INTERVAL_MED_MS = 8000L    // > 30°
    private const val DEVIATION_INTERVAL_SLOW_MS = 15000L  // > 20°
    private const val DEVIATION_INTERVAL_IDLE_MS = 25000L  // ≤ 20°

    // ── Тон approach-писков ───────────────────────────────────────────────────
    const val APPROACH_TONE1_FREQ = 1400
    const val APPROACH_TONE2_FREQ = 1100
    const val APPROACH_TONE_DUR_MS = 120L
    const val APPROACH_TONE_BALANCE = 0.5f
    const val APPROACH_PAIR_GAP_MS = 180L
    const val APPROACH_DOUBLE_GAP_MS = 900L

    // ── Тон ARRIVED (trill) ───────────────────────────────────────────────────
    const val ARRIVED_TONE_FREQ = 1300
    const val ARRIVED_TONE_VOL = 15.0f
    const val ARRIVED_TONE_DUR_MS = 600L
    const val ARRIVED_TONE_VOLUME = 1.0f

    // ── Порог скорости для DEVIATION (км/ч) ───────────────────────────────────
    const val DEVIATION_SPEED_MUTE_KMH = 3.0f

    // ── Режим "Ведение" — частота обновления при active tracking ───────────────
    const val TRACKING_ACTIVE_INTERVAL_MS = 500L

    // ── DEVIATION: длительность тона зависит от отклонения ─────────────────────
    const val DEVIATION_BASE_DUR_MS = 200L
    const val DEVIATION_DEG_PER_MS = 5.0

    private fun scaleFactorFor(confidenceProduct: Double): Double {
        return if (confidenceProduct > 0.0) 1.0 / confidenceProduct else Double.MAX_VALUE
    }

    // ═══════════ 1. ГЕНЕРАЦИЯ СОБЫТИЙ КАДРА ═══════════

    /**
     * Вычисляет события текущего кадра на основе дистанции и истории пересечений.
     * - ON_SEARCH_RADIUS, ON_APPROACH_RADIUS, ON_100_RADIUS — строго 1 раз при входе.
     * - ON_MOVE_TO_CP — каждый кадр, пока находимся вне ON_SEARCH_RADIUS.
     */
    fun evaluateFrameEvents(
        controlNum: Int,
        navData: NavigationData,
        confidenceProduct: Double,
        state: ApproachState
    ): List<ApproachEvent> {
        if (state.controlNum != controlNum) {
            state.resetForControl(controlNum)
        } else {
            state.events.clear()
        }

        val sf = scaleFactorFor(confidenceProduct)
        val searchRadius = BASE_SILENT_RADIUS_M * sf
        val approachRadius = BASE_APPROACH_DOUBLE_M * sf
        val radius100 = BASE_APPROACH_SINGLE_M * sf

        val dist = navData.distanceToTargetMeters
        val devDeg = navData.deviationDeg

        val inSearchNow = dist < searchRadius
        val inDoubleNow = dist < approachRadius
        val inSingleNow = dist < radius100

        // Одноразовые события при пересечении границы снаружи внутрь
        if (inSearchNow && !state.wasInSearchRadius) {
            state.events.add(
                ApproachEvent(ApproachEvent.EventType.ON_SEARCH_RADIUS, controlNum, dist.toFloat(), devDeg)
            )
        }
        if (inDoubleNow && !state.wasInApproachDouble) {
            state.events.add(
                ApproachEvent(ApproachEvent.EventType.ON_APPROACH_RADIUS, controlNum, dist.toFloat(), devDeg)
            )
        }
        if (inSingleNow && !state.wasInApproachSingle) {
            state.events.add(
                ApproachEvent(ApproachEvent.EventType.ON_100_RADIUS, controlNum, dist.toFloat(), devDeg)
            )
        }

        // Непрерывное событие движения — каждый кадр вне радиуса поиска
        if (!inSearchNow) {
            state.events.add(
                ApproachEvent(ApproachEvent.EventType.ON_MOVE_TO_CP, controlNum, dist.toFloat(), devDeg)
            )
        }

        // Фиксируем состояние для следующего кадра
        state.wasInSearchRadius = inSearchNow
        state.wasInApproachDouble = inDoubleNow
        state.wasInApproachSingle = inSingleNow

        return state.events.items()
    }

    // ═══════════ 2. ОБРАБОТКА СОБЫТИЙ И ВЫВОД ЗВУКА ═══════════

    /**
     * Точка входа для кадра: генерирует события и запускает их звуковую обработку.
     */
    fun playSignal(cfg: AudioPlayConfig) {
        val navData = cfg.navData ?: return
        val events = evaluateFrameEvents(
            controlNum = cfg.approachState.controlNum,
            navData = navData,
            confidenceProduct = cfg.confidenceProduct,
            state = cfg.approachState
        )
        handleFrameEvents(events, cfg)
    }

    /**
     * Реагирует на список самостоятельных событий кадра.
     */
    fun handleFrameEvents(events: List<ApproachEvent>, cfg: AudioPlayConfig) {
        val navData = cfg.navData ?: return
        val dist = navData.distanceToTargetMeters
        val devDeg = navData.deviationDeg
        val absDegVal = abs(devDeg)

        val sf = scaleFactorFor(cfg.confidenceProduct)
        val deadZoneDeg = BASE_DEAD_ZONE_DEG * sf
        val isMovingToCp = events.any { it.type == ApproachEvent.EventType.ON_MOVE_TO_CP }

        // Если мы внутри радиуса поиска (!isMovingToCp), но событие входа уже прошло в прошлых кадрах — тишина
        if (!isMovingToCp && events.none { it.type == ApproachEvent.EventType.ON_SEARCH_RADIUS }) {
            cfg.audioSignalState = AudioSignalState(type = AudioSignalType.NONE, intervalMs = 0L)
            cfg.lastPlayedSignalType = AudioSignalType.NONE
            return
        }

        if (cfg.soundMode == "off") {
            cfg.audioSignalState = AudioSignalState(type = AudioSignalType.NONE)
            return
        }

        val now = System.currentTimeMillis()
        var activeTypeForState = AudioSignalType.NONE
        var activeIntervalMs = 0L

        for (event in events) {
            when (event.type) {
                // ── Вход в 40 м (срабатывает 1 раз) ──────────────────────────
                ApproachEvent.EventType.ON_SEARCH_RADIUS -> {
                    val curCpNum = event.controlNum
                    if (cfg.arrivedState.playedControlNum != curCpNum) {
                        Log.d("AudioNav", "▶ ARRIVED trill: CP#$curCpNum dist=${"%.1f".format(Locale.US, event.distM)}m")
                        cfg.audioPlayer.playTrill(
                            ARRIVED_TONE_FREQ,
                            ARRIVED_TONE_VOL,
                            ARRIVED_TONE_DUR_MS,
                            ARRIVED_TONE_VOLUME,
                            APPROACH_TONE_BALANCE
                        )
                        cfg.arrivedState.playedControlNum = curCpNum
                    }
                    cfg.lastAudioPlayTimestampMs = now
                    cfg.lastPlayedSignalType = AudioSignalType.ARRIVED
                    activeTypeForState = AudioSignalType.ARRIVED
                }

                // ── Вход в 50 м (срабатывает 1 раз) ──────────────────────────
                ApproachEvent.EventType.ON_APPROACH_RADIUS -> {
                    Log.d("AudioNav", "▶ APPROACH_DOUBLE: dist=${"%.1f".format(Locale.US, event.distM)}m dev=${"%.1f".format(Locale.US, event.devDeg)}°")
                    playApproachDoubleBeep(cfg.audioPlayer)
                    activeTypeForState = AudioSignalType.APPROACH_DOUBLE
                }

// ── Вход в 100 м (срабатывает 1 раз) ─────────────────────────
                ApproachEvent.EventType.ON_100_RADIUS -> {
                    Log.d("AudioNav", "▶ APPROACH_SINGLE: dist=${"%.1f".format(Locale.US, event.distM)}m dev=${"%.1f".format(Locale.US, event.devDeg)}°")
                    playApproachSingleBeep(cfg.audioPlayer)
                    activeTypeForState = AudioSignalType.APPROACH_SINGLE
                }
// ── Движение к КП (каждый кадр вне 40 м) ──────────────────────
                ApproachEvent.EventType.ON_MOVE_TO_CP -> {
                    val angleIsCenter = absDegVal <= deadZoneDeg || navData.direction == DeviationDirection.CENTER
                    if (angleIsCenter) {
                        if (activeTypeForState == AudioSignalType.NONE) {
                            activeTypeForState = AudioSignalType.NONE
                        }
                        continue
                    }
                    activeIntervalMs = if (cfg.soundMode == "tracking") {
                        TRACKING_ACTIVE_INTERVAL_MS
                    } else {
                        intervalFromDeviation(absDegVal)
                    }
                    if (activeTypeForState == AudioSignalType.NONE) {
                        activeTypeForState = AudioSignalType.DEVIATION
                    }
                    val elapsedMs = now - cfg.lastAudioPlayTimestampMs
                    val canPlayByTimer = elapsedMs >= activeIntervalMs
                    val isSignalChanged = cfg.lastPlayedSignalType != AudioSignalType.DEVIATION &&
                            cfg.lastPlayedSignalType != AudioSignalType.ARRIVED &&
                            elapsedMs >= 3000L
                    val shouldPlay = if (cfg.soundMode == "tracking") {
                        elapsedMs >= TRACKING_ACTIVE_INTERVAL_MS
                    } else {
                        canPlayByTimer || isSignalChanged
                    }
                    if (shouldPlay && cfg.fixSpeedKmH > DEVIATION_SPEED_MUTE_KMH) {
                        val freqHz = frequencyFromDeviation(absDegVal)
                        val balance = balanceFromDeviation(devDeg, absDegVal)
                        val durationMs = deviationDurationMs(absDegVal)
                        Log.d("AudioNav", "▶ DEVIATION: freq=${freqHz}Hz speed=${"%.1f".format(Locale.US, cfg.fixSpeedKmH)} км/ч dev=${"%.1f".format(Locale.US, devDeg)}°")
                        cfg.audioPlayer.playTone(freqHz, durationMs, 1.0f, balance)
                        cfg.lastAudioPlayTimestampMs = now
                        cfg.lastPlayedSignalType = AudioSignalType.DEVIATION
                    }
                }
            }
        }
// Обновляем итоговое состояние кадра для UI
        val freqHz = if (activeTypeForState == AudioSignalType.DEVIATION) frequencyFromDeviation(absDegVal) else DEVIATION_MAX_FREQ_HZ
        val balance = if (activeTypeForState == AudioSignalType.DEVIATION) balanceFromDeviation(devDeg, absDegVal) else APPROACH_TONE_BALANCE
        cfg.audioSignalState = AudioSignalState(
            type = activeTypeForState,
            frequencyHz = freqHz,
            leftBalance = balance,
            intervalMs = activeIntervalMs,
            deviationDeg = devDeg,
            distanceToTargetMeters = dist
        )
    }
    // ═══════════ Helpers для воспроизведения и расчёта параметров ═══════════
    private fun playApproachSingleBeep(audioPlayer: StereoAudioPlayer) {
        audioPlayer.playTone(APPROACH_TONE1_FREQ, APPROACH_TONE_DUR_MS, 1.0f, APPROACH_TONE_BALANCE)
        thread(start = true) {
            Thread.sleep(APPROACH_PAIR_GAP_MS)
            audioPlayer.playTone(APPROACH_TONE2_FREQ, APPROACH_TONE_DUR_MS, 1.0f, APPROACH_TONE_BALANCE)
        }
    }
    private fun playApproachDoubleBeep(audioPlayer: StereoAudioPlayer) {
        audioPlayer.playTone(APPROACH_TONE1_FREQ, APPROACH_TONE_DUR_MS, 1.0f, APPROACH_TONE_BALANCE)
        thread(start = true) {
            Thread.sleep(APPROACH_PAIR_GAP_MS)
            audioPlayer.playTone(APPROACH_TONE2_FREQ, APPROACH_TONE_DUR_MS, 1.0f, APPROACH_TONE_BALANCE)
        }
        thread(start = true) {
            Thread.sleep(APPROACH_DOUBLE_GAP_MS)
            audioPlayer.playTone(APPROACH_TONE1_FREQ, APPROACH_TONE_DUR_MS, 1.0f, APPROACH_TONE_BALANCE)
            Thread.sleep(APPROACH_PAIR_GAP_MS)
            audioPlayer.playTone(APPROACH_TONE2_FREQ, APPROACH_TONE_DUR_MS, 1.0f, APPROACH_TONE_BALANCE)
        }
    }
    fun frequencyFromDeviation(absDeg: Double): Int {
        return (DEVIATION_CENTER_FREQ - ((absDeg.coerceIn(0.0, DEVIATION_MAX_DEG)) / 40.0) * DEVIATION_FREQ_SPAN)
            .coerceIn(DEVIATION_MIN_FREQ_HZ.toDouble(), DEVIATION_MAX_FREQ_HZ.toDouble())
            .toInt()
    }
    fun balanceFromDeviation(dev: Double, absDeg: Double): Float {
        val clampedDeg = absDeg.coerceIn(0.0, 60.0).toFloat()
        return if (dev < 0.0) {
            (APPROACH_TONE_BALANCE - clampedDeg / DEVIATION_BALANCE_SPAN.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            (APPROACH_TONE_BALANCE + clampedDeg / DEVIATION_BALANCE_SPAN.toFloat()).coerceIn(0.0f, 1.0f)
        }
    }
    private fun intervalFromDeviation(absDeg: Double): Long {
        return when {
            absDeg > 60.0 -> DEVIATION_INTERVAL_FAST_MS
            absDeg > 30.0 -> DEVIATION_INTERVAL_MED_MS
            absDeg > 20.0 -> DEVIATION_INTERVAL_SLOW_MS
            else -> DEVIATION_INTERVAL_IDLE_MS
        }
    }
    fun deviationDurationMs(absDeviationDeg: Double): Long {
        return (DEVIATION_BASE_DUR_MS + (DEVIATION_DEG_PER_MS * absDeviationDeg)).toLong().coerceAtMost(600L)
    }
}