package ru.bondarenko.orientvibe.ng.gps

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsState
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * ViewModel для управления спутниковыми GPS данными, реактивной записи треков и вычисления гео-метрик.
 */
class NavViewModel(context: Context) : ViewModel() {

    private var _autoBindActive = false
    private var _autoBindActiveFlag = false
    private var _imageDimensions: Pair<Double, Double>? = null
    private var _kpBoxes: List<BoundingBox> = emptyList()
    private var calibration: MapCalibration? = null

    private val gpsManager = GpsManager(context)
    private val trackRecorder = TrackRecorder()
    private val hitTestRadiusRel = 0.05f

    private val _gpsState = MutableStateFlow(GpsState())
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    val isGpsEnabled: Boolean
        get() = gpsManager.isGpsEnabled

    val trackData: TrackRecorderState
        get() = trackRecorder.trackData

    init {
        // Реактивное слияние и обработка потоков состояния GPS менеджера и рекордера трека
        viewModelScope.launch {
            combine(
                gpsManager.gpsState,
                trackRecorder.trackState
            ) { managerState, recorderState ->
                val state = _gpsState.value
                val currentFix = managerState.currentFix

                val trackPoints = if (recorderState.isTracking && currentFix != null) {
                    trackRecorder.recordPoint(currentFix, calibration)
                    recorderState.trackPoints
                } else {
                    state.trackPoints
                }

                val derivedMapScaleX = calibration?.scaleMetersPerMapX ?: 0.0
                val derivedMapScaleY = calibration?.scaleMetersPerMapY ?: 0.0

                state.copy(
                    isGpsEnabled = managerState.isGpsEnabled,
                    currentFix = currentFix,
                    calibration = calibration,
                    autoBindActive = _autoBindActive,
                    trackPoints = trackPoints,
                    totalDistance = if (recorderState.isTracking) recorderState.totalDistance else state.totalDistance,
                    isTracking = recorderState.isTracking,
                    mapScaleX = if (derivedMapScaleX > 0.0) derivedMapScaleX else null,
                    mapScaleY = if (derivedMapScaleY > 0.0) derivedMapScaleY else null
                )
            }.collect { updatedState ->
                _gpsState.value = updatedState
            }
        }
    }

    fun startGps() {
        gpsManager.startGpsUpdates()
    }

    fun stopGps() {
        gpsManager.stopGpsUpdates()
    }

    fun setAutoBindActive(active: Boolean) {
        _autoBindActive = active
        _autoBindActiveFlag = active
        _gpsState.value = _gpsState.value.copy(autoBindActive = active)
    }

    fun getAutoBindActive(): Boolean = _autoBindActive

    fun getCurrentGpsImageAbs(northAngleDeg: Double): Pair<Double, Double>? {
        val fix = _gpsState.value.currentFix ?: return null
        val cal = calibration ?: return null

        val result = MapCalibrationUtils.gpsToImage(fix.coordinate, cal, northAngleDeg)
        if (result?.first == null || result.second == null) return null
        return Pair(result.first!!, result.second!!)
    }

    fun checkKpHit(absX: Double, absY: Double): Int {
        val dims = _imageDimensions ?: return -1
        if (dims.first <= 0.0 || dims.second <= 0.0 || !_autoBindActiveFlag) return -1

        val relX = absX / dims.first
        val relY = absY / dims.second

        var closestIdx = -1
        var closestDistSq = (hitTestRadiusRel * hitTestRadiusRel).toDouble()

        _kpBoxes.forEachIndexed { index, box ->
            val dx = box.centerX - relX
            val dy = box.centerY - relY
            val distSq = (dx * dx) + (dy * dy)
            if (distSq < closestDistSq) {
                closestDistSq = distSq
                closestIdx = index
            }
        }
        return closestIdx
    }

    fun setAutoBindKpBoxes(boxes: List<BoundingBox>) {
        _kpBoxes = boxes
    }

    fun setAutoBindImageDimensions(dims: Pair<Double, Double>?) {
        _imageDimensions = dims
    }

    fun distanceBetween(a: GpsCoordinate, b: GpsCoordinate): Double {
        return MapCalibrationUtils.haversineDistance(a, b)
    }

    fun magneticBearingBetween(from: GpsCoordinate, to: GpsCoordinate): Double {
        val declination = calibration?.physicalDeclination ?: 0.0
        return MapCalibrationUtils.magneticBearing(from, to, declination)
    }

    fun calculateDestinationCoordinate(start: GpsCoordinate, bearingMagnetic: Double, distanceMeters: Double): GpsCoordinate {
        val angularDistance = distanceMeters / 6371000.0
        val bearingRad = Math.toRadians(bearingMagnetic)
        val lat1Rad = Math.toRadians(start.latitude)
        val lon1Rad = Math.toRadians(start.longitude)

        val lat2Rad = asin(
            (sin(lat1Rad) * cos(angularDistance)) + (cos(lat1Rad) * sin(angularDistance) * cos(bearingRad))
        )
        val lon2Rad = atan2(
            sin(bearingRad) * sin(angularDistance) * cos(lat1Rad),
            cos(angularDistance) - (sin(lat1Rad) * sin(lat2Rad))
        ) + lon1Rad

        return GpsCoordinate(Math.toDegrees(lat2Rad), Math.toDegrees(lon2Rad))
    }

    fun startTracking() {
        trackRecorder.startTracking()
    }

    fun stopTracking() {
        trackRecorder.stopTracking()
    }

    fun recordTrackPoint() {
        val fix = _gpsState.value.currentFix ?: return
        trackRecorder.recordPoint(fix, calibration)
    }

    fun clearTrack() {
        trackRecorder.clearTrack()
    }

    fun applyNewCalibration(cal: MapCalibration) {
        calibration = cal
        _gpsState.value = _gpsState.value.copy(
            calibration = cal,
            isCalibrated = true
        )
    }

    fun resetCalibration() {
        calibration = null
        _gpsState.value = _gpsState.value.copy(
            calibration = null,
            isCalibrated = false
        )
    }

    override fun onCleared() {
        gpsManager.onCleared()
    }

    companion object {
        const val GPS_ACCURACY_LOW_THRESHOLD = 30.0f

        fun Factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return NavViewModel(context) as T
            }
        }
    }
}
