package ru.bondarenko.orientvibe.ng

import org.junit.Assert.*
import org.junit.Test
import ru.bondarenko.orientvibe.ng.gps.GpsCoordinate
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.MapGeometry

/**
 * Tests for the actual bindGpsToFinishWithTrack() method from MapCalibrationUtils.
 * These tests verify that when user stands on ground at GPS coordinate X, presses "Здесь финиш",
 * the synthetic calibration correctly maps currentFixGPS to finishPoint image coordinates.
 */
class BindGpsToFinishWithTrackTest {

    /**
     * Core integration test:
     * 1. User places start (200, 600) and finish (750, 600) on map (horizontal baseline, same row)
     * 2. User walks 800m due east from original start (track bearing ~90°)
     * 3. CurrentFixGPS = 800m east of start GPS
     * 4. bindGpsToFinishWithTrack creates synthetic finish + recalibrates
     * 5. Verify: currentFixGPS maps to finishPoint image coords (750, 600) via gpsToImage
     */
    @Test
    fun `bindGpsToFinishWithTrack horizontal baseline currentFix maps to finishPoint`() {
        // === Setup: user places route on map ===
        val bmpW = 1000f
        val bmpH = 800f
        val startPointImageX = 200f
        val startPointImageY = 600f
        val finishPointImageX = 750f
        val finishPointImageY = 600f // same row → horizontal baseline

        // === Step 1: User walks 800m due east from original start ===
        val originalStartGps = GpsCoordinate(50.45, 30.5)
        val walkedDistanceM = 800.0
        val trackBearingDeg = 90.0 // due east

        // Compute currentFixGPS: 800m east of start (using same offsetCoordinate method as production)
        val dNorth = walkedDistanceM * kotlin.math.cos(Math.toRadians(trackBearingDeg)) // ~0
        val dEast = walkedDistanceM * kotlin.math.sin(Math.toRadians(trackBearingDeg)) // ~800
        val currentFixGPS = MapGeometry.offsetCoordinate(originalStartGps, dNorth, dEast)

        // === Step 2: User presses "Здесь финиш" → bindGpsToFinishWithTrack ===
        val result = MapCalibrationUtils.bindGpsToFinishWithTrack(
            startGPS = originalStartGps,
            startPointImageX = startPointImageX,
            startPointImageY = startPointImageY,
            finishPointImageX = finishPointImageX,
            finishPointImageY = finishPointImageY,
            currentFixGPS = currentFixGPS
        )

        // === Step 3: Verify key invariant — currentFix GPS maps to finishPoint on calibrated map ===
        val projectedCurrentGps = MapCalibrationUtils.gpsToImage(currentFixGPS, result.calibration)!!

        assertEquals(
            "currentFix GPS X → finishPoint image X",
            finishPointImageX.toDouble(), projectedCurrentGps.first.toDouble(), 0.01
        )
        assertEquals(
            "currentFix GPS Y → finishPoint image Y",
            finishPointImageY.toDouble(), projectedCurrentGps.second.toDouble(), 0.01
        )

        // === Sanity checks ===
        assertTrue("scale > 0: ${result.calibration.scaleMetersPerPixel}", result.calibration.scaleMetersPerPixel > 0.1)
        assertTrue(
            "northAngle non-zero: ${result.northAngleDegrees}°",
            kotlin.math.abs(result.northAngleDegrees) > 0.1
        )
    }

