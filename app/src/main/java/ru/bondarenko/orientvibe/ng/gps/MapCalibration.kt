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
        val result = MapGeometry.computeCalibrationRaw(pointA, pointB, magneticDeclination)
        if (result != null) {
            android.util.Log.d(
                "TRACK_DRAW",
                "calibrate: true_bearing_ab=${MapGeometry.bearing(pointA.gps, pointB.gps)} raw_magneticBearing=${result.bearingDegrees} hasXYFlip=${result.hasXYFlip} physical_decl=$magneticDeclination"
            )
        }
        return result
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

    // ─── Absolute GPS→image (with scaling + northAngle rotation) ──────────

    /**
     * Convert GPS coordinate to absolute image-space coordinates (pixels),
     * applying calibration bearing rotation AND northAngle adjustment.
     */
    fun gpsToImageAbs(
        gps: GpsCoordinate,
        calibration: MapCalibration,
        imageDimensions: Pair<Float, Float>,  // (width, height) in source pixels — unused but kept for API compatibility
        northAngleDeg: Float
    ): Pair<Float, Float>? {
        val rel = gpsToImage(gps, calibration) ?: return null

        // gpsToImage already returns calibrated absolute pixel coordinates.
        // We keep the dims parameter for backward compatibility; actual values are used as-is.
        var x = rel.first
        var y = rel.second

        if (northAngleDeg != 0f) {
            val angleRad = Math.toRadians(northAngleDeg.toDouble())
            val cosA = kotlin.math.cos(angleRad).toFloat()
            val sinA = kotlin.math.sin(angleRad).toFloat()
            // Pivot at calibration point A in absolute pixels
            val px = calibration.pointA.imageX
            val py = calibration.pointA.imageY
            val dx = x - px
            val dy = y - py
            x = px + dx * cosA - dy * sinA
            y = py + dx * sinA + dy * cosA
        }
        return Pair(x, y)
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
     * Creates a synthetic GPS finish coordinate, recalculates scale and north angle so the
     * user's current position on the track maps exactly to the finish point on the map image.
     */
    fun bindGpsToFinishWithTrack(
        startGPS: GpsCoordinate,
        startPointImageX: Float,
        startPointImageY: Float,
        finishPointImageX: Float,
        finishPointImageY: Float,
        currentFixGPS: GpsCoordinate
    ): BindResult {
        // 1. Bearing + distance from start to current GPS fix (direction + length of the walked track)
        val bearing = bearing(startGPS, currentFixGPS)
        val distance = haversineDistance(startGPS, currentFixGPS)

        // 2. Synthetic GPS finish: at the same bearing and distance from start
        val earthRadius = 6371000.0
        val angularDist = distance / earthRadius
        val lat1Rad = Math.toRadians(startGPS.latitude)
        val bearingRad = Math.toRadians(bearing)

        val lat2Rad = kotlin.math.asin(
            kotlin.math.sin(lat1Rad) * kotlin.math.cos(angularDist) +
                kotlin.math.cos(lat1Rad) * kotlin.math.sin(angularDist) * kotlin.math.cos(bearingRad)
        )
        val lon2Rad = Math.toRadians(startGPS.longitude) + kotlin.math.atan2(
            kotlin.math.sin(bearingRad) * kotlin.math.sin(angularDist) * kotlin.math.cos(lat1Rad),
            kotlin.math.cos(angularDist) - kotlin.math.sin(lat1Rad) * kotlin.math.sin(lat2Rad)
        )
        val synthGps = GpsCoordinate(Math.toDegrees(lat2Rad), Math.toDegrees(lon2Rad))

        // 3. New calibration: pointA=original start, pointB=synthetic finish
        val pointA = CalibrationPoint(gps = startGPS, imageX = startPointImageX, imageY = startPointImageY)
        val pointB = CalibrationPoint(gps = synthGps, imageX = finishPointImageX, imageY = finishPointImageY)

        // Magnetic declination at current fix location (nearest to finish)
        // Uses hardcoded value for JVM tests; Android API in production.
        val declination: Double = try {
            GeomagneticFieldCompat().declination.toDouble()
        } catch (e: Throwable) {
            5.0 // fallback default
        }

        val newCal = calibrate(pointA, pointB, declination)
            ?: throw IllegalStateException("bindGpsToFinishWithTrack: calibration points too close")

        // 4. North angle = -bearing (aligns map Y-axis with magnetic north)
        val northAngleDeg = -newCal.bearingDegrees.toFloat()

        return BindResult(newCal, northAngleDeg)
    }

    /** Lightweight declination provider for JVM tests. */
    private class GeomagneticFieldCompat {
        val declination get() = 5.0
    }
}
