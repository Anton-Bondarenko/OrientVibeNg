package ru.bondarenko.orientvibe.ng

import org.junit.Assert.*
import org.junit.Test
import ru.bondarenko.orientvibe.ng.gps.GpsCoordinate
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.gps.MapOrientation
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.MapCalibration

/**
 * Verifies that the track line on the map accounts for magnetic declination.
 *
 * Scenario (as specified by the user):
 *  - The map's north line (its image Y-axis pointing up) = 0° on screen = magnetic north.
 *  - A user walks due north on the ground — GPS reports a TRUE bearing of 0°.
 *  - The magnetic declination is +10° (east).
 *
 * Physical reality:
 *  - True north and magnetic north differ by the declination. With declination = +10°,
 *    magnetic north points 10° west of true north, i.e. true north is 10° east (clockwise)
 *    of magnetic north.
 *  - So when the user walks TRUE-north (GPS bearing = 0°), relative to the MAP's north line
 *    (which is magnetic north = screen up) the walking direction is +10° (clockwise from up).
 *
 * Expected behaviour: when the GPS track is rendered on the calibrated map using the
 * production pipeline (gpsToImage + northAngle rotation), the visual track line should
 * appear at ~+10° clockwise from screen-up.
 */
class MagneticDeclinationTrackTest {

    private val SCALE = 1000f // helper: scale relative image coords to pixel space

    /**
     * Build a calibration from two map anchors. The map's Y-axis (imageY going up = smaller
     * pixel index) is intended to be aligned with MAGNETIC north — i.e. the map's north line
     * is the vertical screen-up direction.
     */
    private fun buildCalibration(
        declinationDeg: Double,
        trueBearingAB: Double, // 0° means A and B are on the same meridian
        distanceMeters: Double
    ): MapCalibration {
        val pointAGps = GpsCoordinate(50.450_000, 30.500_000)
        // Place point B at the given true bearing and distance from A.
        val dNorth = distanceMeters * kotlin.math.cos(Math.toRadians(trueBearingAB))
        val dEast = distanceMeters * kotlin.math.sin(Math.toRadians(trueBearingAB))
        val pointBGps = MapGeometry.offsetCoordinate(pointAGps, dNorth, dEast)

        // Place A at the bottom of the image and B above it so the physical map's Y-axis
        // (smaller pixel Y at top) aligns with magnetic north (image up).
        val pointA = CalibrationPoint(gps = pointAGps, imageX = 0.5f * SCALE, imageY = 0.8f * SCALE)
        val pointB = CalibrationPoint(gps = pointBGps, imageX = 0.5f * SCALE, imageY = 0.2f * SCALE)

        return MapGeometry.computeCalibrationRaw(pointA, pointB, declinationDeg)
            ?: throw IllegalStateException("Calibration points too close")
    }

    /** True bearing from A to B in degrees [0, 360). */
    private fun trueBearing(cal: MapCalibration): Double =
        MapGeometry.bearing(cal.pointA.gps, cal.pointB.gps)

    /**
     * Compute the screen-angle of a vector (dx, dy) where 0° = up (screen-up),
     * 90° = right, 180° = down, 270° = left. Matches the convention used by TrackOverlay.
     */
    private fun screenAngleDeg(dx: Float, dy: Float): Double {
        return Math.toDegrees(kotlin.math.atan2(dx.toDouble(), (-dy).toDouble()))
    }

    /**
     * The core test: walking TRUE-north (GPS true bearing 0°) on the ground, with magnetic
     * declination = +10°, the rendered track on the map (whose north line = screen up = magnetic
     * north) should appear at +10° clockwise from screen-up.
     */
    @Test
    fun `gps track walking true north renders at +10deg from screen up when declination is +10`() {
        val declination = 10.0
        val trueBearingAB = 0.0  // point B is due TRUE-north of point A on the ground
        val abDistance = 200.0

        val cal = buildCalibration(declinationDeg = declination, trueBearingAB = trueBearingAB, distanceMeters = abDistance)

        // Sanity: the map's image Y-axis must point to magnetic north. With declination = +10°
        // and trueBearing = 0°, rawMagneticBearing = -10° → physical map's "up" direction
        // (= the line from A's imageX/Y to B's imageX/Y, i.e. decreasing pixel Y) is magnetic
        // bearing -10° from true north. This is exactly how a real orienteering map is printed:
        // its top edge points along magnetic north.
        val trueB = trueBearing(cal)
        val rawMag = MapGeometry.magneticBearing(trueB, declination)
        assertEquals(
            "rawMagneticBearing = trueBearing - declination = -10° (≡ 350°)",
            0.0,
            ((rawMag + 360.0) % 360.0) - 350.0,
            0.5
        )

        // Use the production-correct northAngle (magnetic alignment formula).
        val northAngle = MapOrientation.computeNorthAngleForMagneticAlignment(cal)

        // Build the user's GPS track: walking due TRUE-north (true bearing 0°) for 100 m.
        val trackStartGps = cal.pointA.gps
        val trackEndGps = MapGeometry.offsetCoordinate(trackStartGps, dNorth = 100.0, dEast = 0.0)

        // Project the two GPS points onto the calibrated map (with magnetic-alignment rotation).
        val imageDims = Pair(SCALE, SCALE)
        val startImg = MapCalibrationUtils.gpsToImageAbs(trackStartGps, cal, imageDims, northAngle)!!
        val endImg = MapCalibrationUtils.gpsToImageAbs(trackEndGps, cal, imageDims, northAngle)!!

        // Compute the screen-angle of the rendered track vector.
        val dx = endImg.first - startImg.first
        val dy = endImg.second - startImg.second
        val trackScreenAngle = screenAngleDeg(dx, dy)

        // Expected: walking TRUE-north on a map whose top edge = magnetic north should draw
        // the track at +declination degrees clockwise from screen-up.
        // (The user explicitly stated this expected behaviour in the task description.)
        assertEquals(
            "Track walking TRUE-north must render at +10° from screen-up (declination=${declination}°)",
            declination,
            trackScreenAngle,
            1.0  // 1° tolerance for Rhumb-line / spherical numerical drift
        )

        // Direction check (sign matters): declination is positive → track tilts to the right.
        assertTrue(
            "Track must tilt clockwise (positive screen angle) for positive declination; got $trackScreenAngle°",
            trackScreenAngle > 0.0
        )

        // Track must not be exactly vertical — that's the bug if declination is ignored.
        assertNotEquals(
            "Track must NOT be exactly screen-up when declination is non-zero (got $trackScreenAngle°)",
            0.0, trackScreenAngle, 0.5
        )
    }