    /**
     * Test with diagonal baseline where GPS offset direction matches image offset direction.
     * For uniform-scale two-point calibration to work correctly, the ratio of GPS offsets
     * (dEast/dNorth from offsetCoordinate) must equal the ratio of image offsets (dx/|dy|).
     * Otherwise a single scale factor applied independently to each axis can't satisfy both.
     */
    @Test
    fun `bindGpsToFinishWithTrack diagonal baseline currentFix maps to finishPoint`() {
        val bmpW = 1000f
        val bmpH = 800f

        // User places start and finish on the map image (diagonal, equal dx/dy → clean 45°)
        val startPointImageX = 200f
        val startPointImageY = 600f
        val finishPointImageX = 700f   // +500px X from start
        val finishPointImageY = 100f   // -500px Y from start (equal magnitude → 45° in image space)

        // User's actual GPS coordinates on the ground:
        // Point A (start): where user pressed "Здесь старт"
        val pointAGps = GpsCoordinate(50.45, 30.5)

        // Point B (finish): where user walked to and presses "Здесь финиш"
        // Use offsetCoordinate with EQUAL dNorth/dEast → 45° bearing in GPS space too.
        // This matches the image aspect ratio so uniform-scale calibration projects correctly.
        val dDiag = 550.0  // meters per axis
        val finishGps = MapGeometry.offsetCoordinate(
            pointAGps,
            dNorth = dDiag,
            dEast = dDiag
        )

        // Verify GPS direction matches image direction (both 45° in their spaces)
        System.err.println("DIAG: GPS A=($pointAGps, lat=${pointAGps.latitude}, lon=${pointAGps.longitude})")
        System.err.println("DIAG: GPS B=($finishGps, lat=${finishGps.latitude}, lon=${finishGps.longitude})")
        val gpsEast = MapCalibrationUtils.eastDistance(pointAGps, finishGps)
        val gpsNorth = MapCalibrationUtils.northDistance(pointAGps, finishGps)
        System.err.println("DIAG: GPS dNorth=${gpsEast}, dEast=${gpsNorth}")

        // Press "Здесь финиш" — bindGpsToFinishWithTrack creates calibration
        val result = MapCalibrationUtils.bindGpsToFinishWithTrack(
            startGPS = pointAGps,
            startPointImageX = startPointImageX,
            startPointImageY = startPointImageY,
            finishPointImageX = finishPointImageX,
            finishPointImageY = finishPointImageY,
            currentFixGPS = finishGps  // user stands here
        )

        // Verify pointB was created correctly
        assertEquals(
            "pointB.gps lat must equal finishGps",
            finishGps.latitude, result.calibration.pointB.gps.latitude, 1e-15
        )
        assertEquals(
            "pointB.gps lon must equal finishGps",
            finishGps.longitude, result.calibration.pointB.gps.longitude, 1e-15
        )

        // Verify: currentFix maps to finishPoint (KEY INVARIANT)
        val projectedCurrentGps = MapCalibrationUtils.gpsToImage(finishGps, result.calibration)!!

        System.err.println("DIAG: projected=($projectedCurrentGps, X=${projectedCurrentGps.first}, Y=${projectedCurrentGps.second})")
        System.err.println("DIAG: expected=($finishPointImageX, $finishPointImageY)")
        System.err.println("DIAG: scale=${result.calibration.scaleMetersPerPixel}")

        assertEquals(
            "Diagonal: currentFix GPS X → finishPoint image X",
            finishPointImageX.toDouble(), projectedCurrentGps.first.toDouble(), 0.01
        )
        assertEquals(
            "Diagonal: currentFix GPS Y → finishPoint image Y",
            finishPointImageY.toDouble(), projectedCurrentGps.second.toDouble(), 0.01
        )

        assertTrue("scale > 0: ${result.calibration.scaleMetersPerPixel}", result.calibration.scaleMetersPerPixel > 0.1)
    }

