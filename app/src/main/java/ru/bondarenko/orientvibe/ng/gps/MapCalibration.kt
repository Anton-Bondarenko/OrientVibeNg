package ru.bondarenko.orientvibe.ng.gps

import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Утилитарный менеджер для расчетов и геометрических преобразований калибровки карты.
 */
/** Усреднённые скаляры калибровки */
data class AveragedScalars(
    val scaleX: Double,
    val scaleY: Double,
    val magneticDeclination: Double,
    val physicalDeclination: Double,
    val bearingDegrees: Double,
    val hasPointB: Boolean
)

/** Результат привязки GPS к финишу */
data class BindResult(
    val calibration: MapCalibration,
    val northAngleDeg: Double
)

object MapCalibrationUtils {

    private const val DEFAULT_SCALE = 4000.0

    fun gpsToImage(gps: GpsCoordinate, calibration: MapCalibration): Pair<Double?, Double?> {
        return MapGeometry.gpsToImageTrueNorth(gps, calibration)
    }

    fun gpsToImage(
        gps: GpsCoordinate,
        calibration: MapCalibration,
        northAngleDeg: Double
    ): Pair<Double?, Double?>? {
        return MapGeometry.gpsToImage(gps, calibration, northAngleDeg)
    }

    fun imageToGps(imageX: Double, imageY: Double, calibration: MapCalibration): GpsCoordinate {
        return MapGeometry.imageToGpsTrueNorth(imageX, imageY, calibration)
    }

    fun magneticBearing(
        from: GpsCoordinate,
        to: GpsCoordinate,
        magneticDeclination: Double
    ): Double {
        val trueBearing = bearing(from, to)
        return MapGeometry.magneticBearing(trueBearing, magneticDeclination)
    }

    fun bearing(from: GpsCoordinate, to: GpsCoordinate): Double {
        return MapGeometry.bearing(from, to)
    }

    fun haversineDistance(a: GpsCoordinate, b: GpsCoordinate): Double {
        return MapGeometry.haversineDistance(a, b)
    }

