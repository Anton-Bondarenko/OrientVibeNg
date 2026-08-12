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
        imageDimensions: Pair<Float, Float>,  // (width, height) in source pixels
        northAngleDeg: Float
    ): Pair<Float, Float>? {
        val rel = gpsToImage(gps, calibration) ?: return null
        var x = rel.first * imageDimensions.first
        var y = rel.second * imageDimensions.second

        if (northAngleDeg != 0f) {
            val angleRad = Math.toRadians(northAngleDeg.toDouble())
            val cosA = kotlin.math.cos(angleRad).toFloat()
            val sinA = kotlin.math.sin(angleRad).toFloat()
            // Pivot at calibration point A in image space
            val px = calibration.pointA.imageX * imageDimensions.first
            val py = calibration.pointA.imageY * imageDimensions.second
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
}
