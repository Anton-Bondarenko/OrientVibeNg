package ru.bondarenko.orientvibe.ng.model

/**
 * Represents a GPS coordinate (latitude, longitude).
 */
data class GpsCoordinate(
    val latitude: Double,
    val longitude: Double
)

/**
 * Represents a GPS fix with accuracy and bearing information.
 */
data class GpsFix(
    val coordinate: GpsCoordinate,
    val accuracy: Float,          // meters (lower = better)
    val bearing: Float,           // degrees from true north (0..360)
    val speed: Float,             // meters per second
    val timestamp: Long,          // System.currentTimeMillis()
    val altitude: Double = 0.0    // meters above sea level
)

/**
 * Calibration point: maps a GPS coordinate to a relative position on the image (0..1).
 */
data class CalibrationPoint(
    val gps: GpsCoordinate,
    val imageX: Float,  // relative 0..1
    val imageY: Float   // relative 0..1
)

/**
 * Map calibration data computed from two calibration points.
 * Provides conversion between GPS coordinates and image coordinates.
 */
data class MapCalibration(
    val pointA: CalibrationPoint,
    val pointB: CalibrationPoint,
    val scaleMetersPerPixel: Double,  // meters per image-pixel at the map
    val bearingDegrees: Double,       // angle of the image Y-axis relative to true north
    val magneticDeclination: Double,   // magnetic declination at calibration location (degrees, positive = east)
    val physicalDeclination: Double,   // original magnetic declination before any coordinate flip adjustment
    val hasXYFlip: Boolean = false     // metadata: true when cos(magneticBearing) < 0 (map north opposes screen-up); does not affect transform
)

/**
 * A single track point with both GPS and image coordinates.
 */
data class TrackPoint(
    val gpsFix: GpsFix,
    val imageX: Float,       // relative 0..1
    val imageY: Float,       // relative 0..1
    val distanceFromStart: Double,  // meters
    val timestamp: Long
)

/**
 * Accuracy level classification based on GPS fix accuracy.
 */
enum class AccuracyLevel {
    NO_FIX,       // no GPS signal — red
    LOW_ACCURACY, // accuracy > 10m — yellow
    HIGH_ACCURACY // accuracy <= 10m — green
}

/**
 * Overall GPS state exposed to the UI.
 */
data class GpsState(
    val isGpsEnabled: Boolean = false,
    val currentFix: GpsFix? = null,
    val calibration: MapCalibration? = null,
    val isCalibrated: Boolean = false,
    val startCalibrated: Boolean = false,
    val finishCalibrated: Boolean = false,
    val autoMode: Boolean = false,
    val autoCycleActive: Boolean = false,
    val autoBindActive: Boolean = false,
    val trackPoints: List<TrackPoint> = emptyList(),
    val totalDistance: Double = 0.0,  // meters
    val isTracking: Boolean = false,
    // Производные поля — вычисляются при привязке GPS к карте
    val routeDistance: Double? = null,       // расстояние маршрута в метрах (масштаб карты)
    val mapScale: Double? = null,            // масштаб карты (метры на пиксель)
    val originalStartGps: GpsCoordinate? = null  // исходная GPS точка старта для повторной калибровки
) {
    /**
     * Current accuracy level derived from the latest GPS fix.
     */
    val accuracyLevel: AccuracyLevel
        get() {
            val fix = currentFix ?: return AccuracyLevel.NO_FIX
            return if (fix.accuracy <= 10f) AccuracyLevel.HIGH_ACCURACY
            else AccuracyLevel.LOW_ACCURACY
        }
}