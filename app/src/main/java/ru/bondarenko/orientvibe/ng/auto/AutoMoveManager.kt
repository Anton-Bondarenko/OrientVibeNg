package ru.bondarenko.orientvibe.ng.auto

import android.util.Log
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.*
import kotlin.math.*

/**
 * Результат шага автоматического ведения по дистанции.
 */
data class DoMoveResult(
    val distanceToTarget: Double,
    val switchedControl: Boolean = false
) {
    companion object {
        fun noSwitch(dist: Double): DoMoveResult = DoMoveResult(dist, false)
    }
}

/**
 * Точка GPS-трека с рассчитанными мгновенным и усредненными азимутами движения.
 */
data class TimedGpsPrivate(
    val coordinate: GpsCoordinate,
    val timestamp: Long,
    val realBearing: Double,
    val average3Bearing: Double,
    val average10Bearing: Double
)

/**
 * Менеджер автоматического переключения контрольных пунктов (КП),
 * анализа траектории и фоновой автокалибровки карты.
 */
class AutoMoveManager {

    private var _approachDetected = false
    private var _approachMinDistance = Double.MAX_VALUE
    private var _approachTimeMs: Long? = null
    private var _nextBoundingBox: BoundingBox? = null

    private val _trackHistory = ArrayDeque<TimedGpsPrivate>(128)
    val trackHistory: List<TimedGpsPrivate>
        get() = _trackHistory.toList()

    private val _autoCalibrations = ArrayList<MapCalibration>()
    val autoCalibrations: List<MapCalibration>
        get() = _autoCalibrations.toList()

    /**
     * Возвращает последний усредненный азимут (за 2-3 сек) без копирования списка истории.
     */
    fun getLastAverage3Bearing(): Double? {
        return if (_trackHistory.size >= 2) _trackHistory.last().average3Bearing else null
    }

    fun doMove(
        autoMapState: AutoMapState,
        movementState: MovementState,
        mapCalibration: MapCalibration,
        gpsFix: GpsFix
    ): MovementState {
        val boundingBox = movementState.currentControl.boundingBox ?: return movementState

        val currentControlCoordinates = getBoundingBoxCoordinates(boundingBox, mapCalibration, autoMapState)
            ?: gpsFix.coordinate

        val distance = MapGeometry.haversineDistance(
            gpsFix.coordinate,
            currentControlCoordinates
        )

        val nextNum = movementState.currentControl.num + 1
        _nextBoundingBox = autoMapState.controlsBoundingBoxes.firstOrNull { it.number == nextNum }

        addTrackHistory(gpsFix)

        if (shouldSwitch(distance) && mapCalibration.pointB != null) {
            movementState.newMapCalibration = switchControl(autoMapState, movementState, mapCalibration)
        } else {
            movementState.newMapCalibration = null
        }

        return movementState
    }

    private fun shouldSwitch(distance: Double): Boolean {
        if (distance < AUTO_APPROACHED_DIST && !_approachDetected) {
            _approachDetected = true
        }

        _approachMinDistance = min(_approachMinDistance, distance)

        if (_approachDetected && _approachMinDistance < distance) {
            if (_approachTimeMs == null) {
                _approachTimeMs = System.currentTimeMillis()
            }
            val elapsed = System.currentTimeMillis() - _approachTimeMs!!
            return elapsed >= AUTO_CONFIRM_TIMEOUT_MS && (distance - _approachMinDistance) > DIST_GAP
        }

        return false
    }

    private fun getLastVector(gps: GpsCoordinate, time: Long): Double? {
        if (_trackHistory.size < 2) return null

        if (time == 0L) {
            return MapGeometry.bearing(_trackHistory.last().coordinate, gps)
        }

        val windowEnd = _trackHistory.last().timestamp - time
        var startIdx = 0
        while (startIdx < _trackHistory.size && _trackHistory[startIdx].timestamp < windowEnd) {
            startIdx++
        }

        if (startIdx >= _trackHistory.size) {
            startIdx = _trackHistory.size - 1
        }

        return MapGeometry.bearing(_trackHistory[startIdx].coordinate, gps)
    }

