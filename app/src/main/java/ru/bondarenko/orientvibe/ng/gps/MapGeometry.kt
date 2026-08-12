package ru.bondarenko.orientvibe.ng.gps

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-geometry GPS utilities — no Android SDK dependencies.
 * Every function here is deterministic and testable on the JVM without Robolectric.
 */
object MapGeometry {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** True (geographic) bearing from *from* to *to*, in degrees [0, 360). */
    fun bearing(from: GpsCoordinate, to: GpsCoordinate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Haversine distance in metres between two GPS coordinates. */
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

    /** Northward distance in metres from *from* to *to*. Positive = north. */
    fun northDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        return (to.latitude - from.latitude) * (Math.PI / 180.0) * EARTH_RADIUS_METERS
    }

    /** Eastward distance in metres from *from* to *to*. Positive = east. */
    fun eastDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        val avgLat = Math.toRadians((from.latitude + to.latitude) / 2.0)
        return (to.longitude - from.longitude) * (Math.PI / 180.0) * EARTH_RADIUS_METERS * cos(avgLat)
    }

    /** Offset the starting GPS coordinate by a northward and eastward displacement (metres). */
    fun offsetCoordinate(from: GpsCoordinate, dNorth: Double, dEast: Double): GpsCoordinate {
        val latRad = Math.toRadians(from.latitude)
        val dLat = dNorth / EARTH_RADIUS_METERS
        val dLon = dEast / (EARTH_RADIUS_METERS * cos(latRad))

        return GpsCoordinate(
            latitude = from.latitude + Math.toDegrees(dLat),
            longitude = from.longitude + Math.toDegrees(dLon)
        )
    }

    /** Magnetic bearing = trueBearing − declination (decl positive = east). */
    fun magneticBearing(trueBearing: Double, declination: Double): Double {
        return trueBearing - declination
    }

    // ─── Calibration core (pure math) ───

    /**
     * Compute a MapCalibration from two calibration points and the magnetic declination.
     * Returns null if the points are too close to derive a valid transform.
     *
     * hasXYFlip is set true when cos(magneticBearing) < 0 — it serves as metadata indicating
     * that the physical map's north direction opposes screen-up, but does NOT affect the
     * coordinate transform (dEast/dNorth mapping is inherently correct for any bearing).
     */
    fun computeCalibrationRaw(
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
        val trueBearing = bearing(pointA.gps, pointB.gps)
        val rawMagneticBearing = magneticBearing(trueBearing, magneticDeclination)
        val hasXYFlip = cos(Math.toRadians(rawMagneticBearing)) < 0

        return MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerPixel = scaleMetersPerUnit,
            bearingDegrees = rawMagneticBearing,
            magneticDeclination = magneticDeclination,
            physicalDeclination = magneticDeclination,
            hasXYFlip = hasXYFlip
        )
    }

    /**
     * Forward GPS→image transform (relative 0…1 coordinates).
     *
     * Maps geographic offset (dNorth, dEast) into image-space using canonical orientation:
     * - GPS north → image up    (imageDy < 0)
     * - GPS east  → image right (imageDx > 0)
     *
     * This works correctly for ANY bearing — no rotation or flip needed. The raw dEast/dNorth
     * deltas inherently encode correct screen directions because:
     * - screenX ∝ dEast (positive east displacement always maps to right on screen)
     * - screenY ∝ -dNorth (positive north displacement always maps to up on screen)
     *
     * The caller is responsible for any northAngle or extra scale adjustment.
     */
    fun gpsToImageRelative(
        gps: GpsCoordinate,
        calibration: MapCalibration
    ): Pair<Float, Float>? {
        val dNorth = northDistance(calibration.pointA.gps, gps)
        val dEast = eastDistance(calibration.pointA.gps, gps)

        // Canonical mapping: north → up (negative Y in image space), east → right.
        // The raw dEast/dNorth already give correct screen directions regardless of bearing:
        // - screenX increases with GPS east displacement (always positive dEast → right)
        // - screenY decreases with GPS north displacement (positive dNorth → up)
        // hasXYFlip was previously used to compensate for a non-existent rotation matrix bug.
        val imageDx = dEast
        val imageDy = -dNorth

        val relDx = (imageDx / calibration.scaleMetersPerPixel).toFloat()
        val relDy = (imageDy / calibration.scaleMetersPerPixel).toFloat()

        return Pair(
            calibration.pointA.imageX + relDx,
            calibration.pointA.imageY + relDy
        )
    }

    /** Inverse image→GPS transform. */
    fun imageToGpsRelative(
        imageX: Float,
        imageY: Float,
        calibration: MapCalibration
    ): GpsCoordinate? {
        val relDx = (imageX - calibration.pointA.imageX).toDouble()
        val relDy = (imageY - calibration.pointA.imageY).toDouble()

        var metersDx = relDx * calibration.scaleMetersPerPixel
        var metersDy = relDy * calibration.scaleMetersPerPixel

        // Inverse of canonical: always direct mapping (no flip needed).
        val dEast = metersDx
        val dNorth = -metersDy

        return offsetCoordinate(calibration.pointA.gps, dNorth, dEast)
    }
}
