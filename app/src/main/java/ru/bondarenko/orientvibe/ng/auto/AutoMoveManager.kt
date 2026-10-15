package ru.bondarenko.orientvibe.ng.auto

import android.util.Log
import ru.bondarenko.orientvibe.ng.gps.CalibrationPoint
import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.APPROACH_DOUBLE_DIST
import ru.bondarenko.orientvibe.ng.model.APPROACH_SINGLE_DIST
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
const val AUTO_APPROACHED_DIST = 90.0
const val DIST_GAP = 40.0

/** Максимальное отклонение направления движения для «идём к следующему КП» (°) */
private const val HEADING_TOLERANCE_DEG = 30.0

/** Минимальный скачок направления, считаем что повернули (>°) */
private const val HEADING_JUMP_DEG = 35.0
private const val HEADING_BIG_JUMP_DEG = 35.0

/** Сколько секунд трек-поинтов хранятся для анализа */
private const val TRACK_HISTORY_SECONDS = 30

/** За сколько секунд до уверенного ухода — берём усреднённое направление */
private const val HEADING_WINDOW_SECONDS = 2
private const val HEADING_WINDOW_LONG_SECONDS = 10

/** Время стабильного увеличения дистанции (мс) */
private const val AUTO_CONFIRM_TIMEOUT_MS = 10_000L

private const val TAG = "AutoMoveManager"

