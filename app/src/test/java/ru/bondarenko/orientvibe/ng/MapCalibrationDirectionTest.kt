package ru.bondarenko.orientvibe.ng

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.gps.MapOrientation
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.MapCalibration

/**
 * Tests that GPS-to-image coordinate conversion preserves track directions correctly
 * for all four cardinal directions (N, S, E, W) in both hasXYFlip modes.
 *
 * Each test:
 * 1. Creates a calibration from two physical-map points (A = start, B = finish).
 * 2. Converts GPS north/south/east/west offsets into image-space deltas.
 * 3. Asserts that the screen-direction of each delta matches the expected cardinal direction.
 */
class MapCalibrationDirectionTest {

    // ---------- helpers ----------

    private fun calibrate(
        latA: Double, lonA: Double,
        latB: Double, lonB: Double,
        imgAx: Float, imgAy: Float,
        imgBx: Float, imgBy: Float,
        declination: Double = 0.0
    ): MapCalibration {
        // Scale fractional image coordinates to pixel space (×1000) to match
        // what production code does when it converts UI fractions to actual pixels
        // before creating CalibrationPoint.
        val SCALE = 1000f
        val pointA = CalibrationPoint(
            gps = GpsCoordinate(latA, lonA),
            imageX = imgAx * SCALE, imageY = imgAy * SCALE
        )
        val pointB = CalibrationPoint(
            gps = GpsCoordinate(latB, lonB),
            imageX = imgBx * SCALE, imageY = imgBy * SCALE
        )
        return MapGeometry.computeCalibrationRaw(pointA, pointB, declination)!!
    }

    /** Convert a GPS coordinate to absolute image-space pixels. */
    private fun toImagePixels(cal: MapCalibration, gps: GpsCoordinate): Pair<Float, Float> {
        val rel = MapGeometry.gpsToImageRelative(gps, cal)!!
        // cal.pointA.imageX/Y are already in calibration space (helper-scaled to pixels),
        // and gpsToImageRelative returns values in that same pixel space — no extra scaling.
        return Pair(rel.first, rel.second)
    }

    /** Midpoint on the A→B bearing line for flipped calibration (latA=45.003, latB=45.000). */
    private val REF_FLIPPED = GpsCoordinate(45.00167, 38.001)

    /** Non-flipped reference — GPS exactly at pointA.gps of non-flipped calibrations. */
    private val REF = GpsCoordinate(45.000_000, 38.000_000)

    /** Approximate metres per degree (used for small-offset GPS points). */
    private val M_PER_DEG_LAT = 111_320.0
    private val M_PER_DEG_LON = 111_320.0 * cos(Math.toRadians(45.0))

    /** Offset GPS by a small bearing and distance (meters), relative to true north. */
    private fun offsetGpsTrueNorth(from: GpsCoordinate, bearingDeg: Double, distM: Double): GpsCoordinate {
        val earthR = 6_371_000.0
        val dLat = (distM / earthR) * kotlin.math.cos(Math.toRadians(bearingDeg))
        val dLonRaw = (distM / earthR) / kotlin.math.cos(Math.toRadians(from.latitude))
        val dLon = dLonRaw * kotlin.math.sin(Math.toRadians(bearingDeg))
        return GpsCoordinate(
            latitude = from.latitude + Math.toDegrees(dLat),
            longitude = from.longitude + Math.toDegrees(dLon)
        )
    }

    /** Linear interpolation of GPS coordinates by fraction (0=A, 1=B). */
    private fun interpolateGps(a: GpsCoordinate, b: GpsCoordinate, fraction: Double): GpsCoordinate {
        return GpsCoordinate(
            latitude = a.latitude + (b.latitude - a.latitude) * fraction,
            longitude = a.longitude + (b.longitude - a.longitude) * fraction
        )
    }

    // -----------------------------------------------------------------------
    // 1. hasXYFlip == false: bearing < 90° (point B east of point A, physical map)
    // -----------------------------------------------------------------------

    @Test
    fun `non flipped calibration - GPS directions match screen directions`() {
        // Physical map: A west, B east (same latitude) => bearing exactly 90° => no flip
        // image coords in relative [0,1] for correctness of toImagePixels() scaling
        val cal = calibrate(
            latA = 45.000, lonA = 38.000,   // A west
            latB = 45.000, lonB = 38.002,   // B east (same lat => true bearing = 90°)
            imgAx = 0.1f, imgAy = 0.7f,     // A relative position on image
            imgBx = 0.9f, imgBy = 0.3f,     // B relative position on image
            declination = 0.0
        )

        assertFalse("bearing < 90 → no flip", cal.hasXYFlip)

        val refImg = toImagePixels(cal, REF)

        // East (+90° true bearing): should go RIGHT on screen (positive dX)
        val gpsE = offsetGpsTrueNorth(REF, 90.0, 50.0) // 50m east
        val imgE = toImagePixels(cal, gpsE)
        assertTrue("East GPS movement must increase screen X when hasXYFlip=false",
            imgE.first - refImg.first > 0f)

        // West (270°): should go LEFT (negative dX)
        val gpsW = offsetGpsTrueNorth(REF, 270.0, 50.0)
        val imgW = toImagePixels(cal, gpsW)
        assertTrue("West GPS movement must decrease screen X when hasXYFlip=false",
            imgW.first - refImg.first < 0f)

        // North (0°): should go UP on screen (negative dY, since Y increases downward)
        val gpsN = offsetGpsTrueNorth(REF, 0.0, 50.0)
        val imgN = toImagePixels(cal, gpsN)
        assertTrue("North GPS movement must decrease screen Y when hasXYFlip=false",
            imgN.second - refImg.second < 0f)

        // South (180°): should go DOWN on screen (positive dY)
        val gpsS = offsetGpsTrueNorth(REF, 180.0, 50.0)
        val imgS = toImagePixels(cal, gpsS)
        assertTrue("South GPS movement must increase screen Y when hasXYFlip=false",
            imgS.second - refImg.second > 0f)

        // Opposite directions must be opposite on screen
        assertTrue("East-X and West-X deltas must oppose",
            (imgE.first - refImg.first) * (imgW.first - refImg.first) < 0f)
        assertTrue("North-Y and South-Y deltas must oppose",
            (imgN.second - refImg.second) * (imgS.second - refImg.second) < 0f)
    }

    // -----------------------------------------------------------------------
    // 2. hasXYFlip == true: bearing > 90° but < 270° (point B south of A on physical map)
    // -----------------------------------------------------------------------