    private fun addTrackHistory(gpsFix: GpsFix) {
        val lastVector = getLastVector(gpsFix.coordinate, 0L) ?: 0.0
        val lastVector3 = getLastVector(gpsFix.coordinate, HEADING_WINDOW_SECONDS * 1000L) ?: 0.0
        val lastVector10 = getLastVector(gpsFix.coordinate, HEADING_WINDOW_LONG_SECONDS * 1000L) ?: 0.0

        _trackHistory.addLast(
            TimedGpsPrivate(
                coordinate = gpsFix.coordinate,
                timestamp = gpsFix.timestamp,
                realBearing = lastVector,
                average3Bearing = lastVector3,
                average10Bearing = lastVector10
            )
        )

        val cutoff = gpsFix.timestamp - (TRACK_HISTORY_SECONDS * 1000L)
        while (_trackHistory.isNotEmpty() && _trackHistory.first().timestamp < cutoff) {
            _trackHistory.removeFirst()
        }
    }

    private fun findHeadingAlignedCalibrationPoint(
        currentBox: BoundingBox?,
        nextBox: BoundingBox,
        mapCalibration: MapCalibration,
        autoMapState: AutoMapState
    ): CalibrationPoint? {
        if (currentBox == null || _trackHistory.isEmpty()) return null

        val nextControl = MapGeometry.imageToGps(
            nextBox.centerX,
            nextBox.centerY,
            mapCalibration,
            autoMapState.northAngle
        ) ?: return null

        val currentControl = MapGeometry.imageToGps(
            currentBox.centerX,
            currentBox.centerY,
            mapCalibration,
            autoMapState.northAngle
        ) ?: return null

        val bearingToNext = MapGeometry.bearing(currentControl, nextControl)
        val currentBearing = _trackHistory.last().average10Bearing

        Log.d(TAG, "направление на цель: $bearingToNext движение: $currentBearing")
        val bearingDiff = abs(currentBearing - bearingToNext)
        Log.d(TAG, "Отклонение $bearingDiff")

        val trackLastIndex = rewindTrackHistory(_trackHistory.size - 1, -(TRACK_HISTORY_SECONDS * 1000L))

        // 1. Поиск точки поворота, выровненной по направлению на следующий КП
        if (bearingDiff < HEADING_TOLERANCE_DEG && trackLastIndex <= _trackHistory.size - 2) {
            for (i in (_trackHistory.size - 2) downTo trackLastIndex) {
                val diff = abs(vectorChangeByIndex(i, 1L))
                if (diff >= HEADING_JUMP_DEG) {
                    val diff1 = abs(vectorChangeByIndex(i, AUTO_CONFIRM_TIMEOUT_MS))
                    val confidence = if (diff1 < 55.0) 0.6 else 0.9
                    Log.d(
                        TAG,
                        "Нашлась точка изменения направления на треке на цель: $bearingToNext трек: ${_trackHistory[i + 1].average3Bearing} Вектор:$diff1 Мгновенный:$diff Confidence = $confidence"
                    )
                    return CalibrationPoint(
                        _trackHistory[i].coordinate,
                        currentBox.centerX,
                        currentBox.centerY,
                        confidence
                    )
                }
            }
        }

        // 2. Поиск любой точки резкого изменения направления на треке
        if (trackLastIndex <= _trackHistory.size - 2) {
            for (i in (_trackHistory.size - 2) downTo trackLastIndex) {
                val diff2 = abs(vectorChangeByIndex(i, 0L))
                if (diff2 >= HEADING_BIG_JUMP_DEG) {
                    val diff3 = abs(vectorChangeByIndex(i, AUTO_CONFIRM_TIMEOUT_MS))
                    val confidence2 = if (diff3 < 50.0) 0.4 else 0.8
                    Log.d(
                        TAG,
                        "Нашлась точка сильного изменения направления на треке Вектор:$diff3 Мгновенный:$diff2 confidence:$confidence2"
                    )
                    return CalibrationPoint(
                        _trackHistory[i].coordinate,
                        currentBox.centerX,
                        currentBox.centerY,
                        confidence2
                    )
                }
            }
        }

        // 3. Fallback: поиск геометрически ближайшей точки трека к КП
        var minDistance = MapGeometry.haversineDistance(_trackHistory.last().coordinate, currentControl)
        var nearestCalibrationPoint = CalibrationPoint(
            _trackHistory.last().coordinate,
            currentBox.centerX,
            currentBox.centerY,
            0.2
        )

        if (trackLastIndex <= _trackHistory.size - 2) {
            for (i in (_trackHistory.size - 2) downTo trackLastIndex) {
                val distance = MapGeometry.haversineDistance(_trackHistory[i].coordinate, currentControl)
                if (distance < minDistance) {
                    minDistance = distance
                    nearestCalibrationPoint = CalibrationPoint(
                        _trackHistory[i].coordinate,
                        currentBox.centerX,
                        currentBox.centerY,
                        0.3
                    )
                }
            }
        }

        Log.d(TAG, "Не нашли точку на треке, используем ближайшую")
        return nearestCalibrationPoint
    }

