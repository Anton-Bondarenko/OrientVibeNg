package ru.bondarenko.orientvibe.ng.gps

import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.MapCalibration

/**
 * Convenience wrapper around the pure-math [MapGeometry] — provides logging
 * and a stable public API. All coordinate transformations live in MapGeometry,
 * which has zero Android SDK dependencies and can be unit-tested on the JVM.
 */
object MapCalibrationUtils {

    // ─── Calibration ──────────────────────────────────────────────────────

    fun calibrate(
        pointA: CalibrationPoint,
        pointB: CalibrationPoint,
        magneticDeclination: Double
    ): MapCalibration? {
        return MapGeometry.computeCalibrationRaw(pointA, pointB, magneticDeclination)
    }

    /** Returns the physical magnetic declination used for northAngle computation. */
    fun effectiveDeclination(cal: MapCalibration): Double {
        return cal.physicalDeclination
    }

    // ─── Coordinate transforms ────────────────────────────────────────────

    fun gpsToImage(gps: GpsCoordinate, calibration: MapCalibration): Pair<Float, Float>? {
        return MapGeometry.gpsToImageRelative(gps, calibration)
    }

    fun imageToGps(imageX: Float, imageY: Float, calibration: MapCalibration): GpsCoordinate? {
        return MapGeometry.imageToGpsRelative(imageX, imageY, calibration)
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

    // ─── Absolute GPS→image (with scaling, northAngle rotation handled by canvas) ──

    /**
     * Convert GPS coordinate to absolute image-space coordinates (pixels).
     * Uses the calibration's pointA anchor and uniform scale to map geographic offsets
     * to pixel offsets, then rotates around pointA by northAngleDeg to align with the
     * map's displayed orientation. This ensures projected GPS points stay consistent with
     * the rotated map image — the green GPS dot and purple calibration-point markers
     * both use this function so they share the same coordinate frame before sourceToViewCoord()
     * applies the uniform canvas rotation in production rendering.
     */
    fun gpsToImageAbs(
        gps: GpsCoordinate,
        calibration: MapCalibration,
        imageDimensions: Pair<Float, Float>,  // (width, height) — unused but kept for API compatibility
        northAngleDeg: Float  // degrees to rotate around pointA
    ): Pair<Float, Float>? {
        val rel = gpsToImage(gps, calibration) ?: return null

        if (northAngleDeg == 0f) return rel

        val pivotX = calibration.pointA.imageX.toDouble()
        val pivotY = calibration.pointA.imageY.toDouble()
        val angle = Math.toRadians(northAngleDeg.toDouble())
        val cosA = kotlin.math.cos(angle)
        val sinA = kotlin.math.sin(angle)

        // Rotate around pointA (pivot) by northAngleDeg
        val dx = rel.first.toDouble() - pivotX
        val dy = rel.second.toDouble() - pivotY
        val rx = pivotX + dx * cosA - dy * sinA
        val ry = pivotY + dx * sinA + dy * cosA

        return Pair(rx.toFloat(), ry.toFloat())
    }

    /** Offset the starting GPS coordinate by a northward and eastward displacement (metres). */
    fun offsetCoordinate(from: GpsCoordinate, dNorth: Double, dEast: Double): GpsCoordinate {
        return MapGeometry.offsetCoordinate(from, dNorth, dEast)
    }

    // ─── Single-point (start) calibration ─────────────────────────────────

    /**
     * Create a single-point calibration from one GPS→image mapping.
     * Uses bearing=0 and scale=1 as defaults — meaningful only after two-point
     * recalibration via [bindGpsToFinishWithTrack]. Sets northAngle = 0 so the map
     * is not rotated until proper calibration arrives.
     */
    fun calibrateSinglePoint(
        startGPS: GpsCoordinate,
        startPointImageX: Float,
        startPointImageY: Float
    ): MapCalibration {
        // Synthetic pointB: directly north of pointA (bearing 0), scale=1 m/px → minimal placeholder
        val earthRadius = 6371000.0
        val angDist = 1.0 / earthRadius // 1 meter
        val lat1Rad = Math.toRadians(startGPS.latitude)
        val northGps = GpsCoordinate(
            latitude = Math.toDegrees(lat1Rad + angDist),
            longitude = startGPS.longitude
        )
        return MapGeometry.computeCalibrationRaw(
            CalibrationPoint(gps = startGPS, imageX = startPointImageX, imageY = startPointImageY),
            CalibrationPoint(gps = northGps, imageX = startPointImageX, imageY = startPointImageY + 1f),
            5.0
        )!!
    }

    // ─── Two-point finish calibration (track-based) ───────────────────────

    /**
     * Result of [bindGpsToFinishWithTrack]: the recalibrated calibration and the northAngle to apply.
     */
    data class BindResult(
        val calibration: MapCalibration,
        val northAngleDegrees: Float
    )

    /** Full finish calibration using track direction + distance from original start to current GPS.
     * Creates a two-point calibration where pointA=startGPS and pointB=currentFixGPS,
     * so the user's current position maps exactly to finishPoint on the map image.
     */
    fun bindGpsToFinishWithTrack(
        startGPS: GpsCoordinate,
        startPointImageX: Float,
        startPointImageY: Float,
        finishPointImageX: Float,
        finishPointImageY: Float,
        currentFixGPS: GpsCoordinate,
        magneticDeclination: Double = 0.0
    ): BindResult {
        // Use actual GPS coordinates directly — two-point calibration guarantees
        // gpsToImage(pointB.gps) returns pointB.imageCoords exactly.
        val pointA = CalibrationPoint(gps = startGPS, imageX = startPointImageX, imageY = startPointImageY)
        val pointB = CalibrationPoint(gps = currentFixGPS, imageX = finishPointImageX, imageY = finishPointImageY)

        val newCal = calibrate(pointA, pointB, magneticDeclination)
            ?: throw IllegalStateException("bindGpsToFinishWithTrack: calibration points too close")

        // North angle = -raw_magnetic_bearing = -(trueBearing - declination).
        // For a magnetic-north-aligned orienteering map the image frame is rotated
        // relative to geographic north by +declination.  Compensating with
        // northAngle = -rawMagneticBearing cancels that rotation so currentFixGPS
        // projects EXACTLY onto finishPoint after canvas rotation.
        val trueBearing = MapGeometry.bearing(pointA.gps, pointB.gps)
        val northAngleDeg = -(trueBearing - magneticDeclination).toFloat()

        return BindResult(newCal, northAngleDeg)
    }
}
