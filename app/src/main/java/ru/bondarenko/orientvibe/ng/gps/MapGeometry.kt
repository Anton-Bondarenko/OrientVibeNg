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
    private const val MAX_DELTA_DECLINATION = 1.0
    private const val MAX_DELTA_SCALE = 200.0

    fun calculateMagneticDeclination(
        latitude: Double,
        longitude: Double,
        timeMillis: Long = System.currentTimeMillis()
    ): Double {
        val geomagneticField = GeomagneticField(
            latitude.toFloat(),
            longitude.toFloat(),
            0f,
            timeMillis
        )

        return geomagneticField.declination.toDouble()
    }

    /** True (geographic) bearing from *from* to *to*, in degrees [0, 360). */
    fun bearing(from: GpsCoordinate, to: GpsCoordinate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360f) % 360f).toDouble()
    }

    fun screenBearing(x1: Double, y1: Double, x2: Double, y2: Double): Double {
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

        return degrees.toDouble()
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
    fun magneticBearing(trueBearing: Double, declination: Double): Double {
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
    fun computeCalibrationHard(
        pointA: CalibrationPoint,
        pointB: CalibrationPoint,
        magneticDeclination: Double,
        northAngle: Double = 0.0
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
            (magneticDeclination + northAngle)
        )
        val dx = (rotPointB.first - pointA.imageX)
        val dy = (rotPointB.second - pointA.imageY)

        val imageDistance = sqrt(dx * dx + dy * dy)
        if (imageDistance < 0.001) return null


        val scaleMetersPerUnitX = abs(dEast / dx)
        val scaleMetersPerUnitY = abs(dNorth / dy)

        return MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerMapX = scaleMetersPerUnitX,
            scaleMetersPerMapY = scaleMetersPerUnitY,
            bearingDegrees = magneticDeclination,
            magneticDeclination = magneticDeclination,
            physicalDeclination = magneticDeclination
        )
    }

    // умная калибровка
    fun computeCalibrationSoft(
        pointA: CalibrationPoint,
        pointB: CalibrationPoint,
        northAngle: Double = 0.0,
        oldCalibration: MapCalibration,
        proportion: Double
    ): MapCalibration? {
        // Вычисляем true bearing между точками для коррекции угла севера
        val trueBearing = bearing(pointA.gps, pointB.gps)
        // теперь измерим угол на изображении
        val screenBearing =
            screenBearing(pointA.imageX, pointA.imageY, pointB.imageX, pointB.imageY) - northAngle
        var calculatedDeclination = trueBearing - screenBearing
        // корректируем склонение не более одного градуса за раз
        calculatedDeclination =
            oldCalibration.magneticDeclination + (calculatedDeclination - oldCalibration.magneticDeclination).coerceIn(
                -MAX_DELTA_DECLINATION, MAX_DELTA_DECLINATION
            )
        // Compute Rhumb distance using cos(pointA.gps.lat) for easting — matches offsetCoordinate,
        // gpsToImageRelative, and imageToGpsRelative which all use pointA (start) as reference.
        // Using eastDistance (cos(avgLat)) would diverge when dNorth ≠ 0 because avgLat ≠ pointA.lat.
        val dNorth = northDistance(pointA.gps, pointB.gps)
        val dEast = eastDistance(pointA.gps, pointB.gps)
        val gpsDistance = sqrt(dNorth * dNorth + dEast * dEast)
        if (gpsDistance < 100.0) return null

        // поворачиваем на трек на истинный север
        val rotPointB = rotateAroundPoint(
            pointB.imageX,
            pointB.imageY,
            pointA.imageX,
            pointA.imageY,
            (calculatedDeclination + northAngle)
        )
        // калибруем по большему плечу, для второго пропорционально
        val dx = (rotPointB.first - pointA.imageX)
        val dy = (rotPointB.second - pointA.imageY)

        var scaleMetersPerUnitX: Double
        var scaleMetersPerUnitY: Double
        if (abs(dNorth) < abs(dEast)) {
            scaleMetersPerUnitX = abs(dEast / dx)
            // если двухточечная калибровка уже была и масштаб уже примерно известен, то ограничиваем изменения масштаба
            if (oldCalibration.pointB != null) {
                scaleMetersPerUnitX += (scaleMetersPerUnitX - oldCalibration.scaleMetersPerMapX).coerceIn(
                    -MAX_DELTA_SCALE, MAX_DELTA_SCALE
                )
            }
            scaleMetersPerUnitY = scaleMetersPerUnitX / proportion
        } else {
            scaleMetersPerUnitY = abs(dNorth / dy)
            // если двухточечная калибровка уже была и масштаб уже примерно известен, то ограничиваем изменения масштаба
            if (oldCalibration.pointB != null) {
                scaleMetersPerUnitY += (scaleMetersPerUnitY - oldCalibration.scaleMetersPerMapY).coerceIn(
                    -MAX_DELTA_SCALE, MAX_DELTA_SCALE
                )
            }
            scaleMetersPerUnitX = scaleMetersPerUnitY * proportion
        }

        val imageDistance = sqrt(dx * dx + dy * dy)
        if (imageDistance < 0.01) return null

        return MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerMapX = scaleMetersPerUnitX,
            scaleMetersPerMapY = scaleMetersPerUnitY,
            bearingDegrees = calculatedDeclination,
            magneticDeclination = calculatedDeclination + northAngle,
            physicalDeclination = calculatedDeclination
        )
    }

    /**
     * Калибровка по одной точке
     */
    fun calibrationSingle(
        pointA: CalibrationPoint, magneticDeclination: Double,
        northAngle: Double = 0.0, scaleX: Double, scaleY: Double
    ): MapCalibration {
        return MapCalibration(
            pointA = pointA,
            pointB = null,
            scaleMetersPerMapX = scaleX,
            scaleMetersPerMapY = scaleY,
            bearingDegrees = magneticDeclination,
            magneticDeclination = magneticDeclination + northAngle,
            physicalDeclination = magneticDeclination
        )
    }

    /**
     * Forward GPS→image transform
     */
    fun gpsToImageTrueNorth(
        gps: GpsCoordinate,
        calibration: MapCalibration
    ): Pair<Double, Double> {
        val dNorth = northDistance(calibration.pointA.gps, gps)
        // Use cos(startLat) for easting — matches offsetCoordinate's reference.
        // Using cos(avgLat) (eastDistance) diverges when there's a non-zero dNorth,
        // because the reference latitude differs between projection and coordinate creation.
        val dEast = (gps.longitude - calibration.pointA.gps.longitude) *
                (Math.PI / 180.0) * EARTH_RADIUS_METERS * cos(Math.toRadians(calibration.pointA.gps.latitude))

        // Canonical mapping: north → up (negative Y in image space), east → right.
        // Convert geographic offsets to calibrated pixel distances.
        val relDx = (dEast / calibration.scaleMetersPerMapX).toDouble()
        val relDy = (-dNorth / calibration.scaleMetersPerMapY).toDouble()

        return Pair(
            calibration.pointA.imageX + relDx,
            calibration.pointA.imageY + relDy
        )
    }

    /** Inverse image→GPS transform. */
    fun imageToGpsTrueNorth(
        imageX: Double,
        imageY: Double,
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
        bearingDeg: Double,
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
        northAngle: Double
    ): GpsCoordinate? {
        return imageToGps(point.x.toDouble(), point.y.toDouble(), calibration, northAngle)
    }

    /** JVM-safe version that accepts raw x/y Doubles — avoids PointF stub issues on JVM tests. */
    fun imageToGps(
        imageX: Double,
        imageY: Double,
        calibration: MapCalibration?,
        northAngle: Double
    ): GpsCoordinate? {
        val cal =
            calibration ?: return null

        // Un-rotate the image coordinate by -fullAngle around the pivot.
        // gpsToImage rotates by +θ; imageToGps must undo it with -θ for round-trip symmetry.
        val fullAngle = northAngle + cal.magneticDeclination
        val pair = rotateAroundCalibration(imageX, imageY, cal, fullAngle)

        return imageToGpsTrueNorth(pair.first, pair.second, cal)
    }

    fun rotateAroundCalibration(
        x: Double,
        y: Double,
        calibration: MapCalibration,
        fullAngle: Double
    ): Pair<Double, Double> {
        // Always use the un-rotated (trueNorth) pivot for consistency between gpsToImage and imageToGps.
        // If this depended on northAngle via gpsToImageAbs, the forward and inverse transforms
        // would compute different pivots — breaking the round-trip invariant.
        val pivotImg = gpsToImageTrueNorth(calibration.pointA.gps, calibration)
        val px = pivotImg.first
        val py = pivotImg.second
        val angleRad = Math.toRadians(fullAngle)
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
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
        x: Double,
        y: Double,
        cx: Double,
        cy: Double,
        angleInDegrees: Double
    ): Pair<Double, Double> {
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

        return Pair(newX, newY)
    }

    /**
     * Convert a GPS coordinate to image coordinates (absolute pixels).
     * Uses full calibration (scale + bearing rotation) plus optional northAngle adjustment.
     */
    fun gpsToImage(
        gps: GpsCoordinate,
        calibration: MapCalibration?,
        northAngle: Double
    ): Pair<Double, Double>? {
        val cal =
            calibration ?: return null

        // gpsToImage returns ABSOLUTE pixels (pointA.imageX + relDx in pixels), NOT normalized 0..1
        val imageCoords = gpsToImageTrueNorth(gps, cal) ?: return null

        val x = imageCoords.first
        val y = imageCoords.second

        // Step 2: Apply northAngle rotation around a fixed pivot.
        // Use calibration pointA (the map anchor) as the rotation center, NOT trackPoints.first()
        // because trim removes old points which changes the first element, causing visual drift.
        val fullAngle = northAngle + cal.magneticDeclination

        val pair = rotateAroundCalibration(x, y, cal, -fullAngle)

        return pair
    }
}