    private fun vectorChangeByIndex(startIndex: Int, dTime: Long): Double {
        if (_trackHistory.size < 2) return 0.0

        if (startIndex >= _trackHistory.size - 1) {
            return _trackHistory.last().realBearing - _trackHistory[_trackHistory.size - 2].realBearing
        }
        if (startIndex <= 0) {
            return _trackHistory[0].realBearing - _trackHistory[0].realBearing
        }
        if (dTime == 0L) {
            return _trackHistory[startIndex].realBearing - _trackHistory[startIndex + 1].realBearing
        }

        val vectorBefore = MapGeometry.bearing(
            _trackHistory[startIndex].coordinate,
            _trackHistory[rewindTrackHistory(startIndex, -dTime)].coordinate
        )
        val vectorAfter = MapGeometry.bearing(
            _trackHistory[rewindTrackHistory(startIndex, dTime)].coordinate,
            _trackHistory[startIndex].coordinate
        )
        return vectorBefore - vectorAfter
    }
    private fun rewindTrackHistory(startIndex: Int, dTime: Long): Int {
        var index = startIndex.coerceIn(0, _trackHistory.size - 1)
        val targetTime = abs(_trackHistory[startIndex].timestamp + dTime)
        if (dTime >= 0) {
            while (index < _trackHistory.size - 1 && _trackHistory[index].timestamp < targetTime) {
                index++
            }
            return index
        }
        while (index > 0 && _trackHistory[index].timestamp > targetTime) {
            index--
        }
        return index
    }
    private fun detectionReset() {
        _approachTimeMs = null
        _approachDetected = false
        _approachMinDistance = Double.MAX_VALUE
        _nextBoundingBox = null
    }
    private fun switchControl(
        mapState: AutoMapState,
        ms: MovementState,
        mapCalibration: MapCalibration
    ): MapCalibration? {
        val currentNum = ms.currentControl.num
        Log.d(TAG, "дошли до КП #$currentNum, переключение на следующий")
        val oldCurrentBox = ms.currentControl.boundingBox
        ms.prevControl = CurrentControl(currentNum, oldCurrentBox)
        val nextNum = currentNum + 1
        val nextBox = mapState.controlsBoundingBoxes.firstOrNull { it.number == nextNum }
        ms.currentControl = CurrentControl(nextNum, nextBox)
        val nextBoundingBox = _nextBoundingBox
        val effectivePoint = if (nextBoundingBox != null && oldCurrentBox != null) {
            findHeadingAlignedCalibrationPoint(oldCurrentBox, nextBoundingBox, mapCalibration, mapState)
        } else {
            null
        }
        val newCalibration: MapCalibration? = if (oldCurrentBox != null && effectivePoint != null) {
            val oldCal = computeAveragedOldCalibration(oldCurrentBox, mapCalibration)
            if (mapCalibration.pointB == null) {
                Log.d(TAG, "Калибровка computeCalibrationSoft(первая)")
                MapGeometry.computeCalibrationSoft(
                    mapCalibration.pointA,
                    effectivePoint,
                    mapState.northAngle,
                    oldCal
                )
            } else {
                val oldQuad = MapGeometry.pointInQuadrant(
                    mapCalibration.pointA.imageX,
                    mapCalibration.pointA.imageY
                )
                val newQuad = MapGeometry.pointInQuadrant(
                    oldCurrentBox.centerX,
                    oldCurrentBox.centerY
                )
                if (oldQuad == newQuad) {
                    Log.d(TAG, "Калибровка computeCalibrationSoft (same quadrant)")
                    MapGeometry.computeCalibrationSoft(
                        mapCalibration.pointA,
                        effectivePoint,
                        mapState.northAngle,
                        oldCal
                    )
                } else {
                    Log.d(TAG, "Калибровка calibrationSingleSoft (different quadrant)")
                    MapGeometry.calibrationSingleSoft(
                        effectivePoint,
                        oldCal.magneticDeclination,
                        mapState.northAngle,
                        oldCal.scaleMetersPerMapX,
                        oldCal.scaleMetersPerMapY,
                        oldCal
                    )
                }
            }
        } else {
            null
        }
        if (newCalibration != null) {
            _autoCalibrations.add(newCalibration)
            Log.d(TAG, "Сохранена автоматическая калибровка №${_autoCalibrations.size}")
        }
        detectionReset()
        return newCalibration
    }
    private fun getBoundingBoxCoordinates(
        boundingBox: BoundingBox,
        mapCalibration: MapCalibration,
        autoMapState: AutoMapState
    ): GpsCoordinate? {
        return MapGeometry.imageToGps(
            boundingBox.centerX,
            boundingBox.centerY,
            mapCalibration,
            autoMapState.northAngle
        )
    }
    private fun computeAveragedOldCalibration(
        oldCurrentBox: BoundingBox,
        rawMapCal: MapCalibration
    ): MapCalibration {
        val merged = ArrayList<MapCalibration>(_autoCalibrations.size + 1).apply {
            add(rawMapCal)
            addAll(_autoCalibrations)
        }
        Log.d(TAG, "computeAveragedOldCalibration merged.size=${merged.size}")
        if (merged.size == 1) return rawMapCal
        val centerGps = MapGeometry.imageToGpsTrueNorth(
            oldCurrentBox.centerX,
            oldCurrentBox.centerY,
            rawMapCal
        )
        Log.d(TAG, String.format("computeAveragedOldCalibration targetGps=(%.6f, %.6f)", centerGps.latitude, centerGps.longitude))
        val scalars = MapCalibrationUtils.averageScalarsAt(merged, centerGps)
        if (scalars == null || !scalars.hasPointB) {
            Log.d(TAG, "computeAveragedOldCalibration no pointB in history, using raw")
            return rawMapCal
        }
        var bestPointB: CalibrationPoint? = null
        var maxWeightForPointB = 0.0
        for (cal in _autoCalibrations) {
            val calPointB = cal.pointB ?: continue
            val pts = listOfNotNull(cal.pointA, calPointB)
            if (pts.isNotEmpty()) {
                val centerLat = pts.sumOf { it.gps.latitude } / pts.size
                val centerLon = pts.sumOf { it.gps.longitude } / pts.size
                val avgConf = pts.sumOf { it.confidence } / pts.size
                val center = GpsCoordinate(centerLat, centerLon)
                val dist = MapGeometry.haversineDistance(centerGps, center)
                val distWeight = 1.0 / max(dist * dist, 100.0)
                val weight = avgConf * distWeight
                if (weight > maxWeightForPointB) {
                    maxWeightForPointB = weight
                    bestPointB = calPointB
                }
            }
        }
        if (bestPointB == null) return rawMapCal
        val avg = MapCalibration(
            pointA = rawMapCal.pointA,
            pointB = bestPointB,
            scaleMetersPerMapX = scalars.scaleX,
            scaleMetersPerMapY = scalars.scaleY,
            bearingDegrees = scalars.bearingDegrees,
            magneticDeclination = scalars.magneticDeclination,
            physicalDeclination = scalars.physicalDeclination
        )
        Log.d(TAG, String.format("  oldScaleX=%.1f oldScaleY=%.1f", rawMapCal.scaleMetersPerMapX, rawMapCal.scaleMetersPerMapY))
        Log.d(TAG, String.format("  avgScaleX=%.1f avgScaleY=%.1f", avg.scaleMetersPerMapX, avg.scaleMetersPerMapY))
        Log.d(TAG, String.format("  oldDecl=%.3f avgDecl=%.3f", rawMapCal.magneticDeclination, avg.magneticDeclination))
        Log.d(TAG, String.format("  oldBearing=%.1f avgBearing=%.1f", rawMapCal.bearingDegrees, avg.bearingDegrees))
        val imgToGpsRaw = MapGeometry.imageToGpsTrueNorth(rawMapCal.pointA.imageX, rawMapCal.pointA.imageY, rawMapCal)
        val imgToGpsAvg = MapGeometry.imageToGpsTrueNorth(rawMapCal.pointA.imageX, rawMapCal.pointA.imageY, avg)
        val gpsShift = MapGeometry.haversineDistance(imgToGpsRaw, imgToGpsAvg)
        Log.d(TAG, String.format("  pointA image→gps shift raw→avg: %.2fm", gpsShift))
        return avg
    }
    companion object {
        @Deprecated("Unused legacy threshold")
        const val DISTANCE_FAR_CALIB = 150.0
        const val AUTO_APPROACHED_DIST = 80.0
        const val DIST_GAP = 40.0
        private const val AUTO_CONFIRM_TIMEOUT_MS = 10000L
        private const val HEADING_TOLERANCE_DEG = 30.0
        private const val HEADING_JUMP_DEG = 35.0
        private const val HEADING_BIG_JUMP_DEG = 35.0
        private const val TRACK_HISTORY_SECONDS = 90
        private const val HEADING_WINDOW_SECONDS = 2
        private const val HEADING_WINDOW_LONG_SECONDS = 10
        private const val NAV_BEARING_AVG_SECONDS = 3
        private const val TAG = "AutoMoveManager"
        fun analyzeNavigation(
            currentControlNum: Int,
            gpsFix: GpsFix,
            mapCalibration: MapCalibration,
            autoMapState: AutoMapState,
            lastAvgBearing: Double? = null
        ): NavigationData? {
            if (mapCalibration.pointB == null) return null
            val currentControlBoundingBox = autoMapState.controlsBoundingBoxes
                .firstOrNull { it.number == currentControlNum } ?: return null
            val targetGps = MapGeometry.imageToGps(
                currentControlBoundingBox.centerX,
                currentControlBoundingBox.centerY,
                mapCalibration,
                autoMapState.northAngle
            ) ?: return null
            val bearingToTarget = MapGeometry.bearing(gpsFix.coordinate, targetGps)
            val currentBearing = lastAvgBearing ?: gpsFix.bearing
            var deviation = currentBearing - bearingToTarget
            while (deviation > 180.0) deviation -= 360.0
            while (deviation < -180.0) deviation += 360.0
            val direction = when {
                abs(deviation) <= 10.0 -> DeviationDirection.CENTER
                deviation > 0.0 -> DeviationDirection.RIGHT
                else -> DeviationDirection.LEFT
            }
            val distanceToTarget = MapGeometry.haversineDistance(gpsFix.coordinate, targetGps)
            return NavigationData(bearingToTarget, distanceToTarget, deviation, direction)
        }
        fun analyzeAudioSignal(navigation: NavigationData): AudioSignalState {
            val dist = navigation.distanceToTargetMeters
            val dev = navigation.deviationDeg
            val absDeg = abs(dev)
            if (dist < 40.0) {
                return AudioSignalState(
                    type = AudioSignalType.ARRIVED,
                    frequencyHz = 600,
                    leftBalance = 0.5f,
                    intervalMs = 0L,
                    deviationDeg = dev,
                    distanceToTargetMeters = dist
                )
            }
            if (dist <= 50.0) {
                return AudioSignalState(
                    type = AudioSignalType.APPROACH_DOUBLE,
                    frequencyHz = 600,
                    leftBalance = 0.5f,
                    intervalMs = 10000L,
                    deviationDeg = dev,
                    distanceToTargetMeters = dist
                )
            }
            if (dist <= 100.0) {
                return AudioSignalState(
                    type = AudioSignalType.APPROACH_SINGLE,
                    frequencyHz = 600,
                    leftBalance = 0.5f,
                    intervalMs = 10000L,
                    deviationDeg = dev,
                    distanceToTargetMeters = dist
                )
            }
// Мёртвая зона (коридор ±10°): движемся точно на цель — звук не выводим
            if (absDeg <= 10.0 || navigation.direction == DeviationDirection.CENTER) {
                return AudioSignalState(
                    type = AudioSignalType.NONE,
                    frequencyHz = 0,
                    leftBalance = 0.5f,
                    intervalMs = 0L,
                    deviationDeg = dev,
                    distanceToTargetMeters = dist
                )
            }
            val freq = (800.0 - (((absDeg - 10.0) / 40.0) * 500.0)).coerceIn(300.0, 800.0).toInt()
            val clampedDeg = absDeg.coerceIn(0.0, 70.0)
            val balance = if (dev < 0.0) {
                (0.5 - (clampedDeg / 140.0)).toFloat()
            } else {
                ((clampedDeg / 140.0) + 0.5).toFloat()
            }
            val intervalMs = when {
                absDeg > 60.0 -> 4000L
                absDeg > HEADING_TOLERANCE_DEG -> 8000L
                absDeg > 20.0 -> 15000L
                else -> 25000L
            }
            return AudioSignalState(
                type = AudioSignalType.DEVIATION,
                frequencyHz = freq,
                leftBalance = balance,
                intervalMs = intervalMs,
                deviationDeg = dev,
                distanceToTargetMeters = dist
            )
        }
    }
}