    /**
     * Symmetric negative-declination case: if magnetic declination = -10° (west), then true
     * north is 10° west (counter-clockwise) of magnetic north, so walking TRUE-north should
     * render at -10° on the map (counter-clockwise from screen-up).
     */
    @Test
    fun `gps track walking true north renders at -10deg when declination is -10`() {
        val declination = -10.0
        val cal = buildCalibration(declinationDeg = declination, trueBearingAB = 0.0, distanceMeters = 200.0)
        val northAngle = MapOrientation.computeNorthAngleForMagneticAlignment(cal)

        val startImg = MapCalibrationUtils.gpsToImageAbs(
            cal.pointA.gps, cal, Pair(SCALE, SCALE), northAngle
        )!!
        val endGps = MapGeometry.offsetCoordinate(cal.pointA.gps, dNorth = 100.0, dEast = 0.0)
        val endImg = MapCalibrationUtils.gpsToImageAbs(endGps, cal, Pair(SCALE, SCALE), northAngle)!!

        val trackScreenAngle = screenAngleDeg(endImg.first - startImg.first, endImg.second - startImg.second)

        assertEquals(
            "Track walking TRUE-north must render at -10° (declination=${declination}°)",
            declination, trackScreenAngle, 1.0
        )
        assertTrue(
            "Track must tilt counter-clockwise (negative screen angle) for negative declination; got $trackScreenAngle°",
            trackScreenAngle < 0.0
        )
    }

    /**
     * Control test: when declination = 0 (true north == magnetic north), the track walking
     * TRUE-north must render exactly screen-up (0°). Verifies that the magnetic-alignment
     * pipeline does not introduce spurious rotation in the no-declination case.
     */
    @Test
    fun `gps track walking true north renders at 0deg when declination is zero`() {
        val declination = 0.0
        val cal = buildCalibration(declinationDeg = declination, trueBearingAB = 0.0, distanceMeters = 200.0)
        val northAngle = MapOrientation.computeNorthAngleForMagneticAlignment(cal)

        val startImg = MapCalibrationUtils.gpsToImageAbs(
            cal.pointA.gps, cal, Pair(SCALE, SCALE), northAngle
        )!!
        val endGps = MapGeometry.offsetCoordinate(cal.pointA.gps, dNorth = 100.0, dEast = 0.0)
        val endImg = MapCalibrationUtils.gpsToImageAbs(endGps, cal, Pair(SCALE, SCALE), northAngle)!!

        val trackScreenAngle = screenAngleDeg(endImg.first - startImg.first, endImg.second - startImg.second)

        assertEquals(
            "Track walking TRUE-north must render at 0° (declination=0)",
            0.0, trackScreenAngle, 0.5
        )
    }

    /**
     * Sanity check (architectural): the `computeNorthAngleForMagneticAlignment` rotation is
     * tuned so that GPS TRUE-north tracks appear at the declination angle on a map whose top
     * edge is magnetic north. Verify that GPS TRUE-east (90°) renders at 90°+declination
     * screen angle — confirming the rotation is uniform and consistent with the user's
     * specified behaviour.
     */
    @Test
    fun `gps track walking true east renders at 100deg when declination is +10`() {
        val declination = 10.0
        val cal = buildCalibration(declinationDeg = declination, trueBearingAB = 0.0, distanceMeters = 200.0)
        val northAngle = MapOrientation.computeNorthAngleForMagneticAlignment(cal)

        // Walk TRUE-east 100 m from point A
        val endGps = MapGeometry.offsetCoordinate(cal.pointA.gps, dNorth = 0.0, dEast = 100.0)

        val startImg = MapCalibrationUtils.gpsToImageAbs(
            cal.pointA.gps, cal, Pair(SCALE, SCALE), northAngle
        )!!
        val endImg = MapCalibrationUtils.gpsToImageAbs(endGps, cal, Pair(SCALE, SCALE), northAngle)!!

        val trackScreenAngle = screenAngleDeg(endImg.first - startImg.first, endImg.second - startImg.second)

        // Expected: walking TRUE-east on a map whose top edge = magnetic north should draw
        // the track at 90° + declination = 100° clockwise from screen-up.
        assertEquals(
            "Track walking TRUE-east must render at 90° + declination = 100° from screen-up",
            90.0 + declination, trackScreenAngle, 1.0
        )
    }
}
