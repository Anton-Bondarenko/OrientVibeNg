package ru.bondarenko.orientvibe.ng.gps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.bondarenko.orientvibe.ng.model.*
import java.util.LinkedList

/**
 * Состояние компонента записи траектории движения.
 */
data class TrackRecorderState(
    val trackPoints: List<TrackPoint> = emptyList(),
    val totalDistance: Double = 0.0,
    val isTracking: Boolean = false
)

/**
 * Компонент для циклической записи и накопления точек пройденного трека.
 */
class TrackRecorder {

    private var _totalDistance = 0.0
    private var lastTrackPoint: TrackPoint? = null
    private val _trackPoints = LinkedList<TrackPoint>()

    private val _trackState = MutableStateFlow(TrackRecorderState())
    val trackState: StateFlow<TrackRecorderState> = _trackState.asStateFlow()

    val trackData: TrackRecorderState
        get() = _trackState.value

    fun startTracking() {
        _trackPoints.clear()
        _totalDistance = 0.0
        lastTrackPoint = null
        _trackState.value = TrackRecorderState(
            trackPoints = emptyList(),
            totalDistance = 0.0,
            isTracking = true
        )
    }

    fun stopTracking() {
        _trackState.value = _trackState.value.copy(isTracking = false)
    }

    fun recordPoint(fix: GpsFix, calibration: MapCalibration?): TrackPoint? {
        if (!_trackState.value.isTracking || calibration == null) return null

        val pairGpsToImage = MapCalibrationUtils.gpsToImage(fix.coordinate, calibration)
        if (pairGpsToImage.first == null || pairGpsToImage.second == null) return null

        val distanceFromLast = if (lastTrackPoint != null) {
            MapCalibrationUtils.haversineDistance(
                lastTrackPoint!!.gpsFix.coordinate, // Используем gpsFix для доступа к координатам
                fix.coordinate
            )
        } else {
            0.0
        }

        val actualDistanceFromStart = if (_trackPoints.isEmpty()) {
            0.0
        } else {
            _trackPoints.last.distanceFromStart + distanceFromLast
        }

        val trackPoint = TrackPoint(
            gpsFix = fix,
            imageX = pairGpsToImage.first!!,
            imageY = pairGpsToImage.second!!,
            distanceFromStart = actualDistanceFromStart,
            timestamp = fix.timestamp
        )

        _trackPoints.addLast(trackPoint)
        _totalDistance = actualDistanceFromStart
        lastTrackPoint = trackPoint

        while (_trackPoints.size > MAX_TRACK_POINTS) {
            _trackPoints.removeFirst()
        }

        _trackState.value = TrackRecorderState(
            trackPoints = _trackPoints.toList(),
            totalDistance = _totalDistance,
            isTracking = true
        )

        return trackPoint
    }

    fun clearTrack() {
        _trackPoints.clear()
        _totalDistance = 0.0
        lastTrackPoint = null
        _trackState.value = TrackRecorderState(
            trackPoints = emptyList(),
            totalDistance = 0.0,
            isTracking = false
        )
    }

    companion object {
        const val MAX_TRACK_POINTS = 60000
    }
}
