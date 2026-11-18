package ru.bondarenko.orientvibe.ng.auto

import android.util.Log
import ru.bondarenko.orientvibe.ng.model.AutoMapState
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.CurrentControl
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.MovementState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// Constants (same values as AutoMoveManager)
private const val HEADING_TOLERANCE_DEG = 30.0
private const val HEADING_JUMP_DEG = 35.0
private const val HEADING_BIG_JUMP_DEG = 45.0
private const val AUTO_CONFIRM_TIMEOUT_MS = 10000L
private const val TRACK_HISTORY_SECONDS = 60
private const val MIN_VECTOR_DISTANCE = 10

/**
 * Менеджер калибровки — обрабатывает события ON_LEAVING_CP
 * и запускает логику автоматической калибровки при уходе от КП.
 */
object RecalibrationManager {

    private const val TAG = "RecalibrationManager"

    /**
     * Обработка событий кадра с логикой switchControl.
     *
     * @param events события кадра (включая ON_LEAVING_CP)
     * @param mapState состояние карты для доступа к bounding box следующих КП
     * @param movementState состояние движения — обновляется при переключении КП
     * @param mapCalibration текущая калибровка карты
     * @param autoBind флаг автопривязки
     * @param nextBoundingBox следующая контрольная точка (устанавливается doMove до вызова handleFrameEvents)
     * @param trackHistory история GPS-позиций для findHeadingAlignedCalibrationPoint
     * @param autoCalibrations список для сохранения вычисленных калибровок
     */
    fun handleFrameEvents(
        events: List<FrameEvent>,
        mapState: AutoMapState,
        movementState: MovementState,
        mapCalibration: MapCalibration,
        autoBind: Boolean,
//        nextBoundingBox: BoundingBox?,
        trackHistory: List<TimedGpsPrivate>,
        autoCalibrations: MutableList<MapCalibration>
    ) {
        for (event in events) {
            when (event.type) {
                FrameEvent.EventType.ON_LEAVING_CP -> {
                    Log.d(TAG, "▶ LEAVING_CP: CP#${event.controlNum} dist=${"%.1f".format(event.distM)}m dev=${"%.1f".format(event.devDeg)}°")

                    // Guard: mapCalibration.pointB != null — условие для вычисления калибровки
                    if (mapCalibration.pointB == null) {
                        Log.d(TAG, "   pointB == null — пропуск калибровки (первая точка)")
                        continue
                    }
                    val nextNum = movementState.currentControl.num + 1
                    val nextBoundingBoxForRecalib = mapState.controlsBoundingBoxes.firstOrNull { it.number == nextNum }

                    val newCalibration = switchControl(
                        mapState = mapState,
                        movementState = movementState,
                        mapCalibration = mapCalibration,
                        autoBind = autoBind,
                        nextBoundingBox = nextBoundingBoxForRecalib,
                        trackHistory = trackHistory,
                        autoCalibrations = autoCalibrations
                    )
                    if (newCalibration != null) {
                        movementState.newMapCalibration = newCalibration
                    }
                }

                else -> {}
            }
        }
    }

    /**
     * Логика переключения КП
     */
    private fun switchControl(
        mapState: AutoMapState,
        movementState: MovementState,
        mapCalibration: MapCalibration,
        autoBind: Boolean,
        nextBoundingBox: BoundingBox?,
        trackHistory: List<TimedGpsPrivate>,
        autoCalibrations: MutableList<MapCalibration>
    ): MapCalibration? {
        val currentNum = movementState.currentControl.num
        Log.d(TAG, "дошли до КП #$currentNum, переключение на следующий")

        val oldCurrentBox = movementState.currentControl.boundingBox
        movementState.prevControl = CurrentControl(currentNum, oldCurrentBox)

        val nextNum = currentNum + 1
        val nextBox = mapState.controlsBoundingBoxes.firstOrNull { it.number == nextNum }
        movementState.currentControl = CurrentControl(nextNum, nextBox)

        val effectivePoint = if (nextBoundingBox != null && oldCurrentBox != null) {
            findHeadingAlignedCalibrationPoint(oldCurrentBox, nextBoundingBox, mapCalibration, mapState, trackHistory)
        } else {
            Log.w(TAG, "Не найден следующий КП #$currentNum")
            null
        }

        val newCalibration: MapCalibration? = if (autoBind && oldCurrentBox != null && effectivePoint != null) {
            Log.d(TAG, "Автопривязка вкл — вычисляем калибровку")
            val oldCal = computeAveragedOldCalibration(oldCurrentBox, mapCalibration, autoCalibrations)

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
            autoCalibrations.add(newCalibration)
            Log.d(TAG, "Сохранена автоматическая калибровка №${autoCalibrations.size}")
        }

        return newCalibration
    }
}

