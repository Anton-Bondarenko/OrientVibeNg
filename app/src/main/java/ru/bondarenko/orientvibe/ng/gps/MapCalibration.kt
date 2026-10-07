package ru.bondarenko.orientvibe.ng.gps

import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils.bindGpsToFinishWithTrack
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
        magneticDeclination: Float
    ): MapCalibration? {
        return MapGeometry.computeCalibrationRaw(pointA, pointB, magneticDeclination)
    }

    /** Returns the physical magnetic declination used for northAngle computation. */
    fun effectiveDeclination(cal: MapCalibration): Float {
        return cal.physicalDeclination
    }

    // ─── Coordinate transforms ────────────────────────────────────────────

    fun gpsToImage(gps: GpsCoordinate, calibration: MapCalibration): Pair<Float, Float>? {
        return MapGeometry.gpsToImageTrueNorth(gps, calibration)
    }

    fun imageToGps(imageX: Float, imageY: Float, calibration: MapCalibration): GpsCoordinate? {
        return MapGeometry.imageToGpsTrueNorth(imageX, imageY, calibration)
    }

    fun magneticBearing(
        from: GpsCoordinate,
        to: GpsCoordinate,
        magneticDeclination: Float
    ): Float {
        val trueBearing = bearing(from, to)
        return MapGeometry.magneticBearing(trueBearing, magneticDeclination)
    }

    fun bearing(from: GpsCoordinate, to: GpsCoordinate): Float {
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
    fun gpsToImage(
        gps: GpsCoordinate,
        calibration: MapCalibration,
        northAngleDeg: Float  // degrees to rotate around pointA
    ): Pair<Float, Float>? {
        return MapGeometry.gpsToImage(gps, calibration, northAngleDeg)
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
        val angDist = 4000.0 / earthRadius
        val lat1Rad = Math.toRadians(startGPS.latitude)
        val northGps = GpsCoordinate(
            latitude = Math.toDegrees(lat1Rad + angDist),
            longitude = startGPS.longitude
        )
        val declination = calculateMagneticDeclination(startGPS.latitude, startGPS.longitude)
        var imgCoords = MapGeometry.rotateAroundPoint(startPointImageX, startPointImageY - 1f, startPointImageX, startPointImageY, -declination)

        return MapGeometry.computeCalibrationRaw(
            CalibrationPoint(gps = startGPS, imageX = startPointImageX, imageY = startPointImageY),
            CalibrationPoint(
                gps = northGps,
                imageX = imgCoords.first,
                imageY = imgCoords.second
            ),
            declination
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
        magneticDeclination: Float = 0f
    ): BindResult {
        // Use actual GPS coordinates directly — two-point calibration guarantees
        // gpsToImage(pointB.gps) returns pointB.imageCoords exactly.
        val pointA =
            CalibrationPoint(gps = startGPS, imageX = startPointImageX, imageY = startPointImageY)
        val pointB = CalibrationPoint(
            gps = currentFixGPS,
            imageX = finishPointImageX,
            imageY = finishPointImageY
        )

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
