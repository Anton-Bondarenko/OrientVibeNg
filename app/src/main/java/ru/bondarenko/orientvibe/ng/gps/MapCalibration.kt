package ru.bondarenko.orientvibe.ng.gps

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.MapCalibration

/**
 * Utility for computing map calibration from two GPS/image point pairs,
 * and converting between GPS coordinates and image coordinates.
 */
object MapCalibrationUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun calibrate(
        pointA: CalibrationPoint,
        pointB: CalibrationPoint,
        magneticDeclination: Double
    ): MapCalibration? {
        val gpsDistance = haversineDistance(pointA.gps, pointB.gps)
        if (gpsDistance < 1.0) return null

        val dx = (pointB.imageX - pointA.imageX).toDouble()
        val dy = (pointB.imageY - pointA.imageY).toDouble()
        val imageDistance = sqrt(dx * dx + dy * dy)
        if (imageDistance < 0.001) return null

        val scaleMetersPerUnit = gpsDistance / imageDistance

        // bearingDegrees = магнитный азимут от точки A к точке B
        // (направление, которое нужно показать компасом чтобы двигаться с A к B на карте)
        val trueBearing = bearing(pointA.gps, pointB.gps)
        val magneticBearingDeg = trueBearing - magneticDeclination

        return MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerPixel = scaleMetersPerUnit,
            bearingDegrees = magneticBearingDeg,
            magneticDeclination = magneticDeclination
        )
    }

    fun gpsToImage(
        gps: GpsCoordinate,
        calibration: MapCalibration
    ): Pair<Float, Float>? {
        val dNorth = northDistance(calibration.pointA.gps, gps)
        val dEast = eastDistance(calibration.pointA.gps, gps)

        val bearingRad = Math.toRadians(calibration.bearingDegrees)
        val imageDx = dEast * cos(bearingRad) - dNorth * sin(bearingRad)
        val imageDy = -(dEast * sin(bearingRad) + dNorth * cos(bearingRad))

        val relDx = (imageDx / calibration.scaleMetersPerPixel).toFloat()
        val relDy = (imageDy / calibration.scaleMetersPerPixel).toFloat()

        return Pair(
            calibration.pointA.imageX + relDx,
            calibration.pointA.imageY + relDy
        )
    }

    fun imageToGps(
        imageX: Float,
        imageY: Float,
        calibration: MapCalibration
    ): GpsCoordinate? {
        val relDx = (imageX - calibration.pointA.imageX).toDouble()
        val relDy = (imageY - calibration.pointA.imageY).toDouble()

        val metersDx = relDx * calibration.scaleMetersPerPixel
        val metersDy = relDy * calibration.scaleMetersPerPixel

        val bearingRad = Math.toRadians(calibration.bearingDegrees)
        val dEast = metersDx * cos(bearingRad) - metersDy * sin(bearingRad)
        val dNorth = -(metersDx * sin(bearingRad) + metersDy * cos(bearingRad))

        return offsetCoordinate(calibration.pointA.gps, dNorth, dEast)
    }

    fun magneticBearing(
        from: GpsCoordinate,
        to: GpsCoordinate,
        magneticDeclination: Double
    ): Double {
        val trueBearing = bearing(from, to)
        return trueBearing.minus(magneticDeclination)
    }

    fun bearing(from: GpsCoordinate, to: GpsCoordinate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun haversineDistance(a: GpsCoordinate, b: GpsCoordinate): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val sinDLat = sin(dLat / 2.0)
        val sinDLon = sin(dLon / 2.0)
        val aVal = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        val c = 2.0 * atan2(sqrt(aVal), sqrt(1.0 - aVal))
        return EARTH_RADIUS_METERS * c
    }

    fun northDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        return (to.latitude - from.latitude) * (Math.PI / 180.0) * EARTH_RADIUS_METERS
    }

    fun eastDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        val avgLat = Math.toRadians((from.latitude + to.latitude) / 2.0)
        return (to.longitude - from.longitude) * (Math.PI / 180.0) * EARTH_RADIUS_METERS * cos(avgLat)
    }

    private fun offsetCoordinate(
        from: GpsCoordinate,
        dNorth: Double,
        dEast: Double
    ): GpsCoordinate {
        val latRad = Math.toRadians(from.latitude)
        val dLat = dNorth / EARTH_RADIUS_METERS
        val dLon = dEast / (EARTH_RADIUS_METERS * cos(latRad))

        return GpsCoordinate(
            latitude = from.latitude + Math.toDegrees(dLat),
            longitude = from.longitude + Math.toDegrees(dLon)
        )
    }
}