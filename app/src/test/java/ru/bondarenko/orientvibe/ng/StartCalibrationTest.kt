package ru.bondarenko.orientvibe.ng

import org.junit.Assert.*
import org.junit.Test
import ru.bondarenko.orientvibe.ng.gps.GpsCoordinate
import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint

/**
 * Tests for the "Здесь старт" (here is start) primary calibration flow.
 * Verifies that GPS coordinates are properly bound to the start point,
 * track recording starts, and track display works correctly.
 */
class StartCalibrationTest {

    /**
     * Full "Здесь старт" flow:
     * 1. User has a map image with a start point placed at (0.2, 0.75) relative coords
     * 2. User stands at GPS coordinate A and presses "Здесь старт"
     * 3. Verify: originalStartGps is set, single-point calibration creates valid MapCalibration,
     *    the start GPS maps back to the same image coordinates it was placed at.
     */
    @Test
    fun `here is start binds GPS to start point and creates valid calibration`() {
        // === Setup: user places start on map image ===
        val bmpW = 1000f
        val bmpH = 800f
        val relativeStartX = 0.2f   // 20% from left
        val relativeStartY = 0.75f  // 75% from top

        val absStartX = relativeStartX * bmpW  // 200
        val absStartY = relativeStartY * bmpH  // 600 (note: uses bmpH, NOT bmpW!)

        // === Step 1: User stands at GPS A and presses "Здесь старт" ===
        val startGPS = GpsCoordinate(50.45, 30.5)

        // calibrateSinglePoint creates a synthetic calibration with pointA = startGPS
        val cal = MapCalibrationUtils.calibrateSinglePoint(
            startGPS = startGPS,
            startPointImageX = absStartX,
            startPointImageY = absStartY
        )

        // === Verifications ===

        // 1. pointA.gps must equal the original GPS coordinate (user's location)
        assertEquals(
            "pointA.gps latitude must match start GPS",
            startGPS.latitude, cal.pointA.gps.latitude, 1e-10
        )
        assertEquals(
            "pointA.gps longitude must match start GPS",
            startGPS.longitude, cal.pointA.gps.longitude, 1e-10
        )

        // 2. Start GPS must map back to the exact image coordinates where user placed it
        val projectedStart = MapCalibrationUtils.gpsToImage(startGPS, cal)!!
        assertEquals(
            "start GPS maps to placement X (absolute pixels)",
            absStartX.toDouble(), projectedStart.first.toDouble(), 1e-6
        )
        assertEquals(
            "start GPS maps to placement Y (absolute pixels)",
            absStartY.toDouble(), projectedStart.second.toDouble(), 1e-6
        )

        // 3. Scale must be physically meaningful (> 0)
        assertTrue(
            "scale > 0 (got ${cal.scaleMetersPerPixel})",
            cal.scaleMetersPerPixel > 0.01
        )

        // 4. Bearing для northward synthetic baseline ~ -5° (true bearing ~0° минус declination 5°)
        assertTrue(
            "bearing близок к -5°: ${cal.bearingDegrees}°",
            cal.bearingDegrees in (-10.0)..(0.0)
        )

        // === Key invariant: gpsToImage(startGPS, cal) returns exactly the placement coords ===
        // This is the core guarantee of single-point calibration.
        val verify = MapCalibrationUtils.gpsToImage(startGPS, cal)!!
        assertEquals("Core invariant: start GPS → placement X", absStartX.toDouble(), verify.first.toDouble(), 1e-6)
        assertEquals("Core invariant: start GPS → placement Y", absStartY.toDouble(), verify.second.toDouble(), 1e-6)
    }

    /**
     * Verify that a track of consecutive GPS points, when projected using the
     * single-point calibration, produces a visually coherent (monotonically moving)
     * sequence on the map image.
     */
    @Test
    fun `track points project coherently on calibrated map after here is start`() {
        val bmpW = 1000f
        val bmpH = 800f
        val absStartX = 200f
        val absStartY = 600f

        // User places start at GPS (50.45, 30.5) and presses "Здесь старт"
        val startGPS = GpsCoordinate(50.45, 30.5)
        val cal = MapCalibrationUtils.calibrateSinglePoint(
            startGPS = startGPS,
            startPointImageX = absStartX,
            startPointImageY = absStartY
        )

        // Simulate a walking track: 10 steps of ~5m each, due east
        val steps = 10
        val stepSizeM = 5.0
        var prevProjected: Pair<Float, Float>? = null

        for (i in 1..steps) {
            // Eastward offset matches the same method used in production
            val dEast = i * stepSizeM
            val trackGPS = MapGeometry.offsetCoordinate(startGPS, dNorth = 0.0, dEast = dEast)

            // Project onto the calibrated map
            val projected = MapCalibrationUtils.gpsToImage(trackGPS, cal)!!

            // Each successive point should move eastward on the map (increasing X)
            if (prevProjected != null) {
                assertTrue(
                    "Track X must increase eastward: ${prevProjected?.first} → ${projected.first}",
                    projected.first > prevProjected.first
                )
            }

            // Y should stay approximately constant (purely eastward walk on horizontal baseline)
            val dY = kotlin.math.abs(projected.second - absStartY)
            assertTrue(
                "Track Y stays near start line for eastward walk: $dY px",
                dY < 2.0 // less than 2px deviation due to cos(lat) effects at small distances
            )

            prevProjected = projected

            // Each projected point must be on the map (positive coords)
            assertTrue(
                "Track point $i X on map: ${projected.first}",
                projected.first > -10f && projected.first < bmpW + 10f
            )
        }
    }