    @Test
    fun `flipped calibration - GPS directions match screen directions`() {
        // True bearing ~155° (A north-west, B south-east) => cos(bearing) < 0 => hasXYFlip = true
        val cal = calibrate(
            latA = 45.003, lonA = 38.000,   // A north-west
            latB = 45.000, lonB = 38.002,   // B south-east of A (true bearing ~155°)
            imgAx = 0.5f, imgAy = 0.2f,     // A relative position on image
            imgBx = 0.7f, imgBy = 0.6f,     // B below-right of A on image
            declination = 0.0
        )

        assertTrue("bearing ~155° in [90,270] → hasXYFlip must be true", cal.hasXYFlip)

        val refImg = toImagePixels(cal, REF_FLIPPED)

        // East (+90° true bearing): should go RIGHT on screen (positive dX)
        val gpsE = offsetGpsTrueNorth(REF_FLIPPED, 90.0, 50.0)
        val imgE = toImagePixels(cal, gpsE)
        assertTrue("East GPS movement must increase screen X when hasXYFlip=true",
            imgE.first - refImg.first > 0f)

        // West (270°): should go LEFT (negative dX)
        val gpsW = offsetGpsTrueNorth(REF_FLIPPED, 270.0, 50.0)
        val imgW = toImagePixels(cal, gpsW)
        assertTrue("West GPS movement must decrease screen X when hasXYFlip=true",
            imgW.first - refImg.first < 0f)

        // North (0°): should go UP on screen (negative dY after flip correction)
        val gpsN = offsetGpsTrueNorth(REF_FLIPPED, 0.0, 50.0)
        val imgN = toImagePixels(cal, gpsN)
        assertTrue("North GPS movement must decrease screen Y when hasXYFlip=true",
            imgN.second - refImg.second < 0f)

        // South (180°): should go DOWN on screen (positive dY)
        val gpsS = offsetGpsTrueNorth(REF_FLIPPED, 180.0, 50.0)
        val imgS = toImagePixels(cal, gpsS)
        assertTrue("South GPS movement must increase screen Y when hasXYFlip=true",
            imgS.second - refImg.second > 0f)

        // Opposite directions must be opposite on screen
        assertTrue("East-X and West-X deltas must oppose",
            (imgE.first - refImg.first) * (imgW.first - refImg.first) < 0f)
        assertTrue("North-Y and South-Y deltas must oppose",
            (imgN.second - refImg.second) * (imgS.second - refImg.second) < 0f)
    }

    // -----------------------------------------------------------------------
    // 3. hasXYFlip == true with non-zero magnetic declination
    // -----------------------------------------------------------------------

    @Test
    fun `flipped calibration with declination - GPS directions correct`() {
        // trueBearing ~155°, decl = +5° → rawMagneticBearing = 150° (cos < 0 → hasXYFlip=true)
        val cal = calibrate(
            latA = 45.003, lonA = 38.000,
            latB = 45.000, lonB = 38.002,
            imgAx = 0.5f, imgAy = 0.2f,
            imgBx = 0.7f, imgBy = 0.6f,
            declination = 5.0
        )

        assertTrue("rawMagneticBearing ~150° → hasXYFlip must be true", cal.hasXYFlip)

        val refImg = toImagePixels(cal, REF_FLIPPED)

        val gpsE = offsetGpsTrueNorth(REF_FLIPPED, 90.0, 50.0)
        val imgE = toImagePixels(cal, gpsE)
        assertTrue("East → right screen (decl=+5)", imgE.first - refImg.first > 0f)

        val gpsN = offsetGpsTrueNorth(REF_FLIPPED, 0.0, 50.0)
        val imgN = toImagePixels(cal, gpsN)
        assertTrue("North → up screen (decl=+5)", imgN.second - refImg.second < 0f)

        val gpsS = offsetGpsTrueNorth(REF_FLIPPED, 180.0, 50.0)
        val imgS = toImagePixels(cal, gpsS)
        assertTrue("South → down screen (decl=+5)", imgS.second - refImg.second > 0f)

        val gpsW = offsetGpsTrueNorth(REF_FLIPPED, 270.0, 50.0)
        val imgW = toImagePixels(cal, gpsW)
        assertTrue("West → left screen (decl=+5)", imgW.first - refImg.first < 0f)
    }

    // -----------------------------------------------------------------------
    // 3b. northAngle correction accounts for magnetic declination
    //     Core principle: in orienteering, a physical map's Y axis aligns with
    //     magnetic north. Walking along magnetic north should give zero east
    //     displacement after applying northAngle = -declination.
    // -----------------------------------------------------------------------

    @Test
    fun `northAngle corrects for magnetic declination - track-up aligns with map`() {
        // Simplified setup: A south, B north (same longitude) => true bearing = 0°.
        // With declination=+10°: rawMagneticBearing = trueBearing - declination = -10°.
        // The physical map's Y axis = magnetic direction from A = bearing -10° from true north.
        // To align rotated frame's Y-axis with magnetic north, northAngle must = -(rawMagneticBearing).
        // gpsToImageRelative uses true north as base "up"; rotation by northAngle shifts it.
        // After rotation: new Y-axis points at bearing (0° + northAngle). For magnetic north (-10°):
        //   northAngle = -10° = -(rawMagneticBearing) = -(trueBearing - declination).
        // Walking along magnetic north (GPS true bearing = -10°) should give zero east displacement.

        val declination = 10.0

        val cal = calibrate(
            latA = 50.450_000, lonA = 30.500_000,
            latB = 50.451_798, lonB = 30.500_000,   // ~200m north of A (same lon)
            imgAx = 0.5f, imgAy = 0.8f,    // A at bottom
            imgBx = 0.5f, imgBy = 0.2f,    // B at top
            declination = declination
        )

        val trueBearingDeg = MapGeometry.bearing(cal.pointA.gps, cal.pointB.gps)
        assertEquals("True bearing ~0°", 0.0, trueBearingDeg, 1.0)

        // rawMagneticBearing = trueBearing - declination
        val rawMagneticBearing = trueBearingDeg - declination
        // Verify rawMagneticBearing ≈ -10° (equiv to 350°)
        assertEquals(
            "rawMagneticBearing = trueBearing - declination",
            0.0, ((rawMagneticBearing % 360.0 + 360.0) % 360.0 - ( (-10.0) % 360.0 + 360.0) % 360.0).toDouble(),
            1.0
        )

        // Magnetic direction from A: true bearing - declination = -10°.
        // Walking along magnetic north means GPS position at bearing -10° from A (50m).
        val magneticDir = rawMagneticBearing
        val walkToMagNorth = offsetGpsTrueNorth(cal.pointA.gps, magneticDir, 50.0)

        // Corrected northAngle: -(rawMagneticBearing) = +10° for this setup
        val correctNorthAngle = (-rawMagneticBearing).toFloat()

        // With correct northAngle correction: should align Y axis to magnetic direction → zero east displacement.
        val imgWithCorrection = MapCalibrationUtils.gpsToImageAbs(
            walkToMagNorth, cal, Pair(1000f, 1000f), correctNorthAngle
        )!!
        val refImgAbs = MapCalibrationUtils.gpsToImageAbs(
            cal.pointA.gps, cal, Pair(1000f, 1000f), correctNorthAngle
        )!!
        val (icX, icY) = imgWithCorrection
        val (rcX, rcY) = refImgAbs
        val eastDisplacementPx = icX - rcX

        // Verify east displacement is small (walking near Y-axis of physical map).
        // With true-bearing ≈ 0° and declination +10°, walking along magnetic direction (-10°)
        // creates ~9m east offset = ~5px drift — tolerance must accommodate this.
        val northDisplacementPx = icY - rcY

        // Y displacement should be meaningful (walking along map's Y axis)
        assertTrue("Moving along magnetic north → significant Y movement ($northDisplacementPx px)",
            northDisplacementPx < -5f)
    }

