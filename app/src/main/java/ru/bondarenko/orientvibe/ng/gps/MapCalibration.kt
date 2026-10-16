package ru.bondarenko.orientvibe.ng.gps

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils.bindGpsToFinishWithTrack
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.MapCalibration

/**
 * Convenience wrapper around the pure-math [MapGeometry] — provides logging
 * and a stable public API. All coordinate transformations live in MapGeometry,
 * which has zero Android SDK dependencies and can be unit-tested on the JVM.
 */
object MapCalibrationUtils {

    private const val DEFAULT_SCALE = 4000.0 // метров в карте по высоте

    /** Returns the physical magnetic declination used for northAngle computation. */
    fun effectiveDeclination(cal: MapCalibration): Double {
        return cal.physicalDeclination
    }

    // ─── Coordinate transforms ────────────────────────────────────────────

    fun gpsToImage(gps: GpsCoordinate, calibration: MapCalibration): Pair<Double, Double>? {
        return MapGeometry.gpsToImageTrueNorth(gps, calibration)
    }

    fun imageToGps(imageX: Double, imageY: Double, calibration: MapCalibration): GpsCoordinate? {
        return MapGeometry.imageToGpsTrueNorth(imageX, imageY, calibration)
    }

    fun magneticBearing(
        from: GpsCoordinate,
        to: GpsCoordinate,
        magneticDeclination: Double
    ): Double {
        val trueBearing = bearing(from, to)
        return MapGeometry.magneticBearing(trueBearing, magneticDeclination)
    }

    fun bearing(from: GpsCoordinate, to: GpsCoordinate): Double {
        return MapGeometry.bearing(from, to)
    }

    fun haversineDistance(a: GpsCoordinate, b: GpsCoordinate): Double {
        return MapGeometry.haversineDistance(a, b)
    }

