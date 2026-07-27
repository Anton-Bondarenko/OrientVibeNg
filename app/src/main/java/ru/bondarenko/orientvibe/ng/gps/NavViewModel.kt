package ru.bondarenko.orientvibe.ng.gps

import android.content.Context
import android.hardware.GeomagneticField
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel that combines GpsManager, TrackRecorder, and calibration state.
 * Exposes a unified GpsState for the UI.
 */
class NavViewModel(
    private val context: Context
) : ViewModel() {

    private val gpsManager = GpsManager(context)
    private val trackRecorder = TrackRecorder()

    private val _gpsState = MutableStateFlow(GpsState())
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    /** Calibration points waiting to be set (0, 1, or 2). */
    private val pendingCalibrationPoints = mutableListOf<CalibrationPoint>()
    private var calibration: MapCalibration? = null

    init {
        // Subscribe to GpsManager state
        viewModelScope.launch {
            gpsManager.gpsState.collect { managerState ->
                _gpsState.value = _gpsState.value.copy(
                    isGpsEnabled = managerState.isGpsEnabled,
                    currentFix = managerState.currentFix
                )

                // Auto-record track point if tracking is active and we have a fix
                if (trackRecorder.getTrackData().isTracking && managerState.currentFix != null) {
                    trackRecorder.recordPoint(managerState.currentFix, calibration)
                }
            }
        }

        // Subscribe to TrackRecorder state
        viewModelScope.launch {
            trackRecorder.trackState.collect { trackState ->
                _gpsState.value = _gpsState.value.copy(
                    trackPoints = trackState.trackPoints,
                    totalDistance = trackState.totalDistance,
                    isTracking = trackState.isTracking
                )
            }
        }
    }

    // ──────────────────────────────────────────────
    // GPS Control
    // ──────────────────────────────────────────────

    /**
     * Start GPS location updates.
     */
    fun startGps() {
        gpsManager.startGpsUpdates()
    }

    /**
     * Stop GPS location updates.
     */
    fun stopGps() {
        gpsManager.stopGpsUpdates()
    }

    /**
     * Check if GPS hardware is enabled.
     */
    fun isGpsEnabled(): Boolean = gpsManager.isGpsEnabled()

    // ──────────────────────────────────────────────
    // Calibration
    // ──────────────────────────────────────────────

    /**
     * Add a calibration point mapping a GPS coordinate to an image position.
     * When two points are set, calibration is computed automatically.
     *
     * @param gpsFix The GPS fix at this location
     * @param imageX Relative X position on the image (0..1)
     * @param imageY Relative Y position on the image (0..1)
     * @return true if calibration was completed (both points set), false otherwise
     */
    fun addCalibrationPoint(
        gpsFix: GpsFix,
        imageX: Float,
        imageY: Float
    ): Boolean {
        val calPoint = CalibrationPoint(
            gps = gpsFix.coordinate,
            imageX = imageX,
            imageY = imageY
        )

        pendingCalibrationPoints.add(calPoint)

        if (pendingCalibrationPoints.size >= 2) {
            val pointA = pendingCalibrationPoints[0]
            val pointB = pendingCalibrationPoints[1]
            
            // Get magnetic declination at start point (once during calibration)
            val magneticDeclination = getMagneticDeclination(
                latitude = pointA.gps.latitude,
                longitude = pointA.gps.longitude,
                altitude = gpsFix.altitude,
                timestamp = gpsFix.timestamp
            )
            
            val result = MapCalibrationUtils.calibrate(pointA, pointB, magneticDeclination)
            if (result != null) {
                calibration = result
                _gpsState.value = _gpsState.value.copy(
                    calibration = result,
                    isCalibrated = true
                )
            }
            pendingCalibrationPoints.clear()
            return true
        }
        return false
    }

    /**
     * Clear calibration data.
     */
    fun clearCalibration() {
        calibration = null
        pendingCalibrationPoints.clear()
        _gpsState.value = _gpsState.value.copy(
            calibration = null,
            isCalibrated = false
        )
    }

    /**
     * Returns the current calibration, or null if not calibrated.
     */
    fun getCalibration(): MapCalibration? = calibration

    fun getMagneticDeclination(latitude: Double, longitude: Double, altitude: Double, timestamp: Long): Double {
        val geomagneticField = GeomagneticField(
            latitude.toFloat(),
            longitude.toFloat(),
            altitude.toFloat(),
            timestamp
        )
        return geomagneticField.declination.toDouble()
    }

    // ──────────────────────────────────────────────
    // Coordinate Conversion
    // ──────────────────────────────────────────────

    /**
     * Convert current GPS position to image coordinates.
     * Returns null if not calibrated or no GPS fix.
     */
    fun currentGpsToImage(): Pair<Float, Float>? {
        val fix = _gpsState.value.currentFix ?: return null
        val cal = calibration ?: return null
        return MapCalibrationUtils.gpsToImage(fix.coordinate, cal)
    }

    /**
     * Convert image coordinates to GPS coordinates.
     * Returns null if not calibrated.
     */
    fun imageToGps(imageX: Float, imageY: Float): GpsCoordinate? {
        val cal = calibration ?: return null
        return MapCalibrationUtils.imageToGps(imageX, imageY, cal)
    }

    /**
     * Compute distance between two GPS coordinates in meters.
     */
    fun distanceBetween(a: GpsCoordinate, b: GpsCoordinate): Double {
        return MapCalibrationUtils.haversineDistance(a, b)
    }

    /**
     * Compute bearing from one GPS coordinate to another (degrees from true north).
     */
    fun magneticBearingBetween(from: GpsCoordinate, to: GpsCoordinate): Double {
        val declination = _gpsState.value.calibration?.magneticDeclination ?: 0.0
        return MapCalibrationUtils.magneticBearing(from, to, declination)
    }

    @Deprecated("Use magneticBearingBetween instead")
    fun bearingBetween(from: GpsCoordinate, to: GpsCoordinate): Double {
        return MapCalibrationUtils.bearing(from, to)
    }

    /**
     * Calculate destination GPS coordinate from starting point, bearing, and distance.
     * Uses the Haversine formula for spherical earth approximation.
     *
     * @param start Starting GPS coordinate
     * @param bearingMagnetic Magnetic azimuth in degrees (0..360, measured clockwise from north)
     * @param distanceMeters Distance in meters
     * @return Destination GPS coordinate
     */
    fun calculateDestinationCoordinate(
        start: GpsCoordinate,
        bearingMagnetic: Double,
        distanceMeters: Double
    ): GpsCoordinate {
        val earthRadius = 6371000.0 // meters
        val angularDistance = distanceMeters / earthRadius
        val bearingRad = Math.toRadians(bearingMagnetic)
        val lat1Rad = Math.toRadians(start.latitude)
        val lon1Rad = Math.toRadians(start.longitude)

        val lat2Rad = kotlin.math.asin(
            kotlin.math.sin(lat1Rad) * kotlin.math.cos(angularDistance) +
            kotlin.math.cos(lat1Rad) * kotlin.math.sin(angularDistance) * kotlin.math.cos(bearingRad)
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

    // ──────────────────────────────────────────────
    // Track Recording
    // ──────────────────────────────────────────────

    /**
     * Start recording a track.
     */
    fun startTracking() {
        trackRecorder.startTracking()
    }

    /**
     * Stop recording a track.
     */
    fun stopTracking() {
        trackRecorder.stopTracking()
    }

    /**
     * Record the current GPS position as a track point.
     * Requires calibration to map GPS -> image coordinates.
     * Does nothing if not tracking or not calibrated.
     */
    fun recordTrackPoint() {
        val fix = _gpsState.value.currentFix ?: return
        trackRecorder.recordPoint(fix, calibration)
    }

    /**
     * Clear all track data.
     */
    fun clearTrack() {
        trackRecorder.clearTrack()
    }

    /**
     * Get the current track recorder state.
     */
    fun getTrackData(): TrackRecorderState = trackRecorder.getTrackData()

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        gpsManager.onCleared()
    }

    companion object {
        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NavViewModel(context) as T
            }
        }
    }
}
