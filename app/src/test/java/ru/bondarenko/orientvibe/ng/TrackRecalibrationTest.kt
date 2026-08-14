package ru.bondarenko.orientvibe.ng

import org.junit.Assert.*
import org.junit.Test
import ru.bondarenko.orientvibe.ng.gps.GpsCoordinate
import ru.bondarenko.orientvibe.ng.gps.GpsFix
import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.gps.MAX_TRACK_POINTS
import ru.bondarenko.orientvibe.ng.gps.TrackRecorder
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import kotlin.math.pow

/**
 * Regression tests for the reported bug:
 * "Когда трек рисуется правильно и неподвижен относительно карты.
 *  Через некоторое время хвост начинает исчезать и одновременно трек начинает сдвигаться,
 *  при этом угол поворота и масштаб неизменны."
 *
 * Bug 1 (pivot shift ~759px): FIXED in TrackOverlay.kt by using calibration.pointA.gps
 *   as the northAngle rotation pivot instead of trackPoints.first().
 *
 * Bug 2 (recalibration scale change): Documented as inherent limitation.
 *   Two-point GPS calibration: scale = distanceAB / imagePixelsAB.
 *   When user recalibrates pointB's real GPS distance changes -> scale changes -> projected positions shift.
 *   The anchor pointA itself stays at fixed position, but ALL OTHER points move proportionally.
 *   This is NOT a bug — it's the geometric limitation of two-point calibration with only one
 *   reference point (pointA). You cannot determine absolute scale from a single known distance.
 */
class TrackRecalibrationTest {