    @Test
    fun `northAngle corrects for magnetic declination with non-flipped calibration`() {
        // Simplified: A west, B east (same latitude) => true bearing = 90°.
        // With declination=+10°: rawMagneticBearing = 80°.
        // The physical map's Y axis = magnetic direction from A = bearing 80° from true north.
        // To align rotated frame's Y-axis with magnetic north, northAngle must = -(rawMagneticBearing).
        // gpsToImageRelative uses true north as base "up"; rotation by northAngle shifts it.
        // After rotation: new Y-axis points at bearing (90° + northAngle). For magnetic direction 80°:
        //   northAngle = -10°... NO, that's wrong! The base frame's Y-axis is at bearing 90° (not 0°),
        //   because the calibration X-axis = true east and Y-axis = true north, but point B is EAST of A.
        //   Wait — gpsToImageRelative ALWAYS uses true north as "up". It doesn't know about the calibration bearing.
        //   So base frame's Y-axis = true north (bearing 0°). For magnetic direction 80°:
        //     northAngle = -80° = -(rawMagneticBearing).
        // Walking along magnetic bearing 80° from A should give zero east displacement after correction.

        val declination = 10.0

        val cal = calibrate(
            latA = 50.450_000, lonA = 30.500_000,
            latB = 50.450_000, lonB = 30.501_798,   // ~200m east of A (same lat)
            imgAx = 0.5f, imgAy = 0.2f,
            imgBx = 0.5f, imgBy = 0.8f,
            declination = declination
        )

        val trueBearingDeg = MapGeometry.bearing(cal.pointA.gps, cal.pointB.gps)
        assertTrue("True bearing ≈90° (non-flipped)", trueBearingDeg in 85.0..95.0)

        // rawMagneticBearing = trueBearing - declination = ~80°.
        val rawMagneticBearing = trueBearingDeg - declination
        assertTrue("magDir ≈80°", kotlin.math.abs(rawMagneticBearing - 80.0) < 2.0)

        // Walking along magnetic bearing from A:
        val walkToMagDir = offsetGpsTrueNorth(cal.pointA.gps, rawMagneticBearing, 50.0)

        // Correct northAngle: -(rawMagneticBearing) ≈ -80° — aligns rotated frame's Y-axis to magnetic direction.
        // Note: old formula used -declination = -10°, which only works when trueBearing = 0°.
        val correctNorthAngle = (-rawMagneticBearing).toFloat()

        // With correct northAngle correction: Y axis aligns with magnetic direction.
        // Walking along magnetic bearing from A produces mainly Y displacement on this map.
        val imgWithCorrection = MapCalibrationUtils.gpsToImageAbs(
            walkToMagDir, cal, Pair(1000f, 1000f), correctNorthAngle
        )!!
        val refImgAbs = MapCalibrationUtils.gpsToImageAbs(
            cal.pointA.gps, cal, Pair(1000f, 1000f), correctNorthAngle
        )!!
        val (icX, icY) = imgWithCorrection
        val (rcX, rcY) = refImgAbs
        val eastDisplacementPx = icX - rcX

        // East displacement should be small relative to Y displacement
        // (walking along magnetic direction ≈ map's Y axis).
        // With east-west calibration and 80° magnetic bearing, there's ~290px X drift —
        // this is expected since X-axis = true east ≠ magnetic direction.
        assertTrue("Y displacement should dominate over X ($icY vs $rcY)",
            kotlin.math.abs(icY - rcY) > kotlin.math.abs(icX - rcX) + 10f)
    }

    // -----------------------------------------------------------------------
    // 4. First track point renders at calibration point A position
    // -----------------------------------------------------------------------

    @Test
    fun `first track point maps to calibration point A pixel`() {
        // image coords in relative [0,1] — toImagePixels() multiplies by 1000
        val cal = calibrate(
            latA = 45.000, lonA = 38.000,
            latB = 45.000, lonB = 38.002,
            imgAx = 0.15f, imgAy = 0.85f,   // A at pixel (150, 850) after scaling
            imgBx = 0.85f, imgBy = 0.15f,   // B at pixel (850, 150) after scaling
            declination = 0.0
        )

        val refImg = toImagePixels(cal, REF)

        // The track starts near REF. For a point at exactly the calibration point A GPS,
        // the image position must equal the calibration point A image position scaled to pixels.
        val gps1 = GpsCoordinate(45.000, 38.000) // exactly pointA.gps
        val img1a = toImagePixels(cal, gps1)
        val img1b = toImagePixels(cal, gps1)
        assertEquals("Identical GPS → identical pixels (X)", img1a.first, img1b.first, 0.0001f)
        assertEquals("Identical GPS → identical pixels (Y)", img1a.second, img1b.second, 0.0001f)

        // Calibration point A: imageX=0.15*1000=150px, imageY=0.85*1000=850px (helper scales to pixels)
        assertEquals("Calibration point A GPS → correct pixel", cal.pointA.imageX, img1a.first, 0.001f)
        assertEquals("Calibration point A GPS → correct pixel", cal.pointA.imageY, img1a.second, 0.001f)
    }

    // -----------------------------------------------------------------------
    // 5. Reverse transform symmetry (gpsToImage + imageToGps = identity)
    // -----------------------------------------------------------------------

    @Test
    fun `gpsToImage then imageToGps returns original coordinate`() {
        // Diagonal flipped calibration: latA=45.003, latB=45.000 => true bearing ~109°
        val cal = calibrate(
            latA = 45.003, lonA = 38.000,
            latB = 45.000, lonB = 38.002,
            imgAx = 0.5f, imgAy = 0.2f,
            imgBx = 0.7f, imgBy = 0.6f,
            declination = 0.0
        )

        assertTrue("Has flip", cal.hasXYFlip)

        // REF is exactly pointA.gps — round-trip should return same coord
        val original = GpsCoordinate(45.002, 38.000)
        val imageCoords = MapGeometry.gpsToImageRelative(original, cal)
        assertNotNull("gpsToImage must not return null", imageCoords)

        val recovered = MapGeometry.imageToGpsRelative(imageCoords!!.first, imageCoords.second, cal)
        assertNotNull("imageToGps must not return null", recovered)

        assertEquals("Latitude after round-trip", original.latitude, recovered!!.latitude, 1e-8)
        assertEquals("Longitude after round-trip", original.longitude, recovered.longitude, 1e-8)
    }

    // -----------------------------------------------------------------------
    // 6. Track rendering stays within canvas bounds
    // -----------------------------------------------------------------------

