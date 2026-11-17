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

// Forward-referenced in AudioPlayConfig default params — must be before any class that uses it
enum class AudioSignalType {
    NONE,
    ARRIVED,
    DEVIATION,
    APPROACH_SINGLE,
    APPROACH_DOUBLE
}

// ═══════════ Конфигурация для playSignal (состояние между вызовами) ═══════════

/** Состояние подхода — передается и обновляется в playSignal */
class ApproachState(
    /** Номер точки калибровки текущего маршрута */
    var controlNum: Int = 0,
    /** Контейнер событий пересечения радиусов текущего кадра */
    val events: ApproachEvents = ApproachEvents(),
    /** Были ли внутри каждого радиуса в ПРОШЛОМ кадре */
    var wasInSearchRadius: Boolean = false,   // был ли в <40м
    var wasInApproachDouble: Boolean = false, // был ли в <50м
    var wasInApproachSingle: Boolean = false, // был ли в <100м
    var wasOnMoveToCP: Boolean = false        // был ли вне всех радиусов
)

/** Состояние arrived-трели — передаётся и обновляется в playSignal */
class ArrivedState(
    var playedControlNum: Int = -1
)

/** Параметры для playSignal — mutable поля, результаты записываются обратно в caller */
data class AudioPlayConfig(
    val audioPlayer: StereoAudioPlayer,
    val context: Context,

    // Состояние между вызовами (обновляются внутри playSignal)
    var approachState: ApproachState = ApproachState(),
    var arrivedState: ArrivedState = ArrivedState(),
    var lastPlayedSignalType: AudioSignalType = AudioSignalType.NONE,
    var lastAudioPlayTimestampMs: Long = 0L,

    // Режим звука и текущий GPS fix
    val soundMode: String = "errors",
    val fixSpeedKmH: Float = 0f,

    // Данные навигации (вычисляются до вызова)
    val confidenceProduct: Double = 1.0,
    val hasCalibrationPointB: Boolean = false,
    val navData: NavigationData? = null
) {
    /** результат: обновлённый AudioSignalState для MovementState.audioSignalState */
    var audioSignalState: AudioSignalState = AudioSignalState(type = AudioSignalType.NONE)
}

object AudioSignalConfig {

    // ── Дистанционные пороги (при confidenceProduct = 1.0) ────────────────────
    private const val BASE_SILENT_RADIUS_M = 40.0          // ARRIVED / тишина
    private const val BASE_APPROACH_DOUBLE_M = 50.0        // двойной писк
    private const val BASE_APPROACH_SINGLE_M = 100.0       // одинарный писк

    // ── Мёртвая зона (угол) ───────────────────────────────────────────────────
    private const val BASE_DEAD_ZONE_DEG = 10.0

    // ── Диапазон частот DEVIATION ─────────────────────────────────────────────
    const val DEVIATION_MIN_FREQ_HZ = 300
    const val DEVIATION_MAX_FREQ_HZ = 800
    private const val DEVIATION_CENTER_FREQ = 800.0
    private const val DEVIATION_FREQ_SPAN = 500.0          // разброс от центра до минимума
    private const val DEVIATION_MAX_DEG = 60.0            // за пределами — всегда MAX_FREQ
    private const val DEVIATION_BALANCE_SPAN = 70.0       // угол полного баланса

    // ── Интервалы DEVIATION (по углу) ────────────────────────────────────────
    private const val DEVIATION_INTERVAL_FAST_MS = 4000L   // >60°
    private const val DEVIATION_INTERVAL_MED_MS = 8000L    // >30°
    private const val DEVIATION_INTERVAL_SLOW_MS = 15000L  // >20°
    private const val DEVIATION_INTERVAL_IDLE_MS = 25000L  // ≤20°

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

    // ═══════════ helpers для scale / analysis ═══════════

    /** Масштабный коэффициент: 1.0 при полной уверенности, растёт при падении */
    private fun scaleFactorFor(confidenceProduct: Double): Double {
        return if (confidenceProduct > 0.0) 1.0 / confidenceProduct else Double.MAX_VALUE
    }

    // ═══════════ Основной API — полный анализ + воспроизведение ═══════════