    fun northDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        return MapGeometry.northDistance(from, to)
    }

    fun eastDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        return MapGeometry.eastDistance(from, to)
    }

    fun offsetCoordinate(from: GpsCoordinate, dNorth: Double, dEast: Double): GpsCoordinate {
        return MapGeometry.offsetCoordinate(from, dNorth, dEast)
    }

    fun averageCalibrationsAt(
        history: List<MapCalibration>,
        targetGps: GpsCoordinate
    ): MapCalibration? {
        if (history.isEmpty()) return null

        val samples = ArrayList<Triple<GpsCoordinate, Double, MapCalibration>>()

        for (cal in history) {
            val pts = listOfNotNull(cal.pointA, cal.pointB)
            if (pts.isNotEmpty()) {
                val latSum = pts.sumOf { it.gps.latitude }
                val lonSum = pts.sumOf { it.gps.longitude }
                val confSum = pts.sumOf { it.confidence }

                val center = GpsCoordinate(latSum / pts.size, lonSum / pts.size)
                val dist = haversineDistance(targetGps, center)
                val distWeight = 1.0 / max(dist * dist, 100.0)
                val weight = (confSum / pts.size) * distWeight

                samples.add(Triple(center, weight, cal))
            }
        }

        if (samples.isEmpty()) return null

        var maxWeightForPointB = 0.0
        var maxWeightPointB: CalibrationPoint? = null

        for (triple in samples) {
            val center = triple.first
            val weight = triple.second
            val cal = triple.third

            val calPointB = cal.pointB
            if (calPointB != null && weight > maxWeightForPointB) {
                maxWeightForPointB = weight
                maxWeightPointB = CalibrationPoint(
                    center,
                    calPointB.imageX,
                    calPointB.imageY,
                    1.0
                )
            }
        }

        var bearingSin = 0.0
        var bearingCos = 0.0
        var magDeclSum = 0.0
        var physDeclSum = 0.0
        var totalWeight = 0.0
        var scaleXSum = 0.0
        var scaleYSum = 0.0

        for (triple in samples) {
            val weight = triple.second
            val cal = triple.third

            totalWeight += weight
            scaleXSum += cal.scaleMetersPerMapX * weight
            scaleYSum += cal.scaleMetersPerMapY * weight

            val bRad = Math.toRadians(cal.bearingDegrees)
            bearingSin += sin(bRad) * weight
            bearingCos += cos(bRad) * weight
            magDeclSum += cal.magneticDeclination * weight
            physDeclSum += cal.physicalDeclination * weight
        }

        val avgBearing = Math.toDegrees(atan2(bearingSin / totalWeight, bearingCos / totalWeight))

        return MapCalibration(
            pointA = CalibrationPoint(targetGps, 0.5, 0.5, 1.0),
            pointB = maxWeightPointB,
            scaleMetersPerMapX = scaleXSum / totalWeight,
            scaleMetersPerMapY = scaleYSum / totalWeight,
            bearingDegrees = avgBearing,
            magneticDeclination = magDeclSum / totalWeight,
            physicalDeclination = physDeclSum / totalWeight
        )
    }

    fun averageScalarsAt(
        history: List<MapCalibration>,
        targetGps: GpsCoordinate
    ): AveragedScalars? {
        if (history.isEmpty()) return null

        var totalWeight = 0.0
        var scaleXSum = 0.0
        var scaleYSum = 0.0
        var bearingSin = 0.0
        var bearingCos = 0.0
        var magDeclSum = 0.0
        var physDeclSum = 0.0
        var hasPointB = false

        for (cal in history) {
            val pts = listOfNotNull(cal.pointA, cal.pointB)
            if (pts.isEmpty()) continue

            val latSum = pts.sumOf { it.gps.latitude }
            val lonSum = pts.sumOf { it.gps.longitude }
            val confSum = pts.sumOf { it.confidence }

            val center = GpsCoordinate(latSum / pts.size, lonSum / pts.size)
            val dist = haversineDistance(targetGps, center)
            val distWeight = 1.0 / max(dist * dist, 100.0)
            val weight = (confSum / pts.size) * distWeight

            if (weight > 0.0) {
                totalWeight += weight
                scaleXSum += cal.scaleMetersPerMapX * weight
                scaleYSum += cal.scaleMetersPerMapY * weight

                val bRad = Math.toRadians(cal.bearingDegrees)
                bearingSin += sin(bRad) * weight
                bearingCos += cos(bRad) * weight
                magDeclSum += cal.magneticDeclination * weight
                physDeclSum += cal.physicalDeclination * weight

                if (cal.pointB != null) {
                    hasPointB = true
                }
            }
        }

        if (totalWeight <= 0.0) return null

        val avgBearing = Math.toDegrees(atan2(bearingSin / totalWeight, bearingCos / totalWeight))

        return AveragedScalars(
            scaleX = scaleXSum / totalWeight,
            scaleY = scaleYSum / totalWeight,
            magneticDeclination = magDeclSum / totalWeight,
            physicalDeclination = physDeclSum / totalWeight,
            bearingDegrees = avgBearing,
            hasPointB = hasPointB
        )
    }

    fun calibrateSinglePoint(
        startGPS: GpsCoordinate,
        startPointImageX: Double,
        startPointImageY: Double,
        northAngle: Double,
        magneticDeclination: Double? = null,
        imageProportion: Double,
        scaleMetersPerMapX: Double? = null,
        scaleMetersPerMapY: Double? = null,
        confidence: Double = 1.0
    ): MapCalibration {
        val calibrationPoint =
            CalibrationPoint(startGPS, startPointImageX, startPointImageY, confidence)

        val finalScaleX = scaleMetersPerMapX ?: (imageProportion * DEFAULT_SCALE)
        val finalScaleY = scaleMetersPerMapY ?: DEFAULT_SCALE

        return MapGeometry.calibrationSingle(
            calibrationPoint,
            if (magneticDeclination == null || magneticDeclination == 0.0) MapGeometry.calculateMagneticDeclination(
                startGPS.latitude,
                startGPS.longitude,
                System.currentTimeMillis()
            ) else magneticDeclination,
            northAngle,
            finalScaleX,
            finalScaleY
        )
    }

    fun bindGpsToFinishWithTrack(
        startGPS: GpsCoordinate,
        startPointImageX: Double,
        startPointImageY: Double,
        finishPointImageX: Double,
        finishPointImageY: Double,
        currentFixGPS: GpsCoordinate,
        magneticDeclination: Double = 0.0,
        confidence: Double = 1.0
    ): BindResult {
        val pointA = CalibrationPoint(startGPS, startPointImageX, startPointImageY, confidence)
        val pointB =
            CalibrationPoint(currentFixGPS, finishPointImageX, finishPointImageY, confidence)

        val newCal = MapGeometry.computeCalibrationHard(
            pointA,
            pointB,
            magneticDeclination,
            0.0
        )

        if (newCal != null) {
            val trueBearing = MapGeometry.bearing(pointA.gps, pointB.gps)
            val northAngleDeg = -(trueBearing - magneticDeclination)
            return BindResult(newCal, northAngleDeg)
        }

        throw IllegalStateException("bindGpsToFinishWithTrack: calibration points too close")
    }
}
