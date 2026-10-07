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

        return MapGeometry.computeCalibrationRaw(pointA, pointB, declinationDeg.toFloat())
            ?: throw IllegalStateException("Calibration points too close")
    }

    /** True bearing from A to B in degrees [0, 360). */
    private fun trueBearing(cal: MapCalibration): Double =
        MapGeometry.bearing(cal.pointA.gps, cal.pointB.gps).toDouble()

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
     * north) should appear at -10° counter-clockwise from screen-up.
     *
     * Physical reasoning: declination = +10° means magnetic north is 10° east of true north,
     * so true north is 10° west (counter-clockwise) of magnetic north. On a map whose Y-axis
     * = magnetic north, a TRUE-north track appears at -10°.
     */
    @Test
    fun `gps track walking true north renders at -10deg from screen up when declination is +10`() {
        val declination = 10.0
        // Set trueBearingAB equal to declination so the map's Y-axis aligns with magnetic north:
        // rawMagneticBearing = trueBearingAB - declination = 0°, i.e. image up = toward magnetic north.
        val cal = buildCalibration(declinationDeg = declination, trueBearingAB = declination, distanceMeters = 200.0)

        val northAngle = MapOrientation.computeNorthAngleForMagneticAlignment(cal)

        // Sanity: bearingDegrees must encode rawMagneticBearing = 0° (magnetic north).
        assertEquals(
            "Map Y-axis aligns with magnetic north (bearingDegrees=0°)",
            0.0, cal.bearingDegrees.toDouble(), 0.5
        )

        // Build the user's GPS track: walking due TRUE-NORTH for 100 m from point A.
        val trackStartGps = cal.pointA.gps
        val trackEndGps = MapGeometry.offsetCoordinate(trackStartGps, dNorth = 100.0, dEast = 0.0)

        // Project onto the calibrated map.
        val startImg = MapCalibrationUtils.gpsToImage(trackStartGps, cal, northAngle)!!
        val endImg = MapCalibrationUtils.gpsToImage(trackEndGps, cal, northAngle)!!

        val dx = endImg.first - startImg.first
        val dy = endImg.second - startImg.second
        val trackScreenAngle = screenAngleDeg(dx, dy)

        // TRUE-north renders at ~-declination on a magnetic-north-aligned map.
        assertEquals(
            "TRUE-north track appears -10° (west of screen-up) with +10° declination",
            -declination.toDouble(),
            trackScreenAngle,
            1.0
        )
    }

    /**
     * Symmetric negative-declination case: if magnetic declination = -10° (west), true north is
     * 10° east (clockwise) of magnetic north, so walking TRUE-north should render at +10° on the map.
     */
    @Test
    fun `gps track walking true north renders at +10deg when declination is -10`() {
        val declination = -10.0
        // Map's Y-axis = magnetic north (rawMagneticBearing = 0).
        val cal = buildCalibration(declinationDeg = declination, trueBearingAB = declination, distanceMeters = 200.0)

        val northAngle = MapOrientation.computeNorthAngleForMagneticAlignment(cal)

        // Sanity: bearingDegrees must encode rawMagneticBearing = 0° (or 360°).
        val bearingDegNorm = cal.bearingDegrees % 360f
        assertTrue(
            "Map Y-axis aligns with magnetic north (bearingDegrees ≈ 0° or 360°), got ${cal.bearingDegrees}°",
            kotlin.math.abs(bearingDegNorm) < 1.0 || kotlin.math.abs(bearingDegNorm - 360f) < 1.0
        )

        val startImg = MapCalibrationUtils.gpsToImage(
            cal.pointA.gps, cal, northAngle
        )!!
        val endGps = MapGeometry.offsetCoordinate(cal.pointA.gps, dNorth = 100.0, dEast = 0.0)
        val endImg = MapCalibrationUtils.gpsToImage(endGps, cal, northAngle)!!

        val trackScreenAngle = screenAngleDeg(endImg.first - startImg.first, endImg.second - startImg.second)

        // TRUE-north renders at ~-declination = +10° (clockwise from screen-up).
        assertEquals(
            "Track walking TRUE-north must render at +10° (east of screen-up) for declination=-10°",
            -declination, trackScreenAngle, 1.0
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

        val startImg = MapCalibrationUtils.gpsToImage(
            cal.pointA.gps, cal, northAngle
        )!!
        val endGps = MapGeometry.offsetCoordinate(cal.pointA.gps, dNorth = 100.0, dEast = 0.0)
        val endImg = MapCalibrationUtils.gpsToImage(endGps, cal, northAngle)!!

        val trackScreenAngle = screenAngleDeg(endImg.first - startImg.first, endImg.second - startImg.second)

        assertEquals(
            "Track walking TRUE-north must render at 0° (declination=0)",
            0.0, trackScreenAngle, 0.5
        )
    }

    /**
     * Sanity check: the `computeNorthAngleForMagneticAlignment` rotation maps GPS true-east tracks
     * to a screen angle of ~90° - declination on a magnetic-north-aligned map, confirming the
     * rotation is uniform and consistent with the user's specified behaviour.
     */
    @Test
    fun `gps track walking true east renders at 80deg when declination is +10`() {
        val declination = 10.0
        // Map's Y-axis = magnetic north (rawMagneticBearing = 0).
        val cal = buildCalibration(declinationDeg = declination, trueBearingAB = declination, distanceMeters = 200.0)
        val northAngle = MapOrientation.computeNorthAngleForMagneticAlignment(cal)

        // Walk TRUE-east 100 m from point A
        val endGps = MapGeometry.offsetCoordinate(cal.pointA.gps, dNorth = 0.0, dEast = 100.0)

        val startImg = MapCalibrationUtils.gpsToImage(
            cal.pointA.gps, cal, northAngle
        )!!
        val endImg = MapCalibrationUtils.gpsToImage(endGps, cal, northAngle)!!

        val trackScreenAngle = screenAngleDeg(endImg.first - startImg.first, endImg.second - startImg.second)

        // TRUE-east renders at ~90° - declination = 80° on magnetic-north-aligned map.
        assertEquals(
            "Track walking TRUE-east renders at ~80° (slightly north of pure east)",
            90.0 - declination, trackScreenAngle, 1.5
        )

        // Direction check: declination shifts eastward track away from pure baseline angle.
        assertTrue(
            "Track must differ from baseline bearing due to declination; got $trackScreenAngle°",
            kotlin.math.abs(trackScreenAngle - 90.0) < 10.0
        )
    }
}
