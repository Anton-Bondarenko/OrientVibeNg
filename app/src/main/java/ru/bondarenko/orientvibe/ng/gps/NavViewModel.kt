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
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.GpsState
import ru.bondarenko.orientvibe.ng.model.MapCalibration

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

    // Calibration state
    var originalStartGps: GpsCoordinate? = null
        private set
    var startCalibrated: Boolean = false
        private set
    var finishCalibrated: Boolean = false
        private set
    var autoMode: Boolean = false
        private set
    var autoCycleActive: Boolean = false
        private set

    init {
        viewModelScope.launch {
            gpsManager.gpsState.collect { managerState ->
                _gpsState.value = _gpsState.value.copy(
                    isGpsEnabled = managerState.isGpsEnabled,
                    currentFix = managerState.currentFix
                )
                if (trackRecorder.getTrackData().isTracking && managerState.currentFix != null) {
                    trackRecorder.recordPoint(managerState.currentFix, calibration)
                }
            }
        }

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

    // ── GPS Control ──

    fun startGps() { gpsManager.startGpsUpdates() }
    fun stopGps() { gpsManager.stopGpsUpdates() }
    fun isGpsEnabled(): Boolean = gpsManager.isGpsEnabled()

    // ── Calibration ──

    fun addCalibrationPoint(gpsFix: GpsFix, imageX: Float, imageY: Float): Boolean {
        val calPoint = CalibrationPoint(gps = gpsFix.coordinate, imageX = imageX, imageY = imageY)
        pendingCalibrationPoints.add(calPoint)

        if (pendingCalibrationPoints.size >= 2) {
            val pointA = pendingCalibrationPoints[0]
            val pointB = pendingCalibrationPoints[1]
            val magneticDeclination = getMagneticDeclination(
                latitude = pointA.gps.latitude,
                longitude = pointA.gps.longitude,
                altitude = gpsFix.altitude,
                timestamp = gpsFix.timestamp
            )
            val result = MapCalibrationUtils.calibrate(pointA, pointB, magneticDeclination)
            if (result != null) {
                calibration = result
                _gpsState.value = _gpsState.value.copy(calibration = result, isCalibrated = true)
            }
            pendingCalibrationPoints.clear()
            return true
        }
        return false
    }

    fun clearCalibration() {
        calibration = null
        pendingCalibrationPoints.clear()
        _gpsState.value = _gpsState.value.copy(calibration = null, isCalibrated = false)
    }

    fun getCalibration(): MapCalibration? = calibration

    private fun getMagneticDeclination(latitude: Double, longitude: Double, altitude: Double, timestamp: Long): Double {
        val geomagneticField = GeomagneticField(
            latitude.toFloat(), longitude.toFloat(), altitude.toFloat(), timestamp
        )
        return geomagneticField.declination.toDouble()
    }

    // ── Calibration Logic (moved from MainScreen) ──

    fun bindGpsToStart(fix: GpsFix, startImageX: Float, startImageY: Float, finishImageX: Float, finishImageY: Float) {
        originalStartGps = fix.coordinate
        addCalibrationPoint(gpsFix = fix, imageX = startImageX, imageY = startImageY)
        startTracking()
        startCalibrated = true

        val finishCoordinate = calculateDestinationCoordinate(
            start = fix.coordinate,
            bearingMagnetic = fix.bearing.toDouble(),
            distanceMeters = 1000.0
        )
        val finishFix = GpsFix(
            coordinate = finishCoordinate,
            accuracy = fix.accuracy,
            bearing = fix.bearing,
            speed = 0f,
            timestamp = System.currentTimeMillis(),
            altitude = fix.altitude
        )
        addCalibrationPoint(gpsFix = finishFix, imageX = finishImageX, imageY = finishImageY)
        finishCalibrated = true
    }

    fun bindGpsToFinish(fix: GpsFix, startImageX: Float, startImageY: Float, finishImageX: Float, finishImageY: Float) {
        if (originalStartGps != null) {
            clearCalibration()
            startCalibrated = false
            finishCalibrated = false

            val startFix = GpsFix(
                coordinate = originalStartGps!!,
                accuracy = fix.accuracy,
                bearing = fix.bearing,
                speed = 0f,
                timestamp = System.currentTimeMillis(),
                altitude = fix.altitude
            )
            addCalibrationPoint(gpsFix = startFix, imageX = startImageX, imageY = startImageY)
            startCalibrated = true
            addCalibrationPoint(gpsFix = fix, imageX = finishImageX, imageY = finishImageY)
            finishCalibrated = true
        } else {
            addCalibrationPoint(gpsFix = fix, imageX = finishImageX, imageY = finishImageY)
            finishCalibrated = true
        }
    }

    fun setAutoMode(enabled: Boolean) { autoMode = enabled }
    fun setAutoCycleActive(active: Boolean) { autoCycleActive = active }

    fun getMagneticBearingBetween(from: GpsCoordinate, to: GpsCoordinate): Double {
        val declination = _gpsState.value.calibration?.magneticDeclination ?: 0.0
        return MapCalibrationUtils.magneticBearing(from, to, declination)
    }

    // ── Coordinate Conversion ──

    fun currentGpsToImage(): Pair<Float, Float>? {
        val fix = _gpsState.value.currentFix ?: return null
        val cal = calibration ?: return null
        return MapCalibrationUtils.gpsToImage(fix.coordinate, cal)
    }

    fun imageToGps(imageX: Float, imageY: Float): GpsCoordinate? {
        val cal = calibration ?: return null
        return MapCalibrationUtils.imageToGps(imageX, imageY, cal)
    }

    fun distanceBetween(a: GpsCoordinate, b: GpsCoordinate): Double {
        return MapCalibrationUtils.haversineDistance(a, b)
    }

    fun magneticBearingBetween(from: GpsCoordinate, to: GpsCoordinate): Double {
        val declination = _gpsState.value.calibration?.magneticDeclination ?: 0.0
        return MapCalibrationUtils.magneticBearing(from, to, declination)
    }

    fun calculateDestinationCoordinate(start: GpsCoordinate, bearingMagnetic: Double, distanceMeters: Double): GpsCoordinate {
        val earthRadius = 6371000.0
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
        return GpsCoordinate(latitude = Math.toDegrees(lat2Rad), longitude = Math.toDegrees(lon2Rad))
    }

    // ── Track Recording ──

    fun startTracking() { trackRecorder.startTracking() }
    fun stopTracking() { trackRecorder.stopTracking() }
    fun recordTrackPoint() {
        val fix = _gpsState.value.currentFix ?: return
        trackRecorder.recordPoint(fix, calibration)
    }
    fun clearTrack() { trackRecorder.clearTrack() }
    fun getTrackData() = trackRecorder.getTrackData()

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