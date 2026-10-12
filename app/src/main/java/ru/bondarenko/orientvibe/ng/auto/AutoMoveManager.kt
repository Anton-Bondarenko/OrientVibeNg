package ru.bondarenko.orientvibe.ng.auto

import android.util.Log
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt
import ru.bondarenko.orientvibe.ng.audio.StereoAudioPlayer
import ru.bondarenko.orientvibe.ng.gps.CalibrationPoint
import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.AudioSignalState
import ru.bondarenko.orientvibe.ng.model.AudioSignalType
import ru.bondarenko.orientvibe.ng.model.AutoMapState
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint as ModelCalibrationPoint
import ru.bondarenko.orientvibe.ng.model.CurrentControl
import ru.bondarenko.orientvibe.ng.model.DeviationDirection
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MovementState
import ru.bondarenko.orientvibe.ng.model.ON_TARGET_DEG
import ru.bondarenko.orientvibe.ng.model.FAR_INTERVAL_MS
import ru.bondarenko.orientvibe.ng.model.CLOSE_INTERVAL_MS
import ru.bondarenko.orientvibe.ng.model.AUDIO_ARRIVED_DIST

/** Дистанция до КП, при которой считается что пришли (м) */
const val AUTO_APPROACHED_DIST = 100f
const val DIST_GAP = 3f

/** Минимальная дистанция от начальной точки, чтобы подтвердить уход (м) */
private const val AUTO_MIN_MOVE_METERS = 3f

/** Время стабильного увеличения дистанции (мс) */
private const val AUTO_CONFIRM_TIMEOUT_MS = 10_000L

private const val TAG = "AutoMoveManager"

/** Результат doMove — immutable, ViewModel делает reassignment и StateFlow нотифицирует наблюдателей. */
data class DoMoveResult(
    val distanceToTarget: Double,
    val switchedControl: Boolean = false,
) {
    companion object {
        fun noSwitch(dist: Double) = DoMoveResult(distanceToTarget = dist)
    }
}

/** Результаты анализа навигации к текущей КП */
data class NavigationData(
    val bearingToTargetDeg: Double,   // курс от текущей позиции до КП (0..360)
    val distanceToTargetMeters: Double, // дистанция (Haversine)
    val deviationDeg: Double,          // отклонение от курса на КП (−180…+180), >0 = вправо, <0 = влево
    val direction: DeviationDirection,
)

/**
 * Вычисляет навигационные данные к текущей КП из boundingBox.
 * Требует двухточечную калибровку (pointB != null).
 */
fun analyzeNavigation(
    currentControlNum: Int,
    gpsFix: GpsFix,
    mapCalibration: MapCalibration,
    autoMapState: AutoMapState,
): NavigationData? {
    // Двухточечная калибровка обязательна
    if (mapCalibration.pointB == null) return null

    // Получаем GPS-координаты текущей КП из boundingBox
    val currentControlBoundingBox = autoMapState.controlsBoundingBoxes
        .find { it.number == currentControlNum }
        ?: return null

    // Для текущего КП используем bounding box центра
    val targetGps = MapGeometry.imageToGps(
        currentControlBoundingBox.centerX,
        currentControlBoundingBox.centerY,
        mapCalibration,
        autoMapState.northAngle
    ) ?: return null

    // Вычисляем курс и дистанцию до цели
    val bearingToTarget = MapGeometry.bearing(gpsFix.coordinate, targetGps)
    val distanceToTarget = MapGeometry.haversineDistance(gpsFix.coordinate, targetGps)

    // Отклонение = текущий GPS-курс − курс на цель
    var deviation = gpsFix.bearing - bearingToTarget
    // Приводим к диапазону −180…+180
    while (deviation > 180.0) deviation -= 360.0
    while (deviation < -180.0) deviation += 360.0

    val direction = when {
        abs(deviation) <= ON_TARGET_DEG -> DeviationDirection.CENTER
        deviation > 0 -> DeviationDirection.RIGHT
        else -> DeviationDirection.LEFT
    }

    return NavigationData(
        bearingToTargetDeg = bearingToTarget,
        distanceToTargetMeters = distanceToTarget,
        deviationDeg = deviation,
        direction = direction,
    )
}

