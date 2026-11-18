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
    private var calibration: MapCalibration? = null

    private val gpsManager = GpsManager(context)
    val trackRecorder = TrackRecorder()

    private val _gpsState = MutableStateFlow(GpsState())
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    val isGpsEnabled: Boolean
        get() = gpsManager.isGpsEnabled

    val trackData: TrackRecorderState
        get() = trackRecorder.trackData

    init {
        // 1. Слушаем ТОЛЬКО обновления от GPS-приемника
        viewModelScope.launch {
            gpsManager.gpsState.collect { managerState ->
                val state = _gpsState.value
                val currentFix = managerState.currentFix

                // Записываем точку в трек ТОЛЬКО когда пришел новый fix от GPS
                if (trackRecorder.trackData.isTracking && currentFix != null && currentFix != state.currentFix) {
                    trackRecorder.recordPoint(currentFix, calibration)
                }

                _gpsState.value = state.copy(
                    isGpsEnabled = managerState.isGpsEnabled,
                    currentFix = currentFix,
                    calibration = calibration,
                    autoBindActive = _autoBindActive
                )
            }
        }

        // 2. Отдельно слушаем обновления списка точек из TrackRecorder
        // Используем version-флаг для подавления избыточных StateFlow emissions:
        // emitter только когда recorderState.version изменился (реальное обновление трека).
        viewModelScope.launch {
            trackRecorder.trackState.collect { recorderState ->
                val state = _gpsState.value
                // trackPoints всегда одна и та же view-ссылка (_readOnlyPointsView),
                // so copy() только когда totalDistance или isTracking реально изменились.
                if (state.totalDistance == recorderState.totalDistance && state.isTracking == recorderState.isTracking) return@collect
                val derivedMapScaleX = calibration?.scaleMetersPerMapX ?: 0.0
                val derivedMapScaleY = calibration?.scaleMetersPerMapY ?: 0.0

                _gpsState.value = state.copy(
                    trackPoints = recorderState.trackPoints,
                    totalDistance = if (recorderState.isTracking) recorderState.totalDistance else state.totalDistance,
                    isTracking = recorderState.isTracking,
                    mapScaleX = if (derivedMapScaleX > 0.0) derivedMapScaleX else null,
                    mapScaleY = if (derivedMapScaleY > 0.0) derivedMapScaleY else null
                )
            }
        }
    }

    fun startGps() {
        gpsManager.startGpsUpdates()
    }

    /** Включает трекинг без очистки — для начальной калибровки */
    fun enableTrackingWithoutClearing() {
        trackRecorder.enableTracking()
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