    /**
     * Verify: with northAngle!=0, gpsToImageAbs returns rotated coords (not finishPoint),
     * but with northAngle==0 it does return finishPoint — proving northAngle drives rotation.
     * Both green GPS dot and purple marker use same gpsToImageAbs(northAngle) so they visually coincide.
     */
    @Test
    fun `bindGpsToFinishWithTrack gpsToImageAbs rotation verified with northAngle zero baseline`() {
        val bmpW = 1000f
        val bmpH = 800f
        val startPointImageX = 200f
        val startPointImageY = 600f
        val finishPointImageX = 750f
        val finishPointImageY = 600f

        val originalStartGps = GpsCoordinate(50.45, 30.5)

        // Horizontal baseline: user walks 500m due east
        val currentFixGPS = MapGeometry.offsetCoordinate(originalStartGps, dNorth = 0.0, dEast = 500.0)

        val result = MapCalibrationUtils.bindGpsToFinishWithTrack(
            startGPS = originalStartGps,
            startPointImageX = startPointImageX,
            startPointImageY = startPointImageY,
            finishPointImageX = finishPointImageX,
            finishPointImageY = finishPointImageY,
            currentFixGPS = currentFixGPS
        )

        val northAngleDeg = result.northAngleDegrees
        System.err.println("NORTH: northAngle=${northAngleDeg}°, bearing=${result.calibration.bearingDegrees}°")

        // gpsToImage returns absolute image coords (no northAngle rotation)
        val projected = MapCalibrationUtils.gpsToImage(currentFixGPS, result.calibration)!!
        // gpsToImageAbs returns rotated coords that share the same coordinate frame as the green GPS dot,
        // ensuring both green dot and purple calibration point visually coincide on screen.
        val absProjected = MapCalibrationUtils.gpsToImageAbs(
            currentFixGPS, result.calibration, Pair(bmpW, bmpH), northAngleDeg
        )!!

        // gpsToImage maps currentFixGPS to finishPoint (absolute coords, unrotated frame)
        assertEquals(
            "gpsToImage: X → finishPoint",
            finishPointImageX.toDouble(), projected.first.toDouble(), 0.01
        )
        assertEquals(
            "gpsToImage: Y → finishPoint",
            finishPointImageY.toDouble(), projected.second.toDouble(), 0.01
        )

        // gpsToImageAbs with northAngle returns coordinates in the rotated (screen-aligned) frame.
        // Green GPS dot and purple calibration marker both use gpsToImageAbs(northAngle) + sourceToViewCoord,
        // so they visually coincide on screen. The raw pixel values differ from finishPoint because the
        // coordinate frame is rotated — this is expected behavior for northAngle != 0.
        // Verify: the rotated coords are NOT equal to finishPoint (proves rotation is applied)
        val dx = kotlin.math.abs(absProjected.first - finishPointImageX)
        val dy = kotlin.math.abs(absProjected.second - finishPointImageY)
        assertTrue(
            "gpsToImageAbs with northAngle!=0 returns rotated coords (dx=$dx, dy=$dy — NOT finishPoint)",
            dx + dy > 1.0
        )

        // Verify: gpsToImageAbs with northAngle=0 DOES return finishPoint (proves northAngle causes rotation)
        val zeroAngleProjected = MapCalibrationUtils.gpsToImageAbs(
            currentFixGPS, result.calibration, Pair(bmpW, bmpH), 0f
        )!!
        assertEquals(
            "gpsToImageAbs with northAngle=0: X → finishPoint",
            finishPointImageX.toDouble(), zeroAngleProjected.first.toDouble(), 0.01
        )
        assertEquals(
            "gpsToImageAbs with northAngle=0: Y → finishPoint",
            finishPointImageY.toDouble(), zeroAngleProjected.second.toDouble(), 0.01
        )

        System.err.println("NORTH: projected=($projected, gpsToImageAbs(northAngle)=($absProjected), gpsToImageAbs(0)=($zeroAngleProjected)")
    }

    /**
     * Test with a very short walk (50m) — edge case for minimum calibration distance.
     */
    @Test
    fun `bindGpsToFinishWithTrack short walk 50m produces meaningful calibration`() {
        val startPointImageX = 300f
        val startPointImageY = 400f
        val finishPointImageX = 600f
        val finishPointImageY = 400f

        val originalStartGps = GpsCoordinate(50.45, 30.5)

        // Only walked 50m due east — very short baseline
        val currentFixGPS = MapGeometry.offsetCoordinate(originalStartGps, dNorth = 0.0, dEast = 50.0)

        val result = MapCalibrationUtils.bindGpsToFinishWithTrack(
            startGPS = originalStartGps,
            startPointImageX = startPointImageX,
            startPointImageY = startPointImageY,
            finishPointImageX = finishPointImageX,
            finishPointImageY = finishPointImageY,
            currentFixGPS = currentFixGPS
        )

        val projectedCurrentGps = MapCalibrationUtils.gpsToImage(currentFixGPS, result.calibration)!!

        assertEquals("Short walk: X → finishPoint", finishPointImageX.toDouble(), projectedCurrentGps.first.toDouble(), 0.01)
        assertEquals("Short walk: Y → finishPoint", finishPointImageY.toDouble(), projectedCurrentGps.second.toDouble(), 0.01)

        // Scale should be reasonable even for short distance
        assertTrue("scale > 0 after short walk: ${result.calibration.scaleMetersPerPixel}", result.calibration.scaleMetersPerPixel > 0.01)
    }