/**
 * Определяет тип и параметры аудиосигнала на основе навигационных данных.
 *
 * Правила:
 * 1. Дистанция < 20м → ARRIVED (трель), отключить все сигналы
 * 2. Отклонение ≤ ±5° → ON_TARGET (короткий beep в оба канала)
 *    − каждые 30с если дистанция > 100м
 *    − каждые 10с если дистанция ≤ 100м
 * 3. Отклонение > 5° → DEVIATION (сигнал в канал поворота)
 *    − частота падает с ростом угла: 800Hz при 5°, 300Hz при 45°+
 *    − длительность растёт: 200ms при 5°, +5мс/градус
 */
fun analyzeAudioSignal(
    navigation: NavigationData,
): AudioSignalState {
    val deg = abs(navigation.deviationDeg)
    val dist = navigation.distanceToTargetMeters

    return when {
        // ── Пришли (дистанция < 20м) — трель, отключить все сигналы ──
        dist < AUDIO_ARRIVED_DIST -> AudioSignalState(
            type = AudioSignalType.ARRIVED,
            frequencyHz = 600,
            leftBalance = 0.5f,
            intervalMs = 0L, // однократная трель
            deviationDeg = navigation.deviationDeg,
            distanceToTargetMeters = dist,
        )

        // ── В секторе ±5° — periodic beep в оба канала ──
        deg <= ON_TARGET_DEG -> {
            val interval = if (dist > 100) FAR_INTERVAL_MS else CLOSE_INTERVAL_MS
            val freq = if (dist > 100) 1000 else 1200
            AudioSignalState(
                type = AudioSignalType.ON_TARGET,
                frequencyHz = freq,
                leftBalance = 0.5f, // оба канала одинаково
                intervalMs = interval,
                deviationDeg = navigation.deviationDeg,
                distanceToTargetMeters = dist,
            )
        }

        // ── Отклонение > 5° — сигнал в канал поворота, частота падает ──
        else -> {
            val absDeg = abs(navigation.deviationDeg)
            // Частота: 800Hz при 5°, линейно до 300Hz при 45°+
            val freq = (800.0 - ((absDeg - ON_TARGET_DEG) / 40.0 * 500.0))
                .coerceIn(300.0, 800.0).toInt()
            // Длительность: 200ms при 5°, +5мс за каждый градус
            val durationMs = (200 + absDeg * 5).toLong().coerceAtMost(600)
            // Баланс: левый канал для поворота влево, правый для поворота вправо
            val balance = if (navigation.deviationDeg < 0) 0f else 1f

            Log.d(TAG, "DEVIATION ${absDeg.toInt()}° → freq=${freq}Hz dur=${durationMs}ms bal=$balance")
            AudioSignalState(
                type = AudioSignalType.DEVIATION,
                frequencyHz = freq,
                leftBalance = balance,
                intervalMs = if (dist > 100) FAR_INTERVAL_MS else CLOSE_INTERVAL_MS,
                deviationDeg = navigation.deviationDeg,
                distanceToTargetMeters = dist,
            )
        }
    }
}

class AutoMoveManager {
    /** Момент первого приближения к текущей КП, когда начали уходить */
    private var _approachTimeMs: Long? = null

    /** Дистанция в момент первого приближения (baseline) */
    private var _approachDistance: Double = 0.0
    private var _approachDetected: Boolean = false
    private var _supposeControlHere: GpsCoordinate? = null