/** GPS-фикс с временной меткой и усреднённым направлением (файл-локальный тип) */
data class TimedGpsPrivate(
    val coordinate: GpsCoordinate,
    val timestamp: Long,
    val realBearing: Double,
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
 * 1. Дистанция < 40м → ARRIVED (трель, один раз), потом тишина
 * 2. Дистанция <= 50м → APPROACH_DOUBLE (два двойных сигнала)
 * 3. Дистанция <= 100м → APPROACH_SINGLE (один двойной сигнал)
 * 4. Отклонение > 10° → DEVIATION (сигнал в канал поворота)
 * 5. Иначе → NONE (в секторе — тишина, без ON_TARGET)
 */
fun analyzeAudioSignal(
    navigation: NavigationData,
): AudioSignalState {
    val deg = abs(navigation.deviationDeg)
    val dist = navigation.distanceToTargetMeters

    return when {
        // ── Пришли (дистанция < 40м) — трель, потом тишина ──
        dist < AUDIO_ARRIVED_DIST -> AudioSignalState(
            type = AudioSignalType.ARRIVED,
            frequencyHz = 600,
            leftBalance = 0.5f,
            intervalMs = 0L, // однократная трель
            deviationDeg = navigation.deviationDeg,
            distanceToTargetMeters = dist,
        )

        // ── Подход: два двойных (< 50м) ──
        dist <= APPROACH_DOUBLE_DIST -> AudioSignalState(
            type = AudioSignalType.APPROACH_DOUBLE,
            frequencyHz = 600,
            leftBalance = 0.5f,
            intervalMs = CLOSE_INTERVAL_MS,
            deviationDeg = navigation.deviationDeg,
            distanceToTargetMeters = dist,
        )

        // ── Подход: один двойной (< 100м) ──
        dist <= APPROACH_SINGLE_DIST -> AudioSignalState(
            type = AudioSignalType.APPROACH_SINGLE,
            frequencyHz = 600,
            leftBalance = 0.5f,
            intervalMs = CLOSE_INTERVAL_MS,
            deviationDeg = navigation.deviationDeg,
            distanceToTargetMeters = dist,
        )

        // ── Отклонение > 10° — сигнал в канал поворота, частота падает ──
        else -> {
            val absDeg = abs(navigation.deviationDeg)
            // Частота: 800Hz при 10°, линейно до 300Hz при 50°+
            val freq = (800.0 - ((absDeg - ON_TARGET_DEG) / 40.0 * 500.0))
                .coerceIn(300.0, 800.0).toInt()
            // Длительность: 200ms при 10°, +5мс за каждый градус
            val durationMs = (200 + absDeg * 5).toLong().coerceAtMost(600)
            // Баланс: левый канал для поворота влево, правый для поворота вправо
            val balance = if (navigation.deviationDeg < 0) 0f else 1f

            // Интервал: чем больше отклонение — тем чаще сигнал
            val intervalMs = when {
                absDeg > 30.0 -> 10_000L   // > 30° → каждые 10с
                absDeg > 20.0 -> 20_000L   // > 20° → каждые 20с
                else -> FAR_INTERVAL_MS  // ≤ 20° → каждые 30с
            }

            Log.d(
                TAG,
                "DEVIATION ${absDeg.toInt()}° → freq=${freq}Hz dur=${durationMs}ms bal=$balance interval=${intervalMs / 1000}с"
            )
            AudioSignalState(
                type = AudioSignalType.DEVIATION,
                frequencyHz = freq,
                leftBalance = balance,
                intervalMs = intervalMs,
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
//    private var _supposeControlHere: GpsCoordinate? = null

    /** Следующее КП по номеру — хранится для анализа направления ухода */
    private var _nextBoundingBox: BoundingBox? = null

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
                }
                val elapsed = System.currentTimeMillis() - _approachTimeMs!!
                if (elapsed >= AUTO_CONFIRM_TIMEOUT_MS && (distance - _approachMinDistance) > DIST_GAP) { // переключаемся на следующий
                    return true
                }
            }
        }
        return false
    }


    private fun getLastVector(gps: GpsCoordinate, time: Long): Double? {
        if (_trackHistory.size < 2)
            return null
        // Фильтруем точки в окне поиска (последние 3 сек)
        var startIdx = 0
        if (time == 0L) return MapGeometry.bearing(
            _trackHistory[_trackHistory.size - 1].coordinate,
            gps
        )
        // Определяем начало окна усреднения (последние 3 сек)
        val windowEnd = _trackHistory.last().timestamp - time
        while (startIdx < _trackHistory.size && _trackHistory[startIdx].timestamp < windowEnd) startIdx++
        if (startIdx >= _trackHistory.size - 1) _trackHistory.size - 2
        // Это последние 3 секунды
        val lastPoints = _trackHistory.subList(startIdx, _trackHistory.size)
        return MapGeometry.bearing(lastPoints.first().coordinate, gps)
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
                getLastVector(gpsFix.coordinate, 0L) ?: 0.0,
                getLastVector(gpsFix.coordinate, HEADING_WINDOW_SECONDS * 1000L) ?: 0.0,
                getLastVector(gpsFix.coordinate, HEADING_WINDOW_LONG_SECONDS * 1000L) ?: 0.0
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
    ): ModelCalibrationPoint? {
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

        val currentBearing = _trackHistory.last().average10Bearing

        Log.d(TAG, "направлениe на цель: $bearingToNext движение: ${currentBearing}")

        val bearingDiff = abs(currentBearing - bearingToNext)
        val fullSearchStart = System.currentTimeMillis() - TRACK_HISTORY_SECONDS * 1000L
        var globalStartIdx = 0
        while (globalStartIdx < _trackHistory.size && _trackHistory[globalStartIdx].timestamp < fullSearchStart) globalStartIdx++

        Log.d(TAG, "Отклонение $bearingDiff")

        // Проверяем совпадение с направлением к следующему КП
        if (bearingDiff > HEADING_TOLERANCE_DEG) {
            // Направление совпадает — ищем самую раннюю точку, где курс резко изменился (>10°) к следующему КП
            for (i in _trackHistory.size - 2 downTo globalStartIdx) {
                // Проверяем: направление отличается от первоначального (>10°) И направлено к следующему КП
                val diff =
                    abs(_trackHistory[i].average3Bearing - _trackHistory[i + 1].realBearing)
                if (diff < HEADING_JUMP_DEG) continue
                Log.d(
                    TAG,
                    "Нашлась точка изменения направления на треке на цель: $bearingToNext трек: ${_trackHistory[i + 1].average3Bearing} "
                )
                return CalibrationPoint(
                    _trackHistory[i].coordinate,
                    currentBox.centerX,
                    currentBox.centerY,
                    0.8
                ) // Самая ранняя точка с изменённым направлением
            }
        }

        // попробуем просто найти изменение направления движения
        for (i in _trackHistory.size - 2 downTo globalStartIdx) {
            // Проверяем: направление отличается от первоначального (>10°) И направлено к следующему КП
            val diff =
                abs(_trackHistory[i].average3Bearing - _trackHistory[i + 1].realBearing)
            if (diff < HEADING_BIG_JUMP_DEG) continue
            Log.d(TAG, "Нашлась точка сильного изменения направления на треке $diff")
            return CalibrationPoint(
                _trackHistory[i].coordinate,
                currentBox.centerX,
                currentBox.centerY,
                0.6
            ) // Самая ранняя точка с изменённым направлением
        }

        // Ищем ближайшую точку на треке
        // init
        var minDistance: Double = MapGeometry.haversineDistance(_trackHistory[_trackHistory.size - 1].coordinate, currentControl)
        var nearestCalibrationPoint: CalibrationPoint = CalibrationPoint(_trackHistory[_trackHistory.size - 1].coordinate, currentBox.centerX, currentBox.centerY, 0.3)
        for (i in _trackHistory.size - 2 downTo globalStartIdx) {
            val distance = MapGeometry.haversineDistance(_trackHistory[i].coordinate, currentControl)
            if (distance < minDistance){
                nearestCalibrationPoint = CalibrationPoint(_trackHistory[i].coordinate, currentBox.centerX,
                    currentBox.centerY, 0.4)
            }
        }

        Log.d(TAG, "Не нашли точку на треке, используем ближайшую")
        // Не нашли — возвращаем default
        return nearestCalibrationPoint
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
//        _supposeControlHere = null
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

        // Если направление ухода совпадает с направлением к следующему КП, ищем точку поворота
        val effectivePoint = _nextBoundingBox?.let { nextBox ->
            findHeadingAlignedCalibrationPoint(
                oldCurrentBox,
                nextBox,
                mapCalibration,
                autoMapState
            )
        }
        if (oldCurrentBox != null && effectivePoint != null) {
            // Вычисляем новую точку B (предполагаемое положение старой КП в GPS)
            val newPoint = effectivePoint

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

                // Большое смещение — сравниваем квадранты
                val oldQuad = MapGeometry.pointInQuadrant(
                    mapCalibration.pointA.imageX,
                    mapCalibration.pointA.imageY
                )
                val newQuad = MapGeometry.pointInQuadrant(
                    oldCurrentBox.centerX,
                    oldCurrentBox.centerY
                )
                newCalibration = if (oldQuad == newQuad) {
                    // Та же область карты — обновляем масштаб и угол
                    Log.d(TAG, "Калибровка computeCalibrationSoft (same quadrant)")
                    MapGeometry.computeCalibrationSoft(
                        mapCalibration.pointA,
                        newPoint,
                        autoMapState.northAngle, mapCalibration
                    )
                } else {
                    // Другая область — сохраняем масштаб/угол, обновляем только pointA
                    Log.d(TAG, "Калибровка calibrationSingleSoft (different quadrant)")
                    MapGeometry.calibrationSingleSoft(
                        newPoint,
                        mapCalibration.magneticDeclination,
                        autoMapState.northAngle,
                        mapCalibration.scaleMetersPerMapX,
                        mapCalibration.scaleMetersPerMapY,
                        mapCalibration
                    )
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