    /**
     * Test that gpsToImageAbs with northAngle=0 also returns finishPoint coords.
     * This verifies the pivot-at-pointA invariant is correct for gpsToImageAbs.
     */
    @Test
    fun `bindGpsToFinishWithTrack northAngle zero case currentFix maps to finishPoint`() {
        val bmpW = 1000f
        val bmpH = 800f
        val startPointImageX = 200f
        val startPointImageY = 600f
        val finishPointImageX = 750f
        val finishPointImageY = 600f

        val originalStartGps = GpsCoordinate(50.45, 30.5)
        val currentFixGPS = MapGeometry.offsetCoordinate(originalStartGps, dNorth = 0.0, dEast = 1000.0)

        val result = MapCalibrationUtils.bindGpsToFinishWithTrack(
            startGPS = originalStartGps,
            startPointImageX = startPointImageX,
            startPointImageY = startPointImageY,
            finishPointImageX = finishPointImageX,
            finishPointImageY = finishPointImageY,
            currentFixGPS = currentFixGPS
        )

        // gpsToImageAbs returns same as gpsToImage (no separate rotation applied)
        val zeroAngle = MapCalibrationUtils.gpsToImageAbs(
            currentFixGPS, result.calibration, Pair(bmpW, bmpH), 0f
        )!!
        assertEquals("northAngle=0: X", finishPointImageX.toDouble(), zeroAngle.first.toDouble(), 0.01)
        assertEquals("northAngle=0: Y", finishPointImageY.toDouble(), zeroAngle.second.toDouble(), 0.01)
    }