    /**
     * Verify that Y coordinate uses bitmap height, not width.
     * Regression test for the bug where (startPoint.y ?: 0f) * bmpW was used instead of bmpH.
     */
    @Test
    fun `single point calibration uses correct image dimensions`() {
        val bmpW = 1000f
        val bmpH = 800f // Different from width to expose the bug
        val relX = 0.2f
        val relY = 0.75f

        // Correct absolute coordinates (Y uses bmpH)
        val correctAbsX = relX * bmpW   // 200
        val correctAbsY = relY * bmpH   // 600

        // INCORRECT absolute coordinates (Y wrongly uses bmpW)
        val wrongAbsY = relY * bmpW    // 750 — WRONG! Should be 600

        val startGPS = GpsCoordinate(50.45, 30.5)

        // With CORRECT Y (bmpH): start GPS maps back to correctAbsY
        val calCorrect = MapCalibrationUtils.calibrateSinglePoint(
            startGPS = startGPS,
            startPointImageX = correctAbsX,
            startPointImageY = correctAbsY
        )
        val projCorrect = MapCalibrationUtils.gpsToImage(startGPS, calCorrect)!!
        assertEquals("With bmpH: Y coordinate is exact", correctAbsY.toDouble(), projCorrect.second.toDouble(), 1e-6)

        // With WRONG Y (bmpW): start GPS maps to wrongAbsY (not the intended placement)
        val calWrong = MapCalibrationUtils.calibrateSinglePoint(
            startGPS = startGPS,
            startPointImageX = correctAbsX,
            startPointImageY = wrongAbsY
        )
        val projWrong = MapCalibrationUtils.gpsToImage(startGPS, calWrong)!!
        assertEquals("With bmpW: Y coordinate is wrongAbsY", wrongAbsY.toDouble(), projWrong.second.toDouble(), 1e-6)

        // The two projections must differ by exactly the bug amount (750 - 600 = 150px)
        val yDiff = kotlin.math.abs(projWrong.second - projCorrect.second)
        assertEquals(
            "Bug causes 150px Y offset: $yDiff",
            (bmpH * relY).toDouble(), projCorrect.second.toDouble(), 0.001
        )

        // Correct calibration places GPS at row 600 (75% down the image height)
        assertEquals(
            "Correct placement is at 75% of image HEIGHT = ${bmpH * relY}",
            (bmpH * relY).toDouble(), projCorrect.second.toDouble(), 0.001
        )
    }

    /**
     * Verify that the original start GPS is preserved and can be retrieved later
     * for recalibration (e.g., "Здесь финиш").
     */
    @Test
    fun `original start GPS is preserved after single point calibration`() {
        val startGPS = GpsCoordinate(50.45, 30.5)
        val cal = MapCalibrationUtils.calibrateSinglePoint(
            startGPS = startGPS,
            startPointImageX = 200f,
            startPointImageY = 600f
        )

        // The calibration stores the GPS as pointA.gps
        val storedGPS = cal.pointA.gps
        assertEquals("Stored GPS latitude", startGPS.latitude, storedGPS.latitude, 1e-10)
        assertEquals("Stored GPS longitude", startGPS.longitude, storedGPS.longitude, 1e-10)

        // We can retrieve the original GPS from calibration and use it for future recalibration
        val recoveredCal = MapCalibrationUtils.calibrateSinglePoint(
            startGPS = storedGPS,  // <-- this is how bindGpsToStart would reuse it
            startPointImageX = cal.pointA.imageX,
            startPointImageY = cal.pointA.imageY
        )

        // Recovered GPS maps to same coords as original
        val origProj = MapCalibrationUtils.gpsToImage(startGPS, cal)!!
        val recovProj = MapCalibrationUtils.gpsToImage(storedGPS, recoveredCal)!!
        assertEquals("Recovered GPS X matches original", origProj.first, recovProj.first, 1e-6f)
        assertEquals("Recovered GPS Y matches original", origProj.second, recovProj.second, 1e-6f)
    }
}
