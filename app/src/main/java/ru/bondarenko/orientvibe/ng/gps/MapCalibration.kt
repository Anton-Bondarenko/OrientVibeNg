package ru.bondarenko.orientvibe.ng.gps

import kotlin.math.*
import android.graphics.PointF

/**
 * Utility for computing map calibration from two GPS/image point pairs,
 * and converting between GPS coordinates and image coordinates.
 */
object MapCalibrationUtils {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Compute map calibration from two calibration points.
     * Returns null if the points are too close together (degenerate case).
     *
     * @param pointA First calibration point (GPS + image coordinates)
     * @param pointB Second calibration point (GPS + image coordinates)
     * @return MapCalibration with scale and bearing, or null if degenerate
     */
    fun calibrate(
        pointA: CalibrationPoint,
        pointB: CalibrationPoint
    ): MapCalibration? {
        // Distance between the two GPS points in meters
        val gpsDistance = haversineDistance(pointA.gps, pointB.gps)
        if (gpsDistance < 1.0) return null // too close

        // Distance between the two image points in relative units
        val dx = (pointB.imageX - pointA.imageX).toDouble()
        val dy = (pointB.imageY - pointA.imageY).toDouble()
        val imageDistance = sqrt(dx * dx + dy * dy)
        if (imageDistance < 0.001) return null // too close

        // Scale: meters per relative image unit
        val scaleMetersPerUnit = gpsDistance / imageDistance

        // Bearing: angle from image Y-axis to true north
        // First, compute the bearing from A to B on the ground
        val gpsBearing = bearing(pointA.gps, pointB.gps) // degrees from true north

        // Compute the angle of the vector (B - A) in image space
        // Image Y points down, so we negate dy
        val imageAngle = Math.toDegrees(atan2(dx, -dy))

        // The bearing of the image Y-axis relative to true north
        val bearingDegrees = (gpsBearing - imageAngle + 360.0) % 360.0

        return MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerPixel = scaleMetersPerUnit,
            bearingDegrees = bearingDegrees
        )
    }

    /**
     * Convert a GPS coordinate to relative image coordinates using calibration.
     * Returns null if calibration is null.
     */
    fun gpsToImage(
        gps: GpsCoordinate,
        calibration: MapCalibration
    ): Pair<Float, Float>? {
        // Compute vector from calibration point A to the GPS point
        val dNorth = northDistance(calibration.pointA.gps, gps)   // meters, positive = north
        val dEast = eastDistance(calibration.pointA.gps, gps)     // meters, positive = east

        // Rotate by -bearingDegrees to align with image axes
        val bearingRad = Math.toRadians(calibration.bearingDegrees)
        // Image X = dEast * cos(bearing) - dNorth * sin(bearing)
        // Image Y (down) = -(dEast * sin(bearing) + dNorth * cos(bearing))
        //   Negation because image Y points down, so north (positive dNorth) => up (negative image Y)
        val imageDx = dEast * cos(bearingRad) - dNorth * sin(bearingRad)
        val imageDy = -(dEast * sin(bearingRad) + dNorth * cos(bearingRad))

        // Convert from meters to relative image units
        val relDx = (imageDx / calibration.scaleMetersPerPixel).toFloat()
        val relDy = (imageDy / calibration.scaleMetersPerPixel).toFloat()

        return Pair(
            calibration.pointA.imageX + relDx,
            calibration.pointA.imageY + relDy
        )
    }

    /**
     * Convert relative image coordinates to GPS coordinates using calibration.
     * Returns null if calibration is null.
     */
    fun imageToGps(
        imageX: Float,
        imageY: Float,
        calibration: MapCalibration
    ): GpsCoordinate? {
        // Vector from calibration point A in image space
        val relDx = (imageX - calibration.pointA.imageX).toDouble()
        val relDy = (imageY - calibration.pointA.imageY).toDouble()

        // Convert to meters
        val metersDx = relDx * calibration.scaleMetersPerPixel
        val metersDy = relDy * calibration.scaleMetersPerPixel

        // Rotate by bearingDegrees to align with geographic axes
        // Inverse of gpsToImage: [dx; dy] = [[cos(b), -sin(b)]; [-sin(b), -cos(b)]] * [dE; dN]
        // The matrix is its own inverse, so: [dE; dN] = [[cos(b), -sin(b)]; [-sin(b), -cos(b)]] * [dx; dy]
        val bearingRad = Math.toRadians(calibration.bearingDegrees)
        val dEast = metersDx * cos(bearingRad) - metersDy * sin(bearingRad)
        val dNorth = -(metersDx * sin(bearingRad) + metersDy * cos(bearingRad))

        return offsetCoordinate(calibration.pointA.gps, dNorth, dEast)
    }

    /**
     * Compute the bearing (degrees from true north) from point A to point B.
     */
    fun bearing(from: GpsCoordinate, to: GpsCoordinate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Haversine distance between two GPS coordinates in meters.
     */
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

    /**
     * North-south distance between two points in meters (positive = north).
     */
    fun northDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        return (to.latitude - from.latitude) * (Math.PI / 180.0) * EARTH_RADIUS_METERS
    }

    /**
     * East-west distance between two points in meters (positive = east).
     * Uses the average latitude for the conversion.
     */
    fun eastDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        val avgLat = Math.toRadians((from.latitude + to.latitude) / 2.0)
        return (to.longitude - from.longitude) * (Math.PI / 180.0) * EARTH_RADIUS_METERS * cos(avgLat)
    }

    /**
     * Offset a GPS coordinate by dNorth and dEast meters.
     */
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