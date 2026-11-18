package ru.bondarenko.orientvibe.ng.gps

import android.graphics.PointF
import android.hardware.GeomagneticField
import android.util.Log
import ru.bondarenko.orientvibe.ng.model.*
import kotlin.math.*

object MapGeometry {

    private const val TAG = "MapGeometry"
    private const val EARTH_RADIUS_METERS = 6371000.0
    private const val MAX_DELTA_DECLINATION = 1.2
    private const val MAX_DELTA_DISTANCE = 80 // сдвигаем максимум в метрах
    private const val MAX_DELTA_SCALE = 250.0

    /** Быстрое форматирование doubles для логов — без String.format / Formatter аллокаций */
    internal fun fmt(value: Double, decimals: Int): String {
        val neg = value < 0
        val v = if (neg) -value else value
        val intPart = v.toLong()
        val fracMultiplier = when (decimals) {
            1 -> 10; 2 -> 100; 3 -> 1000; 4 -> 10000; 5 -> 100000; 6 -> 1000000
            else -> 10.0.pow(decimals.toDouble()).toLong()
        }
        val fracDigits = kotlin.math.round((v - intPart) * fracMultiplier).toLong()
        val sb = java.lang.StringBuilder(intPart.toString().length + decimals + 2)
        if (neg) sb.append('-')
        sb.append(intPart).append('.')
        var fracStr = fracDigits.toString()
        while (fracStr.length < decimals) fracStr = "0" + fracStr
        sb.append(fracStr, 0, decimals)
        return sb.toString()
    }

    fun pointInQuadrant(x: Double, y: Double): Int {
        val col = if (x < 0.5) 0 else 1
        val row = if (y >= 0.5) 1 else 0
        return (row * 2) + col + 1
    }

    fun calculateMagneticDeclination(latitude: Double, longitude: Double, timeMillis: Long = System.currentTimeMillis()): Double {
        val geomagneticField = GeomagneticField(latitude.toFloat(), longitude.toFloat(), 0f, timeMillis)
        return geomagneticField.declination.toDouble()
    }

    fun bearing(from: GpsCoordinate, to: GpsCoordinate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = (cos(lat1) * sin(lat2)) - (sin(lat1) * cos(lat2) * cos(dLon))
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun screenBearing(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1
        val dy = y1 - y2
        val degrees = Math.toDegrees(atan2(dx, dy))
        return if (degrees < 0.0) degrees + 360.0 else degrees
    }

    fun haversineDistance(a: GpsCoordinate, b: GpsCoordinate): Double {
        return haversineDistancePrim(a.latitude, a.longitude, b.latitude, b.longitude)
    }

    /** Примитивная версия без аллокаций — для вычислений внутри циклов */
    fun haversineDistancePrim(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val r1 = Math.toRadians(lat1)
        val r2 = Math.toRadians(lat2)

        val sinDLat = sin(dLat / 2.0)
        val sinDLon = sin(dLon / 2.0)
        val aVal = (sinDLat * sinDLat) + (cos(r1) * cos(r2) * sinDLon * sinDLon)
        val c = atan2(sqrt(aVal), sqrt(1.0 - aVal)) * 2.0
        return EARTH_RADIUS_METERS * c
    }

    fun northDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        return (to.latitude - from.latitude) * 0.017453292519943295 * EARTH_RADIUS_METERS
    }

