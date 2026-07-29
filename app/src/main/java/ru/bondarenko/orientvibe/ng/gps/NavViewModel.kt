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
 * All fields are canonical in GpsState — no standalone copies.
 */
class NavViewModel(
    private val context: Context
) : ViewModel() {

    private val gpsManager = GpsManager(context)
    private val trackRecorder = TrackRecorder()
    private val pendingCalibrationPoints = mutableListOf<CalibrationPoint>()
    private var calibration: MapCalibration? = null
    // Внутренние поля для калибровки (не в StateFlow — управляются через методы)
    private var _autoMode = false
    private var _autoCycleActive = false

    private val _gpsState = MutableStateFlow(GpsState())
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    init {
        viewModelScope.launch {
            gpsManager.gpsState.collect { managerState ->
                val state = _gpsState.value
                val hasBothCalibrations = state.startCalibrated && state.finishCalibrated
                // Обновляем трек при каждом GPS-обновлении
                val newTrackPoints = if (trackRecorder.getTrackData().isTracking && managerState.currentFix != null) {
                    trackRecorder.recordPoint(managerState.currentFix, calibration)
                    trackRecorder.getTrackData().trackPoints
                } else {
                    state.trackPoints
                }
                _gpsState.value = state.copy(
                    isGpsEnabled = managerState.isGpsEnabled,
                    currentFix = managerState.currentFix,
                    trackPoints = newTrackPoints,
                    totalDistance = if (trackRecorder.getTrackData().isTracking) {
                        trackRecorder.getTrackData().totalDistance
                    } else {
                        state.totalDistance
                    },
                    isTracking = trackRecorder.getTrackData().isTracking
                )
            }
        }

        viewModelScope.launch {
            trackRecorder.trackState.collect { trackState ->
                val state = _gpsState.value
                val hasBothCalibrations = state.startCalibrated && state.finishCalibrated
                // Вычисляем производные поля из калибровки
                val derivedRouteDistance = if (hasBothCalibrations) {
                    routeDistanceFromTrack(state.originalStartGps)
                } else {
                    0.0
                }
                val derivedMapScale = if (calibration != null) calibration!!.scaleMetersPerPixel else 0.0

                _gpsState.value = state.copy(
                    trackPoints = trackState.trackPoints,
                    totalDistance = trackState.totalDistance,
                    isTracking = trackState.isTracking,
                    routeDistance = derivedRouteDistance.takeIf { it > 0.0 },
                    mapScale = derivedMapScale.takeIf { it > 0.0 }
                )
            }
        }
    }

    /** Вычисляет расстояние между originalStartGps и текущей точкой трека */
    private fun routeDistanceFromTrack(originalStart: GpsCoordinate?): Double {
        val track = trackRecorder.getTrackData().trackPoints
        return if (originalStart != null && track.isNotEmpty()) {
            MapCalibrationUtils.haversineDistance(originalStart, track.last().gpsFix.coordinate)
        } else {
            0.0
        }
    }

    // ── GPS Control ──

    fun startGps() { gpsManager.startGpsUpdates() }
    fun stopGps() { gpsManager.stopGpsUpdates() }
    fun isGpsEnabled(): Boolean = gpsManager.isGpsEnabled()

    // ── Calibration ──

    /** Возвращает originalStartGps для сохранения в MainScreen */
    fun getOriginalStartGps(): GpsCoordinate? = _gpsState.value.originalStartGps

    /** Сохраняет originalStartGps (вызывается при привязке старта) */
    fun setOriginalStartGps(coord: GpsCoordinate) {
        _gpsState.value = _gpsState.value.copy(originalStartGps = coord)
    }

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
                _gpsState.value = _gpsState.value.copy(
                    calibration = result,
                    isCalibrated = true,
                    startCalibrated = true,
                    finishCalibrated = true
                )
            } else {
                _gpsState.value = _gpsState.value.copy(
                    startCalibrated = false,
                    finishCalibrated = false
                )
            }
            pendingCalibrationPoints.clear()
            return true
        }
        return false
    }

    fun clearCalibration() {
        calibration = null
        pendingCalibrationPoints.clear()
        _gpsState.value = _gpsState.value.copy(
            calibration = null,
            isCalibrated = false,
            startCalibrated = false,
            finishCalibrated = false
        )
    }

    fun getCalibration(): MapCalibration? = calibration

    private fun getMagneticDeclination(latitude: Double, longitude: Double, altitude: Double, timestamp: Long): Double {
        val geomagneticField = GeomagneticField(
            latitude.toFloat(), longitude.toFloat(), altitude.toFloat(), timestamp
        )
        return geomagneticField.declination.toDouble()
    }

    // ── Mode Control (синхронизировано с GpsState) ──

    fun setAutoMode(enabled: Boolean) {
        _autoMode = enabled
        _gpsState.value = _gpsState.value.copy(autoMode = enabled)
    }

    fun setAutoCycleActive(active: Boolean) {
        _autoCycleActive = active
        _gpsState.value = _gpsState.value.copy(autoCycleActive = active)
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
        val declination = calibration?.magneticDeclination ?: 0.0
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
        const val GPS_ACCURACY_LOW_THRESHOLD = 30f

        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NavViewModel(context) as T
            }
        }
    }
}
