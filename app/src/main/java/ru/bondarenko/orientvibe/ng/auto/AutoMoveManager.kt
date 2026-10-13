package ru.bondarenko.orientvibe.ng.auto

import android.util.Log
import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.AUDIO_ARRIVED_DIST
import ru.bondarenko.orientvibe.ng.model.AudioSignalState
import ru.bondarenko.orientvibe.ng.model.AudioSignalType
import ru.bondarenko.orientvibe.ng.model.AutoMapState
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.CLOSE_INTERVAL_MS
import ru.bondarenko.orientvibe.ng.model.CurrentControl
import ru.bondarenko.orientvibe.ng.model.DeviationDirection
import ru.bondarenko.orientvibe.ng.model.FAR_INTERVAL_MS
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MovementState
import ru.bondarenko.orientvibe.ng.model.NavigationData
import ru.bondarenko.orientvibe.ng.model.ON_TARGET_DEG
import kotlin.math.abs
import kotlin.math.min
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint as ModelCalibrationPoint

/** Дистанция до КП, при которой считается что пришли (м) */
const val AUTO_APPROACHED_DIST = 100f
const val DIST_GAP = 40f

/** Максимальное отклонение направления движения для «идём к следующему КП» (°) */
private const val HEADING_TOLERANCE_DEG = 20.0

/** Минимальный скачок направления, считаем что повернули (>°) */
private const val HEADING_JUMP_DEG = 15.0

/** Сколько секунд трек-поинтов хранятся для анализа */
private const val TRACK_HISTORY_SECONDS = 30

/** За сколько секунд до уверенного ухода — берём усреднённое направление */
private const val HEADING_WINDOW_SECONDS = 3
private const val HEADING_WINDOW_LONG_SECONDS = 10

/** Время стабильного увеличения дистанции (мс) */
private const val AUTO_CONFIRM_TIMEOUT_MS = 10_000L

private const val TAG = "AutoMoveManager"

/** GPS-фикс с временной меткой и усреднённым направлением (файл-локальный тип) */
data class TimedGpsPrivate(
    val coordinate: GpsCoordinate,
    val timestamp: Long,
    val average3Bearing: Double,
    val average10Bearing: Double
)

/** Результат doMove — immutable, ViewModel делает reassignment и StateFlow нотифицирует наблюдателей. */
data class DoMoveResult(
    val distanceToTarget: Double,
    val switchedControl: Boolean = false,
) {
    companion object {
        fun noSwitch(dist: Double) = DoMoveResult(distanceToTarget = dist)
    }
}

/** Время усреднения курса до цели (сек) */
private const val NAV_BEARING_AVG_SECONDS = 3

/** Усредняет курс от точек за последние NAV_BEARING_AVG_SECONDS до цели. */
private fun avgBearingToTrack(
    currentPos: GpsCoordinate,
    history: List<TimedGpsPrivate>,
    target: GpsCoordinate
): Double {
    val cutoff = history.last().timestamp - NAV_BEARING_AVG_SECONDS * 1000L
    var sinSum = 0.0
    var cosSum = 0.0
    var n = 0
    for (pt in history) {
        if (pt.timestamp < cutoff) continue
        val b = MapGeometry.bearing(pt.coordinate, target)
        sinSum += kotlin.math.sin(b / 180.0 * kotlin.math.PI)
        cosSum += kotlin.math.cos(b / 180.0 * kotlin.math.PI)
        n++
    }
    return if (n > 0) {
        ((kotlin.math.atan2(sinSum / n, cosSum / n) / kotlin.math.PI * 180.0) + 360.0) % 360.0
    } else {
        MapGeometry.bearing(currentPos, target)
    }
}

/**
 * Вычисляет навигационные данные к текущей КП из boundingBox.
 * Требует двухточечную калибровку (pointB != null).
 * Если передана trackHistory, усредняет bearingToTarget за последние NAV_BEARING_AVG_SECONDS.
 */
