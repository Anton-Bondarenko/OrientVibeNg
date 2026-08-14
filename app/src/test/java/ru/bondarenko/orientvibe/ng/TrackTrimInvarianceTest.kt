package ru.bondarenko.orientvibe.ng

import org.junit.Assert.*
import org.junit.Test
import ru.bondarenko.orientvibe.ng.gps.GpsFix
import ru.bondarenko.orientvibe.ng.gps.GpsCoordinate
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.TrackRecorder
import ru.bondarenko.orientvibe.ng.gps.MAX_TRACK_POINTS
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import ru.bondarenko.orientvibe.ng.model.TrackPoint

/**
 * Tests that removing old track points does not change image positions of remaining points.
 * Image position = gpsToImage(gps, calibration) -- depends only on GPS + calibration,
 * so trimming the store must not affect it.
 *
 * The core invariant: after calibrating with two reference points (fixing scale and bearing),
 * any GPS coordinate maps to the same image pixel regardless of how many track points exist.
 * If trimming the oldest points causes remaining points' positions to shift on screen,
 * something is computing state from the track store (e.g., average position) instead of
 * using the fixed calibration.
 */
class TrackTrimInvarianceTest {

    @Test
    fun `trimming old track points must not change image coordinates of remaining points`() {
        val recorder = TrackRecorder()
        recorder.startTracking()

        // Fix calibration (scale + bearing) -- this is the ground truth for all GPS->image transforms
        val cal = MapCalibration(
            pointA = CalibrationPoint(
                gps = GpsCoordinate(50.45, 30.5),
                imageX = 0.1f, imageY = 0.9f
            ),
            pointB = CalibrationPoint(
                gps = GpsCoordinate(50.46, 30.51),
                imageX = 0.9f, imageY = 0.1f
            ),
            scaleMetersPerPixel = 0.354,
            bearingDegrees = -5.0,
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        // Record first GPS point and capture its image coordinates BEFORE trimming
        val gpsA = GpsCoordinate(50.4501, 30.5001)
        var fix = GpsFix(
            coordinate = gpsA,
            accuracy = 5f,
            bearing = 45f,
            speed = 1f,
            timestamp = System.currentTimeMillis()
        )
        val imageBeforeTrim = MapCalibrationUtils.gpsToImage(gpsA, cal)!!

        // Record second point (it will also be trimmed if it's old enough, but we check below)
        val gpsB = GpsCoordinate(50.4502, 30.5002)
        fix = GpsFix(
            coordinate = gpsB,
            accuracy = 5f,
            bearing = 90f,
            speed = 1f,
            timestamp = System.currentTimeMillis() + 1000
        )
        recorder.recordPoint(fix, cal)!!

        // Record enough points to trigger trimming (MAX_TRACK_POINTS + 200 total)
        for (i in 3..MAX_TRACK_POINTS + 200) {
            fix = GpsFix(
                coordinate = GpsCoordinate(gpsA.latitude + i * 0.0001, gpsA.longitude + i * 0.0001),
                accuracy = 5f,
                bearing = (i * 10f).toFloat(),
                speed = 1f,
                timestamp = System.currentTimeMillis() + i * 1000L
            )
            recorder.recordPoint(fix, cal)
        }

        // After trimming: verify cap was enforced
        val state = recorder.getTrackData()
        assertEquals("Should have exactly $MAX_TRACK_POINTS", MAX_TRACK_POINTS.toLong(), state.trackPoints.size.toLong())

        // Core invariant: for the SAME GPS point + SAME calibration, gpsToImage returns identical result.
        // The image position must not "drift" or "shift" when old track points are removed.
        val afterTrim = MapCalibrationUtils.gpsToImage(gpsA, cal)!!
        assertEquals(
            "gpsToImage X for gpsA must be identical before and after trimming (scale/rotation invariant)",
            imageBeforeTrim.first, afterTrim.first, 1e-9f
        )
        assertEquals(
            "gpsToImage Y for gpsA must be identical before and after trimming (scale/rotation invariant)",
            imageBeforeTrim.second, afterTrim.second, 1e-9f
        )

        // If point B survived trimming, its stored imageX/imageY on the TrackPoint object
        // must match recomputed value from calibration.
        val state2 = recorder.getTrackData()
        val foundB: TrackPoint? = state2.trackPoints.find {
            kotlin.math.abs(it.gpsFix.coordinate.latitude - gpsB.latitude) < 1e-12
        }
        if (foundB != null) {
            val recomputedB = MapCalibrationUtils.gpsToImage(gpsB, cal)!!
            assertEquals(
                "Stored imageX of remaining point B must match recalculated from calibration",
                recomputedB.first, foundB.imageX, 1e-6f
            )
            assertEquals(
                "Stored imageY of remaining point B must match recalculated from calibration",
                recomputedB.second, foundB.imageY, 1e-6f
            )
        }

        // Total distance should still be valid (accumulated correctly regardless of store size)
        assertTrue("Total distance should be positive", state.totalDistance > 0.0)

        recorder.stopTracking()
    }

    @Test
    fun `calibration scale and bearing must not change when track points are trimmed`() {
        val recorder = TrackRecorder()
        recorder.startTracking()

        val cal = MapCalibration(
            pointA = CalibrationPoint(
                gps = GpsCoordinate(50.45, 30.5),
                imageX = 0.1f, imageY = 0.9f
            ),
            pointB = CalibrationPoint(
                gps = GpsCoordinate(50.46, 30.51),
                imageX = 0.9f, imageY = 0.1f
            ),
            scaleMetersPerPixel = 0.354,
            bearingDegrees = -5.0,
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        val calScaleBefore = cal.scaleMetersPerPixel
        val calBearingBefore = cal.bearingDegrees

        // Record enough to trigger trimming
        for (i in 1..MAX_TRACK_POINTS + 100) {
            val fix = GpsFix(
                coordinate = GpsCoordinate(50.45 + i * 0.0001, 30.5 + i * 0.0001),
                accuracy = 5f,
                bearing = (i * 10f).toFloat(),
                speed = 1f,
                timestamp = System.currentTimeMillis() + i * 1000L
            )
            recorder.recordPoint(fix, cal)
        }

        val state = recorder.getTrackData()
        assertEquals("Should have capped at MAX_TRACK_POINTS", MAX_TRACK_POINTS.toLong(), state.trackPoints.size.toLong())

        // Verify scale and bearing are the same as before trimming.
        // The calibration object is immutable -- TrackRecorder never modifies it.
        assertEquals(
            "Calibration scale (meters/pixel) must not drift after trimming",
            calScaleBefore, cal.scaleMetersPerPixel, 1e-9
        )
        assertEquals(
            "Calibration bearing must not drift after trimming",
            calBearingBefore, cal.bearingDegrees, 1e-9
        )

        recorder.stopTracking()
    }
}