    fun eastDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        val refLat = Math.toRadians(from.latitude)
        return (to.longitude - from.longitude) * 0.017453292519943295 * EARTH_RADIUS_METERS * cos(refLat)
    }

    fun offsetCoordinate(from: GpsCoordinate, dNorth: Double, dEast: Double): GpsCoordinate {
        val latRad = Math.toRadians(from.latitude)
        val dLat = dNorth / EARTH_RADIUS_METERS
        val dLon = dEast / (cos(latRad) * EARTH_RADIUS_METERS)
        return GpsCoordinate(
            from.latitude + Math.toDegrees(dLat),
            from.longitude + Math.toDegrees(dLon)
        )
    }

    fun magneticBearing(trueBearing: Double, declination: Double): Double {
        return trueBearing - declination
    }

    fun computeCalibrationHard(pointA: CalibrationPoint, pointB: CalibrationPoint, northAngle: Double = 0.0, oldCalibration: MapCalibration): MapCalibration? {
        val dNorth = northDistance(pointA.gps, pointB.gps)
        val dEast = (pointB.gps.longitude - pointA.gps.longitude) *
                0.017453292519943295 * EARTH_RADIUS_METERS * cos(Math.toRadians(pointA.gps.latitude))

        val gpsDistance = sqrt((dNorth * dNorth) + (dEast * dEast))
        if (gpsDistance < 1.0) return null

        val rotatedPoint = rotateAroundPoint(pointB.imageX, pointB.imageY, pointA.imageX, pointA.imageY, oldCalibration.magneticDeclination + northAngle)
        val dx = rotatedPoint.first - pointA.imageX
        val dy = rotatedPoint.second - pointA.imageY

        val imageDistance = sqrt((dx * dx) + (dy * dy))
        if (imageDistance < 0.001) return null

        return MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerMapX = abs(dEast / dx),
            scaleMetersPerMapY = abs(dNorth / dy),
            bearingDegrees = oldCalibration.magneticDeclination,
            magneticDeclination = oldCalibration.magneticDeclination,
            physicalDeclination = oldCalibration.magneticDeclination
        )
    }

    fun computeCalibrationSoft(pointA: CalibrationPoint, pointB: CalibrationPoint, northAngle: Double = 0.0, oldCalibration: MapCalibration): MapCalibration? {
        val trueBearing = bearing(pointA.gps, pointB.gps)
        val screenBearing = screenBearing(pointA.imageX, pointA.imageY, pointB.imageX, pointB.imageY) - northAngle
        val calculatedDeclinationRaw = trueBearing - screenBearing

        var limitDecl: String? = null
        val calculatedDeclination = if (oldCalibration.pointB == null) {
            calculatedDeclinationRaw
        } else {
            val deltaDeclMax = pointA.confidence * MAX_DELTA_DECLINATION * pointB.confidence
            val declMin = oldCalibration.magneticDeclination - deltaDeclMax
            val declMax = oldCalibration.magneticDeclination + deltaDeclMax
            val declClamped = calculatedDeclinationRaw.coerceIn(declMin, declMax)
            if (declClamped != calculatedDeclinationRaw) {
                limitDecl = "dec:${fmt(deltaDeclMax, 3)}°|${fmt(calculatedDeclinationRaw, 2)}→${fmt(declClamped, 2)}"
            }
            declClamped
        }

        val dNorth = northDistance(pointA.gps, pointB.gps)
        val dEast = eastDistance(pointA.gps, pointB.gps)
        if (sqrt((dNorth * dNorth) + (dEast * dEast)) < 100.0) return null

        val rotatedPoint = rotateAroundPoint(pointB.imageX, pointB.imageY, pointA.imageX, pointA.imageY, calculatedDeclination + northAngle)
        val dx = rotatedPoint.first - pointA.imageX
        val dy = rotatedPoint.second - pointA.imageY

        var limitSx: String? = null
        var limitSy: String? = null
        val scaleMetersPerUnitX: Double
        val scaleMetersPerUnitY: Double

        if (abs(dNorth) < abs(dEast)) {
            val scaleXRaw = abs(dEast / dx)
            val confX = pointA.confidence * MAX_DELTA_SCALE * pointB.confidence
            var scaleX = scaleXRaw
            if (oldCalibration.pointB != null) {
                val clamped = scaleXRaw.coerceIn(oldCalibration.scaleMetersPerMapX - confX, oldCalibration.scaleMetersPerMapX + confX)
                if (clamped != scaleXRaw) {
                    limitSx = "sx:${fmt(confX, 3)}|${fmt(scaleXRaw, 1)}→${fmt(clamped, 1)}"
                }
                scaleX = clamped
            }

            val scaleYRaw = abs(dNorth / dy)
            val confY = abs(pointA.confidence * MAX_DELTA_SCALE * pointB.confidence * (dNorth / dEast))
            val scaleYClamped = scaleYRaw.coerceIn(oldCalibration.scaleMetersPerMapY - confY, oldCalibration.scaleMetersPerMapY + confY)
            if (scaleYClamped != scaleYRaw) {
                limitSy = "sy:${fmt(confY, 3)}|${fmt(scaleYRaw, 1)}→${fmt(scaleYClamped, 1)}"
            }

            scaleMetersPerUnitX = scaleX
            scaleMetersPerUnitY = scaleYClamped
        } else {
            val scaleYRaw = abs(dNorth / dy)
            val confY = pointA.confidence * MAX_DELTA_SCALE * pointB.confidence
            var scaleY = scaleYRaw
            if (oldCalibration.pointB != null) {
                val clamped = scaleYRaw.coerceIn(oldCalibration.scaleMetersPerMapY - confY, oldCalibration.scaleMetersPerMapY + confY)
                if (clamped != scaleYRaw) {
                    limitSy = "sy:${fmt(confY, 3)}|${fmt(scaleYRaw, 1)}→${fmt(clamped, 1)}"
                }
                scaleY = clamped
            }

            val scaleXRaw = abs(dEast / dx)
            val confX = abs(pointA.confidence * MAX_DELTA_SCALE * pointB.confidence * (dEast / dNorth))
            val scaleXClamped = scaleXRaw.coerceIn(oldCalibration.scaleMetersPerMapX - confX, oldCalibration.scaleMetersPerMapX + confX)
            if (scaleXClamped != scaleXRaw) {
                limitSx = "sx:${fmt(confX, 3)}|${fmt(scaleXRaw, 1)}→${fmt(scaleXClamped, 1)}"
            }

            scaleMetersPerUnitX = scaleXClamped
            scaleMetersPerUnitY = scaleY
        }

        // Детальный лог ограничений — строим напрямую без listOfNotNull/ArrayList
        if (limitDecl != null || limitSx != null || limitSy != null) {
            val sbLimits = java.lang.StringBuilder(80)
            sbLimits.append("computeCalibrationSoft LIMITS:")
            if (limitDecl != null) sbLimits.append(" dec:$limitDecl")
            if (limitSx != null) sbLimits.append(" sx:$limitSx")
            if (limitSy != null) sbLimits.append(" sy:$limitSy")
            Log.d(TAG, sbLimits.toString())
        // Полный итог — строим напрямую без String.format
            val sb = java.lang.StringBuilder(200)
            sb.append("computeCalibrationSoft dec={raw=${fmt(calculatedDeclinationRaw, 2)}")
            sb.append(" old=${fmt(oldCalibration.magneticDeclination, 2)}")
            sb.append(if (limitDecl != null) "[CLAMPED]" else "OK")
            sb.append("} sx={raw=${fmt(scaleMetersPerUnitX, 1)}")
            sb.append(" old=${fmt(oldCalibration.scaleMetersPerMapX, 1)}")
            sb.append(if (limitSx != null) "[CLAMPED]" else "OK")
            sb.append("} sy={raw=${fmt(scaleMetersPerUnitY, 1)}")
            sb.append(" old=${fmt(oldCalibration.scaleMetersPerMapY, 1)}")
            sb.append(if (limitSy != null) "[CLAMPED]" else "OK")
            sb.append("} north=${fmt(northAngle, 1)} conf=[${fmt(pointA.confidence, 2)},${fmt(pointB.confidence, 2)}]")
            Log.d(TAG, sb.toString())
        }

        return MapCalibration(pointA, pointB, scaleMetersPerUnitX, scaleMetersPerUnitY, calculatedDeclination, calculatedDeclination + northAngle, calculatedDeclination)
    }

    fun calibrationSingle(pointA: CalibrationPoint, magneticDeclination: Double, northAngle: Double = 0.0, scaleX: Double, scaleY: Double, pointB: CalibrationPoint? = null): MapCalibration {
        Log.d(TAG, "calibrationSingle Sx=$scaleX Sy=$scaleY declination=$magneticDeclination")
        return MapCalibration(pointA, pointB, scaleX, scaleY, magneticDeclination, magneticDeclination + northAngle, magneticDeclination)
    }

    fun calibrationSingleSoft(
        pointA: CalibrationPoint,
        magneticDeclination: Double,
        northAngle: Double = 0.0,
        scaleX: Double,
        scaleY: Double,
        oldCalibration: MapCalibration
    ): MapCalibration {
        val pairGpsToImage = gpsToImage(pointA.gps, oldCalibration, northAngle)

        var clampedX = false
        var clampedY = false
        val newImgX: Double
        val newImgY: Double

        if (pairGpsToImage != null) {
            val oldImgX = pairGpsToImage.first
            val oldImgY = pairGpsToImage.second
            val dxVal = pointA.imageX - oldImgX
            val dyVal = pointA.imageY - oldImgY
//            val dist = sqrt((dxVal * dxVal) + (dyVal * dyVal))

            val maxDeltaX = MAX_DELTA_DISTANCE / scaleX * pointA.confidence
            val maxDeltaY = MAX_DELTA_DISTANCE / scaleY * pointA.confidence

            if (dxVal > maxDeltaX) {
                val ratio = maxDeltaX / dxVal
                newImgX = oldImgX + (dxVal * ratio)
                clampedX = true
            } else {
                newImgX = pointA.imageX
            }

            if (dyVal > maxDeltaY) {
                val ratio = maxDeltaX / dxVal
                newImgY = oldImgY + (dyVal * ratio)
                clampedY = true
            } else {
                newImgY = pointA.imageY
            }


            val sb = java.lang.StringBuilder(180)
            sb.append("calibrationSingleSoft ")
            sb.append(if (clampedX) "[CLAMPED X]" else "OK")
            sb.append(if (clampedY) "[CLAMPED Y]" else "OK")
            sb.append(" distX=${fmt(dxVal, 5)} maxDelta=${fmt(maxDeltaX, 5)}")
            sb.append(" distY=${fmt(dyVal, 5)} maxDelta=${fmt(maxDeltaY, 5)}")
            sb.append(" conf=${fmt(pointA.confidence, 2)}")
            Log.d(TAG, sb.toString())
        } else {
            newImgX = pointA.imageX
            newImgY = pointA.imageY
            Log.d(TAG, "calibrationSingleSoft [NO_OLD_PROJ] raw=(${fmt(newImgX, 4)}, ${fmt(newImgY, 4)})")
        }

        return MapCalibration(
            pointA = pointA.copy(imageX = newImgX, imageY = newImgY),
            pointB = oldCalibration.pointB,
            scaleMetersPerMapX = scaleX,
            scaleMetersPerMapY = scaleY,
            bearingDegrees = magneticDeclination,
            magneticDeclination = magneticDeclination + northAngle,
            physicalDeclination = magneticDeclination
        )
    }
    fun snapBoundary(basePoint: GpsCoordinate, secondPoint: GpsCoordinate): GpsCoordinate {
        val results = FloatArray(2)
        android.location.Location.distanceBetween(basePoint.latitude, basePoint.longitude, secondPoint.latitude, secondPoint.longitude, results)
        if (results[0] <= MAX_DELTA_DISTANCE) return secondPoint
        Log.d(TAG, "Максимальная калибровка calibrationSingleSoft")
        return calculateDestinationPoint(basePoint, results[1].toDouble())
    }
    private fun calculateDestinationPoint(startPoint: GpsCoordinate, bearingDegrees: Double): GpsCoordinate {
        val lat1 = Math.toRadians(startPoint.latitude)
        val lon1 = Math.toRadians(startPoint.longitude)
        val bearingRad = Math.toRadians(bearingDegrees)
        val angularDistance = 50.0 / EARTH_RADIUS_METERS
        val lat2 = asin((sin(lat1) * cos(angularDistance)) + (cos(lat1) * sin(angularDistance) * cos(bearingRad)))
        val dSin = sin(bearingRad) * sin(angularDistance) * cos(lat1)
        val denom = cos(angularDistance) - (sin(lat1) * sin(lat2))
        val lon2 = atan2(dSin, denom) + lon1
        return GpsCoordinate(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }
    fun gpsToImageTrueNorth(gps: GpsCoordinate, calibration: MapCalibration): Pair<Double, Double> {
        val dNorth = northDistance(calibration.pointA.gps, gps)
        val dEast = (gps.longitude - calibration.pointA.gps.longitude) *
                0.017453292519943295 * EARTH_RADIUS_METERS * cos(Math.toRadians(calibration.pointA.gps.latitude))
        return Pair(
            calibration.pointA.imageX + (dEast / calibration.scaleMetersPerMapX),
            calibration.pointA.imageY + (-dNorth / calibration.scaleMetersPerMapY)
        )
    }
    fun gpsToImage(gps: GpsCoordinate, calibration: MapCalibration, northAngleDeg: Double): Pair<Double, Double>? {
        val baseCoords = gpsToImageTrueNorth(gps, calibration)
        val fullAngle = calibration.magneticDeclination + northAngleDeg
        return rotateAroundCalibration(baseCoords.first, baseCoords.second, calibration, -fullAngle)
    }
    fun imageToGpsTrueNorth(imageX: Double, imageY: Double, calibration: MapCalibration): GpsCoordinate {
        val relDx = imageX - calibration.pointA.imageX
        val relDy = imageY - calibration.pointA.imageY
        return offsetCoordinate(calibration.pointA.gps, -(calibration.scaleMetersPerMapY * relDy), calibration.scaleMetersPerMapX * relDx)
    }
    fun imageToGps(imageX: Double, imageY: Double, calibration: MapCalibration?, northAngle: Double): GpsCoordinate? {
        if (calibration == null) return null
        val fullAngle = northAngle + calibration.magneticDeclination
        val rotated = rotateAroundCalibration(imageX, imageY, calibration, fullAngle) ?: return null
        return imageToGpsTrueNorth(rotated.first, rotated.second, calibration)
    }
    fun imageToGps(point: PointF, calibration: MapCalibration?, northAngle: Double): GpsCoordinate? {
        return imageToGps(point.x.toDouble(), point.y.toDouble(), calibration, northAngle)
    }

    /** Версия без аллокаций Pair и GpsCoordinate — записывает результат (imageX, imageY) в outPoint */
    fun gpsToImagePrim(
        lat: Double,
        lon: Double,
        calibration: MapCalibration,
        northAngleDeg: Double,
        outPoint: PointF
    ) {
        val refLatRad = Math.toRadians(calibration.pointA.gps.latitude)
        val dNorth = (lat - calibration.pointA.gps.latitude) * 0.017453292519943295 * EARTH_RADIUS_METERS
        val dEast = (lon - calibration.pointA.gps.longitude) * 0.017453292519943295 * EARTH_RADIUS_METERS * cos(refLatRad)

        val baseX = calibration.pointA.imageX + (dEast / calibration.scaleMetersPerMapX)
        val baseY = calibration.pointA.imageY + (-dNorth / calibration.scaleMetersPerMapY)

        // Pivot совпадает с pointA.imageX / pointA.imageY (так как dNorth=0, dEast=0 в pointA)
        val cx = calibration.pointA.imageX
        val cy = calibration.pointA.imageY

        val fullAngleRad = Math.toRadians(-(calibration.magneticDeclination + northAngleDeg))
        val cosA = cos(fullAngleRad)
        val sinA = sin(fullAngleRad)

        val tx = baseX - cx
        val ty = baseY - cy

        outPoint.x = ((tx * cosA) - (ty * sinA) + cx).toFloat()
        outPoint.y = ((tx * sinA) + (ty * cosA) + cy).toFloat()
    }

    fun offsetGps(from: GpsCoordinate, bearingDeg: Double, distanceMeters: Double): GpsCoordinate {
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearingRad = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = asin((sin(lat1) * cos(angularDistance)) + (cos(lat1) * sin(angularDistance) * cos(bearingRad)))
        val lon2 = atan2(sin(bearingRad) * sin(angularDistance) * cos(lat1), cos(angularDistance) - (sin(lat1) * sin(lat2))) + lon1
        return GpsCoordinate(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }
    private fun rotateAroundCalibration(x: Double, y: Double, calibration: MapCalibration, fullAngle: Double): Pair<Double, Double>? {
        val pivot = gpsToImageTrueNorth(calibration.pointA.gps, calibration)
        return rotateAroundPoint(x, y, pivot.first, pivot.second, fullAngle)
    }
    private fun rotateAroundPoint(x: Double, y: Double, cx: Double, cy: Double, angleInDegrees: Double): Pair<Double, Double> {
        val radians = Math.toRadians(angleInDegrees)
        val cosA = cos(radians)
        val sinA = sin(radians)
        val tx = x - cx
        val ty = y - cy
        return Pair(
            (tx * cosA) - (ty * sinA) + cx,
            (tx * sinA) + (ty * cosA) + cy
        )
    }
    fun convertToNormalized(pixelX: Double, pixelY: Double, width: Double, height: Double): PointF {
        val size = min(width, height)
        return PointF(
            ((pixelX - (width - size) / 2.0) / size).toFloat(),
            ((pixelY - (height - size) / 2.0) / size).toFloat()
        )
    }
    fun convertToPixels(x: Double, y: Double, width: Double, height: Double): PointF {
        val size = min(width, height)
        return PointF(
            ((x * size) + (width - size) / 2.0).toFloat(),
            ((y * size) + (height - size) / 2.0).toFloat()
        )
    }
}