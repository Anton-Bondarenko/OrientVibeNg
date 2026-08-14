package ru.bondarenko.orientvibe.ng.gps

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orienteering map orientation utilities.
 *
 * Core idea: a physical orienteering map has its Y-axis aligned with magnetic north,
 * not true geographic north. When we overlay a GPS track on the map image, we must
 * rotate the GPS frame so that "walking magnetic north" maps to a straight line up
 * (negative Y in image space).
 *
 * Coordinate pipeline:
 * 1. gpsToImageRelative — GPS offset → relative image pixels in TRUE-NORTH canonical frame
 *    (dEast → screenX+, -dNorth → screenY+; always correct regardless of bearing)
 * 2. Scale to absolute pixels
 * 3. Rotate around calibration point A by northAngle to align map Y-axis with magnetic north
 */
object MapOrientation {

    /**
     * The correct northAngle that aligns the rotated image frame's Y-axis with magnetic north.
     * This makes walking along magnetic bearing from ANY GPS position trace a straight line
     * up/down on the map (zero east drift in the corrected frame).
     *
     * northAngle = -(rawMagneticBearing) where rawMagneticBearing = trueBearing - declination.
     */
    fun computeNorthAngleForMagneticAlignment(calibration: ru.bondarenko.orientvibe.ng.model.MapCalibration): Float {
        return (-calibration.bearingDegrees).toFloat()
    }

    /**
     * Reverse lookup: find the northAngle that maps a given GPS coordinate to a specific
     * target position along the A→B line in image space.
     *
     * This answers: "At what map rotation would my current GPS position lie exactly on the
     * AB line at fractional distance `fraction` (0=A, 1=B)?"
     *
     * Returns null if the point cannot be placed on the AB line for any rotation
     * (distance from A differs from the target line distance).
     */
    fun computeNorthAngleToLandOnABLine(
        calibration: ru.bondarenko.orientvibe.ng.model.MapCalibration,
        gps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate,
        fraction: Double, // 0..1 along A→B line in image space
        imageDimensions: Pair<Float, Float>
    ): Float? {
        val rel = MapGeometry.gpsToImageRelative(gps, calibration) ?: return null

        // Position of GPS point in unrotated absolute pixels (relative to origin 0,0)
        val gpsAbsX = rel.first * imageDimensions.first
        val gpsAbsY = rel.second * imageDimensions.second

        // Pivot (point A in absolute pixels)
        val px = calibration.pointA.imageX * imageDimensions.first
        val py = calibration.pointA.imageY * imageDimensions.second

        // Vector from pivot to GPS point in unrotated frame
        val dx0 = gpsAbsX - px
        val dy0 = gpsAbsY - py
        val distFromA = sqrt(dx0 * dx0 + dy0 * dy0)

        // Target position on AB line
        val targetRelX = (calibration.pointB.imageX - calibration.pointA.imageX) * imageDimensions.first
        val targetRelY = (calibration.pointB.imageY - calibration.pointA.imageY) * imageDimensions.second
        val distAB = sqrt(targetRelX * targetRelX + targetRelY * targetRelY)
        if (distAB < 1e-9) return null

        // Target point on AB line at fraction
        val targetX = px + targetRelX * fraction
        val targetY = py + targetRelY * fraction
        val distTargetFromA = sqrt((targetX - px) * (targetX - px) + (targetY - py) * (targetY - py))

        // For the GPS point to land exactly on the target after rotation, distances must match.
        if (kotlin.math.abs(distFromA - distTargetFromA) > 1e-6) return null

        // northAngle = angle needed to rotate vector (dx0, dy0) to direction of (targetX-px, targetY-py)
        val currentAngle = kotlin.math.atan2(dy0, dx0)
        val targetAngle = kotlin.math.atan2(targetY - py, targetX - px)
        val angleDiff = (targetAngle - currentAngle) * 180.0 / PI
        return angleDiff.toFloat()
    }