    /**
     * Verify: northAngle uses TRUE bearing (not magnetic bearing) so that
     * the correction angle aligns with geographic direction, not compass direction.
     *
     * Before fix: northAngle = -raw_mag_bearing ≈ -(true_bearing - declination).
     *   For horizontal baseline: true_bearing ≈ 90°, raw_mag_bearing ≈ 85°.
     *   northAngle ≈ -85° → gpsToImageAbs shifts currentFixGPS off finishPoint.
     *
     * After fix: northAngle = -true_bearing ≈ -90°.
     *   The correction angle matches the TRUE geographic direction from A to B,
     *   NOT the compass direction (which includes declination).
     *
     * Key insight: cal.bearingDegrees already encodes bearing in the calibration frame.
     * Using northAngle = -raw_mag_bearing double-encodes declination — once through
     * cal.scaleMetersPerPixel and again through the explicit rotation, shifting X by ~48px.
     * Fix: use true bearing for northAngle only (geographic alignment), let calibration
     * frame handle magnetic direction independently.
     */
    @Test
    fun `bindGpsToFinishWithTrack northAngle uses true bearing not raw magnetic`() {
        val bmpW = 1000f
        val bmpH = 800f
        val startPointImageX = 200f
        val startPointImageY = 600f
        val finishPointImageX = 750f
        val finishPointImageY = 600f

        val originalStartGps = GpsCoordinate(50.45, 30.5)

        // Horizontal baseline: user walks due east along same latitude
        val currentFixGPS = MapGeometry.offsetCoordinate(originalStartGps, dNorth = 0.0, dEast = 800.0)

        val result = MapCalibrationUtils.bindGpsToFinishWithTrack(
            startGPS = originalStartGps,
            startPointImageX = startPointImageX,
            startPointImageY = startPointImageY,
            finishPointImageX = finishPointImageX,
            finishPointImageY = finishPointImageY,
            currentFixGPS = currentFixGPS
        )

        val northAngleDeg = result.northAngleDegrees
        val trueBearing = MapGeometry.bearing(originalStartGps, currentFixGPS)

        System.err.println("NORTH: northAngle=${northAngleDeg}°, true_bearing=${trueBearing}°")

        // KEY ASSERTION: northAngle must equal -true_bearing (declination-independent).
        // This ensures the correction angle aligns with geographic direction.
        val expectedNorthAngle = -trueBearing.toFloat()
        assertEquals(
            "northAngle must equal -true_bearing, not -raw_mag_bearing",
            expectedNorthAngle.toDouble(),
            northAngleDeg.toDouble(),
            0.1  // small tolerance for Rhumb-line vs haversine bearing differences
        )

        // Verify: gpsToImage (unrotated) maps currentFixGPS to finishPoint EXACTLY.
        // This is the core two-point invariant — holds regardless of northAngle.
        val unrotated = MapCalibrationUtils.gpsToImage(currentFixGPS, result.calibration)!!
        assertEquals(
            "gpsToImage: X → finishPoint (unconditional invariant)",
            finishPointImageX.toDouble(), unrotated.first.toDouble(), 0.01
        )
        assertEquals(
            "gpsToImage: Y → finishPoint (unconditional invariant)",
            finishPointImageY.toDouble(), unrotated.second.toDouble(), 0.01
        )

        // Verify: northAngle is close to -90° for horizontal baseline (due east walk).
        assertTrue(
            "northAngle ≈ -90° for due-east walk: ${northAngleDeg}°",
            kotlin.math.abs(kotlin.math.abs(northAngleDeg) - 90.0) < 1.0
        )

        // Verify: gpsToImageAbs at northAngle=0 returns same as gpsToImage (proves pivot=pointA).
        val zeroProjected = MapCalibrationUtils.gpsToImageAbs(
            currentFixGPS, result.calibration, Pair(bmpW, bmpH), 0f
        )!!
        assertEquals(
            "gpsToImageAbs(0) == gpsToImage at finishPoint (pivot=pointA)",
            unrotated.first.toDouble(), zeroProjected.first.toDouble(), 0.01
        )
        assertEquals(
            "gpsToImageAbs(0) Y matches",
            unrotated.second.toDouble(), zeroProjected.second.toDouble(), 0.01
        )

        System.err.println("NORTH: unrotated=($unrotated), absProj(northAngle)=(${result.calibration.scaleMetersPerPixel})")
    }