    /**
     * Единый метод анализа и воспроизведения навигационного аудио-сигнала.
     * Все вызовы из onMove: один вызов → результат сразу в MovementState.audioSignalState.
     *
     * Подход-писки (APPROACH_SINGLE / APPROACH_DOUBLE) и трель ARRIVED играют
     * только ОДИН РАЗ при пересечении радиуса — не на каждом кадре внутри зоны.
     * DEVIATION — непрерывный фон, играет с таймером пока человек движется > 3 км/ч.
     */
    fun playSignal(cfg: AudioPlayConfig) {
        val navData = cfg.navData ?: return // нет данных → тишина
        val dist = navData.distanceToTargetMeters
        val devDeg = navData.deviationDeg
        val absDegVal = abs(devDeg)
        val navDir = navData.direction

        // ── Scale thresholds by confidence ───────────────────────────────────────
        val sf = scaleFactorFor(cfg.confidenceProduct)
        val silentRadius = BASE_SILENT_RADIUS_M * sf
        val approachDoubleDist = BASE_APPROACH_DOUBLE_M * sf
        val approachSingleDist = BASE_APPROACH_SINGLE_M * sf
        val deadZoneDeg = BASE_DEAD_ZONE_DEG * sf

        // ── 1. События: детекция пересечений + onMoveToCP ────────────────────────
        cfg.approachState.events.clear()

        val inSearchNow = dist < silentRadius
        val inDoubleNow = dist < approachDoubleDist
        val inSingleNow = dist < approachSingleDist
        val inAnyRadius = inSearchNow || inDoubleNow || inSingleNow

        // ON_MOVE_TO_CP — каждый кадр пока вне всех радиусов (один раз при выходе из зоны)
        if (!inAnyRadius && !cfg.approachState.wasOnMoveToCP) {
            cfg.approachState.events.add(
                ApproachEvent(
                    ApproachEvent.EventType.ON_MOVE_TO_CP,
                    cfg.approachState.controlNum,
                    dist.toFloat(),
                    devDeg
                )
            )
        }

        // Радиусные события — при входе в зону
        if (inSearchNow && !cfg.approachState.wasInSearchRadius) {
            cfg.approachState.events.add(
                ApproachEvent(
                    ApproachEvent.EventType.ON_SEARCH_RADIUS,
                    cfg.approachState.controlNum,
                    dist.toFloat(),
                    devDeg
                )
            )
        }
        if (inDoubleNow && !cfg.approachState.wasInApproachDouble) {
            cfg.approachState.events.add(
                ApproachEvent(
                    ApproachEvent.EventType.ON_APPROACH_RADIUS,
                    cfg.approachState.controlNum,
                    dist.toFloat(),
                    devDeg
                )
            )
        }
        if (inSingleNow && !cfg.approachState.wasInApproachSingle) {
            cfg.approachState.events.add(
                ApproachEvent(
                    ApproachEvent.EventType.ON_100_RADIUS,
                    cfg.approachState.controlNum,
                    dist.toFloat(),
                    devDeg
                )
            )
        }

        // Обновляем wasIn* флаги
        cfg.approachState.wasInSearchRadius = inSearchNow
        cfg.approachState.wasInApproachDouble = inDoubleNow
        cfg.approachState.wasInApproachSingle = inSingleNow
        cfg.approachState.wasOnMoveToCP = !inAnyRadius

        // ── 2. Маппинг событий → аудио-тип ───────────────────────────────────────
        var audioType: AudioSignalType
        val events = cfg.approachState.events.items()

        if (events.any { it.type == ApproachEvent.EventType.ON_SEARCH_RADIUS }) {
            audioType = AudioSignalType.ARRIVED
        } else if (events.any { it.type == ApproachEvent.EventType.ON_APPROACH_RADIUS }) {
            audioType = AudioSignalType.APPROACH_DOUBLE
        } else if (events.any { it.type == ApproachEvent.EventType.ON_100_RADIUS }) {
            audioType = AudioSignalType.APPROACH_SINGLE
        } else {
            // Нет радиусных событий — определяем по расстоянию
            when {
                dist < silentRadius -> audioType = AudioSignalType.ARRIVED
                dist <= approachDoubleDist -> audioType = AudioSignalType.APPROACH_DOUBLE
                dist <= approachSingleDist -> audioType = AudioSignalType.APPROACH_SINGLE
                absDegVal <= deadZoneDeg || navDir == DeviationDirection.CENTER -> audioType = AudioSignalType.NONE
                else -> audioType = AudioSignalType.DEVIATION
            }
        }

        // ── 3. Воспроизведение по маппингу ────────────────────────────────────────

        // ARRIVED — один раз трель при пересечении → потом полная тишина на этот CP
        if (audioType == AudioSignalType.ARRIVED) {
            val curCpNum = cfg.approachState.controlNum
            val justArrived = cfg.arrivedState.playedControlNum != curCpNum

            // Сброс таймера — больше ни звука
            cfg.lastAudioPlayTimestampMs = System.currentTimeMillis()
            cfg.approachState.wasInSearchRadius = true
            cfg.approachState.wasInApproachDouble = true
            cfg.approachState.wasInApproachSingle = true

            if (justArrived) {
                Log.d("AudioNav", "▶ ARRIVED trill: CP#$curCpNum dist=${"%.1f".format(Locale.US, dist)}m")
                cfg.audioPlayer.playTrill(
                    ARRIVED_TONE_FREQ,
                    ARRIVED_TONE_VOL,
                    ARRIVED_TONE_DUR_MS,
                    ARRIVED_TONE_VOLUME,
                    APPROACH_TONE_BALANCE
                )
                cfg.arrivedState.playedControlNum = curCpNum
            }

            // Полная тишина — подход/DEVIATION игнорируем внутри ARRIVED
            cfg.audioSignalState = AudioSignalState(
                type = AudioSignalType.NONE,
                intervalMs = 0L
            )
            cfg.lastPlayedSignalType = AudioSignalType.NONE
        } else if (audioType == AudioSignalType.NONE) {
            // В мёртвой зоне / без калибровки — тишина, сброс подходов
            cfg.audioSignalState = AudioSignalState(type = AudioSignalType.NONE, intervalMs = 0L)
            cfg.lastAudioPlayTimestampMs = System.currentTimeMillis()
        } else if (cfg.soundMode == "off") {
            // Игнорируем события подхода если звук выключен
            cfg.audioSignalState = AudioSignalState(type = audioType)
            cfg.approachState.events.clear()
        } else {

        // Подход-писки — играем по событиям пересечения
        for (event in events) {
            when (event.type) {
                ApproachEvent.EventType.ON_APPROACH_RADIUS -> {
                    Log.d("AudioNav", "▶ APPROACH_DOUBLE: dist=${"%.1f".format(Locale.US, dist)}m dev=${"%.1f".format(Locale.US, devDeg)}°")
                    playApproachDoubleBeep(cfg.audioPlayer)
                }

                ApproachEvent.EventType.ON_100_RADIUS -> {
                    Log.d("AudioNav", "▶ APPROACH_SINGLE: dist=${"%.1f".format(Locale.US, dist)}m dev=${"%.1f".format(Locale.US, devDeg)}°")
                    playApproachSingleBeep(cfg.audioPlayer)
                }

                ApproachEvent.EventType.ON_MOVE_TO_CP -> {
                    Log.d("AudioNav", "▶ ON_MOVE_TO_CP: dist=${"%.1f".format(Locale.US, dist)}m dev=${"%.1f".format(Locale.US, devDeg)}°")
                }

                // ON_SEARCH_RADIUS → трель выше
                else -> {}
            }
        }

        // DEVIATION — непрерывный фон с таймером (по событию ON_MOVE_TO_CP)
        val now = System.currentTimeMillis()
        val elapsedMs = now - cfg.lastAudioPlayTimestampMs
        val canPlayByTimer = elapsedMs >= cfg.audioSignalState.intervalMs
        val isSignalChanged = audioType != cfg.lastPlayedSignalType

        val shouldPlayDeviation = when {
            cfg.soundMode == "tracking" && audioType == AudioSignalType.DEVIATION -> {
                elapsedMs >= TRACKING_ACTIVE_INTERVAL_MS
            }
            audioType == AudioSignalType.DEVIATION -> {
                canPlayByTimer || (isSignalChanged && cfg.lastPlayedSignalType != AudioSignalType.ARRIVED && elapsedMs >= 3000L)
            }
            else -> false
        }

        val freqHz = if (audioType == AudioSignalType.DEVIATION) frequencyFromDeviation(absDegVal) else DEVIATION_MAX_FREQ_HZ
        val balance = if (audioType == AudioSignalType.DEVIATION) balanceFromDeviation(devDeg, absDegVal) else APPROACH_TONE_BALANCE

        if (shouldPlayDeviation && audioType == AudioSignalType.DEVIATION) {
            if (cfg.fixSpeedKmH <= DEVIATION_SPEED_MUTE_KMH) {
                // Скорость слишком низкая — тишина, но не играем тон
                cfg.audioSignalState = AudioSignalState(type = audioType, frequencyHz = freqHz, leftBalance = balance, intervalMs = cfg.audioSignalState.intervalMs, deviationDeg = devDeg, distanceToTargetMeters = dist)
            } else {
                val durationMs = deviationDurationMs(absDegVal)
                Log.d("AudioNav", "▶ DEVIATION: freq=${freqHz}Hz speed=${"%.1f".format(Locale.US, cfg.fixSpeedKmH)} км/ч dev=${"%.1f".format(Locale.US, devDeg)}°")
                cfg.audioPlayer.playTone(freqHz, durationMs, 1.0f, balance)
                cfg.lastAudioPlayTimestampMs = now
                cfg.lastPlayedSignalType = AudioSignalType.DEVIATION
                cfg.audioSignalState = AudioSignalState(
                    type = audioType,
                    frequencyHz = freqHz,
                    leftBalance = balance,
                    intervalMs = cfg.audioSignalState.intervalMs,
                    deviationDeg = devDeg,
                    distanceToTargetMeters = dist
                )
            }
        } else {
            // ── 5. Сохраняем анализ в state ─────────────────────────────────────────
            cfg.audioSignalState = AudioSignalState(
                type = audioType,
                frequencyHz = freqHz,
                leftBalance = balance,
                intervalMs = cfg.audioSignalState.intervalMs,
                deviationDeg = devDeg,
                distanceToTargetMeters = dist
            )
        }
        }     // close else from line 264
    }         // close playSignal