    /**
     * Check if two image positions are approximately equal (within tolerance in pixels).
     */
    fun positionsEqual(
        cal: ru.bondarenko.orientvibe.ng.model.MapCalibration,
        expectedFraction: Double,
        actualGps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate,
        northAngleDeg: Float,
        imageDimensions: Pair<Float, Float>,
        tolerancePx: Float = 1e-3f
    ): Boolean {
        val expectedRelX = cal.pointA.imageX + (cal.pointB.imageX - cal.pointA.imageX) * expectedFraction
        val expectedRelY = cal.pointA.imageY + (cal.pointB.imageY - cal.pointA.imageY) * expectedFraction

        val actualAbs = MapCalibrationUtils.gpsToImageAbs(actualGps, cal, imageDimensions, northAngleDeg) ?: return false
        val expectedAbsX = expectedRelX * imageDimensions.first
        val expectedAbsY = expectedRelY * imageDimensions.second

        val dx = (actualAbs.first - expectedAbsX).toDouble()
        val dy = (actualAbs.second - expectedAbsY).toDouble()
        return sqrt(dx * dx + dy * dy) < tolerancePx
    }

    // ─── Compose helper for creating calibration with orientation info ───

    /** Result of computing a full oriented calibration. */
    data class OrientedCalibration(
        val calibration: ru.bondarenko.orientvibe.ng.model.MapCalibration,
        val northAngleDeg: Float,           // degrees; positive = clockwise rotation of map Y-axis from screen-up
        val trueBearingDeg: Double,         // geographic bearing from A to B
        val rawMagneticBearingDeg: Double,  // compass bearing from A to B (true - declination)
        val scaleMetersPerPixel: Double     // meters per source pixel at this calibration
    ) {
        /** Image position of point B in absolute pixels (for given imageDimensions). */
        fun pointBImagePos(imageDimensions: Pair<Float, Float>): Pair<Float, Float> {
            return Pair(
                calibration.pointB.imageX * imageDimensions.first,
                calibration.pointB.imageY * imageDimensions.second
            )
        }

        /** Image position of point A in absolute pixels. */
        fun pointAImagePos(imageDimensions: Pair<Float, Float>): Pair<Float, Float> {
            return Pair(
                calibration.pointA.imageX * imageDimensions.first,
                calibration.pointA.imageY * imageDimensions.second
            )
        }

        /** Distance between A and B in absolute pixels. */
        fun distanceABPixels(imageDimensions: Pair<Float, Float>): Double {
            val a = pointAImagePos(imageDimensions)
            val b = pointBImagePos(imageDimensions)
            return kotlin.math.sqrt((b.first - a.first).toDouble() * (b.first - a.first) +
                                  (b.second - a.second).toDouble() * (b.second - a.second))
        }

        /** Convert GPS coordinate to absolute image pixels, applying magnetic alignment. */
        fun gpsToImageAbs(gps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate,
                          imageDimensions: Pair<Float, Float>): Pair<Float, Float>? {
            return MapCalibrationUtils.gpsToImageAbs(gps, calibration, imageDimensions, northAngleDeg)
        }

        /** Convert GPS coordinate to relative (0..1) image position. */
        fun gpsToImageRel(gps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate): Pair<Float, Float>? {
            return MapCalibrationUtils.gpsToImage(gps, calibration)
        }

        /** Convert image pixel to GPS coordinate (uses uncalibrated frame, ignores northAngle). */
        fun imageToGps(imageX: Float, imageY: Float): ru.bondarenko.orientvibe.ng.model.GpsCoordinate? {
            return MapCalibrationUtils.imageToGps(imageX, imageY, calibration)
        }
    }

    /** Create an oriented calibration from two GPS-to-image mapping points. */
    fun create(
        pointAGps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate,
        pointAImageRel: Pair<Float, Float>,  // relative (0..1) on the image
        pointBGps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate,
        pointBImageRel: Pair<Float, Float>,  // relative (0..1) on the image
        magneticDeclination: Double
    ): OrientedCalibration? {
        val cal = MapGeometry.computeCalibrationRaw(
            ru.bondarenko.orientvibe.ng.model.CalibrationPoint(pointAGps, pointAImageRel.first, pointAImageRel.second),
            ru.bondarenko.orientvibe.ng.model.CalibrationPoint(pointBGps, pointBImageRel.first, pointBImageRel.second),
            magneticDeclination
        ) ?: return null

        val trueBearing = MapGeometry.bearing(pointAGps, pointBGps)
        val rawMagBearing = MapGeometry.magneticBearing(trueBearing, magneticDeclination)
        val northAngle = (-rawMagBearing).toFloat()

        return OrientedCalibration(
            calibration = cal,
            northAngleDeg = northAngle,
            trueBearingDeg = trueBearing,
            rawMagneticBearingDeg = rawMagBearing,
            scaleMetersPerPixel = cal.scaleMetersPerPixel
        )
    }
}