    /**
     * Regression test: bindGpsToFinishWithTrack's northAngle must account for magnetic
     * declination. The user has placed the start/finish such that the map's top edge is
     * aligned with magnetic north (a typical orienteering map). With declination = +10°,
     * walking TRUE-north on the ground must produce a track on the map that tilts +10°
     * clockwise from screen-up — not 0°.
     *
     * Scenario:
     *   - Map: bottom = start point (imageY = 0.8 * 1000 = 800), top = finish point
     *          (imageY = 0.2 * 1000 = 200). The image Y-axis (smaller pixel index at the top)
     *          corresponds to magnetic north on the physical map.
     *   - GPS: start = (50.45, 30.5). User walks due TRUE-north (true bearing 0°) for 500m,
     *          then presses "Здесь финиш" → bindGpsToFinishWithTrack is invoked.
     *   - Declination at the user's location: +10° (east).
     *
     * Expected:
     *   - finishPoint GPS = 500m due north of startGPS.
     *   - The northAngle returned by bindGpsToFinishWithTrack must equal
     *     -(trueBearing - declination) = -(0° - 10°) = +10° — NOT -(0°) = 0°.
     *   - The rendered track (start→finish via gpsToImageAbs + northAngle) must appear at
     *     screen angle +10° (clockwise from screen-up), confirming the user sees the
     *     GPS north track on the magnetic-north-aligned map at the expected angle.
     */
    @Test
    fun `bindGpsToFinishWithTrack northAngle accounts for magnetic declination`() {
        val declination = 10.0

        val bmpW = 1000f
        val bmpH = 1000f

        // Map: start at bottom (imageY=800), finish at top (imageY=200). The map's
        // top edge is the magnetic-north direction.
        val startPointImageX = 500f
        val startPointImageY = 800f
        val finishPointImageX = 500f
        val finishPointImageY = 200f

        // User starts at this GPS and walks 500m due TRUE-north (true bearing 0°)
        // before pressing "Здесь финиш".
        val originalStartGps = GpsCoordinate(50.45, 30.5)
        val currentFixGPS = MapGeometry.offsetCoordinate(
            originalStartGps, dNorth = 500.0, dEast = 0.0
        )

        // === Run bindGpsToFinishWithTrack with the explicit declination overload ===
        val result = MapCalibrationUtils.bindGpsToFinishWithTrack(
            startGPS = originalStartGps,
            startPointImageX = startPointImageX,
            startPointImageY = startPointImageY,
            finishPointImageX = finishPointImageX,
            finishPointImageY = finishPointImageY,
            currentFixGPS = currentFixGPS,
            magneticDeclination = declination
        )

        // === Assertion 1: northAngle must equal -(trueBearing - declination) ===
        val trueBearing = MapGeometry.bearing(originalStartGps, currentFixGPS)
        val expectedNorthAngle = -(trueBearing - declination).toFloat()
        assertEquals(
            "northAngle must use rawMagneticBearing (trueBearing - declination), not raw trueBearing",
            expectedNorthAngle.toDouble(),
            result.northAngleDegrees.toDouble(),
            0.5
        )
        // With trueBearing ≈ 0° and declination +10°, northAngle must be ≈ +10° (not 0°).
        assertTrue(
            "northAngle must be ≈ +10° for declination=+10°; got ${result.northAngleDegrees}°",
            kotlin.math.abs(result.northAngleDegrees - 10f) < 1f
        )

        // === Assertion 2: Rendered track screen angle ===
        // Use the calibration's bearing-derived northAngle to project start and finish GPS
        // onto the calibrated map, and check that the resulting vector tilts +10°.
        val startImg = MapCalibrationUtils.gpsToImageAbs(
            originalStartGps, result.calibration, Pair(bmpW, bmpH), result.northAngleDegrees
        )!!
        val finishImg = MapCalibrationUtils.gpsToImageAbs(
            currentFixGPS, result.calibration, Pair(bmpW, bmpH), result.northAngleDegrees
        )!!
        val dx = finishImg.first - startImg.first
        val dy = finishImg.second - startImg.second
        // 0° = up (screen-up), 90° = right, etc.
        val trackScreenAngle = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), (-dy).toDouble()))
        assertEquals(
            "Track walking TRUE-north must render at +10° (declination=${declination}°); got $trackScreenAngle°",
            declination, trackScreenAngle, 1.0
        )
    }

    /**
     * Regression test (negative declination): with declination = -10° (west), walking
     * TRUE-north must render at -10° (counter-clockwise from screen-up).
     */
    @Test
    fun `bindGpsToFinishWithTrack northAngle handles negative declination`() {
        val declination = -10.0
        val startPointImageX = 500f
        val startPointImageY = 800f
        val finishPointImageX = 500f
        val finishPointImageY = 200f

        val originalStartGps = GpsCoordinate(50.45, 30.5)
        val currentFixGPS = MapGeometry.offsetCoordinate(
            originalStartGps, dNorth = 500.0, dEast = 0.0
        )

        val result = MapCalibrationUtils.bindGpsToFinishWithTrack(
            startGPS = originalStartGps,
            startPointImageX = startPointImageX,
            startPointImageY = startPointImageY,
            finishPointImageX = finishPointImageX,
            finishPointImageY = finishPointImageY,
            currentFixGPS = currentFixGPS,
            magneticDeclination = declination
        )

        val trueBearing = MapGeometry.bearing(originalStartGps, currentFixGPS)
        val expectedNorthAngle = -(trueBearing - declination).toFloat()
        assertEquals(
            "northAngle must use rawMagneticBearing",
            expectedNorthAngle.toDouble(),
            result.northAngleDegrees.toDouble(),
            0.5
        )
        assertTrue(
            "northAngle must be ≈ -10° for declination=-10°; got ${result.northAngleDegrees}°",
            kotlin.math.abs(result.northAngleDegrees + 10f) < 1f
        )
    }

    private fun Double.toFixed(digits: Int): String = "%.${digits}f".format(this)
}
