package ru.bondarenko.orientvibe.ng.model

/**
 * Перечисление уровней точности геолокации.
 */
enum class AccuracyLevel {
    NO_FIX,
    LOW_ACCURACY,
    HIGH_ACCURACY
}

/**
 * Базовый дата-класс географических координат.
 */
data class GpsCoordinate(
    val latitude: Double,
    val longitude: Double
)

/**
 * Точка калибровки, связывающая реальный GPS с пикселем на карте.
 */
data class CalibrationPoint(
    val gps: GpsCoordinate,
    val imageX: Double,
    val imageY: Double,
    val confidence: Double
)

/**
 * Модель данных неизмененного системного GPS-фикса.
 */
data class GpsFix(
    val coordinate: GpsCoordinate,
    val accuracy: Double,
    val bearing: Double,
    val speed: Double,
    val timestamp: Long,
    val altitude: Double = 0.0
)

/**
 * Точка записанного пользовательского трека.
 */
data class TrackPoint(
    val gpsFix: GpsFix,
    val imageX: Double,
    val imageY: Double,
    val distanceFromStart: Double,
    val timestamp: Long
)

/**
 * Модель активной калибровочной матрицы карты.
 */
data class MapCalibration(
    val pointA: CalibrationPoint,
    val pointB: CalibrationPoint? = null,
    val scaleMetersPerMapX: Double,
    val scaleMetersPerMapY: Double,
    val bearingDegrees: Double,
    val magneticDeclination: Double = 0.0,
    val physicalDeclination: Double = 0.0
)

/**
 * Глобальный реактивный стейт геолокационной подсистемы.
 */
data class GpsState(
    val isGpsEnabled: Boolean = false,
    val currentFix: GpsFix? = null,
    val calibration: MapCalibration? = null,
    val isCalibrated: Boolean = false,
    val startCalibrated: Boolean = false,
    val finishCalibrated: Boolean = false,
    val autoBindActive: Boolean = false,
    val trackPoints: List<TrackPoint> = emptyList(),
    val totalDistance: Double = 0.0,
    val isTracking: Boolean = false,
    val routeDistance: Double? = null,
    val mapScaleX: Double? = null,
    val mapScaleY: Double? = null,
    val originalStartGps: GpsCoordinate? = null
) {
    /**
     * Динамически вычисляемый уровень точности на основе текущего GPS фикса.
     */
    val accuracyLevel: AccuracyLevel
        get() {
            val fix = currentFix ?: return AccuracyLevel.NO_FIX
            return if (fix.accuracy <= 10.0) AccuracyLevel.HIGH_ACCURACY else AccuracyLevel.LOW_ACCURACY
        }
}
