package ru.bondarenko.orientvibe.ng.gps

import android.graphics.PointF
import android.hardware.GeomagneticField
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Получаем склонение в градусах (положительное — на восток, отрицательное — на запад)
 **/

/**
 * Pure-geometry GPS utilities — no Android SDK dependencies.
 * Every function here is deterministic and testable on the JVM without Robolectric.
 */
object MapGeometry {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

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
        magneticDeclination: Float,
        northAngle: Float = 0f
    ): MapCalibration? {
        // Вычисляем true bearing между точками для коррекции угла севера
        val trueBearing = bearing(pointA.gps, pointB.gps)
        // теперь измерим угол на изображении
        val screenBearing =
            screenBearing(pointA.imageX, pointA.imageY, pointB.imageX, pointB.imageY) - northAngle
        val rawMagneticBearing = trueBearing - screenBearing

        // Compute Rhumb distance using cos(pointA.gps.lat) for easting — matches offsetCoordinate,
        // gpsToImageRelative, and imageToGpsRelative which all use pointA (start) as reference.
        // Using eastDistance (cos(avgLat)) would diverge when dNorth ≠ 0 because avgLat ≠ pointA.lat.
        val dNorth = northDistance(pointA.gps, pointB.gps)
        val dEast = (pointB.gps.longitude - pointA.gps.longitude) *
                (Math.PI / 180.0) * EARTH_RADIUS_METERS * cos(Math.toRadians(pointA.gps.latitude))
        val gpsDistance = sqrt(dNorth * dNorth + dEast * dEast)
        if (gpsDistance < 1.0) return null

        // поворачиваем на трек на истинный север
        val rotPointB = rotateAroundPoint(
            pointB.imageX,
            pointB.imageY,
            pointA.imageX,
            pointA.imageY,
            (rawMagneticBearing + northAngle)
        )
        val dx = (rotPointB.first - pointA.imageX).toDouble()
        val dy = (rotPointB.second - pointA.imageY).toDouble()

        val imageDistance = sqrt(dx * dx + dy * dy)
        if (imageDistance < 0.001) return null



        val scaleMetersPerUnitX = abs(dEast / dx)
        val scaleMetersPerUnitY = abs(dNorth / dy)
        val hasXYFlip = cos(Math.toRadians(rawMagneticBearing.toDouble())) < 0

        return MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerMapX = scaleMetersPerUnitX,
            scaleMetersPerMapY = scaleMetersPerUnitY,
            bearingDegrees = rawMagneticBearing,
            magneticDeclination = rawMagneticBearing,
            physicalDeclination = magneticDeclination,
            hasXYFlip = hasXYFlip
        )
    }

    /**
     * Калибровка по одной точке
     */
    fun calibrationSingle(
        pointA: CalibrationPoint, magneticDeclination: Float,
        northAngle: Float = 0f, scaleX: Double, scaleY: Double
    ): MapCalibration {
        return MapCalibration(
            pointA = pointA,
            pointB = null,
            scaleMetersPerMapX = scaleX,
            scaleMetersPerMapY = scaleY,
            bearingDegrees = magneticDeclination,
            magneticDeclination = magneticDeclination + northAngle,
            physicalDeclination = magneticDeclination,
            hasXYFlip = cos(Math.toRadians((magneticDeclination + northAngle).toDouble())) < 0
        )
    }

    /**
     * Forward GPS→image transform
     */
    fun gpsToImageTrueNorth(
        gps: GpsCoordinate,
        calibration: MapCalibration
    ): Pair<Float, Float> {
        val dNorth = northDistance(calibration.pointA.gps, gps)
        // Use cos(startLat) for easting — matches offsetCoordinate's reference.
        // Using cos(avgLat) (eastDistance) diverges when there's a non-zero dNorth,
        // because the reference latitude differs between projection and coordinate creation.
        val dEast = (gps.longitude - calibration.pointA.gps.longitude) *
                (Math.PI / 180.0) * EARTH_RADIUS_METERS * cos(Math.toRadians(calibration.pointA.gps.latitude))

        // Canonical mapping: north → up (negative Y in image space), east → right.
        // Convert geographic offsets to calibrated pixel distances.
        val relDx = (dEast / calibration.scaleMetersPerMapX).toFloat()
        val relDy = (-dNorth / calibration.scaleMetersPerMapY).toFloat()

        return Pair(
            calibration.pointA.imageX + relDx,
            calibration.pointA.imageY + relDy
        )
    }

    /** Inverse image→GPS transform. */
    fun imageToGpsTrueNorth(
        imageX: Float,
        imageY: Float,
        calibration: MapCalibration
    ): GpsCoordinate {
        val relDx = (imageX - calibration.pointA.imageX).toDouble()
        val relDy = (imageY - calibration.pointA.imageY).toDouble()

        var metersDx = relDx * calibration.scaleMetersPerMapX
        var metersDy = relDy * calibration.scaleMetersPerMapY

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
            sin(lat1Rad) * cos(angularDistance) +
                    cos(lat1Rad) * sin(angularDistance) * cos(
                bearingRad
            )
        )

        val lon2Rad = lon1Rad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(lat1Rad),
            cos(angularDistance) - sin(lat1Rad) * sin(lat2Rad)
        )

        return GpsCoordinate(
            latitude = Math.toDegrees(lat2Rad),
            longitude = Math.toDegrees(lon2Rad)
        )
    }

    fun imageToGps(
        point: PointF,
        calibration: MapCalibration?,
        northAngle: Float
    ): GpsCoordinate? {
        return imageToGps(point.x, point.y, calibration, northAngle)
    }

    /** JVM-safe version that accepts raw x/y floats — avoids PointF stub issues on JVM tests. */
    fun imageToGps(
        imageX: Float,
        imageY: Float,
        calibration: MapCalibration?,
        northAngle: Float
    ): GpsCoordinate? {
        val cal =
            calibration ?: return null

        // Un-rotate the image coordinate by -fullAngle around the pivot.
        // gpsToImage rotates by +θ; imageToGps must undo it with -θ for round-trip symmetry.
        val fullAngle =
            magneticBearing(northAngle, cal.magneticDeclination)
        val pair = rotateAroundCalibration(imageX, imageY, cal, -fullAngle)

        return imageToGpsTrueNorth(pair.first, pair.second, cal)
    }

    fun rotateAroundCalibration(
        x: Float,
        y: Float,
        calibration: MapCalibration,
        fullAngle: Float
    ): Pair<Float, Float> {
        // Always use the un-rotated (trueNorth) pivot for consistency between gpsToImage and imageToGps.
        // If this depended on northAngle via gpsToImageAbs, the forward and inverse transforms
        // would compute different pivots — breaking the round-trip invariant.
        val pivotImg = gpsToImageTrueNorth(calibration.pointA.gps, calibration)
        val px = pivotImg.first
        val py = pivotImg.second
        val angleRad = Math.toRadians(fullAngle.toDouble())
        val cosA = cos(angleRad).toFloat()
        val sinA = sin(angleRad).toFloat()
        val dx = x - px
        val dy = y - py

        return Pair(px + dx * cosA - dy * sinA, py + dx * sinA + dy * cosA)
    }

    /**
     * Поворачивает точку относительно заданного центра.
     *
     * @param x Координата X поворачиваемой точки
     * @param y Координата Y поворачиваемой точки
     * @param cx Координата X центра поворота
     * @param cy Координата Y центра поворота
     * @param angleInDegrees Угол поворота в градусах (положительный — против часовой стрелки)
     * @return Пара новых координат (Pair<Double, Double>)
     */
    fun rotateAroundPoint(
        x: Float,
        y: Float,
        cx: Float,
        cy: Float,
        angleInDegrees: Float
    ): Pair<Float, Float> {
        // Переводим угол из градусов в радианы
        val radians = Math.toRadians(angleInDegrees.toDouble())
        val cosA = cos(radians)
        val sinA = sin(radians)

        // Шаг 1: Сдвиг к началу координат (0, 0)
        val translatedX = x - cx
        val translatedY = y - cy

        // Шаг 2: Поворот по формуле матрицы поворота
        val rotatedX = translatedX * cosA - translatedY * sinA
        val rotatedY = translatedX * sinA + translatedY * cosA

        // Шаг 3: Обратный сдвиг к исходному центру
        val newX = rotatedX + cx
        val newY = rotatedY + cy

        return Pair(newX.toFloat(), newY.toFloat())
    }

    /**
     * Convert a GPS coordinate to image coordinates (absolute pixels).
     * Uses full calibration (scale + bearing rotation) plus optional northAngle adjustment.
     */
    fun gpsToImage(
        gps: GpsCoordinate,
        calibration: MapCalibration?,
        northAngle: Float
    ): Pair<Float, Float>? {
        val cal =
            calibration ?: return null

        // gpsToImage returns ABSOLUTE pixels (pointA.imageX + relDx in pixels), NOT normalized 0..1
        val imageCoords = gpsToImageTrueNorth(gps, cal) ?: return null

        var x = imageCoords.first
        var y = imageCoords.second

        // Step 2: Apply northAngle rotation around a fixed pivot.
        // Use calibration pointA (the map anchor) as the rotation center, NOT trackPoints.first()
        // because trim removes old points which changes the first element, causing visual drift.
        val fullAngle =
            magneticBearing(northAngle, cal.magneticDeclination)

        val pair = rotateAroundCalibration(x, y, cal, fullAngle)

        return pair
    }
}