    fun northDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        return MapGeometry.northDistance(from, to)
    }

    fun eastDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        return MapGeometry.eastDistance(from, to)
    }

    // ─── Absolute GPS→image (with scaling, northAngle rotation handled by canvas) ──

    /**
     * Convert GPS coordinate to absolute image-space coordinates (pixels).
     * Uses the calibration's pointA anchor and uniform scale to map geographic offsets
     * to pixel offsets, then rotates around pointA by northAngleDeg to align with the
     * map's displayed orientation. This ensures projected GPS points stay consistent with
     * the rotated map image — the green GPS dot and purple calibration-point markers
     * both use this function so they share the same coordinate frame before sourceToViewCoord()
     * applies the uniform canvas rotation in production rendering.
     */
    fun gpsToImage(
        gps: GpsCoordinate,
        calibration: MapCalibration,
        northAngleDeg: Double  // degrees to rotate around pointA
    ): Pair<Double, Double>? {
        return MapGeometry.gpsToImage(gps, calibration, northAngleDeg)
    }

    /** Offset the starting GPS coordinate by a northward and eastward displacement (metres). */
    fun offsetCoordinate(from: GpsCoordinate, dNorth: Double, dEast: Double): GpsCoordinate {
        return MapGeometry.offsetCoordinate(from, dNorth, dEast)
    }

    // ─── Average calibration from history ─────────────────────────────────

    /**
     * Compute a weighted-average calibration from a list of historical calibrations,
     * focused around [targetGps].
     *
     * For each calibration the "center" GPS position is the midpoint of its available
     * calibration points (pointA alone if pointB is missing). Each calibration receives a
     * weight proportional to `confidence / distance²` — closer and more trustworthy
     * calibrations dominate the result.
     *
     * Returns null when the input list is empty or none of the calibrations have valid
     * GPS data.
     */
    fun averageCalibrationsAt(
        history: List<MapCalibration>,
        targetGps: GpsCoordinate
    ): MapCalibration? {
        if (history.isEmpty()) return null

        // Collect (center, weight, cal) tuples
        val samples = mutableListOf<Triple<GpsCoordinate, Double, MapCalibration>>()

        for (cal in history) {
            val pts = listOfNotNull(cal.pointA, cal.pointB)
            if (pts.size < 1) continue

            // Centroid of calibration GPS points
            var latSum = 0.0
            var lonSum = 0.0
            var confSum = 0.0
            for (p in pts) {
                latSum += p.gps.latitude
                lonSum += p.gps.longitude
                confSum += p.confidence
            }
            val centerLat = latSum / pts.size
            val centerLon = lonSum / pts.size
            val center = GpsCoordinate(centerLat, centerLon)

            // Distance weight (inverse-square with 10 m floor to avoid division by zero)
            val dist = haversineDistance(targetGps, center)
            val distWeight = 1.0 / maxOf(dist * dist, 100.0) // 10m floor

            // Combined weight: distance × confidence (mean of points in this calibration)
            val weight = distWeight * (confSum / pts.size)

            samples.add(Triple(center, weight, cal))
        }

        if (samples.isEmpty()) return null

        // Weighted sum
        var wTotal = 0.0
        var scaleWXSum = 0.0
        var scaleWYSum = 0.0
        var bearingSin = 0.0
        var bearingCos = 0.0
        var magDeclSum = 0.0
        var physDeclSum = 0.0

        // Сохраняем pointB если хотя бы одна калибровка была двухточечной — нужен для clamp-логики в computeCalibrationSoft
        // Берём pointB из калибровки с максимальным весом (ближайшая к targetGps)
        var maxWeightPointB: CalibrationPoint? = null
        var maxWeightForPointB = 0.0

        for ((center, weight, cal) in samples) {
            if (cal.pointB != null && weight > maxWeightForPointB) {
                maxWeightForPointB = weight
                // Создаём pointB с GPS центра ближайшей калибровки + image coords из оригинала
                maxWeightPointB = CalibrationPoint(
                    gps = center,
                    imageX = cal.pointB!!.imageX,
                    imageY = cal.pointB!!.imageY,
                    confidence = 1.0
                )
            }
        }

        for ((_, weight, cal) in samples) {
            wTotal += weight
            scaleWXSum += weight * cal.scaleMetersPerMapX
            scaleWYSum += weight * cal.scaleMetersPerMapY
            // Circular mean for bearing (convert degrees to radians first)
            val bRad = Math.toRadians(cal.bearingDegrees)
            bearingSin += weight * sin(bRad)
            bearingCos += weight * cos(bRad)
            magDeclSum += weight * cal.magneticDeclination
            physDeclSum += weight * cal.physicalDeclination
        }

        val avgBearing = Math.toDegrees(atan2(bearingSin / wTotal, bearingCos / wTotal))

        return MapCalibration(
            pointA = CalibrationPoint(
                gps = targetGps, // use caller's position as reference
                imageX = 0.5, imageY = 0.5, confidence = 1.0
            ),
            pointB = maxWeightPointB,
            scaleMetersPerMapX = scaleWXSum / wTotal,
            scaleMetersPerMapY = scaleWYSum / wTotal,
            bearingDegrees = avgBearing,
            magneticDeclination = magDeclSum / wTotal,
            physicalDeclination = physDeclSum / wTotal
        )
    }

    // ─── Single-point (start) calibration ─────────────────────────────────

    /**
     * Create a single-point calibration from one GPS→image mapping.
     * Uses bearing=0 and scale=1 as defaults — meaningful only after two-point
     * recalibration via [bindGpsToFinishWithTrack]. Sets northAngle = 0 so the map
     * is not rotated until proper calibration arrives.
     * А4 = 1.414 : 1
     */
    fun calibrateSinglePoint(
        startGPS: GpsCoordinate,
        startPointImageX: Double,
        startPointImageY: Double,
        northAngle: Double,
        imageProportion: Double = 1.44, // width/height
        scaleMetersPerMapX: Double? = null,
        scaleMetersPerMapY: Double? = null,
        confidence: Double = 1.0
        ): MapCalibration {

        return MapGeometry.calibrationSingle(
            ru.bondarenko.orientvibe.ng.gps.CalibrationPoint(
                gps = startGPS,
                imageX = startPointImageX,
                imageY = startPointImageY,
                confidence = confidence
            ),
            MapGeometry.calculateMagneticDeclination(startGPS.latitude, startGPS.longitude),
            northAngle,
            scaleMetersPerMapX ?: (DEFAULT_SCALE * imageProportion),//2700.0,
            scaleMetersPerMapY ?: DEFAULT_SCALE//3900.0
        )
    }

    // ─── Two-point finish calibration (track-based) ───────────────────────

    /**
     * Result of [bindGpsToFinishWithTrack]: the recalibrated calibration and the northAngle to apply.
     */
    data class BindResult(
        val calibration: MapCalibration,
        val northAngleDegrees: Double
    )

    /** Full finish calibration using track direction + distance from original start to current GPS.
     * Creates a two-point calibration where pointA=startGPS and pointB=currentFixGPS,
     * so the user's current position maps exactly to finishPoint on the map image.
     */
    fun bindGpsToFinishWithTrack(
        startGPS: GpsCoordinate,
        startPointImageX: Double,
        startPointImageY: Double,
        finishPointImageX: Double,
        finishPointImageY: Double,
        currentFixGPS: GpsCoordinate,
        magneticDeclination: Double = 0.0,
        confidence: Double = 1.0
    ): BindResult {
        // Use actual GPS coordinates directly — two-point calibration guarantees
        // gpsToImage(pointB.gps) returns pointB.imageCoords exactly.
        val pointA =
            CalibrationPoint(gps = startGPS, imageX = startPointImageX, imageY = startPointImageY, confidence = confidence)
        val pointB = CalibrationPoint(
            gps = currentFixGPS,
            imageX = finishPointImageX,
            imageY = finishPointImageY,
            confidence
        )

        val newCal = MapGeometry.computeCalibrationHard(pointA, pointB, magneticDeclination)
            ?: throw IllegalStateException("bindGpsToFinishWithTrack: calibration points too close")

        // North angle = -raw_magnetic_bearing = -(trueBearing - declination).
        // For a magnetic-north-aligned orienteering map the image frame is rotated
        // relative to geographic north by +declination.  Compensating with
        // northAngle = -rawMagneticBearing cancels that rotation so currentFixGPS
        // projects EXACTLY onto finishPoint after canvas rotation.
        val trueBearing = MapGeometry.bearing(pointA.gps, pointB.gps)
        val northAngleDeg = -(trueBearing - magneticDeclination)

        return BindResult(newCal, northAngleDeg)
    }
}