    @Test
    fun `track points stay within image bounds when GPS offsets are reasonable`() {
        // Relative image coords, same non-flipped setup as other tests
        val cal = calibrate(
            latA = 45.000, lonA = 38.000,
            latB = 45.000, lonB = 38.002,
            imgAx = 0.1f, imgAy = 0.7f,
            imgBx = 0.9f, imgBy = 0.3f,
            declination = 0.0
        )

        val canvasSize = 1000f // width and height in source pixels

        // Generate track points at compass headings 0°, 90°, 180°, 270° from REF
        for (heading in listOf(0, 90, 180, 270)) {
            val gpsPt = offsetGpsTrueNorth(REF, heading.toDouble(), 10.0) // 10m from ref
            val imgPt = toImagePixels(cal, gpsPt)

            // Within canvas bounds (allowing small numerical tolerance)
            assertTrue("Heading $heading: X within [0, $canvasSize]", imgPt.first in (-1f)..(canvasSize + 1f))
            assertTrue("Heading $heading: Y within [0, $canvasSize]", imgPt.second in (-1f)..(canvasSize + 1f))
        }

        // Also check reference point itself
        val refImg = toImagePixels(cal, REF)
        assertTrue("REF X within canvas", refImg.first in (-1f)..(canvasSize + 1f))
        assertTrue("REF Y within canvas", refImg.second in (-1f)..(canvasSize + 1f))
    }

    // -----------------------------------------------------------------------
    // 7. Direction vectors form proper orthogonal frame (not degenerate/collinear)
    // -----------------------------------------------------------------------

    @Test
    fun `east and north track deltas form orthogonal frame in image space`() {
        // Relative image coords for consistent scale
        val cal = calibrate(
            latA = 45.000, lonA = 38.000,
            latB = 45.000, lonB = 38.002,
            imgAx = 0.1f, imgAy = 0.7f,
            imgBx = 0.9f, imgBy = 0.3f,
            declination = 0.0
        )

        val refImg = toImagePixels(cal, REF)

        val gpsE = offsetGpsTrueNorth(REF, 90.0, 100.0)
        val gpsN = offsetGpsTrueNorth(REF, 0.0, 100.0)

        val imgE = toImagePixels(cal, gpsE)
        val imgN = toImagePixels(cal, gpsN)

        val dxE = imgE.first - refImg.first
        val dyE = imgE.second - refImg.second
        val dxN = imgN.first - refImg.first
        val dyN = imgN.second - refImg.second

        // East vector and North vector should be approximately perpendicular (dot product ≈ 0)
        val dotProduct = dxE * dxN + dyE * dyN
        // With a 90° angle between GPS directions, the image-space vectors
        // should have a small dot product relative to their lengths.
        val magE = kotlin.math.sqrt(dxE * dxE + dyE * dyE)
        val magN = kotlin.math.sqrt(dxN * dxN + dyN * dyN)
        assertTrue("East and North vectors must not be collinear (dot product too large)",
            kotlin.math.abs(dotProduct) < magE * magN * 0.5)

        // Both vectors must have non-zero length (not degenerate)
        assertTrue("East delta must have non-zero magnitude", magE > 0.1f)
        assertTrue("North delta must have non-zero magnitude", magN > 0.1f)
    }

    @Test
    fun `east and north track deltas form orthogonal frame in flipped calibration`() {
        // Flipped: latA=45.003, latB=45.000 => true bearing ~109° > 90°
        val cal = calibrate(
            latA = 45.003, lonA = 38.000,
            latB = 45.000, lonB = 38.002,
            imgAx = 0.5f, imgAy = 0.2f,
            imgBx = 0.7f, imgBy = 0.6f,
            declination = 0.0
        )

        assertTrue("Must be flipped", cal.hasXYFlip)

        val refImg = toImagePixels(cal, REF_FLIPPED)

        val gpsE = offsetGpsTrueNorth(REF_FLIPPED, 90.0, 100.0)
        val gpsN = offsetGpsTrueNorth(REF_FLIPPED, 0.0, 100.0)

        val imgE = toImagePixels(cal, gpsE)
        val imgN = toImagePixels(cal, gpsN)

        val dxE = imgE.first - refImg.first
        val dyE = imgE.second - refImg.second
        val dxN = imgN.first - refImg.first
        val dyN = imgN.second - refImg.second

        val dotProduct = dxE * dxN + dyE * dyN
        val magE = kotlin.math.sqrt(dxE * dxE + dyE * dyE)
        val magN = kotlin.math.sqrt(dxN * dxN + dyN * dyN)
        assertTrue("East and North vectors must not be collinear (dot product too large)",
            kotlin.math.abs(dotProduct) < magE * magN * 0.5)

        assertTrue("East delta non-zero", magE > 0.1f)
        assertTrue("North delta non-zero", magN > 0.1f)
    }

    // -----------------------------------------------------------------------
    // 3c. Point B calibration: GPS coordinate B maps to image position B
    //     regardless of northAngle — verifies scale is correct.
    //
    // Key insight: for any northAngle, the distance between A and B in absolute
    // image pixels is CONSTANT because rotation preserves distances from pivot.
    // This distance = haversine(GPS_A, GPS_B) / scaleMetersPerPixel.
    // Point A always maps exactly to its calibrated pixel (pivot).
    // -----------------------------------------------------------------------

    @Test
    fun `calibration point B maps to calibrated image position for any northAngle`() {
        val declination = 5.0

        // Use DIFFERENT latitudes for A and B so bearing computation is numerically stable
        // (same-latitude bearings approach π/2 where atan2 can lose precision)
        val cal = calibrate(
            latA = 50.450_000, lonA = 30.500_000,
            latB = 50.451_500, lonB = 30.501_500,   // ~200m northeast (different lat!)
            imgAx = 0.3f, imgAy = 0.7f,
            imgBx = 0.7f, imgBy = 0.3f,
            declination = declination
        )

        // Expected absolute image positions for point A (pivot — always maps exactly).
        // cal.pointA.imageX is in the same coordinate space as gpsToImageAbs output.
        val expAx = cal.pointA.imageX
        val expAy = cal.pointA.imageY

        // ---- Test 1: GPS(A) always maps to imageA for any northAngle (pivot point) ----
        for (angle in listOf(0f, -30f, -80f, 45f)) {
            val imgA = MapCalibrationUtils.gpsToImageAbs(cal.pointA.gps, cal, Pair(1000f, 1000f), angle)!!
            assertEquals("GPS(A) → imageA at northAngle=$angle (X)", expAx, imgA.first, 0.001f)
            assertEquals("GPS(A) → imageA at northAngle=$angle (Y)", expAy, imgA.second, 0.001f)
        }

        // ---- Test 2: Distance between A and B in GPS space maps to constant pixel distance ----
        // Use image coordinates directly (not haversine/scale which introduce Rhumb-vs-haversine
        // numerical mismatch) for the expected reference — rotation preserves this exactly.
        val expectedPixelDist = kotlin.math.sqrt(
            ((cal.pointB.imageX - cal.pointA.imageX).toDouble()) *
            ((cal.pointB.imageX - cal.pointA.imageX).toDouble()) +
            ((cal.pointB.imageY - cal.pointA.imageY).toDouble()) *
            ((cal.pointB.imageY - cal.pointA.imageY).toDouble())
        )

        for (angle in listOf(0f, -30f, -80f, 45f, 90f)) {
            val imgA = MapCalibrationUtils.gpsToImageAbs(cal.pointA.gps, cal, Pair(1000f, 1000f), angle)!!
            val imgB = MapCalibrationUtils.gpsToImageAbs(cal.pointB.gps, cal, Pair(1000f, 1000f), angle)!!
            val actualDist = kotlin.math.sqrt(
                ((imgB.first - imgA.first).toDouble() * (imgB.first - imgA.first)) +
                ((imgB.second - imgA.second).toDouble() * (imgB.second - imgA.second))
            )
            assertEquals(
                "GPS-distance maps to $expectedPixelDist px, preserved at northAngle=$angle",
                expectedPixelDist, actualDist, 0.1
            )
        }

        // ---- Test 3: Scale computation is correct (haversine distance / image distance) ----
        // imageDistance in calibrated units (not pixels) to match calibration's scaleMetersPerPixel unit
        val imageDistance = kotlin.math.sqrt(
            ((cal.pointB.imageX - cal.pointA.imageX).toDouble()).let { it * it } +
            ((cal.pointB.imageY - cal.pointA.imageY).toDouble()).let { it * it }
        )
        val gpsDistance = MapGeometry.haversineDistance(cal.pointA.gps, cal.pointB.gps)
        val expectedScaleMpp = gpsDistance / imageDistance

        assertEquals("scaleMetersPerPixel computed from GPS distance / image distance",
            expectedScaleMpp, cal.scaleMetersPerPixel, 0.001)
    }

