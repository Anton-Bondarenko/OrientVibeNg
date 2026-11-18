package ru.bondarenko.orientvibe.ng.auto

import android.util.Log
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.gps.MapGeometry.turnDetection
import ru.bondarenko.orientvibe.ng.model.AutoMapState
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.CurrentControl
import ru.bondarenko.orientvibe.ng.model.DeviationDirection
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import ru.bondarenko.orientvibe.ng.model.MovementState
import ru.bondarenko.orientvibe.ng.model.NavigationData
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
        gpsFix: GpsFix,
        autoBind: Boolean = true
    ): MovementState {
        val boundingBox = movementState.currentControl.boundingBox ?: return movementState

        val currentControlCoordinates =
            getBoundingBoxCoordinates(boundingBox, mapCalibration, autoMapState)
                ?: gpsFix.coordinate

        val distance = MapGeometry.haversineDistance(
            gpsFix.coordinate,
            currentControlCoordinates
        )

        val nextNum = movementState.currentControl.num + 1
        _nextBoundingBox = autoMapState.controlsBoundingBoxes.firstOrNull { it.number == nextNum }

        addTrackHistory(gpsFix)

        if (shouldSwitch(distance) && mapCalibration.pointB != null) {
            movementState.newMapCalibration =
                switchControl(autoMapState, movementState, mapCalibration, autoBind)
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
        val lastVector10 =
            getLastVector(gpsFix.coordinate, HEADING_WINDOW_LONG_SECONDS * 1000L) ?: 0.0

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

    fun findHeadingAlignedCalibrationPoint(
        currentBox: BoundingBox?,
        nextBox: BoundingBox,
        mapCalibration: MapCalibration,
        autoMapState: AutoMapState,
        trackHistory: List<TimedGpsPrivate>
    ): CalibrationPoint? {
        if (currentBox == null || trackHistory.isEmpty()) return null

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
        val currentBearing = trackHistory.last().average10Bearing

        Log.d(
            "RecalibrationManager",
            "направление на цель: $bearingToNext движение: $currentBearing"
        )
        val bearingDiff = abs(currentBearing - bearingToNext)
        Log.d("RecalibrationManager", "Отклонение $bearingDiff")

        val trackLastIndex = rewindTrackHistory(
            trackHistory,
            trackHistory.size - 1,
            -(TRACK_HISTORY_SECONDS * 1000L)
        )

//    // 1. Поиск точки поворота, выровненной по направлению на следующий КП
//    if (bearingDiff < HEADING_TOLERANCE_DEG && trackLastIndex <= trackHistory.size - 2) {
//        for (i in (trackHistory.size - 2) downTo trackLastIndex) {
//            val diff = abs(vectorChangeByIndex(trackHistory, i, 1L))
//            if (diff >= HEADING_JUMP_DEG) {
//                val diff1 = abs(vectorChangeByIndex(trackHistory, i, AUTO_CONFIRM_TIMEOUT_MS))
//                val confidence = if (diff1 < 55.0) 0.6 else 0.9
//                Log.d(
//                    "RecalibrationManager",
//                    "Нашлась точка изменения направления на треке на цель: $bearingToNext трек: ${trackHistory[i + 1].average3Bearing} Вектор:$diff1 Мгновенный:$diff Confidence = $confidence"
//                )
//                return CalibrationPoint(
//                    trackHistory[i].coordinate,
//                    currentBox.centerX,
//                    currentBox.centerY,
//                    confidence
//                )
//            }
//        }
//    }

        // alternative direction change algorithm
        // detect outline direction and search for direction change
        if (trackLastIndex <= trackHistory.size - 2) {
            // попробуем выяснить генеральное направление:
//        var currentDirection = trackHistory.last().average10Bearing // попробуем вектор за 10 секунд
//        if (abs(currentDirection - trackHistory.last().average3Bearing) >= HEADING_BIG_JUMP_DEG) // три секунды назад направление было другим, не доверяем 10-ти секундному вектору
//            currentDirection = trackHistory.last().average3Bearing
            val vectorMinDistanceIndex = rewindTrackHistoryInMeters(
                trackHistory,
                trackLastIndex - 1,
                MIN_VECTOR_DISTANCE
            ) // на меньшей дистанции вектор бесполезен
            var foundIndex: Int? = null;
            for (i in (trackHistory.size - 2) downTo trackLastIndex) {
                val bearingChange =
                    abs(
                        if (i > vectorMinDistanceIndex) abs(
                            turnDetection(
                                trackHistory[trackHistory.size - 1].average3Bearing,
                                trackHistory[i].average3Bearing
                            )
                        ) else
                            vectorChangeByIndexInMeters(trackHistory, i, 20)
                    )
                if (bearingChange >= HEADING_BIG_JUMP_DEG) {
                    Log.d(
                        "RecalibrationManager",
                        "Нашлась точка сильного изменения направления на треке Вектор:$bearingChange"
                    )
                    foundIndex = i // больше фильтровать не нужно, найдём точное место
                    break
                }
            }
            if (foundIndex != null) {
                for (i in foundIndex downTo rewindTrackHistoryInMeters(
                    trackHistory,
                    foundIndex,
                    -SEARCH_DISTANCE
                )) {
                    val bearingChange =
                        abs(turnDetection(trackHistory[i].realBearing, trackHistory[i + 1].realBearing))
                    if (bearingChange > HEADING_BIG_JUMP_DEG) {
                        Log.d(
                            "RecalibrationManager",
                            "В точке: $bearingChange conf=0.8"
                        )
                        return CalibrationPoint(
                            trackHistory[i].coordinate,
                            currentBox.centerX,
                            currentBox.centerY,
                            0.8
                        )
                    }
                }
            }
        }

//    // 2. Поиск любой точки резкого изменения направления на треке
//    if (trackLastIndex <= trackHistory.size - 2) {
//        for (i in (trackHistory.size - 2) downTo trackLastIndex) {
//            val diff2 = abs(vectorChangeByIndex(trackHistory, i, 0L))
//            if (diff2 >= HEADING_BIG_JUMP_DEG) {
//                val diff3 = abs(vectorChangeByIndex(trackHistory, i, AUTO_CONFIRM_TIMEOUT_MS))
//                val confidence2 = if (diff3 < 50.0) 0.4 else 0.8
//                Log.d(
//                    "RecalibrationManager",
//                    "Нашлась точка сильного изменения направления на треке Вектор:$diff3 Мгновенный:$diff2 confidence:$confidence2"
//                )
//                return CalibrationPoint(
//                    trackHistory[i].coordinate,
//                    currentBox.centerX,
//                    currentBox.centerY,
//                    confidence2
//                )
//            }
//        }
//    }

        // 3. Fallback: поиск геометрически ближайшей точки трека к КП
        var minDistance =
            MapGeometry.haversineDistance(trackHistory.last().coordinate, currentControl)
        var confidence = 0.4
        var nearestCalibrationPoint = CalibrationPoint(
            trackHistory.last().coordinate,
            currentBox.centerX,
            currentBox.centerY,
            confidence
        )

        if (trackLastIndex <= trackHistory.size - 2) {
            for (i in (trackHistory.size - 2) downTo trackLastIndex) {
                val distance =
                    MapGeometry.haversineDistance(trackHistory[i].coordinate, currentControl)
                if (distance < minDistance) {
                    confidence = 0.5
                    minDistance = distance
                    nearestCalibrationPoint = CalibrationPoint(
                        trackHistory[i].coordinate,
                        currentBox.centerX,
                        currentBox.centerY,
                        confidence
                    )
                }
            }
        }

        Log.d("RecalibrationManager", "Не нашли точку на треке, используем ближайшую conf=$confidence")
        return nearestCalibrationPoint
    }

    private fun vectorChangeByIndex(
        trackHistory: List<TimedGpsPrivate>,
        startIndex: Int,
        dTime: Long
    ): Double {
        if (trackHistory.size < 2) return 0.0

        if (startIndex >= trackHistory.size - 1) {
            return trackHistory.last().realBearing - trackHistory[trackHistory.size - 2].realBearing
        }
        if (startIndex <= 0) {
            return trackHistory[0].realBearing - trackHistory[0].realBearing
        }
        if (dTime == 0L) {
            return trackHistory[startIndex].realBearing - trackHistory[startIndex + 1].realBearing
        }

        val vectorBefore = MapGeometry.bearing(
            trackHistory[startIndex].coordinate,
            trackHistory[rewindTrackHistory(
                trackHistory,
                startIndex,
                -dTime
            )].coordinate
        )
        val vectorAfter = MapGeometry.bearing(
            trackHistory[rewindTrackHistory(
                trackHistory,
                startIndex,
                dTime
            )].coordinate,
            trackHistory[startIndex].coordinate
        )
        return abs(turnDetection(vectorBefore, vectorAfter))
    }

    private fun rewindTrackHistory(
        trackHistory: List<TimedGpsPrivate>,
        startIndex: Int,
        dTime: Long
    ): Int {
        if (trackHistory.isEmpty()) return 0
        var index = startIndex.coerceIn(0, trackHistory.size - 1)
        val targetTime = abs(trackHistory[index].timestamp + dTime)
        while (index > 0 && trackHistory[index].timestamp > targetTime) {
            index--
        }
        return index
    }

    private fun rewindTrackHistoryInMeters(
        trackHistory: List<TimedGpsPrivate>,
        startIndex: Int,
        dMeters: Int
    ): Int {
        if (trackHistory.isEmpty()) return 0
        var index = startIndex.coerceIn(0, trackHistory.size - 1)
        val currentCoords = trackHistory.get(index).coordinate
        while (index > 0 && index < trackHistory.size - 1 && MapGeometry.haversineDistance(
                currentCoords,
                trackHistory.get(index).coordinate
            ) < abs(dMeters)
        ) {
            if (dMeters < 0) index--
            else index++
        }
        return index
    }

    // считаем удаление не по секундам, а по метрам
    private fun vectorChangeByIndexInMeters(
        trackHistory: List<TimedGpsPrivate>,
        startIndex: Int,
        dMeters: Int // Positive number
    ): Double {
        val fromIndex = rewindTrackHistoryInMeters(trackHistory, startIndex, -abs(dMeters));
        val toIndex = rewindTrackHistoryInMeters(trackHistory, startIndex, abs(dMeters));
        val firstSegmentDirection = MapGeometry.bearing(
            trackHistory[fromIndex].coordinate,
            trackHistory[startIndex].coordinate
        );
        val secondSegmentDirection = MapGeometry.bearing(
            trackHistory[startIndex].coordinate,
            trackHistory[toIndex].coordinate
        );
        return abs(turnDetection(firstSegmentDirection, secondSegmentDirection))
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
        mapCalibration: MapCalibration,
        autoBind: Boolean
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
            findHeadingAlignedCalibrationPoint(
                oldCurrentBox,
                nextBoundingBox,
                mapCalibration,
                mapState,
                trackHistory
            )
        } else {
            null
        }
        val newCalibration: MapCalibration? =
            if (autoBind && oldCurrentBox != null && effectivePoint != null) {
                Log.d(TAG, "Автопривязка вкл — вычисляем калибровку")
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
            } else if (!autoBind) {
                Log.d(TAG, "Автопривязка выкл — калибровку не вычисляем, только переключение КП")
                null
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
        // Избегаем ArrayList аллокации: итерируем rawMapCal + _autoCalibrations вручную
        val hasHistory = _autoCalibrations.isNotEmpty()
        Log.d(
            TAG,
            "computeAveragedOldCalibration items=${if (hasHistory) _autoCalibrations.size + 1 else 1}"
        )
        if (!hasHistory) return rawMapCal

        val centerGps = MapGeometry.imageToGpsTrueNorth(
            oldCurrentBox.centerX,
            oldCurrentBox.centerY,
            rawMapCal
        )
        Log.d(
            TAG,
            "computeAveragedOldCalibration targetGps=(${
                MapGeometry.fmt(
                    centerGps.latitude,
                    6
                )
            },${MapGeometry.fmt(centerGps.longitude, 6)})"
        )

        // Inline averageScalarsAt — avoids allocating merged ArrayList, listOfNotNull per iteration
        var totalWeight = 0.0
        var scaleXSum = 0.0
        var scaleYSum = 0.0
        var bearingSin = 0.0
        var bearingCos = 0.0
        var magDeclSum = 0.0
        var physDeclSum = 0.0
        var hasPointB = false

        // rawMapCal first — inline listOfNotNull(pointA, pointB) without ArrayList
        val ptA_raw = rawMapCal.pointA ?: return rawMapCal
        val ptB_raw = rawMapCal.pointB ?: return rawMapCal
        val cLat_raw = (ptA_raw.gps.latitude + ptB_raw.gps.latitude) / 2.0
        val cLon_raw = (ptA_raw.gps.longitude + ptB_raw.gps.longitude) / 2.0
        val confSum_raw = ptA_raw.confidence + ptB_raw.confidence
        val dist_raw = MapGeometry.haversineDistancePrim(
            centerGps.latitude,
            centerGps.longitude,
            cLat_raw,
            cLon_raw
        )
        val distWeight_raw = 1.0 / max(dist_raw * dist_raw, 100.0)
        val weight_raw = (confSum_raw / 2.0) * distWeight_raw
        if (weight_raw > 0.0) {
            totalWeight += weight_raw
            scaleXSum += rawMapCal.scaleMetersPerMapX * weight_raw
            scaleYSum += rawMapCal.scaleMetersPerMapY * weight_raw
            bearingSin += sin(Math.toRadians(rawMapCal.bearingDegrees)) * weight_raw
            bearingCos += cos(Math.toRadians(rawMapCal.bearingDegrees)) * weight_raw
            magDeclSum += rawMapCal.magneticDeclination * weight_raw
            physDeclSum += rawMapCal.physicalDeclination * weight_raw
            hasPointB = true
        }

        // _autoCalibrations items
        for (i in _autoCalibrations.indices) {
            val cal = _autoCalibrations[i]
            // Direct access without listOfNotNull ArrayList allocation
            val ptA = cal.pointA ?: continue
            val ptB = cal.pointB ?: continue
            val ptsCount = 2
            val cLat = (ptA.gps.latitude + ptB.gps.latitude) / ptsCount
            val cLon = (ptA.gps.longitude + ptB.gps.longitude) / ptsCount
            val confSum = ptA.confidence + ptB.confidence
            val dist = MapGeometry.haversineDistancePrim(
                centerGps.latitude,
                centerGps.longitude,
                cLat,
                cLon
            )
            val distWeight = 1.0 / max(dist * dist, 100.0)
            val weight = (confSum / ptsCount) * distWeight
            if (weight > 0.0) {
                totalWeight += weight
                scaleXSum += cal.scaleMetersPerMapX * weight
                scaleYSum += cal.scaleMetersPerMapY * weight
                bearingSin += sin(Math.toRadians(cal.bearingDegrees)) * weight
                bearingCos += cos(Math.toRadians(cal.bearingDegrees)) * weight
                magDeclSum += cal.magneticDeclination * weight
                physDeclSum += cal.physicalDeclination * weight
                hasPointB = true
            }
        }

        val hasScalars = totalWeight > 0.0
        var avgScaleX = 0.0
        var avgScaleY = 0.0
        var avgBearingDegrees = 0.0
        var avgMagDecl = 0.0
        var avgPhysDecl = 0.0
        if (hasScalars) {
            avgScaleX = scaleXSum / totalWeight
            avgScaleY = scaleYSum / totalWeight
            avgBearingDegrees = Math.toDegrees(atan2(bearingSin, bearingCos))
            avgMagDecl = magDeclSum / totalWeight
            avgPhysDecl = physDeclSum / totalWeight
        }
        if (!hasScalars || !hasPointB) {
            Log.d(TAG, "computeAveragedOldCalibration no pointB in history, using raw")
            return rawMapCal
        }
        var bestPointB: CalibrationPoint? = null
        var maxWeightForPointB = 0.0
        for (i in _autoCalibrations.indices) {
            val cal = _autoCalibrations[i]
            val calPointB = cal.pointB ?: continue
            val calPointA = cal.pointA ?: continue
            // Direct averaging without listOfNotNull ArrayList allocation
            val centerLat = (calPointA.gps.latitude + calPointB.gps.latitude) / 2.0
            val centerLon = (calPointA.gps.longitude + calPointB.gps.longitude) / 2.0
            val avgConf = (calPointA.confidence + calPointB.confidence) / 2.0
            val dist = MapGeometry.haversineDistancePrim(
                centerGps.latitude,
                centerGps.longitude,
                centerLat,
                centerLon
            )
            val distWeight = 1.0 / max(dist * dist, 100.0)
            val weight = avgConf * distWeight
            if (weight > maxWeightForPointB) {
                maxWeightForPointB = weight
                bestPointB = calPointB
            }
        }
        if (bestPointB == null) return rawMapCal
        val avg = MapCalibration(
            pointA = rawMapCal.pointA,
            pointB = bestPointB,
            scaleMetersPerMapX = avgScaleX,
            scaleMetersPerMapY = avgScaleY,
            bearingDegrees = avgBearingDegrees,
            magneticDeclination = avgMagDecl,
            physicalDeclination = avgPhysDecl
        )
        Log.d(
            TAG,
            "  oldScaleX=${
                MapGeometry.fmt(
                    rawMapCal.scaleMetersPerMapX,
                    1
                )
            } oldScaleY=${MapGeometry.fmt(rawMapCal.scaleMetersPerMapY, 1)}"
        )
        Log.d(
            TAG,
            "  avgScaleX=${MapGeometry.fmt(avg.scaleMetersPerMapX, 1)} avgScaleY=${
                MapGeometry.fmt(
                    avg.scaleMetersPerMapY,
                    1
                )
            }"
        )
        Log.d(
            TAG,
            "  oldDecl=${
                MapGeometry.fmt(
                    rawMapCal.magneticDeclination,
                    3
                )
            } avgDecl=${MapGeometry.fmt(avg.magneticDeclination, 3)}"
        )
        Log.d(
            TAG,
            "  oldBearing=${
                MapGeometry.fmt(
                    rawMapCal.bearingDegrees,
                    1
                )
            } avgBearing=${MapGeometry.fmt(avg.bearingDegrees, 1)}"
        )
        val imgToGpsRaw = MapGeometry.imageToGpsTrueNorth(
            rawMapCal.pointA.imageX,
            rawMapCal.pointA.imageY,
            rawMapCal
        )
        val imgToGpsAvg =
            MapGeometry.imageToGpsTrueNorth(rawMapCal.pointA.imageX, rawMapCal.pointA.imageY, avg)
        val gpsShift = MapGeometry.haversineDistance(imgToGpsRaw, imgToGpsAvg)
        Log.d(TAG, "  pointA image→gps shift raw→avg: ${MapGeometry.fmt(gpsShift, 2)}m")
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
        private const val HEADING_BIG_JUMP_DEG = 45.0
        private const val TRACK_HISTORY_SECONDS = 120
        private const val MIN_VECTOR_DISTANCE = 10
        private const val SEARCH_DISTANCE = 30
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
    }
}