    // ═══════════ helpers для approach / analysis ═══════════

    private fun playApproachSingleBeep(audioPlayer: StereoAudioPlayer) {
        audioPlayer.playTone(APPROACH_TONE1_FREQ, APPROACH_TONE_DUR_MS, 1.0f, APPROACH_TONE_BALANCE)
        thread(start = true) {
            Thread.sleep(APPROACH_PAIR_GAP_MS)
            audioPlayer.playTone(APPROACH_TONE2_FREQ, APPROACH_TONE_DUR_MS, 1.0f, APPROACH_TONE_BALANCE)
        }
    }

    private fun playApproachDoubleBeep(audioPlayer: StereoAudioPlayer) {
        // Первая пара (2 писка)
        audioPlayer.playTone(APPROACH_TONE1_FREQ, APPROACH_TONE_DUR_MS, 1.0f, APPROACH_TONE_BALANCE)
        thread(start = true) {
            Thread.sleep(APPROACH_PAIR_GAP_MS)
            audioPlayer.playTone(APPROACH_TONE2_FREQ, APPROACH_TONE_DUR_MS, 1.0f, APPROACH_TONE_BALANCE)
        }
        // Вторая пара (4 писка всего)
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
            // цель справа → баланс > 0.5 → звук вправо
            (APPROACH_TONE_BALANCE - clampedDeg / DEVIATION_BALANCE_SPAN.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            // цель слева → баланс < 0.5 → звук влево
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

    /** Вычислить длительность тона DEVIATION (мс) на основе отклонения в градусах. */
    fun deviationDurationMs(absDeviationDeg: Double): Long {
        return (DEVIATION_BASE_DUR_MS + (DEVIATION_DEG_PER_MS * absDeviationDeg)).toLong().coerceAtMost(600L)
    }
}