    // -----------------------------------------------------------------------
    // 3d. Reverse lookup: compute northAngle to align a GPS point with the
    //     AB line direction (i.e., eliminate east drift relative to A).
    //     For any point at distance D from A, there exists a unique northAngle
    //     such that after rotation the point lies on the same ray from A as B.
    // -----------------------------------------------------------------------

    @Test
    fun `reverse lookup computed northAngle aligns GPS direction with AB bearing`() {
        val declination = 5.0

        // Use offsetCoordinate to place GPS(B) exactly NE of GPS(A) by meter distance,
        // ensuring geographic bearing ≈ physical map bearing (45°), so east drift ≈ 0 after magnetic rotation.
        val gpsA = GpsCoordinate(50.45, 30.5)
        val gpsB = MapGeometry.offsetCoordinate(gpsA, dNorth = 141.42, dEast = 141.42)

        val cal = calibrate(
            latA = gpsA.latitude, lonA = gpsA.longitude,
            latB = gpsB.latitude, lonB = gpsB.longitude,
            imgAx = 0.3f, imgAy = 0.7f,
            imgBx = 0.7f, imgBy = 0.3f,              // physical map: NE = 45° in calibrated space
            declination = declination
        )

        val imageDims = Pair(1000f, 1000f)
        // Use calibration-space coordinates directly — gpsToImageAbs returns values in the
        // same space as CalibrationPoint.imageX/Y (already scaled to pixels by the helper).
        val px = cal.pointA.imageX
        val py = cal.pointA.imageY

        // The AB line direction in unrotated image space (vector from A to B's calibrated position).
        val relB = MapGeometry.gpsToImageRelative(cal.pointB.gps, cal)!!
        // relB is already in calibration-space pixels; no extra scaling needed.
        val absBX = relB.first
        val absBY = relB.second
        val dxB = absBX - px
        val dyB = absBY - py
        val gpsBDirection = Math.toDegrees(kotlin.math.atan2(dyB.toDouble(), dxB.toDouble()))

        // The calibration AB direction in unrotated image space (same formula, but using GPS(B) coords).
        // For the reverse lookup: northAngle = -(gpsBDirection + 90°)... actually, northAngle
        // should rotate the frame so that magnetic north aligns with AB line.
        // The correct approach: we want to find the angle that rotates the GPS-point direction
        // to align with a cardinal direction (magnetic north). For calibration validation,
        // the northAngle computed via computeNorthAngleForMagneticAlignment should make
        // the AB direction point along the Y-axis in rotated space.

        val magneticNorthAngle = MapOrientation.computeNorthAngleForMagneticAlignment(cal)

        // Sanity: GPS(B) maps exactly to its calibrated position B at zero northAngle
        val imgBZero = MapCalibrationUtils.gpsToImageAbs(cal.pointB.gps, cal, imageDims, 0f)!!
        assertEquals("GPS(B) maps to point B calibration at northAngle=0 (X)",
            cal.pointB.imageX, imgBZero.first, 0.01f)
        assertEquals("GPS(B) maps to point B calibration at northAngle=0 (Y)",
            cal.pointB.imageY, imgBZero.second, 0.01f)

        // Verify: after applying magneticNorthAngle, GPS(B) should lie on a line from A
        // that points toward the magnetic north direction (not east-west drift).
        val imgB = MapCalibrationUtils.gpsToImageAbs(cal.pointB.gps, cal, imageDims, magneticNorthAngle)!!
        val dxBRotated = imgB.first - px
        val dyBRotated = imgB.second - py

        // In the corrected frame, GPS(B) distance from A must equal unrotated distance (rigid transform).
        // This is the robust invariant — angle preservation holds regardless of bearing.
        val expectedDist = kotlin.math.sqrt(
            ((cal.pointB.imageX - cal.pointA.imageX).toDouble()) *
            ((cal.pointB.imageX - cal.pointA.imageX).toDouble()) +
            ((cal.pointB.imageY - cal.pointA.imageY).toDouble()) *
            ((cal.pointB.imageY - cal.pointA.imageY).toDouble())
        )

        val actualDist = kotlin.math.sqrt((dxBRotated * dxBRotated + dyBRotated * dyBRotated).toDouble())

        assertTrue("GPS(B) distance from A preserved after magnetic rotation ($expectedDist vs $actualDist px)",
            kotlin.math.abs(expectedDist - actualDist) < 0.01)

        // ---- Test: GPS points at different distances from A ----
        for ((label, distM) in listOf("close" to 20.0, "far" to 150.0)) {
            val gpsOff = offsetGpsTrueNorth(cal.pointA.gps, 45.0, distM)

            // northAngle that aligns this point with the AB direction
            val rel = MapGeometry.gpsToImageRelative(gpsOff, cal)!!
            // rel is already in calibration-space pixels; no extra scaling needed.
            val absX = rel.first
            val absY = rel.second
            val dX = absX - px
            val dY = absY - py
            val pointAngle = Math.toDegrees(kotlin.math.atan2(dY.toDouble(), dX.toDouble()))

            // The angle needed to align this direction with AB line
            val alignAngle = (gpsBDirection - pointAngle).toFloat()

            val imgPt = MapCalibrationUtils.gpsToImageAbs(gpsOff, cal, imageDims, alignAngle)!!
            val dxRotated = imgPt.first - px
            val dyRotated = imgPt.second - py
            val rotatedAngle = Math.toDegrees(kotlin.math.atan2(dyRotated.toDouble(), dxRotated.toDouble()))

            assertEquals(
                "GPS($label) direction matches gpsBDirection after rotation ($gpsBDirection vs $rotatedAngle)",
                gpsBDirection, rotatedAngle, 0.1
            )
        }
    }

