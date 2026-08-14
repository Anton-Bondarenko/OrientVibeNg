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

    // ─── Bind GPS to finish point ─────────────────────────────────────────

    /**
     * Result of [bindGpsToFinish]: the recalibrated calibration and the northAngle to apply.
     */
    data class BindResult(
        val calibration: MapCalibration,
        val northAngleDegrees: Float
    )

    /**
     * Bind a real-time GPS finish position to a finish point on the map image.
     * Creates a two-point calibration from startPointGPS → finishPointGps mapped to
     * absolute pixel coordinates on the bitmap. Returns the new calibration and
     * northAngle = -bearing so the map rotates to magnetic north.
     *
     * This is the canonical method for the "Здесь финиш" binding flow:
     * 1. User taps a finish point on the map image (finishPointImageX/Y in absolute pixels)
     * 2. Current GPS fix provides the real-world location of that tap
     * 3. The calibration is created so gpsToImage(finishGps) returns exactly finishPoint's coords
     */
    fun bindGpsToFinish(
        startGps: GpsCoordinate,         // GPS coordinate where user pressed "here is start"
        startPointImageX: Float,          // absolute pixel X on bitmap for startPoint
        startPointImageY: Float,          // absolute pixel Y on bitmap for startPoint
        finishGps: GpsCoordinate,         // GPS coordinate of current fix when user presses "here is finish"
        finishPointImageX: Float,         // absolute pixel X on bitmap where user tapped finish
        finishPointImageY: Float,         // absolute pixel Y on bitmap where user tapped finish
        magneticDeclination: Double       // magnetic declination at start location (from GeomagneticField)
    ): BindResult {
        val pointA = CalibrationPoint(gps = startGps, imageX = startPointImageX, imageY = startPointImageY)
        val pointB = CalibrationPoint(gps = finishGps, imageX = finishPointImageX, imageY = finishPointImageY)

        val result = calibrate(pointA, pointB, magneticDeclination)
            ?: throw IllegalStateException(
                "bindGpsToFinish failed: calibration points too close or invalid. " +
                    "startGPS=(${startGps.latitude}, ${startGps.longitude}), " +
                    "finishGPS=(${finishGps.latitude}, ${finishGps.longitude})"
            )

        // North angle is -bearing so the map rotates to physical (magnetic) north direction.
        // For GPS = pointB, displacement from pointA pivot is zero so rotation has no effect —
        // the purple dot always lands at finishPoint regardless of northAngle value.
        val northAngleDeg = -result.bearingDegrees.toFloat()

        android.util.Log.d(
            "MAP_BIND",
            "bindGpsToFinish: scale=${result.scaleMetersPerPixel}m/px, bearing=${result.bearingDegrees}°, " +
                "northAngle=$northAngleDeg°, declination=$magneticDeclination"
        )

        return BindResult(result, northAngleDeg)
    }
}
