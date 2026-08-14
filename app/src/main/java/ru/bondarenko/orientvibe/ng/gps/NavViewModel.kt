package ru.bondarenko.orientvibe.ng.gps

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsState
import ru.bondarenko.orientvibe.ng.model.BoundingBox
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
    private var calibration: MapCalibration? = null
    private var _autoBindActive = false

    // ── Auto-bind internal state (inlined from AutoBindManager to avoid cross-file incremental compile issues) ──

    private var _autoBindActiveFlag = false
    private var _kpBoxes: List<BoundingBox> = emptyList()
    private var _imageDimensions: Pair<Float, Float>? = null
    private val HIT_TEST_RADIUS_REL = 0.05f

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
                    isTracking = trackRecorder.getTrackData().isTracking,
                    autoBindActive = _autoBindActive
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
                    mapScale = derivedMapScale.takeIf { it > 0.0 },
                    autoBindActive = _autoBindActive
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

    /** Применяет одно-точечную калибровку (только старт). Флаг finishCalibrated остаётся false до «Здесь финиш». */
    fun applyStartCalibration(cal: MapCalibration) {
        calibration = cal
        _gpsState.value = _gpsState.value.copy(
            calibration = cal,
            isCalibrated = true,
            startCalibrated = true,
            finishCalibrated = false
        )
    }



    // ── Auto-Bind GPS Mode (привязка "Здесь") ──

    fun setAutoBindActive(active: Boolean) {
        _autoBindActive = active
        _autoBindActiveFlag = active
        _gpsState.value = _gpsState.value.copy(autoBindActive = active)
    }

    fun getAutoBindActive(): Boolean = _autoBindActive

    /** Convert current GPS fix to absolute image-space coordinates (pixels). */
    fun getCurrentGpsImageAbs(northAngleDeg: Float): Pair<Float, Float>? {
        val fix = _gpsState.value.currentFix ?: return null
        val cal = calibration ?: return null
        val dims = _imageDimensions ?: return null
        return MapCalibrationUtils.gpsToImageAbs(fix.coordinate, cal, dims, northAngleDeg)
    }

    /** Check if image-space point is near any KP; returns index or -1. */
    fun checkKpHit(absX: Float, absY: Float): Int {
        if (!_autoBindActiveFlag) return -1
        val dims = _imageDimensions ?: return -1
        if (dims.first <= 0f || dims.second <= 0f) return -1
        val relX = absX / dims.first
        val relY = absY / dims.second
        var closestIdx = -1
        var closestDistSq = (HIT_TEST_RADIUS_REL * HIT_TEST_RADIUS_REL).toDouble()
        for ((i, box) in _kpBoxes.withIndex()) {
            val dx = box.centerX - relX
            val dy = box.centerY - relY
            val d2 = (dx * dx + dy * dy).toDouble()
            if (d2 < closestDistSq) {
                closestDistSq = d2
                closestIdx = i
            }
        }
        return closestIdx
    }

    // ── Exposed setters for MainScreen LaunchedEffect ──

    fun setAutoBindKpBoxes(boxes: List<BoundingBox>) {
        _kpBoxes = boxes
    }

    fun setAutoBindImageDimensions(dims: Pair<Float, Float>) {
        _imageDimensions = dims
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
        val declination = calibration?.physicalDeclination ?: 0.0
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

    /** Применяет новую калибровку из MapCalibrationUtils (вся логика в Utils). */
    fun applyNewCalibration(cal: MapCalibration) {
        calibration = cal
        _gpsState.value = _gpsState.value.copy(
            calibration = cal,
            isCalibrated = true,
            startCalibrated = true,
            finishCalibrated = true
        )
    }

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