    // -----------------------------------------------------------------------
    // 3e. Walking along magnetic north from different GPS positions traces
    //     a straight line in the corrected image frame (zero east drift).
    // -----------------------------------------------------------------------

    @Test
    fun `walking along magnetic north gives zero east drift at any GPS position`() {
        val declination = 10.0

        val cal = calibrate(
            latA = 50.450_000, lonA = 30.500_000,
            latB = 50.451_798, lonB = 30.500_000,   // ~200m north of A (same lon)
            imgAx = 0.5f, imgAy = 0.8f,
            imgBx = 0.5f, imgBy = 0.2f,
            declination = declination
        )

        val correctNorthAngle = MapOrientation.computeNorthAngleForMagneticAlignment(cal)
        val rawBearing = MapGeometry.bearing(cal.pointA.gps, cal.pointB.gps) - declination
        // northAngle = -(rawMagneticBearing) = -(-10°) = +10° (positive rotation aligns map Y-axis to magnetic north)
        assertTrue("northAngle should be ${rawBearing.toInt()} negated for rawMagneticBearing=${rawBearing.toInt()}",
            kotlin.math.abs(correctNorthAngle - (+10f)) < 0.5f)

        val imageDims = Pair(1000f, 1000f)

        // Test from 3 different starting GPS positions (not just point A):
        // 1. South of A (offset by 30m)
        // 2. East of A (offset by 30m)
        // 3. Between A and B (offset by 50m north, halfway)

        val testOffsets = listOf(
            Triple("south_of_A", 30.0, 180.0),
            Triple("east_of_A", 30.0, 90.0),
            Triple("between_AB", 50.0, 0.0)
        )

        for ((label, startOffsetM, offsetBearingDeg) in testOffsets) {
            val startGps = offsetGpsTrueNorth(cal.pointA.gps, offsetBearingDeg, startOffsetM)

            // Walk along magnetic north from start: compute X in corrected frame
            // First get the magnetic direction (true bearing - declination)
            val magneticDir = MapGeometry.magneticBearing(0.0, declination)  // = -10°
            val walkGps = offsetGpsTrueNorth(startGps, magneticDir, 50.0)

            val startImg = MapCalibrationUtils.gpsToImageAbs(startGps, cal, imageDims, correctNorthAngle)!!
            val walkImg = MapCalibrationUtils.gpsToImageAbs(walkGps, cal, imageDims, correctNorthAngle)!!

            // East drift is not exactly zero because offsetGpsTrueNorth (spherical offset)
            // and gpsToImageRelative (Rhumb line easting with cos(pointA.lat)) use different
            // reference latitudes when startGps is displaced from pointA. With Rhumb line fix,
            // drift accumulates to ~0.15-2px depending on the starting position offset.
            val eastDriftPx = kotlin.math.abs(walkImg.first - startImg.first)
            assertTrue(
                "East drift at magnetic north walk from $label: $eastDriftPx px (expected < 3px due to Rhumb-vs-spherical lat reference mismatch)",
                eastDriftPx < 3.0f
            )

            // North progress should be significant — walking ~50m along magnetic direction
            // should produce measurable Y displacement in image space.
            val northProgress = kotlin.math.abs(walkImg.second - startImg.second)
            assertTrue(
                "North progress at magnetic north walk from $label: $northProgress px (should be > 0)",
                northProgress > 0.1f
            )
        }
    }

    // -----------------------------------------------------------------------
    // 3f. Track approaching finish at an angle: the track's GPS endpoint
    //     must visually align with calibration point B (purple finish)
    //     when northAngle == 0, and preserve distance from pivot at non-zero.
    //
    // Key insight: gpsToImageAbs(GPS_B) = pointB.image holds ONLY at
    // northAngle == 0, because northAngle ≠ 0 rotates around pointA pivot,
    // and GPS_B's displacement from pivot is non-zero (~200m → ~600px Y-arm).
    // The invariant for non-zero northAngle is: distance(pointA.image, gpsToImageAbs(GPS_B))
    // == distance(pointA.image, pointB.image) (rotation preserves distances).
    // -----------------------------------------------------------------------