// ═══════════ Package-level helpers ═══════════

/**
 * Находит точку калибровки, выровненную по направлению движения.
 * Три подхода (в порядке приоритета):
 * 1. Точка поворота, выровненная по направлению на следующий КП
 * 2. Любая точка резкого изменения направления
 * 3. Fallback: геометрически ближайшая к КП
 */
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

    Log.d("RecalibrationManager", "направление на цель: $bearingToNext движение: $currentBearing")
    val bearingDiff = abs(currentBearing - bearingToNext)
    Log.d("RecalibrationManager", "Отклонение $bearingDiff")

    val trackLastIndex = rewindTrackHistory(trackHistory, trackHistory.size - 1, -(TRACK_HISTORY_SECONDS * 1000L))

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
        val vectorMinDistanceIndex = rewindTrackHistoryInMeters(trackHistory, trackLastIndex-1, MIN_VECTOR_DISTANCE) // на меньшей дистанции вектор бесполезен
        for (i in (trackHistory.size - 2) downTo trackLastIndex) {
            var bearingChange =
                abs(if (i > vectorMinDistanceIndex) trackHistory[trackHistory.size - 1].average3Bearing - trackHistory[i].average3Bearing else
                    vectorChangeByIndexInMeters(trackHistory, i, 20))
            if (bearingChange >= HEADING_BIG_JUMP_DEG) {
                val confidence = 0.8
                Log.d(
                    "RecalibrationManager",
                    "Нашлась точка сильного изменения направления на треке Вектор:$bearingChange confidence:$confidence"
                )
                return CalibrationPoint(
                    trackHistory[i].coordinate,
                    currentBox.centerX,
                    currentBox.centerY,
                    confidence
                )
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
    var minDistance = MapGeometry.haversineDistance(trackHistory.last().coordinate, currentControl)
    var nearestCalibrationPoint = CalibrationPoint(
        trackHistory.last().coordinate,
        currentBox.centerX,
        currentBox.centerY,
        0.2
    )

    if (trackLastIndex <= trackHistory.size - 2) {
        for (i in (trackHistory.size - 2) downTo trackLastIndex) {
            val distance = MapGeometry.haversineDistance(trackHistory[i].coordinate, currentControl)
            if (distance < minDistance) {
                minDistance = distance
                nearestCalibrationPoint = CalibrationPoint(
                    trackHistory[i].coordinate,
                    currentBox.centerX,
                    currentBox.centerY,
                    0.3
                )
            }
        }
    }

    Log.d("RecalibrationManager", "Не нашли точку на треке, используем ближайшую")
    return nearestCalibrationPoint
}

/**
 * Вычисляет усреднённую калибровку из rawMapCal и истории autoCalibrations.
 * Вес каждой точки = (confidence/2) * (1 / max(dist², 100))
 */
fun computeAveragedOldCalibration(
    oldCurrentBox: BoundingBox,
    rawMapCal: MapCalibration,
    autoCalibrations: List<MapCalibration>
): MapCalibration {
    val hasHistory = autoCalibrations.isNotEmpty()
    Log.d("RecalibrationManager", "computeAveragedOldCalibration items=${if (hasHistory) autoCalibrations.size + 1 else 1}")
    if (!hasHistory) return rawMapCal

    val centerGps = MapGeometry.imageToGpsTrueNorth(
        oldCurrentBox.centerX,
        oldCurrentBox.centerY,
        rawMapCal
    )
    Log.d("RecalibrationManager", "computeAveragedOldCalibration targetGps=(${MapGeometry.fmt(centerGps.latitude, 6)},${MapGeometry.fmt(centerGps.longitude, 6)})")

    // Weighted averaging — zero allocation (local vars only)
    var totalWeight = 0.0
    var scaleXSum = 0.0
    var scaleYSum = 0.0
    var bearingSin = 0.0
    var bearingCos = 0.0
    var magDeclSum = 0.0
    var physDeclSum = 0.0
    var hasPointB = false

    // rawMapCal: pointA + pointB (inline listOfNotNull without allocation)
    val ptA_raw = rawMapCal.pointA ?: return rawMapCal
    val ptB_raw = rawMapCal.pointB ?: return rawMapCal
    val cLat_raw = (ptA_raw.gps.latitude + ptB_raw.gps.latitude) / 2.0
    val cLon_raw = (ptA_raw.gps.longitude + ptB_raw.gps.longitude) / 2.0
    val confSum_raw = ptA_raw.confidence + ptB_raw.confidence
    val dist_raw = MapGeometry.haversineDistancePrim(centerGps.latitude, centerGps.longitude, cLat_raw, cLon_raw)
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

    // autoCalibrations items — loop over indices to avoid iterator allocation
    for (i in autoCalibrations.indices) {
        val cal = autoCalibrations[i]
        val ptA = cal.pointA ?: continue
        val ptB = cal.pointB ?: continue
        val cLat = (ptA.gps.latitude + ptB.gps.latitude) / 2.0
        val cLon = (ptA.gps.longitude + ptB.gps.longitude) / 2.0
        val confSum = ptA.confidence + ptB.confidence
        val dist = MapGeometry.haversineDistancePrim(centerGps.latitude, centerGps.longitude, cLat, cLon)
        val distWeight = 1.0 / max(dist * dist, 100.0)
        val weight = (confSum / 2.0) * distWeight
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

    // Нормализация и восстановление параметров
    val avgScaleX = scaleXSum / totalWeight
    val avgScaleY = scaleYSum / totalWeight
    val avgBearing = Math.toDegrees(atan2(bearingSin / totalWeight, bearingCos / totalWeight))
    val avgMagDecl = magDeclSum / totalWeight
    val avgPhysDecl = physDeclSum / totalWeight

    val hasScalars = totalWeight > 0.0
    if (!hasScalars || !hasPointB) {
        Log.d("RecalibrationManager", "computeAveragedOldCalibration no pointB in history, using raw")
        return rawMapCal
    }

    // Find best pointB (highest weight) for the averaged calibration
    var bestPointB: CalibrationPoint? = null
    var maxWeightForPointB = 0.0
    for (i in autoCalibrations.indices) {
        val cal = autoCalibrations[i]
        val calPointB = cal.pointB ?: continue
        val calPointA = cal.pointA ?: continue
        val centerLat = (calPointA.gps.latitude + calPointB.gps.latitude) / 2.0
        val centerLon = (calPointA.gps.longitude + calPointB.gps.longitude) / 2.0
        val avgConf = (calPointA.confidence + calPointB.confidence) / 2.0
        val dist = MapGeometry.haversineDistancePrim(centerGps.latitude, centerGps.longitude, centerLat, centerLon)
        val distWeight = 1.0 / max(dist * dist, 100.0)
        val weight = avgConf * distWeight
        if (weight > maxWeightForPointB) {
            maxWeightForPointB = weight
            bestPointB = calPointB
        }
    }
    if (bestPointB == null) return rawMapCal

    Log.d(
        "RecalibrationManager",
        "computeAveragedOldCalibration result: scaleX=$avgScaleX scaleY=$avgScaleY bearing=$avgBearing"
    )

    return MapCalibration(
        pointA = rawMapCal.pointA,
        pointB = bestPointB,
        scaleMetersPerMapX = avgScaleX,
        scaleMetersPerMapY = avgScaleY,
        bearingDegrees = avgBearing,
        magneticDeclination = avgMagDecl,
        physicalDeclination = avgPhysDecl
    )
}

// ═══════════ Helpers для findHeadingAlignedCalibrationPoint ═══════════

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
    while (index > 0 && index < trackHistory.size - 1 && MapGeometry.haversineDistance(currentCoords, trackHistory.get(index).coordinate) < dMeters) {
        if (dMeters < 0) index--
        else index ++
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
    val firstSegmentDirection = MapGeometry.bearing(trackHistory[fromIndex].coordinate, trackHistory[startIndex].coordinate);
    val secondSegmentDirection = MapGeometry.bearing(trackHistory[startIndex].coordinate, trackHistory[toIndex].coordinate);
    return abs(firstSegmentDirection - secondSegmentDirection)
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
        trackHistory[rewindTrackHistory(trackHistory, startIndex, -dTime)].coordinate
    )
    val vectorAfter = MapGeometry.bearing(
        trackHistory[rewindTrackHistory(trackHistory, startIndex, dTime)].coordinate,
        trackHistory[startIndex].coordinate
    )
    return abs(vectorBefore - vectorAfter)
}
