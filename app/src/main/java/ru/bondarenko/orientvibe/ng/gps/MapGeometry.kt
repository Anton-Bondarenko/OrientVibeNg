package ru.bondarenko.orientvibe.ng.gps

import android.graphics.PointF
import android.hardware.GeomagneticField
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Получаем склонение в градусах (положительное — на восток, отрицательное — на запад)
 **/
fun calculateMagneticDeclination(
    latitude: Double,
    longitude: Double,
    timeMillis: Long = System.currentTimeMillis()
): Float {
    val geomagneticField = GeomagneticField(
        latitude.toFloat(),
        longitude.toFloat(),
        0f,
        timeMillis
    )

    return geomagneticField.declination
}

/**
 * Pure-geometry GPS utilities — no Android SDK dependencies.
 * Every function here is deterministic and testable on the JVM without Robolectric.
 */
object MapGeometry {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** True (geographic) bearing from *from* to *to*, in degrees [0, 360). */
    fun bearing(from: GpsCoordinate, to: GpsCoordinate): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360f) % 360f).toFloat()
    }

    fun screenBearing(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y1 - y2

        // Для угла с осью Y меняем dx и dy местами в atan2
        val radians = atan2(dx, dy)

        // Переводим радианы в градусы
        var degrees = Math.toDegrees(radians.toDouble())

        // Если вам нужен результат строго от 0 до 360 градусов:
        if (degrees < 0) {
            degrees += 360.0
        }

        return degrees.toFloat()
    }

    /** Haversine distance in metres between two GPS coordinates. */
    fun haversineDistance(a: GpsCoordinate, b: GpsCoordinate): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)

        val sinDLat = sin(dLat / 2.0)
        val sinDLon = sin(dLon / 2.0)
        val aVal = sinDLat * sinDLat + cos(lat1) * cos(lat2) * sinDLon * sinDLon
        val c = 2.0 * atan2(sqrt(aVal), sqrt(1.0 - aVal))
        return EARTH_RADIUS_METERS * c
    }

    /** Northward distance in metres from *from* to *to*. Positive = north. */
    fun northDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        return (to.latitude - from.latitude) * (Math.PI / 180.0) * EARTH_RADIUS_METERS
    }

    /** Eastward distance in metres from *from* to *to*. Positive = east. */
    fun eastDistance(from: GpsCoordinate, to: GpsCoordinate): Double {
        // Use cos(from.lat) for longitude-to-meters conversion — matches offsetCoordinate,
        // computeCalibrationRaw, and gpsToImageRelative which all use from (anchor) as reference.
        val refLat = Math.toRadians(from.latitude)
        return (to.longitude - from.longitude) * (Math.PI / 180.0) * EARTH_RADIUS_METERS * cos(
            refLat
        )
    }

    /** Offset the starting GPS coordinate by a northward and eastward displacement (metres). */
    fun offsetCoordinate(from: GpsCoordinate, dNorth: Double, dEast: Double): GpsCoordinate {
        val latRad = Math.toRadians(from.latitude)
        val dLat = dNorth / EARTH_RADIUS_METERS
        val dLon = dEast / (EARTH_RADIUS_METERS * cos(latRad))

        return GpsCoordinate(
            latitude = from.latitude + Math.toDegrees(dLat),
            longitude = from.longitude + Math.toDegrees(dLon)
        )
    }

    /** Magnetic bearing = trueBearing − declination (decl positive = east). */
    fun magneticBearing(trueBearing: Float, declination: Float): Float {
        return trueBearing - declination
    }

    // ─── Calibration core (pure math) ───

    /**
     * Compute a MapCalibration from two calibration points and the magnetic declination.
     * Returns null if the points are too close to derive a valid transform.
     *
     * hasXYFlip is set true when cos(magneticBearing) < 0 — it serves as metadata indicating
     * that the physical map's north direction opposes screen-up, but does NOT affect the
     * coordinate transform (dEast/dNorth mapping is inherently correct for any bearing).
     */
    fun computeCalibrationRaw(
        pointA: CalibrationPoint,
        pointB: CalibrationPoint,
        magneticDeclination: Float
    ): MapCalibration? {
        // Compute Rhumb distance using cos(pointA.gps.lat) for easting — matches offsetCoordinate,
        // gpsToImageRelative, and imageToGpsRelative which all use pointA (start) as reference.
        // Using eastDistance (cos(avgLat)) would diverge when dNorth ≠ 0 because avgLat ≠ pointA.lat.
        val dNorth = northDistance(pointA.gps, pointB.gps)
        val dEast = (pointB.gps.longitude - pointA.gps.longitude) *
                (Math.PI / 180.0) * EARTH_RADIUS_METERS * cos(Math.toRadians(pointA.gps.latitude))
        val gpsDistance = sqrt(dNorth * dNorth + dEast * dEast)
        if (gpsDistance < 1.0) return null

        val dx = (pointB.imageX - pointA.imageX).toDouble()
        val dy = (pointB.imageY - pointA.imageY).toDouble()
        val imageDistance = sqrt(dx * dx + dy * dy)
        if (imageDistance < 0.001) return null

        val scaleMetersPerUnit = gpsDistance / imageDistance
        val trueBearing = bearing(pointA.gps, pointB.gps)
        val rawMagneticBearing = magneticBearing(trueBearing, magneticDeclination)
        val hasXYFlip = cos(Math.toRadians(rawMagneticBearing.toDouble())) < 0

        return MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerPixel = scaleMetersPerUnit,
            bearingDegrees = rawMagneticBearing,
            magneticDeclination = magneticDeclination,
            physicalDeclination = magneticDeclination,
            hasXYFlip = hasXYFlip
        )
    }

    /**
     * Forward GPS→image transform (calibrated absolute pixel coordinates anchored at pointA).
     *
     * Returns coordinates relative to image origin (0, 0) using the map's calibration:
     * - pointA.imageX/Y serve as the anchor position in calibrated space
     * - Geographic offset (dNorth, dEast) is converted to pixels using scaleMetersPerPixel
     *
     * These are NOT [0,1] ratios — they are calibrated image-space coordinates suitable for:
     * - Direct rendering (with proper scaling by actual image dimensions)
     * - Rotation around calibration point A in image space
     * - Distance preservation across different GPS ↔ image transforms
     */
    fun gpsToImageRelative(
        gps: GpsCoordinate,
        calibration: MapCalibration
    ): Pair<Float, Float>? {
        val dNorth = northDistance(calibration.pointA.gps, gps)
        // Use cos(startLat) for easting — matches offsetCoordinate's reference.
        // Using cos(avgLat) (eastDistance) diverges when there's a non-zero dNorth,
        // because the reference latitude differs between projection and coordinate creation.
        val dEast = (gps.longitude - calibration.pointA.gps.longitude) *
                (Math.PI / 180.0) * EARTH_RADIUS_METERS * cos(Math.toRadians(calibration.pointA.gps.latitude))

        // Canonical mapping: north → up (negative Y in image space), east → right.
        // Convert geographic offsets to calibrated pixel distances.
        val relDx = (dEast / calibration.scaleMetersPerPixel).toFloat()
        val relDy = (-dNorth / calibration.scaleMetersPerPixel).toFloat()

        return Pair(
            calibration.pointA.imageX + relDx,
            calibration.pointA.imageY + relDy
        )
    }

    /** Inverse image→GPS transform. */
    fun imageToGpsRelative(
        imageX: Float,
        imageY: Float,
        calibration: MapCalibration
    ): GpsCoordinate? {
        val relDx = (imageX - calibration.pointA.imageX).toDouble()
        val relDy = (imageY - calibration.pointA.imageY).toDouble()

        var metersDx = relDx * calibration.scaleMetersPerPixel
        var metersDy = relDy * calibration.scaleMetersPerPixel

        // Inverse of canonical: always direct mapping (no flip needed).
        val dEast = metersDx
        val dNorth = -metersDy

        return offsetCoordinate(calibration.pointA.gps, dNorth, dEast)
    }

    /**
     * Offset a GPS coordinate by a given bearing (degrees from true north) and distance (meters).
     * Uses spherical earth approximation.
     */
    fun offsetGps(
        from: GpsCoordinate,
        bearingDeg: Float,
        distanceMeters: Double
    ): GpsCoordinate {
        val earthRadius = 6_371_000.0
        val angularDistance = distanceMeters / earthRadius
        val bearingRad = Math.toRadians(bearingDeg.toDouble())
        val lat1Rad = Math.toRadians(from.latitude)
        val lon1Rad = Math.toRadians(from.longitude)

        val lat2Rad = kotlin.math.asin(
            kotlin.math.sin(lat1Rad) * kotlin.math.cos(angularDistance) +
                    kotlin.math.cos(lat1Rad) * kotlin.math.sin(angularDistance) * kotlin.math.cos(
                bearingRad
            )
        )

        val lon2Rad = lon1Rad + kotlin.math.atan2(
            kotlin.math.sin(bearingRad) * kotlin.math.sin(angularDistance) * kotlin.math.cos(lat1Rad),
            kotlin.math.cos(angularDistance) - kotlin.math.sin(lat1Rad) * kotlin.math.sin(lat2Rad)
        )

        return GpsCoordinate(
            latitude = Math.toDegrees(lat2Rad),
            longitude = Math.toDegrees(lon2Rad)
        )
    }

    fun imageAbsToGps(
        point: PointF,
        calibration: MapCalibration?,
        northAngle: Float
    ): GpsCoordinate? {
        val cal =
            calibration ?: return null

        val fullAngle =
            -magneticBearing(northAngle, cal.magneticDeclination)
        val pair = rotateAroundCalibration(point.x, point.y, cal, fullAngle);

        return imageToGpsRelative(pair.first, pair.second, cal)
    }

    fun rotateAroundCalibration(
        x: Float,
        y: Float,
        calibration: MapCalibration,
        fullAngle: Float
    ): Pair<Float, Float> {
        val calAnchorGps = calibration.pointA.gps
        val pivotImg = calAnchorGps.let { MapCalibrationUtils.gpsToImage(it, calibration) }
        if (pivotImg != null) {
            val px = pivotImg.first
            val py = pivotImg.second
            val angleRad = Math.toRadians(fullAngle.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()
            val dx = x - px
            val dy = y - py

            return Pair(px + dx * cosA - dy * sinA, py + dx * sinA + dy * cosA)
        }
        return Pair(x, y);
    }

    /**
     * Convert a GPS coordinate to image coordinates (absolute pixels).
     * Uses full calibration (scale + bearing rotation) plus optional northAngle adjustment.
     */
    fun gpsToImageAbs(
        gps: GpsCoordinate,
        calibration: MapCalibration?,
        northAngle: Float
    ): PointF? {
        val cal =
            calibration ?: return null

        // gpsToImage returns ABSOLUTE pixels (pointA.imageX + relDx in pixels), NOT normalized 0..1
        val imageCoords = gpsToImageRelative(gps, cal) ?: return null

        var x = imageCoords.first
        var y = imageCoords.second

        // Step 2: Apply northAngle rotation around a fixed pivot.
        // Use calibration pointA (the map anchor) as the rotation center, NOT trackPoints.first()
        // because trim removes old points which changes the first element, causing visual drift.
        val fullAngle =
            magneticBearing(northAngle, cal.magneticDeclination)

        val pair = rotateAroundCalibration(x, y, cal, fullAngle)

        return PointF(pair.first, pair.second)
    }
}