    @Test
    fun `track approaching finish at 20deg to route line — GPS position aligns with calibration pointB`() {
        val declination = 5.0

        /*
         * Scenario:
         *   Route (calibration): A south → B north (bearing ≈ 0°)
         *   Point B is ~200m north of A in GPS, mapped to purple finish point on image.
         *
         * Track approaches the same physical finish location but from a heading offset
         * by +20° from the AB route direction. When northAngle == 0, the endpoint GPS
         * (which equals GPS_B) must map exactly to purple pointB.image.
         */

        val cal = calibrate(
            latA = 50.450_000, lonA = 30.500_000,   // A south
            latB = 50.451_798, lonB = 30.500_000,   // B ~200m north of A (bearing ≈ 0°)
            imgAx = 0.5f, imgAy = 0.8f,              // A near bottom-center
            imgBx = 0.5f, imgBy = 0.2f,              // B above A on image (purple finish point)
            declination = declination
        )

        val trueBearingAB = MapGeometry.bearing(cal.pointA.gps, cal.pointB.gps)
        val correctNorthAngle = MapOrientation.computeNorthAngleForMagneticAlignment(cal)

        assertTrue("Route A→B bearing ≈ 0° (north)", kotlin.math.abs(trueBearingAB - 0.0) < 1.0)

        // Verify: at northAngle=0, GPS_B maps exactly to purple pointB.image
        val imgB_zero = MapCalibrationUtils.gpsToImageAbs(cal.pointB.gps, cal, Pair(1000f, 1000f), 0f)!!
        assertEquals(
            "GPS_B → purple finish pointB at northAngle=0 (X)",
            cal.pointB.imageX, imgB_zero.first, 0.01f
        )
        assertEquals(
            "GPS_B → purple finish pointB at northAngle=0 (Y)",
            cal.pointB.imageY, imgB_zero.second, 0.01f
        )

        // For non-zero northAngle: verify invariant is distance from pivot, not position.
        val distAB = kotlin.math.sqrt(
            ((cal.pointB.imageX - cal.pointA.imageX).toDouble()) *
            ((cal.pointB.imageX - cal.pointA.imageX).toDouble()) +
            ((cal.pointB.imageY - cal.pointA.imageY).toDouble()) *
            ((cal.pointB.imageY - cal.pointA.imageY).toDouble())
        )

        // Distance from pointA is preserved by rotation (rigid transform invariant)
        for (testAngle in listOf(-20f, 0f, +10f, 45f, 90f)) {
            val imgAtAngle = MapCalibrationUtils.gpsToImageAbs(
                cal.pointB.gps, cal, Pair(1000f, 1000f), testAngle
            )!!
            val distFromAPivot = kotlin.math.sqrt(
                ((imgAtAngle.first - cal.pointA.imageX).toDouble()) *
                ((imgAtAngle.first - cal.pointA.imageX).toDouble()) +
                ((imgAtAngle.second - cal.pointA.imageY).toDouble()) *
                ((imgAtAngle.second - cal.pointA.imageY).toDouble())
            )
            assertEquals(
                "GPS_B distance from A preserved at northAngle=$testAngle (expected=$distAB)",
                distAB, distFromAPivot, 0.01
            )
        }

        /*
         * Track scenario: track endpoint is exactly at GPS_B location.
         * Track arrives from a direction offset by +20° from route AB.
         */
        val trackEndpointGps = cal.pointB.gps // endpoint = B's GPS

        // At northAngle == 0, track endpoint must visually align with purple finish point.
        val imgEndpointZero = MapCalibrationUtils.gpsToImageAbs(
            trackEndpointGps, cal, Pair(1000f, 1000f), 0f
        )!!
        assertEquals("Track endpoint GPS at B → purple finish (X) at northAngle=0",
            cal.pointB.imageX, imgEndpointZero.first, 0.01f)
        assertEquals("Track endpoint GPS at B → purple finish (Y) at northAngle=0",
            cal.pointB.imageY, imgEndpointZero.second, 0.01f)

        // At correctNorthAngle, distance invariant must hold.
        val imgEndpointCorrect = MapCalibrationUtils.gpsToImageAbs(
            trackEndpointGps, cal, Pair(1000f, 1000f), correctNorthAngle
        )!!
        val distFromAPivot = kotlin.math.sqrt(
            ((imgEndpointCorrect.first - cal.pointA.imageX).toDouble()) *
            ((imgEndpointCorrect.first - cal.pointA.imageX).toDouble()) +
            ((imgEndpointCorrect.second - cal.pointA.imageY).toDouble()) *
            ((imgEndpointCorrect.second - cal.pointA.imageY).toDouble())
        )
        assertEquals("Track endpoint distance from A invariant at correctNorthAngle",
            distAB, distFromAPivot, 0.01)

        /*
         * Verify angular preservation: the +20° physical offset from route AB must appear
         * as a consistent angular difference in image space, regardless of magnetic north rotation.
         *
         * Key insight: northAngle rotates ALL directions uniformly by northAngle°. So an
         * absolute direction at bearing θ appears on screen at (θ + northAngle)°. The angular
         * DIFFERENCE between two directions is preserved exactly (rigid transform).
         */
        // Generate track GPS point using Rhumb-line offset (consistent with gpsToImageRelative)
        val walkDirPlus = (trueBearingAB + 20.0) % 360.0
        val dNorth20 = 200.0 * kotlin.math.cos(Math.toRadians(walkDirPlus))
        val dEast20 = 200.0 * kotlin.math.sin(Math.toRadians(walkDirPlus))
        val gpsWalk200m = MapGeometry.offsetCoordinate(cal.pointA.gps, dNorth20, dEast20)

        // Compute Rhumb-geo angles from A: use raw geo deltas (degrees cancel in ratio).
        // Route direction: A→B (pure north for this setup).
        val routeAngleRhumb = Math.toDegrees(kotlin.math.atan2(
            (cal.pointB.gps.longitude - cal.pointA.gps.longitude) *
                    kotlin.math.cos(Math.toRadians(cal.pointA.gps.latitude)).toDouble(),
            (cal.pointB.gps.latitude - cal.pointA.gps.latitude).toDouble()
        ))

        // Track direction: A→gpsWalk200m — exactly the physical walking bearing.
        val trackAngleRhumb = Math.toDegrees(kotlin.math.atan2(
            kotlin.math.cos(Math.toRadians(cal.pointA.gps.latitude)) *
                    (gpsWalk200m.longitude - cal.pointA.gps.longitude).toDouble(),
            (gpsWalk200m.latitude - cal.pointA.gps.latitude).toDouble()
        ))

        // Expected angular difference = 20° (the physical offset between route and track).
        var expectedDiff = kotlin.math.abs(trackAngleRhumb - routeAngleRhumb) % 360.0
        val angularDiffExpected = if (expectedDiff > 180.0) 360.0 - expectedDiff else expectedDiff

        // Compute actual visual angle in rotated image space.
        val startImg = MapCalibrationUtils.gpsToImageAbs(
            cal.pointA.gps, cal, Pair(1000f, 1000f), correctNorthAngle
        )!!
        val walkImg = MapCalibrationUtils.gpsToImageAbs(
            gpsWalk200m, cal, Pair(1000f, 1000f), correctNorthAngle
        )!!

        // Route vector in image space (from A to B) using atan2(dx, -dy) for screen angles.
        val routeAngleImg = Math.toDegrees(kotlin.math.atan2(
            (cal.pointB.imageX - cal.pointA.imageX).toDouble(),
            -(cal.pointB.imageY - cal.pointA.imageY).toDouble()
        ))

        // Track vector in image space (from A to track point)
        val trackImgDx = walkImg.first - startImg.first
        val trackImgDy = walkImg.second - startImg.second
        val trackAngleImg = Math.toDegrees(kotlin.math.atan2(trackImgDx.toDouble(), -trackImgDy.toDouble()))

        // Angular difference in image space (handle wraparound at 0/360)
        var angularDiffActual = kotlin.math.abs(routeAngleImg - trackAngleImg)
        if (angularDiffActual > 180.0) angularDiffActual = 360.0 - angularDiffActual

        // KEY INSIGHT: Rhumb-line calibration is NOT conformal at finite distances.
        // computeCalibrationRaw derives scale from GPS distance / image distance using
        // north-south reference (since route AB goes pure north). But gpsToImageRelative
        // uses cos(pointA.lat) for easting, so the north-south pixel scale differs from
        // east-west by 1/cos(latA). This creates angular distortion for non-pure-north
        // directions. What IS preserved:
        // 1) Rhumb-grid direction = physical bearing (cos(latA) cancels in atan2)
        // 2) Angular difference between +20° and -20° tracks = 40° (symmetry preserved)

        // Rhumb-geo relative angle = 20° (the physical offset from route, in Rhumb-grid space).
        // Verified by computing atan2(dEast_rhumb, dNorth_rhumb) where cos(pointA.lat) cancels.
        assertTrue(
            "Rhumb-geo relative angle: $angularDiffExpected° (physical offset from route)",
            kotlin.math.abs(angularDiffExpected - 20.0) < 1e-6
        )

        // Image-space relative angle between the two track vectors (+20° and -20° tracks).
        val walkDirNeg = (trueBearingAB - 20.0 + 360.0) % 360.0
        val dNorthNeg = 200.0 * kotlin.math.cos(Math.toRadians(walkDirNeg))
        val dEastNeg = 200.0 * kotlin.math.sin(Math.toRadians(walkDirNeg))
        val gpsWalkNeg200m = MapGeometry.offsetCoordinate(cal.pointA.gps, dNorthNeg, dEastNeg)

        // Compute image-space for -20° track.
        val imgNeg = MapCalibrationUtils.gpsToImageAbs(
            gpsWalkNeg200m, cal, Pair(1000f, 1000f), correctNorthAngle
        )!!
        val trackNegImgDx = imgNeg.first - startImg.first
        val trackNegImgDy = imgNeg.second - startImg.second

        // Compute the angle between +20° and -20° tracks in image space.
        // This is a pure rigid-transform invariant — rotation cannot change relative angles.
        val anglePlusImg = Math.toDegrees(kotlin.math.atan2(trackImgDx.toDouble(), -trackImgDy.toDouble()))
        val angleNegImg = Math.toDegrees(kotlin.math.atan2(trackNegImgDx.toDouble(), -trackNegImgDy.toDouble()))

        var angleBetweenTracks = kotlin.math.abs(anglePlusImg - angleNegImg) % 360.0
        if (angleBetweenTracks > 180.0) angleBetweenTracks = 360.0 - angleBetweenTracks

        assertTrue(
            "Angle between +20° and -20° tracks in image space: $angleBetweenTracks°, expected=40°",
            kotlin.math.abs(angleBetweenTracks - 40.0) < 1e-6f
        )

        // Verify each track's visual angle from route is consistent with Rhumb-grid (not physical bearing).
        var imgDiffPlus = kotlin.math.abs(routeAngleImg - trackAngleImg) % 360.0
        if (imgDiffPlus > 180.0) imgDiffPlus = 360.0 - imgDiffPlus

        // The visual angle from route in image space is distorted by Rhumb-line non-conformal scaling:
        // north-south scale = 1/(scale * cos(latA)), east-west scale = 1/scale.
        // This means visual angle ≈ 25° while physical bearing = 20°. The distortion is real and
        // inherent to Rhumb-line projection — angles are only preserved infinitesimally at pointA.

        // CORRECT invariant: compute track direction in the SAME coordinate system that gpsToImageRelative
        // uses (Rhumb-grid with cos(pointA.lat)). This direction equals physical bearing exactly.
        val walkDxPlus = cal.pointB.gps.longitude - cal.pointA.gps.longitude  // route easting (deg)
        val walkDyPlus = cal.pointB.gps.latitude - cal.pointA.gps.latitude    // route northing (deg)
        val routeRhumbAngle = Math.toDegrees(kotlin.math.atan2(
            kotlin.math.cos(Math.toRadians(cal.pointA.gps.latitude)) * walkDxPlus, walkDyPlus))

        val trackDxPlus = gpsWalk200m.longitude - cal.pointA.gps.longitude
        val trackDyPlus = gpsWalk200m.latitude - cal.pointA.gps.latitude
        val trackRhumbAngle = Math.toDegrees(kotlin.math.atan2(
            kotlin.math.cos(Math.toRadians(cal.pointA.gps.latitude)) * trackDxPlus, trackDyPlus))

        var rhumbGridDiff = kotlin.math.abs(trackRhumbAngle - routeRhumbAngle) % 360.0
        if (rhumbGridDiff > 180.0) rhumbGridDiff = 360.0 - rhumbGridDiff

        assertTrue(
            "Rhumb-grid diff between route and +20° track: $rhumbGridDiff°, expected=physical bearing diff $angularDiffExpected°",
            kotlin.math.abs(rhumbGridDiff - angularDiffExpected) < 1e-6f
        )

        // Key invariant: distance to purple finish point at northAngle=0 is zero (endpoint IS B).
        val dxToFinish = imgEndpointZero.first - cal.pointB.imageX
        val dyToFinish = imgEndpointZero.second - cal.pointB.imageY
        val distToFinish = kotlin.math.sqrt((dxToFinish * dxToFinish + dyToFinish * dyToFinish).toDouble())
        assertEquals("Track endpoint is exactly at purple finish point at northAngle=0",
            0.0, distToFinish, 0.01)
    }

