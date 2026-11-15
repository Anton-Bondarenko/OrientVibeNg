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
    private const val MAX_DELTA_DISTANCE = 0.01
    private const val MAX_DELTA_SCALE = 200.0

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
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val sinDLat = sin(dLat / 2.0)
        val sinDLon = sin(dLon / 2.0)
        val aVal = (sinDLat * sinDLat) + (cos(lat1) * cos(lat2) * sinDLon * sinDLon)
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

    fun computeCalibrationHard(pointA: CalibrationPoint, pointB: CalibrationPoint, magneticDeclination: Double, northAngle: Double = 0.0): MapCalibration? {
        val dNorth = northDistance(pointA.gps, pointB.gps)
        val dEast = (pointB.gps.longitude - pointA.gps.longitude) *
                0.017453292519943295 * EARTH_RADIUS_METERS * cos(Math.toRadians(pointA.gps.latitude))

        val gpsDistance = sqrt((dNorth * dNorth) + (dEast * dEast))
        if (gpsDistance < 1.0) return null

        val rotatedPoint = rotateAroundPoint(pointB.imageX, pointB.imageY, pointA.imageX, pointA.imageY, magneticDeclination + northAngle)
        val dx = rotatedPoint.first - pointA.imageX
        val dy = rotatedPoint.second - pointA.imageY

        val imageDistance = sqrt((dx * dx) + (dy * dy))
        if (imageDistance < 0.001) return null

        return MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerMapX = abs(dEast / dx),
            scaleMetersPerMapY = abs(dNorth / dy),
            bearingDegrees = magneticDeclination,
            magneticDeclination = magneticDeclination,
            physicalDeclination = magneticDeclination
        )
    }

    fun computeCalibrationSoft(pointA: CalibrationPoint, pointB: CalibrationPoint, northAngle: Double = 0.0, oldCalibration: MapCalibration): MapCalibration? {
        val trueBearing = bearing(pointA.gps, pointB.gps)
        val screenBearing = screenBearing(pointA.imageX, pointA.imageY, pointB.imageX, pointB.imageY) - northAngle
        val calculatedDeclinationRaw = trueBearing - screenBearing

        val calculatedDeclination = if (oldCalibration.pointB == null) {
            calculatedDeclinationRaw
        } else {
            oldCalibration.magneticDeclination + (calculatedDeclinationRaw - oldCalibration.magneticDeclination).coerceIn(
                pointA.confidence * -MAX_DELTA_DECLINATION * pointB.confidence,
                pointA.confidence * MAX_DELTA_DECLINATION * pointB.confidence
            )
        }

        val dNorth = northDistance(pointA.gps, pointB.gps)
        val dEast = eastDistance(pointA.gps, pointB.gps)
        if (sqrt((dNorth * dNorth) + (dEast * dEast)) < 100.0) return null

        val rotatedPoint = rotateAroundPoint(pointB.imageX, pointB.imageY, pointA.imageX, pointA.imageY, calculatedDeclination + northAngle)
        val dx = rotatedPoint.first - pointA.imageX
        val dy = rotatedPoint.second - pointA.imageY

        val scaleMetersPerUnitX: Double
        val scaleMetersPerUnitY: Double

        if (abs(dNorth) < abs(dEast)) {
            var scaleX = abs(dEast / dx)
            if (oldCalibration.pointB != null) {
                val confidence = pointA.confidence * MAX_DELTA_SCALE * pointB.confidence
                scaleX = oldCalibration.scaleMetersPerMapX + (scaleX - oldCalibration.scaleMetersPerMapX).coerceIn(-confidence, confidence)
            }
            val scaleYRaw = abs(dNorth / dy)
            val confidenceY = abs(pointA.confidence * MAX_DELTA_SCALE * pointB.confidence * (dNorth / dEast))

            scaleMetersPerUnitX = scaleX
            scaleMetersPerUnitY = oldCalibration.scaleMetersPerMapY + (scaleYRaw - oldCalibration.scaleMetersPerMapY).coerceIn(-confidenceY, confidenceY)
        } else {
            val scaleYRaw = abs(dNorth / dy)
            val scaleY = if (oldCalibration.pointB == null) {
                scaleYRaw
            } else {
                val confidence = pointA.confidence * MAX_DELTA_SCALE * pointB.confidence
                oldCalibration.scaleMetersPerMapY + (scaleYRaw - oldCalibration.scaleMetersPerMapY).coerceIn(-confidence, confidence)
            }
            val scaleXRaw = abs(dEast / dx)
            val confidenceX = abs(pointA.confidence * MAX_DELTA_SCALE * pointB.confidence * (dEast / dNorth))

            scaleMetersPerUnitX = oldCalibration.scaleMetersPerMapX + (scaleXRaw - oldCalibration.scaleMetersPerMapX).coerceIn(-confidenceX, confidenceX)
            scaleMetersPerUnitY = scaleY
        }

        Log.d(TAG, String.format("computeCalibrationSoft Sx=%.1f Sy=%.1f declination=%.1f dDec=%.1f north=%.1f dSx=%.1f dSy=%.1f confidenceA=%.2f confidenceB=%.2f",
            scaleMetersPerUnitX, scaleMetersPerUnitY, calculatedDeclination, (calculatedDeclination - oldCalibration.magneticDeclination),
            northAngle, (scaleMetersPerUnitX - oldCalibration.scaleMetersPerMapX), (scaleMetersPerUnitY - oldCalibration.scaleMetersPerMapY),
            pointA.confidence, pointB.confidence))

        return MapCalibration(pointA, pointB, scaleMetersPerUnitX, scaleMetersPerUnitY, calculatedDeclination, calculatedDeclination + northAngle, calculatedDeclination)
    }

    fun calibrationSingle(pointA: CalibrationPoint, magneticDeclination: Double, northAngle: Double = 0.0, scaleX: Double, scaleY: Double, pointB: CalibrationPoint? = null): MapCalibration {
        Log.d(TAG, "calibrationSingle Sx=$scaleX Sy=$scaleY declination=$magneticDeclination")
        return MapCalibration(pointA, pointB, scaleX, scaleY, magneticDeclination, magneticDeclination + northAngle, magneticDeclination)
    }

    fun calibrationSingleSoft(pointA: CalibrationPoint, magneticDeclination: Double, northAngle: Double = 0.0, scaleX: Double, scaleY: Double, oldCalibration: MapCalibration): MapCalibration {
        val pairGpsToImage = gpsToImage(pointA.gps, oldCalibration, northAngle)
        val confidence = MAX_DELTA_DISTANCE * pointA.confidence

        val (newImgX, newImgY) = if (pairGpsToImage?.first != null && pairGpsToImage.second != null) {
            val x = pairGpsToImage.first!! + (pointA.imageX - pairGpsToImage.first!!).coerceIn(-confidence, confidence)
            val y = pairGpsToImage.second!! + (pointA.imageY - pairGpsToImage.second!!).coerceIn(-confidence, confidence)
            Log.d(TAG, String.format("dX=%.3f dY=%.3f confidenceA=%.2f", (pointA.imageX - pairGpsToImage.first!!), (pointA.imageY - pairGpsToImage.second!!), pointA.confidence))
            Pair(x, y)
        } else {
            Pair(pointA.imageX, pointA.imageY)
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