    /**
     * Выполняет «движение»: вычисляет дистанцию до текущей КП.
     * Переключает на следующую когда:
     * 1) расстояние <= AUTO_APPROACHED_DIST (20 м), И
     * 2) с момента первого приближения прошло ≥ 5 секунд,
     *    И дистанция увеличилась минимум на AUTO_MIN_MOVE_METERS
     *
     * Возвращает DoMoveResult. ViewModel должен сделать:
     *   _movementState.value = _autoMoveManager.doMove(...)
     * чтобы StateFlow уведомил наблюдателей.
     */
    fun doMove(
        autoMapState: AutoMapState,
        movementState: MovementState,
        mapCalibration: MapCalibration,
        gpsFix: GpsFix
    ): MovementState {
        val boundingBox = movementState.currentControl.boundingBox
        if (boundingBox != null) {
            val currentControlCoordinates =
                getBoundingBoxCoordinates(boundingBox, mapCalibration, autoMapState)
                    ?: gpsFix.coordinate
            val distance =
                MapGeometry.haversineDistance(gpsFix.coordinate, currentControlCoordinates)
                    .toDouble()

            if (shouldSwitch(distance, gpsFix) && mapCalibration.pointB != null) {
                movementState.newMapCalibration = switchControl(
                    autoMapState,
                    movementState,
                    mapCalibration,
                    autoMapState
                )
            } else {
                movementState.newMapCalibration = null
            }
            _approachDistance = distance
            return movementState
        }
        return movementState
    }

    /**
     * @return true если прошло ≥ 5с и дистанция стабильно выросла от baseline
     */
    private fun shouldSwitch(distance: Double, gpsFix: GpsFix): Boolean {
        // Первый вход в зону КП — фиксируем baseline
        if (distance < AUTO_APPROACHED_DIST && !_approachDetected) {
            _approachDetected = true
        }

        // удаляемся от КП
        if (_approachDistance < (distance + DIST_GAP) && _approachDetected) {
            if (_approachTimeMs == null) {
                _approachTimeMs = System.currentTimeMillis()
                _supposeControlHere = gpsFix.coordinate
            }

            val elapsed = System.currentTimeMillis() - _approachTimeMs!!
            if (elapsed >= AUTO_CONFIRM_TIMEOUT_MS) { // переключаемся на следующий
                return true
            }
        } else {
            _approachTimeMs = null
            _supposeControlHere = null
        }
        return false
    }

    private fun detectionReset() {
        _approachTimeMs = null
        _approachDetected = false
        _approachDistance = Double.MAX_VALUE
        _supposeControlHere = null
    }

    private fun switchControl(
        mapState: AutoMapState,
        ms: MovementState,
        mapCalibration: MapCalibration,
        autoMapState: AutoMapState
    ): MapCalibration? {
        val currentNum = ms.currentControl.num
        Log.d(TAG, "дошли до КП #$currentNum, переключение на следующую")

        // Сохраняем старую текущую КП в prev перед обновлением
        val oldCurrentBox = ms.currentControl.boundingBox
        ms.prevControl = CurrentControl(
            num = currentNum,
            boundingBox = oldCurrentBox
        )

        val nextNum = currentNum + 1
        val nextBox = mapState.controlsBoundingBoxes.find { it.number == nextNum }

        ms.currentControl = CurrentControl(
            num = nextNum,
            boundingBox = nextBox
        )
        var newCalibration: MapCalibration? = null
        val supposeControlHere = _supposeControlHere
        if (oldCurrentBox != null && supposeControlHere != null && mapCalibration.pointB != null) {
            newCalibration = MapGeometry.computeCalibrationSoft(
                mapCalibration.pointA,
                ModelCalibrationPoint(supposeControlHere, oldCurrentBox.centerX, oldCurrentBox.centerY),
                autoMapState.northAngle, mapCalibration
            )
        }
        detectionReset()

        return newCalibration
    }

    fun getBoundingBoxCoordinates(
        boundingBox: BoundingBox,
        mapCalibration: MapCalibration,
        autoMapState: AutoMapState
    ): GpsCoordinate? {
        return MapGeometry.imageToGps(
            boundingBox.centerX, boundingBox.centerY, mapCalibration, autoMapState.northAngle
        )
    }
}