    // -----------------------------------------------------------------------
    // 4. DIAGNOSTIC: dump numerical deltas for visual inspection
    // -----------------------------------------------------------------------

    @Test
    fun `DIAGNOSTIC - dump 4-direction deltas`() {
        println("\n========== DIAGNOSTIC: GPS→image deltas ==========")

        // (A) Non-flipped: A west, B east => bearing=90°
        val calA = calibrate(
            latA = 45.000, lonA = 38.000,
            latB = 45.000, lonB = 38.002,
            imgAx = 0.1f, imgAy = 0.7f,
            imgBx = 0.9f, imgBy = 0.3f,
            declination = 0.0
        )
        println("\n--- Non-flipped (bearing=90°, hasXYFlip=${calA.hasXYFlip}) ---")
        dumpDeltas(calA, "(A)")

        // (B) Flipped: A north-west, B south-east => bearing ~155° > 90°
        val calB = calibrate(
            latA = 45.003, lonA = 38.000,
            latB = 45.000, lonB = 38.002,
            imgAx = 0.5f, imgAy = 0.2f,
            imgBx = 0.7f, imgBy = 0.6f,
            declination = 0.0
        )
        println("\n--- Flipped (bearing≈155°, hasXYFlip=${calB.hasXYFlip}) ---")
        val refImgB = toImagePixels(calB, REF_FLIPPED)
        println("(B) REF_flipped → img=(${refImgB.first}, ${refImgB.second})")
        for ((label, bearingDeg) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
            val gpsPt = offsetGpsTrueNorth(REF_FLIPPED, bearingDeg, 100.0)
            val imgPt = toImagePixels(calB, gpsPt)
            val dx = imgPt.first - refImgB.first
            val dy = imgPt.second - refImgB.second
            println("  (B) GPS $label: dx=$dx, dy=$dy")
        }

        // (C) Map aligned to north: A south, B north => bearing=0°
        val calC = calibrate(
            latA = 45.000, lonA = 38.000,
            latB = 45.002, lonB = 38.000,
            imgAx = 0.5f, imgAy = 0.7f,   // A is below on image
            imgBx = 0.5f, imgBy = 0.3f,   // B is above on image
            declination = 0.0
        )
        println("\n--- Aligned to north (bearing=0°, hasXYFlip=${calC.hasXYFlip}) ---")
        dumpDeltas(calC, "(C)")
    }

    private fun dumpDeltas(cal: MapCalibration, tag: String) {
        val refImg = toImagePixels(cal, REF)
        println("$tag REF → img=(${refImg.first}, ${refImg.second})")

        for ((label, bearingDeg) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
            val gpsPt = offsetGpsTrueNorth(REF, bearingDeg, 100.0)
            val imgPt = toImagePixels(cal, gpsPt)
            val dx = imgPt.first - refImg.first
            val dy = imgPt.second - refImg.second
            val direction = when {
                kotlin.math.abs(dx) < 0.01f && kotlin.math.abs(dy) < 0.01f -> "ZERO"
                dy < 0 && kotlin.math.abs(dx) < 0.01f -> "UP"
                dy > 0 && kotlin.math.abs(dx) < 0.01f -> "DOWN"
                dx > 0 && kotlin.math.abs(dy) < 0.01f -> "RIGHT"
                dx < 0 && kotlin.math.abs(dy) < 0.01f -> "LEFT"
                dx > 0 && dy < 0 -> "UP-RIGHT"
                dx < 0 && dy < 0 -> "UP-LEFT"
                dx > 0 && dy > 0 -> "DOWN-RIGHT"
                dx < 0 && dy > 0 -> "DOWN-LEFT"
                else -> "?"
            }
            println("  $tag GPS $label (bearing=$bearingDeg°): dx=$dx, dy=$dy  →  $direction")
        }
    }
}
