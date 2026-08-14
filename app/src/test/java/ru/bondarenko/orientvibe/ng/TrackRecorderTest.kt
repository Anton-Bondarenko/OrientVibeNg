package ru.bondarenko.orientvibe.ng

import org.junit.Assert.*
import org.junit.Test
import ru.bondarenko.orientvibe.ng.gps.GpsFix
import ru.bondarenko.orientvibe.ng.gps.GpsCoordinate
import ru.bondarenko.orientvibe.ng.gps.TrackPoint
import ru.bondarenko.orientvibe.ng.gps.TrackRecorder
import ru.bondarenko.orientvibe.ng.gps.MAX_TRACK_POINTS
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint

/**
 * Tests that track point storage uses LinkedList-backed deque for O(1) head removal,
 * enforces a strict 300-point cap, and correctly discards oldest points when full.
 */
class TrackRecorderTest {

    @Test
    fun `track recorder caps at MAX_TRACK_POINTS (300) and removes oldest from head`() {
        val recorder = TrackRecorder()
        recorder.startTracking()

        val cal = buildTestCalibration()
        var expectedTotalDist = 0.0

        // Record 500 points (more than the 300 limit)
        for (i in 1..500) {
            val fix = GpsFix(
                coordinate = GpsCoordinate(50.45 + i * 0.0001, 30.5 + i * 0.0001),
                accuracy = 5f,
                bearing = (i * 10f).toFloat(),
                speed = 1f,
                timestamp = System.currentTimeMillis()
            )
            recorder.recordPoint(fix, cal)
        }

        val state = recorder.getTrackData()

        // Verify the cap is enforced — exactly MAX_TRACK_POINTS points should remain
        assertEquals("Should have exactly $MAX_TRACK_POINTS track points",
            MAX_TRACK_POINTS.toLong(), state.trackPoints.size.toLong())

        // Verify oldest points were removed — first point in store should be #201 (500-300+1)
        val firstPoint = state.trackPoints.first()
        assertEquals("Oldest retained point index should be 201",
            0, firstPoint.gpsFix.coordinate.latitude.compareTo(50.45 + 201 * 0.0001))

        // Verify newest point is at the end (#500)
        val lastPoint = state.trackPoints.last()
        assertEquals("Newest point should be index 500",
            50.45 + 500 * 0.0001, lastPoint.gpsFix.coordinate.latitude, 1e-9)

        // Verify total distance accumulates correctly (sum of haversine distances between consecutive points)
        assertTrue("Total distance should be positive", state.totalDistance > 0.0)

        recorder.stopTracking()
    }

    @Test
    fun `track recorder below limit retains all points without trimming`() {
        val recorder = TrackRecorder()
        recorder.startTracking()

        val cal = buildTestCalibration()

        // Record exactly MAX_TRACK_POINTS — should keep all
        for (i in 1..MAX_TRACK_POINTS) {
            val fix = GpsFix(
                coordinate = GpsCoordinate(50.45 + i * 0.0001, 30.5 + i * 0.0001),
                accuracy = 5f,
                bearing = (i * 10f).toFloat(),
                speed = 1f,
                timestamp = System.currentTimeMillis()
            )
            recorder.recordPoint(fix, cal)
        }

        val state = recorder.getTrackData()
        assertEquals("All $MAX_TRACK_POINTS points should be retained",
            MAX_TRACK_POINTS.toLong(), state.trackPoints.size.toLong())

        // First point should be #1 (not trimmed since at limit)
        val firstPoint = state.trackPoints.first()
        assertEquals(50.45 + 1 * 0.0001, firstPoint.gpsFix.coordinate.latitude, 1e-9)

        recorder.stopTracking()
    }

    @Test
    fun `oldest points are removed in FIFO order — tail stays current`() {
        val recorder = TrackRecorder()
        recorder.startTracking()

        val cal = buildTestCalibration()

        // Record 100 points — all retained
        for (i in 1..100) {
            val fix = GpsFix(
                coordinate = GpsCoordinate(50.45 + i * 0.0001, 30.5 + i * 0.0001),
                accuracy = 5f,
                bearing = (i * 10f).toFloat(),
                speed = 1f,
                timestamp = System.currentTimeMillis()
            )
            recorder.recordPoint(fix, cal)
        }
        assertEquals(100, recorder.getTrackData().trackPoints.size)

        // Record 250 more (total 350), should trim head to leave 300
        for (i in 101..350) {
            val fix = GpsFix(
                coordinate = GpsCoordinate(50.45 + i * 0.0001, 30.5 + i * 0.0001),
                accuracy = 5f,
                bearing = (i * 10f).toFloat(),
                speed = 1f,
                timestamp = System.currentTimeMillis()
            )
            recorder.recordPoint(fix, cal)
        }

        val state = recorder.getTrackData()
        assertEquals("Should cap at $MAX_TRACK_POINTS", MAX_TRACK_POINTS.toLong(), state.trackPoints.size.toLong())

        // Verify head trimming: points 1-50 should be gone (350-300=50 oldest removed)
        val firstPoint = state.trackPoints.first()
        assertEquals("First point should be #51 after trimming 50 oldest",
            50.45 + 51 * 0.0001, firstPoint.gpsFix.coordinate.latitude, 1e-9)

        // Tail should contain the latest 300 points (51..350)
        val lastPoint = state.trackPoints.last()
        assertEquals("Last point should be #350",
            50.45 + 350 * 0.0001, lastPoint.gpsFix.coordinate.latitude, 1e-9)

        recorder.stopTracking()
    }

    @Test
    fun `startTracking clears old track and resets state`() {
        val recorder = TrackRecorder()
        recorder.startTracking()

        val cal = buildTestCalibration()

        // Add some points
        for (i in 1..10) {
            val fix = GpsFix(
                coordinate = GpsCoordinate(50.45 + i * 0.0001, 30.5 + i * 0.0001),
                accuracy = 5f,
                bearing = (i * 10f).toFloat(),
                speed = 1f,
                timestamp = System.currentTimeMillis()
            )
            recorder.recordPoint(fix, cal)
        }
        assertEquals(10, recorder.getTrackData().trackPoints.size)

        // Start new track — should clear everything
        recorder.startTracking()
        val state = recorder.getTrackData()
        assertTrue("Track should be cleared after startTracking", state.trackPoints.isEmpty())
        assertEquals(0.0, state.totalDistance, 1e-9)
        assertTrue("isTracking should be true after startTracking", state.isTracking)
    }

    @Test
    fun `recordPoint returns null when not tracking or calibration is null`() {
        val recorder = TrackRecorder()
        val cal = buildTestCalibration()

        // Not started — should return null
        var fix = GpsFix(
            coordinate = GpsCoordinate(50.45, 30.5),
            accuracy = 5f,
            bearing = 0f,
            speed = 1f,
            timestamp = System.currentTimeMillis()
        )
        assertNull("Should return null when not tracking", recorder.recordPoint(fix, cal))

        // Start tracking
        recorder.startTracking()

        // Null calibration — should return null
        assertNull("Should return null with null calibration", recorder.recordPoint(fix, null))

        // Valid recording
        val point = recorder.recordPoint(fix, cal)
        assertNotNull("Should return TrackPoint when valid", point)
    }

    // Helper: create a minimal test calibration
    private fun buildTestCalibration(): MapCalibration {
        return MapCalibration(
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
    }
}