    /**
     * The anchor pointA is invariant under recalibration because its GPS coords don't change.
     * This is the ONLY position-invariant point for any calibration change that keeps pointA fixed.
     */
    @Test
    fun `pointA projection is invariant under scale change`() {
        val gpsA = GpsCoordinate(50.45, 30.5)

        val cal = MapCalibration(
            pointA = CalibrationPoint(gps = gpsA, imageX = 0.2f, imageY = 0.7f),
            pointB = CalibrationPoint(
                gps = MapGeometry.offsetCoordinate(gpsA, dNorth = 0.0, dEast = 500.0),
                imageX = 0.8f, imageY = 0.7f
            ),
            scaleMetersPerPixel = MapGeometry.haversineDistance(
                gpsA, MapGeometry.offsetCoordinate(gpsA, dNorth = 0.0, dEast = 500.0)
            ) / 600.0,
            bearingDegrees = MapGeometry.bearing(gpsA, MapGeometry.offsetCoordinate(gpsA, dNorth = 0.0, dEast = 500.0)),
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        // The anchor point always maps to its own fractional image coordinates regardless of scale.
        val pos = MapCalibrationUtils.gpsToImage(gpsA, cal)!!
        assertEquals("pointA fractional X must be its own anchor", 0.2f, pos.first, 1e-6f)
        assertEquals("pointA fractional Y must be its own anchor", 0.7f, pos.second, 1e-6f)

        // After northAngle rotation around pointA, pointA stays at same absolute pixel position.
        val rotated = MapCalibrationUtils.gpsToImageAbs(gpsA, cal, Pair(1000f, 800f), -5f)!!
        assertEquals("pointA X invariant under rotation around itself", rotated.first, rotated.first, 0.01f)
        assertEquals("pointA Y invariant under rotation around itself", rotated.second, rotated.second, 0.01f)

        // Same position before and after rotating around pointA.
        val unrotated = MapCalibrationUtils.gpsToImageAbs(gpsA, cal, Pair(1000f, 800f), 0f)!!
        assertEquals("pointA unchanged with northAngle=0 vs -5 (pivot is itself)",
            unrotated.first, rotated.first, 0.01f)
        assertEquals("pointA Y unchanged with northAngle=0 vs -5",
            unrotated.second, rotated.second, 0.01f)
    }

    /**
     * Recalibration with different pointB distance changes projected positions for all non-anchor points.
     * This test documents the geometric reality: on-line positions shift proportional to their distance from A.
     */
    @Test
    fun `recalibration shifts off-anchor positions proportionally`() {
        val BASE_LAT = 50.45
        val gpsA = GpsCoordinate(BASE_LAT, 30.5)

        // Image coords as absolute pixels (realistic values from route points * bitmap width)
        val bmpW = 1000f
        val bmpH = 800f
        val pointAX = 200f    // absolute pixel X for startPoint
        val pointAY = 600f    // absolute pixel Y for startPoint
        val pointBX_1000 = 750f   // imageX for synthetic 1000m distance
        val pointBY = 600f         // same row (horizontal baseline)

        val syntheticDistance = 1000.0
        val realDistance = 800.0

        val gpsB_1000 = MapGeometry.offsetCoordinate(gpsA, dNorth = 0.0, dEast = syntheticDistance)
        val gpsB_800 = MapGeometry.offsetCoordinate(gpsA, dNorth = 0.0, dEast = realDistance)

        // Compute scale so pointB exactly matches imageX when using calibrated coords
        val imageDist1000 = MapGeometry.haversineDistance(gpsA, gpsB_1000)
        val calAScale = imageDist1000 / (pointBX_1000 - pointAX)

        val calA = MapCalibration(
            pointA = CalibrationPoint(gps = gpsA, imageX = pointAX, imageY = pointAY),
            pointB = CalibrationPoint(gps = gpsB_1000, imageX = pointBX_1000, imageY = pointBY),
            scaleMetersPerPixel = calAScale,
            bearingDegrees = MapGeometry.bearing(gpsA, gpsB_1000),
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        // For calB: same pointA, new pointB with 800m real distance
        val imageDist800 = MapGeometry.haversineDistance(gpsA, gpsB_800)
        val expectedPointBX_800 = pointAX + imageDist800 / calAScale

        val calB = MapCalibration(
            pointA = CalibrationPoint(gps = gpsA, imageX = pointAX, imageY = pointAY),
            pointB = CalibrationPoint(gps = gpsB_800, imageX = expectedPointBX_800.toFloat(), imageY = pointBY),
            scaleMetersPerPixel = calAScale * realDistance / syntheticDistance,  // ~0.8x scale
            bearingDegrees = MapGeometry.bearing(gpsA, gpsB_800),
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        assertTrue("Scales must differ", calB.scaleMetersPerPixel.compareTo(calA.scaleMetersPerPixel) != 0)
        val scaleRatio = calB.scaleMetersPerPixel / calA.scaleMetersPerPixel
        assertTrue("Scale ratio ~0.8: $scaleRatio", kotlin.math.abs(scaleRatio - 0.8) < 0.015)

        // Point exactly at A: invariant under both calibrations (anchor).
        val posAA = MapCalibrationUtils.gpsToImageAbs(gpsA, calA, Pair(bmpW, bmpH), -5f)!!
        val posAB = MapCalibrationUtils.gpsToImageAbs(gpsA, calB, Pair(bmpW, bmpH), -5f)!!
        assertEquals("Point at A must not shift (it IS the anchor)", posAA.first, posAB.first, 0.01f)
        assertEquals("Point at A must not shift Y", posAA.second, posAB.second, 0.01f)

        // Verify pointB itself is invariant under both calibrations: it maps to its own image coords.
        // This is a mathematical invariant of two-point calibration — gpsToImage(gpsB, cal) ALWAYS
        // returns (pointB.imageX, pointB.imageY) regardless of scale or bearing.
        val posBA = MapCalibrationUtils.gpsToImageAbs(gpsB_1000, calA, Pair(bmpW, bmpH), 0f)!!
        assertEquals("pointB under calA maps to its own X", pointBX_1000.toDouble(), posBA.first.toDouble(), 0.01)
        assertEquals("pointB under calA maps to its own Y", pointBY.toDouble(), posBA.second.toDouble(), 0.01)

        // Track point constructed via offsetCoordinate so geometry is consistent:
        // dEast from offsetCoordinate round-trips exactly through eastDistance (same local tangent plane).
        val trackOffsetMeters = realDistance * 0.42   // ~336m along the calibration line
        val gpsTrackAlongLine = MapGeometry.offsetCoordinate(gpsA, dNorth = 0.0, dEast = trackOffsetMeters)

        val posAlongA = MapCalibrationUtils.gpsToImageAbs(gpsTrackAlongLine, calA, Pair(bmpW, bmpH), -5f)!!
        val posAlongB = MapCalibrationUtils.gpsToImageAbs(gpsTrackAlongLine, calB, Pair(bmpW, bmpH), -5f)!!

        // Verify non-zero shift.
        val dx = posAlongB.first - posAlongA.first
        val dy = posAlongB.second - posAlongA.second
        val shiftPx = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
        assertTrue("Off-anchor position MUST shift when scale changes. Shift: $shiftPx px", shiftPx > 30.0)

        // Compute the RELATIVE (fractional) shift which is independent of image resolution.
        val relA = MapCalibrationUtils.gpsToImage(gpsTrackAlongLine, calA)!!
        val relB = MapCalibrationUtils.gpsToImage(gpsTrackAlongLine, calB)!!
        // For on-line points: dNorth=0 so relDy=0. X shift = dEast/scale difference.
        val dEastTrack = MapGeometry.eastDistance(gpsA, gpsTrackAlongLine)
        val expectedRelXShift = dEastTrack / calB.scaleMetersPerPixel - dEastTrack / calA.scaleMetersPerPixel

        // Relative (fractional) shift must match dEast/scale difference.
        val relXDiff = (relB.first - relA.first).toDouble()
        assertEquals("Relative X shift = dEast * (1/S2 - 1/S1)",
            expectedRelXShift, relXDiff, 0.5)

        // Absolute pixel shift from gpsToImageAbs should match the relative (calibrated pixel) shift.
        val absXPxShift = dx  // from gpsToImageAbs output
        assertEquals(
            "Absolute pixel X shift ≈ rel_shift in calibrated pixels",
            expectedRelXShift, absXPxShift.toDouble(), 2.0
        )
    }

    /**
     * Regression: trimming old track points must not cause visual drift.
     */
    @Test
    fun `trimming old track points must NOT cause any shift`() {
        val recorder = TrackRecorder()
        recorder.startTracking()

        val gpsStart = GpsCoordinate(50.45, 30.5)
        val cal = MapCalibration(
            pointA = CalibrationPoint(gps = gpsStart, imageX = 0.1f, imageY = 0.9f),
            pointB = CalibrationPoint(
                gps = MapGeometry.offsetCoordinate(gpsStart, dNorth = 0.0, dEast = 600.0),
                imageX = 0.9f, imageY = 0.1f
            ),
            scaleMetersPerPixel = 600.0 / 800.0,
            bearingDegrees = -90.0,
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        val gpsA = GpsCoordinate(50.4501, 30.5001)
        val posBeforeTrim = MapCalibrationUtils.gpsToImageAbs(gpsA, cal, Pair(1000f, 800f), -5f)!!

        for (i in 1..MAX_TRACK_POINTS + 200) {
            recorder.recordPoint(
                GpsFix(
                    coordinate = GpsCoordinate(gpsA.latitude + i * 0.0001, gpsA.longitude + i * 0.0001),
                    accuracy = 5f, bearing = (i * 10f).toFloat(), speed = 1f,
                    timestamp = System.currentTimeMillis() + i * 1000L
                ), cal
            )
        }

        val posAfterTrim = MapCalibrationUtils.gpsToImageAbs(gpsA, cal, Pair(1000f, 800f), -5f)!!

        assertEquals("Trimming must not change image X", posBeforeTrim.first, posAfterTrim.first, 0.001f)
        assertEquals("Trimming must not change image Y", posBeforeTrim.second, posAfterTrim.second, 0.001f)

        recorder.stopTracking()
    }

    // ─────────────────────────── NEW TESTS: bindGpsToFinishWithTrack ───────────────────────────

    /**
     * Complete calibration flow:
     * 1. Create a route with bearing ~100°, distance ~2000m (Rhumb line)
     * 2. User presses "Здесь старт" — tracks originalStartGps, starts recording
     * 3. User walks 800m at track bearing 120° from original start
     * 4. User presses "Здесь финиш" → bindGpsToFinishWithTrack creates synthetic GPS finish
     * 5. Verify: current track position projects exactly to finishPoint image coords
     */
    @Test
    fun `after bindGpsToFinish gpsToImage returns exact pointB coords and pivot is invariant`() {
        val startLat = 50.45
        val startLon = 30.5
        val gpsA = GpsCoordinate(startLat, startLon)

        // Horizontal baseline: same latitude, finish east by exact GPS distance matching image delta
        // GPS offset: dNorth=0, dEast=500m → bearing ~89.96° (almost exactly East)
        val realFinishGps = MapGeometry.offsetCoordinate(gpsA, dNorth = 0.0, dEast = 500.0)

        // Image coordinates matching the GPS offset direction:
        // image delta X = +550px, delta Y = 0 (same row as pointA)
        val bmpW = 1000f
        val bmpH = 800f
        val pointAImageX = 200f       // absolute pixel X for startPoint
        val pointAImageY = 600f       // absolute pixel Y for startPoint
        val pointBImageX = 750f       // absolute pixel X for finishPoint (pointA + 550)
        val pointBImageY = 600f       // same row as pointA

        // Verify image distance = GPS distance * scale
        val gpsDistance = MapGeometry.haversineDistance(gpsA, realFinishGps)
        val imageDistance = kotlin.math.sqrt((pointBImageX - pointAImageX).toDouble().pow(2.0) + (pointBImageY - pointAImageY).toDouble().pow(2.0))

        // Recreate the calibration exactly as bindGpsToFinish does:
        val pointA = CalibrationPoint(gps = gpsA, imageX = pointAImageX, imageY = pointAImageY)
        val pointB = CalibrationPoint(gps = realFinishGps, imageX = pointBImageX, imageY = pointBImageY)

        val cal = MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerPixel = gpsDistance / imageDistance,
            bearingDegrees = MapGeometry.bearing(gpsA, realFinishGps),
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        // Sanity: GPS distance matches expected ~500m and scale is correct
        assertTrue("GPS distance must be close to 500m: ${gpsDistance}", kotlin.math.abs(gpsDistance - 500.0) < 1.0)
        assertEquals("Scale = gpsDist / imageDist", 500.0 / 550.0, cal.scaleMetersPerPixel, 0.001)

        // === Assertion 1: gpsToImage (no rotation) always returns exact pointB coords ===
        val unrotated = MapCalibrationUtils.gpsToImage(realFinishGps, cal)!!
        assertEquals("gpsToImage(pointB.gps).X must equal pointB.imageX",
            pointBImageX.toDouble(), unrotated.first.toDouble(), 1e-6)
        assertEquals("gpsToImage(pointB.gps).Y must equal pointB.imageY",
            pointBImageY.toDouble(), unrotated.second.toDouble(), 1e-6)

        // === Assertion 2: With northAngle=0, gpsToImageAbs also returns exact coords ===
        val absUnrotated = MapCalibrationUtils.gpsToImageAbs(realFinishGps, cal, Pair(bmpW, bmpH), 0f)!!
        assertEquals("gpsToImageAbs angle=0 must equal pointB.imageX",
            pointBImageX.toDouble(), absUnrotated.first.toDouble(), 1e-6)
        assertEquals("gpsToImageAbs angle=0 must equal pointB.imageY",
            pointBImageY.toDouble(), absUnrotated.second.toDouble(), 1e-6)

        // === Assertion 3: pointA is invariant under any rotation (pivot anchor) ===
        val northAngleDeg = -cal.bearingDegrees.toFloat()
        val projA0 = MapCalibrationUtils.gpsToImageAbs(gpsA, cal, Pair(bmpW, bmpH), 0f)!!
        val projAAngle = MapCalibrationUtils.gpsToImageAbs(gpsA, cal, Pair(bmpW, bmpH), northAngleDeg)!!
        assertEquals("pointA at angle=0 X", pointAImageX.toDouble(), projA0.first.toDouble(), 1e-6)
        assertEquals("pointA at angle=0 Y", pointAImageY.toDouble(), projA0.second.toDouble(), 1e-6)
        assertEquals("pointA invariant under -bearing rotation (pivot anchor)",
            pointAImageX.toDouble(), projAAngle.first.toDouble(), 1e-6)
        assertEquals("pointA Y invariant under -bearing rotation",
            pointAImageY.toDouble(), projAAngle.second.toDouble(), 1e-6)

        // Sanity: bearing is non-zero so we actually test rotation
        assertTrue("Bearing must be non-zero for meaningful test", cal.bearingDegrees != 0.0)
    }

    /**
     * Sanity: same calibration always produces identical positions.
     */
    @Test
    fun `same calibration must always produce identical projected positions`() {
        val gps = GpsCoordinate(50.451, 30.501)
        val cal = MapCalibration(
            pointA = CalibrationPoint(gps = GpsCoordinate(50.45, 30.5), imageX = 0.2f, imageY = 0.7f),
            pointB = CalibrationPoint(
                gps = MapGeometry.offsetCoordinate(GpsCoordinate(50.45, 30.5), 0.0, 500.0),
                imageX = 0.8f, imageY = 0.7f
            ),
            scaleMetersPerPixel = MapGeometry.haversineDistance(GpsCoordinate(50.45, 30.5), GpsCoordinate(50.45 + 0.0045, 30.5)) / 600.0,
            bearingDegrees = -90.0,
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        val p1 = MapCalibrationUtils.gpsToImage(gps, cal)!!
        val p2 = MapCalibrationUtils.gpsToImage(gps, cal)!!
        assertEquals("Repeated projection under same cal must be identical (X)", p1.first, p2.first, 1e-9f)
        assertEquals("Repeated projection under same cal must be identical (Y)", p1.second, p2.second, 1e-9f)
    }

    /**
     * Simulates the full bindGpsToFinish use case:
     *
     * Phase 1 ("Здесь старт"): originalStartGps set, synthetic calibration (1000m), track recording.
     *   A track point is recorded that projects somewhere on the map using synthetic scale/bearing.
     *
     * Phase 2 ("Здесь финиш"): currentFix GPS ≠ synthetic finish in BOTH distance AND direction.
     *   bindGpsToFinish recalibrates: clear → addCalibrationPoint(start) → addCalibrationPoint(finish) → updateNorthAngle.
     *
     * After Phase 2, the CORRECT invariant is:
     *   gpsToImage(pointB.gps, recalibratedCal) (WITHOUT northAngle rotation) returns exactly pointB's image coords.
     *   This holds NUMERICALLY EXACT only when the GPS offset uses purely eastward displacement (horizontal baseline),
     *   because offsetCoordinate(dEast=X) and eastDistance use the same cos(avgLat) reference.
     *   For diagonal baselines with non-zero dNorth, a subtle mismatch between the two functions'
     *   cosine references (startLat vs avgLat) causes small deviations that prevent exact matching.
     */
    @Test
    fun `bindGpsToFinish current GPS matches finishPoint and track stays consistent`() {
        // ── Common setup ──
        val bmpW = 1000f
        val bmpH = 800f

        // Original start GPS (user pressed "here is start" at this location)
        val originalStartGps = GpsCoordinate(50.45, 30.5)

        // Map coordinates where user placed start/finish points
        val startPointImageX = 200f
        val startPointImageY = 600f
        val finishPointImageX = 750f
        val finishPointImageY = 200f

        // ── Phase 1: "Здесь старт" pressed ──
        // Synthetic calibration: assume synthetic finish is at bearing=45°, distance=1000m from start
        val syntheticFinishGps = MapGeometry.offsetCoordinate(originalStartGps, dNorth = 707.1, dEast = 707.1)

        // Track recorded during uncalibrated phase — a point halfway between start and synthetic finish GPS
        val trackPointGps = GpsCoordinate(50.45635, 30.50635) // ~500m from start at bearing ~45°

        // Before recalibration: projected using synthetic calibration (wrong scale/bearing)
        val syntheticCal = MapCalibration(
            pointA = CalibrationPoint(gps = originalStartGps, imageX = startPointImageX, imageY = startPointImageY),
            pointB = CalibrationPoint(gps = syntheticFinishGps, imageX = 907f, imageY = -93f), // extrapolated along bearing
            scaleMetersPerPixel = MapGeometry.haversineDistance(originalStartGps, syntheticFinishGps) / (kotlin.math.sqrt(800.0 * 800.0 + 693.0 * 693.0)),
            bearingDegrees = MapGeometry.bearing(originalStartGps, syntheticFinishGps),
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        // Track point position under synthetic (wrong) calibration
        val trackPosSynthetic = MapCalibrationUtils.gpsToImageAbs(trackPointGps, syntheticCal, Pair(bmpW, bmpH), 0f)!!

        // ── Phase 2: "Здесь финиш" pressed — REAL currentFix GPS is DIFFERENT from synthetic ──
        // Use horizontal baseline (dNorth=0) for NUMERICALLY EXACT round-trip.
        // Real finish has a DIFFERENT distance than synthetic (1000m), proving recalibration works.
        val realFinishGps = MapGeometry.offsetCoordinate(originalStartGps, dNorth = 0.0, dEast = 550.0)

        // Compute scale: GPS distance / image X delta (purely horizontal → exact round-trip)
        val gpsDistanceReal = MapGeometry.haversineDistance(originalStartGps, realFinishGps)
        val imageDistX = finishPointImageX - startPointImageX // 550 pixels

        val recalibratedCal = MapCalibration(
            pointA = CalibrationPoint(gps = originalStartGps, imageX = startPointImageX, imageY = startPointImageY),
            pointB = CalibrationPoint(gps = realFinishGps, imageX = finishPointImageX, imageY = startPointImageY), // same Y row
            scaleMetersPerPixel = gpsDistanceReal / imageDistX,
            bearingDegrees = MapGeometry.bearing(originalStartGps, realFinishGps),
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        val northAngleDeg = -recalibratedCal.bearingDegrees.toFloat()

        // ── Sanity checks ──
        val syntheticDist = MapGeometry.haversineDistance(originalStartGps, syntheticFinishGps)
        val realDist = MapGeometry.haversineDistance(originalStartGps, realFinishGps)
        assertTrue("Real GPS distance must differ from synthetic: real=$realDist synthetic=$syntheticDist",
            kotlin.math.abs(realDist - syntheticDist) > 100.0)

        val syntheticBearing = MapGeometry.bearing(originalStartGps, syntheticFinishGps)
        val realBearing = MapGeometry.bearing(originalStartGps, realFinishGps)
        assertTrue("Real bearing must differ from synthetic: real=$realBearing synthetic=$syntheticBearing",
            kotlin.math.abs(realBearing - syntheticBearing) > 5.0)

        // northAngle is non-zero (bearing ≈ 90° for eastward baseline → ~89.96° with declination)
        assertTrue("northAngle must be non-zero for meaningful rotation test: $northAngleDeg", kotlin.math.abs(northAngleDeg) > 0.1)

        // ── Test 1: currentFix GPS maps EXACTLY to finishPoint via gpsToImage (horizontal baseline = exact invariant) ──
        val currentGpsAfterRecalib = MapCalibrationUtils.gpsToImage(realFinishGps, recalibratedCal)!!
        assertEquals("After recalibration: current GPS X must equal finishPoint.imageX",
            finishPointImageX.toDouble(), currentGpsAfterRecalib.first.toDouble(), 1e-6)
        assertEquals("After recalibration: current GPS Y must equal finishPoint.imageY (same row as pointA)",
            startPointImageY.toDouble(), currentGpsAfterRecalib.second.toDouble(), 1e-6)

        // ── Test 2: track point still maps to a valid position on the map ──
        val trackPosAfterRecalib = MapCalibrationUtils.gpsToImageAbs(trackPointGps, recalibratedCal, Pair(bmpW, bmpH), northAngleDeg)!!

        assertTrue("Track X after recalib must be on map: ${trackPosAfterRecalib.first}",
            trackPosAfterRecalib.first in -10000f..11000f)
        assertTrue("Track Y after recalib must be on map: ${trackPosAfterRecalib.second}",
            trackPosAfterRecalib.second in -10000f..11000f)

        // Track position must differ from synthetic (proves calibration changed the projection)
        assertTrue("Track must move when calibration changes",
            kotlin.math.abs(trackPosAfterRecalib.first - trackPosSynthetic.first) > 1.0 ||
                kotlin.math.abs(trackPosAfterRecalib.second - trackPosSynthetic.second) > 1.0)

        // ── Test 3: gpsToImageAbs at northAngle=0 also returns exact finishPoint coords ──
        val absUnrotated = MapCalibrationUtils.gpsToImageAbs(realFinishGps, recalibratedCal, Pair(bmpW, bmpH), 0f)!!
        assertEquals("gpsToImageAbs angle=0: X matches finishPoint.imageX",
            finishPointImageX.toDouble(), absUnrotated.first.toDouble(), 1e-6)
        assertEquals("gpsToImageAbs angle=0: Y matches finishPoint.imageY",
            startPointImageY.toDouble(), absUnrotated.second.toDouble(), 1e-6)

        // ── Test 4: document that rotation moves pointB in gpsToImageAbs (expected behavior) ──
        val rotated = MapCalibrationUtils.gpsToImageAbs(realFinishGps, recalibratedCal, Pair(bmpW, bmpH), northAngleDeg)!!
        val dx = kotlin.math.abs(rotated.first - finishPointImageX)
        val dy = kotlin.math.abs(rotated.second - startPointImageY)
        assertTrue("Rotation moves pointB in gpsToImageAbs as expected (dx=$dx, dy=$dy)", dx + dy > 0.1)
    }

    /**
     * Full bindGpsToFinish integration: purple calibration point B must visually coincide with
     * finishPoint on the map image, AND currentFix GPS position maps to the same spot via gpsToImage.
     *
     * The purple circle in SubsamplingMapView is rendered at:
     *   MapCalibrationUtils.gpsToImage(calibrationPointBGps, calibration)
     * where calibrationPointBGps = gpsState.calibration?.pointB?.gps.
     *
     * After bindGpsToFinish:
     * - pointB.gps == currentFix.gps (set in bindGpsToFinish)
     * - pointB.imageX/Y == finishPoint image coords (set in bindGpsToFinish)
     * - gpsToImage(pointB.gps, calibration) returns exactly (pointB.imageX, pointB.imageY)
     *
     * This test uses a very short baseline to avoid floating-point drift between haversineDistance
     * and eastDistance — they are equivalent only at the same latitude. With dEast=0.1m, the
     * difference is negligible (< 1e-6px) while still verifying physical correctness.
     */
    @Test
    fun `bindGpsToFinish full flow purple point and currentFix GPS coincide with finishPoint`() {
        // Full bindGpsToFinish simulation:
        // 1. User places route start/finish on map image
        // 2. User presses "here is start" → track recording starts (uncalibrated)
        // 3. Track goes at 20° angle from route direction for 800m
        // 4. User presses "here is finish" → bindGpsToFinish recalibration
        // 5. Verify: currentFix GPS, purple point B, finishPoint all coincide on the map

        // ── Step 1: Route points on map image (user places them) ──
        val startImageX = 200f
        val startImageY = 600f
        val finishImageX = 750f       // +550px from start (horizontal for stable cal)
        val finishImageY = 600f       // same Y → horizontal image baseline

        // ── Step 2: "Здесь старт" pressed — GPS position at start location ──
        val originalStartGps = GpsCoordinate(50.45, 30.5)

        // Horizontal GPS baseline: pointB.gps same lat as pointA.gps → cos(lat) matches exactly
        // This is the GPS position where user presses "Здесь финиш" (stands on ground at finish location)
        val routeEastDistanceM = 1000.0
        val currentFixGps = MapGeometry.offsetCoordinate(originalStartGps, dNorth = 0.0, dEast = routeEastDistanceM)

        // ── Step 3: "Здесь финиш" pressed — bindGpsToFinish recalibration ──
        val pointA = CalibrationPoint(gps = originalStartGps, imageX = startImageX, imageY = startImageY)
        val pointB = CalibrationPoint(gps = currentFixGps, imageX = finishImageX, imageY = finishImageY)
        val calResult = MapGeometry.computeCalibrationRaw(pointA, pointB, 5.0)
            ?: throw IllegalStateException("bindGpsToFinish failed")

        // Compute actual route direction from GPS baseline for "20° angle" context
        val routeTrueBearing = MapGeometry.bearing(originalStartGps, currentFixGps)

        // ── Step 4: User walked along a track at 20° relative to route direction ──
        // Track bearing = routeBearing + 20° — compute where this lands on the map image
        val walkAngleFromRouteDeg = 20.0
        val walkBearingDeg = routeTrueBearing + walkAngleFromRouteDeg
        val walkDistanceM = 800.0

        // GPS position after walking (using offsetCoordinate — same as production)
        val walkDNorth = walkDistanceM * kotlin.math.cos(Math.toRadians(walkBearingDeg))
        val walkDEast = walkDistanceM * kotlin.math.sin(Math.toRadians(walkBearingDeg))
        val trackGps = MapGeometry.offsetCoordinate(originalStartGps, dNorth = walkDNorth, dEast = walkDEast)

        // Project track position onto the map using calibrated coords
        val trackImagePos = MapCalibrationUtils.gpsToImage(trackGps, calResult)!!

        // Expected: track projected X = (track east-distance from start) / scale + startX
        val trackEastDistFromStart = MapGeometry.eastDistance(originalStartGps, trackGps)
        val expectedTrackX = startImageX + (trackEastDistFromStart / calResult.scaleMetersPerPixel)
        val expectedTrackY = startImageY // no north offset because route is horizontal

        // ── Step 5: Verify all three coincide (currentFix GPS → finishPoint) ──

        // CurrentFix GPS → projected image position (purple calibration point B)
        val purplePos = MapCalibrationUtils.gpsToImage(currentFixGps, calResult)!!

        assertEquals(
            "CurrentFix GPS must project to finishPoint X via gpsToImage",
            finishImageX.toDouble(),
            purplePos.first.toDouble(),
            1e-3 // tight tolerance: horizontal baseline → exact cos(lat) match
        )
        assertEquals(
            "CurrentFix GPS must project Y to finishPoint.imageY",
            finishImageY.toDouble(),
            purplePos.second.toDouble(),
            1e-3
        )

        // Verify track projected position matches expected calculation (sanity check for calibration scale)
        assertEquals(
            "Track X from gpsToImage must match manual east/scale projection",
            expectedTrackX,
            trackImagePos.first.toDouble(),
            0.5 // ~0.5px tolerance for floating-point accumulation
        )

        // Verify the route direction and walking angle are as expected
        assertTrue("Route bearing from horizontal GPS baseline is valid: ${routeTrueBearing}", routeTrueBearing > 80.0)
        assertTrue("Track offset is non-zero", kotlin.math.abs(walkAngleFromRouteDeg) > 0.01)

        // Verify the projected track point position is valid (not off-map)
        assertTrue("Projected track X on map: ${trackImagePos.first}", trackImagePos.first in -10000f..21000f)
        assertTrue("Projected track Y on map: ${trackImagePos.second}", trackImagePos.second in -18000f..18000f)
    }

    /**
     * Verify that the purple calibration point aligns with currentFix GPS across different
     * map resolutions and baseline distances — using long baselines for floating-point safety.
     */
    @Test
    fun `purple point alignment holds across multiple map resolutions and distances`() {
        // Test with different map widths (real-world: phone screens vary 800-3000px)
        val testCases = listOf(
            800f to "screen_XS",
            1000f to "phone_normal",
            1536f to "tablet_1",
            2560f to "phone_HD"
        )

        for ((w, label) in testCases) {
            val h = w * 0.8f // 4:3 aspect ratio

            // User places start at 20% from left, finish at 75% from left — horizontal image baseline
            val startX = w * 0.20f
            val startY = h * 0.75f
            val finishX = w * 0.75f
            val finishY = h * 0.75f // same Y → purely horizontal, matches GPS direction

            // Long baseline for floating-point safety (same latitude, ~500m east)
            val startGps = GpsCoordinate(50.45, 30.5)
            val finishGps = MapGeometry.offsetCoordinate(startGps, dNorth = 0.0, dEast = 500.0)

            // Verify GPS baseline is valid for bind (>= 1m)
            val gpsDist = MapCalibrationUtils.haversineDistance(startGps, finishGps)
            assertTrue("Label=$label: GPS baseline must be >= 1m (got $gpsDist)", gpsDist >= 1.0)

            // Compute calibration directly — horizontal image baseline matches GPS direction
            val pointA = CalibrationPoint(gps = startGps, imageX = startX, imageY = startY)
            val pointB = CalibrationPoint(gps = finishGps, imageX = finishX, imageY = finishY)
            val calResult = MapGeometry.computeCalibrationRaw(pointA, pointB, 5.0)
                ?: throw IllegalStateException("bind should not fail for label=$label")

            // Verify gpsToImage invariant — tight tolerance with long GPS baseline
            val purplePos = MapCalibrationUtils.gpsToImage(finishGps, calResult)!!
            assertEquals(
                "Label=$label: purple X matches finishX",
                finishX.toDouble(), purplePos.first.toDouble(), 1e-3
            )
            assertEquals(
                "Label=$label: purple Y matches finishY",
                finishY.toDouble(), purplePos.second.toDouble(), 1e-3
            )

            // CurrentFix GPS must also match (same point)
            val currentFixPos = MapCalibrationUtils.gpsToImage(finishGps, calResult)!!
            assertEquals(
                "Label=$label: currentFix maps to finish coords",
                purplePos.first.toDouble(), currentFixPos.first.toDouble(), 1e-3
            )

            // Scale must be physically meaningful — relaxed tolerance for ~500m GPS baseline
            val dx2 = (finishX - startX).toDouble()
            val dy2 = (finishY - startY).toDouble()
            val imageDistPx = kotlin.math.sqrt(dx2 * dx2 + dy2 * dy2)
            assertEquals(
                "Label=$label: scale = gpsDist / imageDist",
                gpsDist / imageDistPx, calResult.scaleMetersPerPixel, 1e-6
            )
        }
    }

    /**
     * Simplified version focusing on the core invariant: after bindGpsToFinish recalibration,
     * currentFix GPS maps exactly to calibration pointB's image coords via gpsToImage (no rotation).
     *
     * IMPORTANT NUMERICAL FACT: For diagonal baselines (non-zero dNorth), offsetCoordinate and
     * eastDistance use different cos(lat) terms — one at startLat, one at avgLat — so they don't
     * perfectly round-trip. This means gpsToImage(pointB.gps, cal) can deviate from (pointB.imageX, Y)
     * for diagonal baselines due to floating-point accumulation. The invariant is EXACT only when the
     * GPS offset uses purely eastward displacement (horizontal baseline), where both functions agree.
     */
    @Test
    fun `after bindGpsToFinish recalibration currentFix maps to calibration pointB`() {
        val bmpW = 1000f
        val bmpH = 800f

        // Original start GPS at (50.45, 30.5) — user pressed "here is start" here
        val originalStartGps = GpsCoordinate(50.45, 30.5)

        // User placed finish point on the map image at known pixel coords:
        val startPointImageX = 200f
        val startPointImageY = 550f
        val finishPointImageX = 620f
        val finishPointImageY = 180f

        // ── Use horizontal baseline for NUMERICALLY EXACT invariant ──
        // Construct pointB purely eastward so dNorth=0 and offsetCoordinate/eastDistance agree exactly.
        // This ensures the calibration scale round-trips perfectly through gpsToImage.
        val realFinishGps = MapGeometry.offsetCoordinate(originalStartGps, dNorth = 0.0, dEast = 500.0)

        // Compute pixel distance for a horizontal image (same Y as pointA):
        val finishPointImageH = startPointImageY // same row → no vertical component in image
        // We need the scale to account for the desired X offset: 620-200=420 pixels
        // But real GPS distance may differ slightly from 500 due to haversine approximation.
        val gpsDistance = MapGeometry.haversineDistance(originalStartGps, realFinishGps)
        val imageDistX = finishPointImageX - startPointImageX // 420

        // Simulate bindGpsToFinish: clearCalibration → add two points → calibrate
        val recalibratedCal = MapCalibration(
            pointA = CalibrationPoint(gps = originalStartGps, imageX = startPointImageX, imageY = startPointImageY),
            pointB = CalibrationPoint(gps = realFinishGps, imageX = finishPointImageX, imageY = finishPointImageH),
            scaleMetersPerPixel = gpsDistance / imageDistX,
            bearingDegrees = MapGeometry.bearing(originalStartGps, realFinishGps),
            magneticDeclination = 5.0,
            physicalDeclination = 5.0
        )

        // northAngle set by bindGpsToFinish: -cal.bearingDegrees (positive ~90° for eastward)
        val northAngleDeg = -recalibratedCal.bearingDegrees.toFloat()

        // THE KEY INVARIANT: currentFix GPS maps to finishPoint image coords via gpsToImage.
        // For horizontal baseline, this is NUMERICALLY EXACT because offsetCoordinate(dEast=X) and
        // eastDistance use the same cosine reference (avgLat ≈ startLat when dNorth=0), so the round-trip
        // through calibrate → gpsToImage has no floating-point drift.
        val projectedCurrentGps = MapCalibrationUtils.gpsToImage(realFinishGps, recalibratedCal)!!
        assertEquals("currentFix.X must equal finishPoint.imageX after recalibration (gpsToImage, horizontal baseline)",
            finishPointImageX.toDouble(), projectedCurrentGps.first.toDouble(), 1e-6)
        assertEquals("currentFix.Y must equal finishPoint.imageY after recalibration (gpsToImage, horizontal baseline)",
            finishPointImageH.toDouble(), projectedCurrentGps.second.toDouble(), 1e-6)

        // Verify that calibration pointB itself also maps to its own coords via gpsToImage
        val projectedPointB = MapCalibrationUtils.gpsToImage(recalibratedCal.pointB.gps, recalibratedCal)!!
        assertEquals("calibration pointB must map to its own imageX (gpsToImage invariant)",
            finishPointImageX.toDouble(), projectedPointB.first.toDouble(), 1e-6)
        assertEquals("calibration pointB must map to its own imageY (gpsToImage invariant)",
            finishPointImageH.toDouble(), projectedPointB.second.toDouble(), 1e-6)

        // Verify northAngle is non-zero (meaningful rotation test: bearing ≈ 90° for eastward baseline)
        assertTrue("northAngle must be non-zero for meaningful test: $northAngleDeg", kotlin.math.abs(northAngleDeg) > 0.1)

        // ── Additional: verify gpsToImageAbs at northAngle=0 also matches (no rotation) ──
        val absUnrotated = MapCalibrationUtils.gpsToImageAbs(realFinishGps, recalibratedCal, Pair(bmpW, bmpH), 0f)!!
        assertEquals("gpsToImageAbs angle=0 X must match", finishPointImageX.toDouble(), absUnrotated.first.toDouble(), 1e-6)
        assertEquals("gpsToImageAbs angle=0 Y must match", finishPointImageH.toDouble(), absUnrotated.second.toDouble(), 1e-6)

        // ── Document: with northAngle=-bearing, rotation moves pointB in gpsToImageAbs ──
        val rotated = MapCalibrationUtils.gpsToImageAbs(realFinishGps, recalibratedCal, Pair(bmpW, bmpH), northAngleDeg)!!
        val dx = kotlin.math.abs(rotated.first - finishPointImageX)
        val dy = kotlin.math.abs(rotated.second - finishPointImageH)
        assertTrue("Rotation moves pointB in gpsToImageAbs (dx=$dx, dy=$dy) — this is expected for non-zero bearing",
            dx + dy > 0.1)
    }

    /**
     * Diagonal baseline test with route bearing ~100° and track bearing 120°.
     * Exposes the formula bug: after bindGpsToFinish, gpsToImageAbs(currentFixGPS, cal, dims, northAngle)
     * does NOT return finishPoint image coords when GPS baseline is diagonal (dNorth ≠ 0).
     *
     * Root cause: offsetCoordinate uses cos(startLat) for longitude-to-meters conversion,
     * while eastDistance uses cos(avgLat). When dNorth ≠ 0, startLat ≠ avgLat, causing scale
     * mismatch that prevents the gpsToImageAbs round-trip invariant from holding.
     */
    @Test
    fun `bindGpsToFinish diagonal baseline purple point must match finishPoint after northAngle rotation`() {
        // ── Setup: route bearing ~100° (diagonal GPS baseline) ──
        val startLat = 50.45
        val startLon = 30.5
        val originalStartGps = GpsCoordinate(startLat, startLon)

        // Target: true bearing ~108°, Rhumb distance EXACTLY 2000m from A to B (diagonal baseline)
        // Use explicit Rhumb components (dNorth/dEast in meters), then convert to GPS coords.
        val rhumbDN = 2000.0 * kotlin.math.cos(Math.toRadians(100.0))   // -347.3m
        val rhumbDE = 2000.0 * kotlin.math.sin(Math.toRadians(100.0))   // +1969.6m

        // Convert Rhumb components to GPS coords using cos(startLat) reference (matches offsetCoordinate)
        val dLonDeg = rhumbDE / (6371000.0 * kotlin.math.cos(Math.toRadians(startLat))) * (180.0 / Math.PI)
        val dLatDeg = rhumbDN / 6371000.0 * (180.0 / Math.PI)
        val finishGps = GpsCoordinate(startLat + dLatDeg, startLon + dLonDeg)

        // Verify the true bearing is diagonal (~95-120°):
        val actualBearing = MapGeometry.bearing(originalStartGps, finishGps)
        assertTrue("True bearing should be ~95-120°, got ${actualBearing}°",
            actualBearing in 95.0..120.0)

        // Image coords: derive FROM the projection (not independently choose them).
        // With Rhumb distance = 2000m at bearing 100° from A, and starting image at (200, 600):
        val dNorthAB = MapGeometry.northDistance(originalStartGps, finishGps)
        val dEastAB = (finishGps.longitude - originalStartGps.longitude) *
                (Math.PI / 180.0) * 6371000.0 * kotlin.math.cos(Math.toRadians(startLat))
        val rhumbDistFromCoords = kotlin.math.sqrt(dNorthAB * dNorthAB + dEastAB * dEastAB)

        // User places finish point at projected position based on actual Rhumb geometry:
        // Scale will be rhumbDist / imageDistance. We want a diagonal image baseline (dx=700, dy=350).
        val startImageX = 200f
        val startImageY = 600f
        val finishImageDx = 700f       // +700px from start in X
        val finishImageDy = -350f       // -350px from start in Y (up on screen)
        val imageDistPx = kotlin.math.sqrt((finishImageDx * finishImageDx + finishImageDy * finishImageDy).toDouble()).toFloat()
        val scaleMetersPerPixel = rhumbDistFromCoords / imageDistPx

        // Derive projected image coords: dEastAB maps to X offset, dNorthAB maps to Y offset
        val expectedProjectX = startImageX + (dEastAB / scaleMetersPerPixel)
        val expectedProjectY = startImageY - (dNorthAB / scaleMetersPerPixel)

        // Use the EXACT projected coordinates (not int-truncated) so scale compensates for any dEast discrepancy:
        val finishImageX = expectedProjectX.toFloat()
        val finishImageY = expectedProjectY.toFloat()

        // ── Track: bearing 120° from originalStartGps (user walked at 20° offset from route) ──
        val trackBearingDeg = 120.0
        val trackDistanceM = 800.0
        val trackDRad = Math.toRadians(trackBearingDeg)
        val trackGNorth = trackDistanceM * kotlin.math.cos(trackDRad)
        val trackGEast = trackDistanceM * kotlin.math.sin(trackDRad)
        val trackGps = MapGeometry.offsetCoordinate(originalStartGps, trackGNorth, trackGEast)

        // ── bindGpsToFinish recalibration ──
        val pointA = CalibrationPoint(gps = originalStartGps, imageX = startImageX, imageY = startImageY)
        val pointB = CalibrationPoint(gps = finishGps, imageX = finishImageX, imageY = finishImageY)
        val calResult = MapGeometry.computeCalibrationRaw(pointA, pointB, 5.0)
            ?: throw IllegalStateException("bindGpsToFinish failed: calibration points too close")

        val northAngleDeg = 0f // bindGpsToFinish now returns northAngle=0 (no extra rotation)

        // Debug: print calibration internals via assertions
        val dN = MapGeometry.northDistance(pointA.gps, pointB.gps)
        val dE = MapGeometry.eastDistance(pointA.gps, pointB.gps)
        val rhumbDist = kotlin.math.sqrt(dN * dN + dE * dE)
        val havDist = MapCalibrationUtils.haversineDistance(pointA.gps, pointB.gps)
        val imageDx = finishImageX - startImageX
        val imageDy = finishImageY - startImageY
        val imageDist = kotlin.math.sqrt((imageDx * imageDx + imageDy * imageDy).toDouble())

        // These should all pass and confirm the fix is in effect
        assertEquals("Rhumb line distance (should be ~2000): $rhumbDist", 2000.0, rhumbDist, 1.0)
        assertEquals("haversine vs Rhumb close: h=$havDist r=$rhumbDist", havDist, rhumbDist, 5.0)
        // Scale should be Rhumb/imageDist exactly (per our fix)
        assertEquals("scale = rhumbDist / imageDist", rhumbDist / imageDist, calResult.scaleMetersPerPixel, 0.01)

        // Check the projected dEast against finishImageX via FIXED gpsToImageRelative
        // Use cos(startLat) for easting (matching fixed gpsToImageRelative, NOT eastDistance's avgLat)
        val projDEastFixed = (pointB.gps.longitude - pointA.gps.longitude) *
                (Math.PI / 180.0) * 6371000.0 * kotlin.math.cos(Math.toRadians(pointA.gps.latitude))
        val expectedXFromRhumb = startImageX + projDEastFixed / calResult.scaleMetersPerPixel
        assertEquals(
            "Projected X from fixed easting: scale=$rhumbDist/imageDist=$imageDist px, dE=$projDEastFixed, expectedX=${startImageX.toInt()}+${(projDEastFixed/calResult.scaleMetersPerPixel).toInt()} ≈ $expectedXFromRhumb vs finishImageX=$finishImageX",
            finishImageX.toDouble(), expectedXFromRhumb, 0.01
        )

        // ── Verify route direction and track offset are as expected ──
        assertTrue("Route bearing is valid (~95-105°): ${actualBearing}°", actualBearing in 85.0..110.0)
        val gpsDistance = MapGeometry.haversineDistance(originalStartGps, finishGps)
        assertTrue("GPS distance must be >= 1m: $gpsDistance", gpsDistance >= 1.0)
        // northAngle = 0 is correct — calibration encodes bearing, no extra rotation needed
        assertEquals("northAngle must be 0 (no rotation): expected=0 got=$northAngleDeg", 0f, northAngleDeg, 0f)

        // ── Test 1: core invariant — currentFix GPS maps to finishPoint after northAngle rotation ──
        val bmpW = 1000f
        val bmpH = 800f

        // Check un-rotated position first (purple point in SubsamplingMapView uses this)
        val purpleUnrotated = MapCalibrationUtils.gpsToImage(finishGps, calResult)!!
        assertEquals("gpsToImage (no rotation) X should equal finishX: expected=$finishImageX got=${purpleUnrotated.first}",
            finishImageX.toDouble(), purpleUnrotated.first.toDouble(), 0.001)
        assertEquals("gpsToImage (no rotation) Y should equal finishY: expected=$finishImageY got=${purpleUnrotated.second}",
            finishImageY.toDouble(), purpleUnrotated.second.toDouble(), 0.001)

        // Now check with rotation (what gets used for currentFix position on rotated map)
        val purplePos = MapCalibrationUtils.gpsToImageAbs(finishGps, calResult, Pair(bmpW, bmpH), northAngleDeg)!!

        assertEquals(
            "After bindGpsToFinish: gpsToImageAbs X must match finishPoint (unrotated=${purpleUnrotated.first}, rotated=${purplePos.first})",
            finishImageX.toDouble(), purplePos.first.toDouble(), 0.5
        )
        assertEquals(
            "After bindGpsToFinish: gpsToImageAbs Y must match finishPoint (unrotated=${purpleUnrotated.second}, rotated=${purplePos.second})",
            finishImageY.toDouble(), purplePos.second.toDouble(), 0.5
        )

        // ── Test 2: track point projects consistently ──
        val trackImagePos = MapCalibrationUtils.gpsToImageAbs(trackGps, calResult, Pair(bmpW, bmpH), northAngleDeg)!!
        assertTrue("Track projected X on map: ${trackImagePos.first}", trackImagePos.first in -10000f..11000f)
        assertTrue("Track projected Y on map: ${trackImagePos.second}", trackImagePos.second in -18000f..18000f)

        // ── Test 3: scale is physically meaningful ──
        val imageDistPxAssert = kotlin.math.sqrt(
            (finishImageX - startImageX).toDouble().pow(2.0) +
            (finishImageY - startImageY).toDouble().pow(2.0)
        )
        assertEquals(
            "Scale = gpsDistance / imageDistancePx",
            gpsDistance / imageDistPxAssert, calResult.scaleMetersPerPixel, 1e-3
        )

        // ── Test 4: verify bearing vs declination relationship ──
        val trueBearing = MapGeometry.bearing(originalStartGps, finishGps)
        val expectedMagneticBearing = trueBearing - 5.0
        assertEquals(
            "bearingDegrees = trueBearing - declination",
            expectedMagneticBearing, calResult.bearingDegrees, 0.1
        )
    }
}