fun analyzeNavigation(
    currentControlNum: Int,
    gpsFix: GpsFix,
    mapCalibration: MapCalibration,
    autoMapState: AutoMapState,
    trackHistory: List<TimedGpsPrivate> = emptyList(),
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

    // Усредняем bearingToTarget за последние NAV_BEARING_AVG_SECONDS по треку
    val bearingToTarget = MapGeometry.bearing(gpsFix.coordinate, targetGps)

    // Отклонение = текущий GPS-курс − усреднённый курс на цель
    var deviation =
        if (trackHistory.isEmpty()) trackHistory.last().average10Bearing - bearingToTarget else gpsFix.bearing - bearingToTarget
    // Приводим к диапазону −180…+180
    while (deviation > 180.0) deviation -= 360.0
    while (deviation < -180.0) deviation += 360.0

    val direction = when {
        abs(deviation) <= ON_TARGET_DEG -> DeviationDirection.CENTER
        deviation > 0 -> DeviationDirection.RIGHT
        else -> DeviationDirection.LEFT
    }

    // Дистанция до цели (Haversine)
    val distanceToTarget = MapGeometry.haversineDistance(gpsFix.coordinate, targetGps)

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

        // ── В секторе — periodic beep в оба канала ──
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

            Log.d(
                TAG,
                "DEVIATION ${absDeg.toInt()}° → freq=${freq}Hz dur=${durationMs}ms bal=$balance"
            )
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
    private var _approachMinDistance: Double = 0.0
    private var _approachDetected: Boolean = false
    private var _supposeControlHere: GpsCoordinate? = null

    /** Следующее КП по номеру — хранится для анализа направления ухода */
    private var _nextBoundingBox: BoundingBox? = null

    /** Для чередования двухточечной калибровки при дистанции >= DISTANCE_FAR_CALIB (м) */
    private var _farCalibToggle: Boolean = false

    /** История GPS-фиксов за последние 30 сек — для анализа направления ухода */
    private val _trackHistory = mutableListOf<TimedGpsPrivate>()

    /** Расстояние, выше которого начинается чередование типов калибровки */
    private companion object {
        val DISTANCE_FAR_CALIB = 150.0
    }

    /** Публичный доступ к трек-истории для анализа навигации */
    val trackHistory: List<TimedGpsPrivate> get() = _trackHistory

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

            // Запоминаем следующее КП для анализа направления ухода
            val nextNum = movementState.currentControl.num + 1
            _nextBoundingBox = autoMapState.controlsBoundingBoxes.find { it.number == nextNum }

            // Добавляем фикс в историю трек-поинтов (последние ? сек)
            addTrackHistory(gpsFix)

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
            //_approachDistance = distance
            return movementState
        }
        return movementState
    }

    /**
     * @return true если прошло ≥ 5с и дистанция стабильно выросла от baseline,
     *         и направление движения совпадает с направлением к следующему КП (±10°)
     */
    private fun shouldSwitch(distance: Double, gpsFix: GpsFix): Boolean {
        // Первый вход в зону КП — фиксируем baseline
        if (distance < AUTO_APPROACHED_DIST && !_approachDetected) {
            _approachDetected = true
        }
        _approachMinDistance = min(_approachMinDistance, distance)
        // удаляемся от КП
        if (_approachDetected) {
            if (_approachMinDistance < distance) {
                if (_approachTimeMs == null) {
                    _approachTimeMs = System.currentTimeMillis()
                    _supposeControlHere = gpsFix.coordinate
                }
                val elapsed = System.currentTimeMillis() - _approachTimeMs!!
                if (elapsed >= AUTO_CONFIRM_TIMEOUT_MS && (distance - _approachMinDistance) > DIST_GAP) { // переключаемся на следующий
                    return true
                }
            }
        } else {
            _approachTimeMs = null
            _supposeControlHere = null
        }
        return false
    }

    private fun getLastVector(time: Long): Double? {
        if (_trackHistory.isEmpty())
            return null
        // Фильтруем точки в окне поиска (последние 3 сек)
        var startIdx = 0
        // Определяем начало окна усреднения (последние 3 сек)
        val windowEnd = _trackHistory.last().timestamp - time
        while (startIdx < _trackHistory.size && _trackHistory[startIdx].timestamp < windowEnd) startIdx++
        if (startIdx >= _trackHistory.size - 1) startIdx = 0
        // Это последние 3 секунды
        val lastPoints = _trackHistory.subList(startIdx, _trackHistory.size)
        return MapGeometry.bearing(lastPoints.first().coordinate, lastPoints.last().coordinate)
    }

    /** Добавляем GPS-фикс в историю трек-поинтов (последние ? сек) */
    private fun addTrackHistory(gpsFix: GpsFix) {
        // Фильтруем точки в окне поиска (последние 3 сек)
//        var startIdx = 0
//        // Определяем начало окна усреднения (последние 3 сек)
//        val windowEnd = System.currentTimeMillis() - HEADING_WINDOW_SECONDS * 1000L
//        while (startIdx < _trackHistory.size && _trackHistory[startIdx].timestamp < windowEnd) startIdx++
//        if (startIdx >= _trackHistory.size - 1) startIdx = _trackHistory.size
//        // Это последние 3 секунды
//        val lastPoints = _trackHistory.subList(startIdx, _trackHistory.size)
//        // Вычисляем усреднённый вектор направления за последние 3 сек
//        val avgLastBearing = averageBearing(lastPoints) ?: 0.0
        _trackHistory.add(
            TimedGpsPrivate(
                gpsFix.coordinate,
                gpsFix.timestamp,
                getLastVector(HEADING_WINDOW_SECONDS * 1000L) ?: 0.0,
                getLastVector(HEADING_WINDOW_LONG_SECONDS * 1000L) ?: 0.0
            )
        )


        // Удаляем старые точки
        val cutoff = gpsFix.timestamp - TRACK_HISTORY_SECONDS * 1000L
        var i = 0
        while (i < _trackHistory.size && _trackHistory[i].timestamp < cutoff) i++
        if (i > 0) _trackHistory.subList(0, i).clear()
    }

    /**
     * Проверяет, что усреднённое направление движения за последние HEADING_WINDOW_SECONDS
     * совпадает с направлением до nextBox в пределах HEADING_TOLERANCE_DEG.
     * Если совпадает — ищет самую раннюю точку в треке (до 30 сек назад), где направление
     * значительно (>10°) изменилось к следующему КП, и возвращает её координату.
     * Иначе — null.
     */
    private fun findHeadingAlignedCalibrationPoint(
        currentBox: BoundingBox?,
        nextBox: BoundingBox,
        mapCalibration: MapCalibration,
        autoMapState: AutoMapState
    ): GpsCoordinate? {
        currentBox ?: return null
        // Определяем ориентир
        val nextControl = MapGeometry.imageToGps(
            nextBox.centerX, nextBox.centerY, mapCalibration, autoMapState.northAngle
        )
        if (nextControl == null) return null
        val currentControl = MapGeometry.imageToGps(
            currentBox.centerX, currentBox.centerY, mapCalibration, autoMapState.northAngle
        )
        if (currentControl == null) return null

        // направление до следующего КП
        val bearingToNext = MapGeometry.bearing(currentControl, nextControl)

        val currentBearing = _trackHistory.last().average3Bearing

        Log.d(TAG, "направлениe на цель: $bearingToNext движение: ${currentBearing}")
        // Проверяем совпадение с направлением к следующему КП
        val bearingDiff = abs(currentBearing - bearingToNext)
        if (bearingDiff > HEADING_TOLERANCE_DEG) return null

        // Направление совпадает — ищем самую раннюю точку, где курс резко изменился (>10°) к следующему КП
        val fullSearchStart = System.currentTimeMillis() - TRACK_HISTORY_SECONDS * 1000L
        var globalStartIdx = 0
        while (globalStartIdx < _trackHistory.size && _trackHistory[globalStartIdx].timestamp < fullSearchStart) globalStartIdx++

        for (i in globalStartIdx until _trackHistory.size - 2) {
            // Проверяем: направление отличается от первоначального (>10°) И направлено к следующему КП
            val diff = abs(_trackHistory[i].average3Bearing - _trackHistory[i + 1].average3Bearing)
            val diffCourseToNext = abs(_trackHistory[i + 1].average3Bearing - bearingToNext)
            if (diff < HEADING_JUMP_DEG || diffCourseToNext > HEADING_TOLERANCE_DEG) continue
            Log.d(
                TAG,
                "Нашлась точка изменения направления на треке цель: $bearingToNext трек: ${_trackHistory[i + 1].average3Bearing}"
            )
            return _trackHistory[i].coordinate // Самая ранняя точка с изменённым направлением
        }

        // Не нашли — возвращаем default (начало ухода)
        return null
    }

    /** Вычисляет усреднённый векторный курс из списка точек */
    private fun averageBearing(points: List<TimedGpsPrivate>): Double? {
        if (points.size < 2) return null
        var sinSum = 0.0
        var cosSum = 0.0
        val n = points.size - 1
        for (i in 0 until n) {
            val b = MapGeometry.bearing(points[i].coordinate, points[i + 1].coordinate)
            sinSum += kotlin.math.sin(b / 180.0 * kotlin.math.PI)
            cosSum += kotlin.math.cos(b / 180.0 * kotlin.math.PI)
        }
        return ((kotlin.math.atan2(
            sinSum / n,
            cosSum / n
        ) / kotlin.math.PI * 180.0) + 360.0) % 360.0
    }

    private fun detectionReset() {
        _approachTimeMs = null
        _approachDetected = false
        _approachMinDistance = Double.MAX_VALUE
        _supposeControlHere = null
        _nextBoundingBox = null
    }

    private fun switchControl(
        mapState: AutoMapState,
        ms: MovementState,
        mapCalibration: MapCalibration,
        autoMapState: AutoMapState
    ): MapCalibration? {
        val currentNum = ms.currentControl.num
        Log.d(TAG, "дошли до КП #$currentNum, переключение на следующий")

        // Сохраняем старую текущую КП в prev перед обновлением
        val oldCurrentBox = ms.currentControl.boundingBox
        ms.prevControl = CurrentControl(
            num = currentNum,
            boundingBox = oldCurrentBox
        )
        Log.d(TAG, "current num ${currentNum}")
        val nextNum = currentNum + 1
        val nextBox = mapState.controlsBoundingBoxes.find { it.number == nextNum }

        ms.currentControl = CurrentControl(
            num = nextNum,
            boundingBox = nextBox
        )

        var newCalibration: MapCalibration? = null
        val supposeControlHere = _supposeControlHere

        // Если направление ухода совпадает с направлением к следующему КП, ищем точку поворота
        val effectivePoint = _nextBoundingBox?.let { nextBox ->
            findHeadingAlignedCalibrationPoint(oldCurrentBox, nextBox, mapCalibration, autoMapState)
        } ?: supposeControlHere
        if (oldCurrentBox != null && effectivePoint != null) {
            // Вычисляем новую точку B (предполагаемое положение старой КП в GPS)
            val newPoint = ModelCalibrationPoint(
                effectivePoint,
                oldCurrentBox.centerX,
                oldCurrentBox.centerY
            )

            // Если pointB был null (одноточечная калибровка) — используем computeCalibrationSoft
            if (mapCalibration.pointB == null) {
                Log.d(TAG, "Калибровка computeCalibrationSoft(первая)")
                newCalibration = MapGeometry.computeCalibrationSoft(
                    mapCalibration.pointA,
                    newPoint,
                    autoMapState.northAngle, mapCalibration
                )
            } else {
                // Дистанция между текущим pointA.gps и новым GPS
                val dist = MapGeometry.haversineDistance(
                    mapCalibration.pointA.gps,
                    newPoint.gps
                )

                if (dist < 150.0) {
                    // Маленькое смещение — одноточечная калибровка: меняем только pointA GPS,
                    // масштаб и угол без изменений
                    Log.d(TAG, "Калибровка calibrationSingleSoft(малая дистанция)")
                    newCalibration = MapGeometry.calibrationSingleSoft(
                        newPoint,
                        mapCalibration.magneticDeclination,
                        autoMapState.northAngle,
                        mapCalibration.scaleMetersPerMapX,
                        mapCalibration.scaleMetersPerMapY,
                        mapCalibration
                    )
                } else {
                    // Большое смещение — чередуем два типа двухточечной калибровки
                    newCalibration = if (_farCalibToggle) {
                        Log.d(TAG, "Калибровка computeCalibrationSoft")
                        MapGeometry.computeCalibrationSoft(
                            mapCalibration.pointA,
                            newPoint,
                            autoMapState.northAngle, mapCalibration
                        )
                    } else {
                        Log.d(TAG, "Калибровка calibrationSingle")
                        MapGeometry.calibrationSingleSoft(
                            newPoint,
                            mapCalibration.magneticDeclination,
                            autoMapState.northAngle,
                            mapCalibration.scaleMetersPerMapX,
                            mapCalibration.scaleMetersPerMapY,
                            mapCalibration
                        )
                    }
                    _farCalibToggle = !_farCalibToggle
                }
            }
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
