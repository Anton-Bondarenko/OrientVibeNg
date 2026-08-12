package ru.bondarenko.orientvibe.ng

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
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
        val pointA = CalibrationPoint(
            gps = GpsCoordinate(latA, lonA),
            imageX = imgAx, imageY = imgAy
        )
        val pointB = CalibrationPoint(
            gps = GpsCoordinate(latB, lonB),
            imageX = imgBx, imageY = imgBy
        )
        return MapGeometry.computeCalibrationRaw(pointA, pointB, declination)!!
    }

    /** Convert a GPS coordinate to absolute image-space pixels. */
    private fun toImagePixels(cal: MapCalibration, gps: GpsCoordinate): Pair<Float, Float> {
        val rel = MapGeometry.gpsToImageRelative(gps, cal)!!
        return Pair(rel.first * 1000f, rel.second * 1000f) // scale to 1000 px
    }

    /** Reference point for all offset tests. */
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
        // Physical map: latA == latB, lonB > lonA => true bearing = 90° => no flip needed
        // We need flipped: make latB < latA slightly while keeping lonB > lonA
        val cal = calibrate(
            latA = 45.002, lonA = 38.000,   // A north-west
            latB = 45.00199, lonB = 38.002, // B south-east of A (bearing > 90°)
            imgAx = 0.5f, imgAy = 0.2f,     // A relative position on image
            imgBx = 0.7f, imgBy = 0.6f,     // B below-right of A on image
            declination = 0.0
        )

        assertTrue("bearing in [90,270] → hasXYFlip must be true", cal.hasXYFlip)

        val refImg = toImagePixels(cal, REF)

        // East (+90° true bearing): should go RIGHT on screen (positive dX after flip correction)
        val gpsE = offsetGpsTrueNorth(REF, 90.0, 50.0)
        val imgE = toImagePixels(cal, gpsE)
        assertTrue("East GPS movement must increase screen X when hasXYFlip=true",
            imgE.first - refImg.first > 0f)

        // West (270°): should go LEFT (negative dX)
        val gpsW = offsetGpsTrueNorth(REF, 270.0, 50.0)
        val imgW = toImagePixels(cal, gpsW)
        assertTrue("West GPS movement must decrease screen X when hasXYFlip=true",
            imgW.first - refImg.first < 0f)

        // North (0°): should go UP on screen (negative dY after flip correction)
        val gpsN = offsetGpsTrueNorth(REF, 0.0, 50.0)
        val imgN = toImagePixels(cal, gpsN)
        assertTrue("North GPS movement must decrease screen Y when hasXYFlip=true",
            imgN.second - refImg.second < 0f)

        // South (180°): should go DOWN on screen (positive dY)
        val gpsS = offsetGpsTrueNorth(REF, 180.0, 50.0)
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
        // Goal: rawMagneticBearing in [90°, 270°] so cos() < 0 → hasXYFlip = true.
        // Construct coordinates that give trueBearing ≈ 170° (south-southeast),
        // then subtract declination 5° → rawMagneticBearing = 165° (still in flip range).
        val cal = calibrate(
            latA = 45.000, lonA = 38.000,
            latB = 44.998, lonB = 38.0005,   // B south-southeast of A → trueBearing ≈ 170°
            imgAx = 0.5f, imgAy = 0.2f,
            imgBx = 0.7f, imgBy = 0.6f,
            declination = 5.0
        )

        assertTrue("165° magneticBearing → hasXYFlip must be true", cal.hasXYFlip)

        val refImg = toImagePixels(cal, REF)

        val gpsE = offsetGpsTrueNorth(REF, 90.0, 50.0)
        val imgE = toImagePixels(cal, gpsE)
        assertTrue("East → right screen (decl=+5)", imgE.first - refImg.first > 0f)

        val gpsN = offsetGpsTrueNorth(REF, 0.0, 50.0)
        val imgN = toImagePixels(cal, gpsN)
        assertTrue("North → up screen (decl=+5)", imgN.second - refImg.second < 0f)

        val gpsS = offsetGpsTrueNorth(REF, 180.0, 50.0)
        val imgS = toImagePixels(cal, gpsS)
        assertTrue("South → down screen (decl=+5)", imgS.second - refImg.second > 0f)

        val gpsW = offsetGpsTrueNorth(REF, 270.0, 50.0)
        val imgW = toImagePixels(cal, gpsW)
        assertTrue("West → left screen (decl=+5)", imgW.first - refImg.first < 0f)
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

        // Calibration point A: imageX=0.15, imageY=0.85 relative → 150px, 850px absolute
        assertEquals("Calibration point A GPS → correct pixel", 150f, img1a.first, 0.001f)
        assertEquals("Calibration point A GPS → correct pixel", 850f, img1a.second, 0.001f)
    }

    // -----------------------------------------------------------------------
    // 5. Reverse transform symmetry (gpsToImage + imageToGps = identity)
    // -----------------------------------------------------------------------

    @Test
    fun `gpsToImage then imageToGps returns original coordinate`() {
        // Flipped calibration with relative image coords
        val cal = calibrate(
            latA = 45.002, lonA = 38.000,
            latB = 45.00199, lonB = 38.002,
            imgAx = 0.5f, imgAy = 0.2f,
            imgBx = 0.7f, imgBy = 0.6f,
            declination = 5.0
        )

        val original = GpsCoordinate(45.002, 38.001)
        val imageCoords = MapGeometry.gpsToImageRelative(original, cal)
        assertNotNull("gpsToImage must not return null", imageCoords)

        val recovered = MapGeometry.imageToGpsRelative(imageCoords!!.first, imageCoords.second, cal)
        assertNotNull("imageToGps must not return null", recovered)

        assertEquals("Latitude after round-trip", original.latitude, recovered!!.latitude, 1e-10)
        assertEquals("Longitude after round-trip", original.longitude, recovered.longitude, 1e-10)
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

        val canvasSize = 1000f // width and height in source pixels (matches toImagePixels() scaling)

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
        // Relative image coords for consistent scale
        val cal = calibrate(
            latA = 45.002, lonA = 38.000,
            latB = 45.00199, lonB = 38.002,
            imgAx = 0.5f, imgAy = 0.2f,
            imgBx = 0.7f, imgBy = 0.6f,
            declination = 0.0
        )

        assertTrue("Must be flipped", cal.hasXYFlip)

        val refImg = toImagePixels(cal, REF)

        val gpsE = offsetGpsTrueNorth(REF, 90.0, 100.0)
        val gpsN = offsetGpsTrueNorth(REF, 0.0, 100.0)

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
    // 8. DIAGNOSTIC: dump numerical deltas for visual inspection
    // -----------------------------------------------------------------------

    @Test
    fun `DIAGNOSTIC - dump 4-direction deltas`() {
        println("\n========== DIAGNOSTIC: GPS→image deltas ==========")

        // (A) Non-flipped: A west, B east, imgA→imgB goes up-right
        val calA = calibrate(
            latA = 45.000, lonA = 38.000,
            latB = 45.000, lonB = 38.002,
            imgAx = 0.1f, imgAy = 0.7f,
            imgBx = 0.9f, imgBy = 0.3f,
            declination = 0.0
        )
        println("\n--- Non-flipped (bearing=90°, hasXYFlip=${calA.hasXYFlip}) ---")
        dumpDeltas(calA, "(A)")

        // (B) Flipped: A north-west, B south-east of A on physical map
        val calB = calibrate(
            latA = 45.002, lonA = 38.000,
            latB = 45.00199, lonB = 38.002,
            imgAx = 0.5f, imgAy = 0.2f,
            imgBx = 0.7f, imgBy = 0.6f,
            declination = 0.0
        )
        println("\n--- Flipped (bearing≈170°, hasXYFlip=${calB.hasXYFlip}) ---")
        dumpDeltas(calB, "(B)")

        // (C) Map aligned to north: A south, B north (bearing=0°)
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
