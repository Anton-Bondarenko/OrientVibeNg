package ru.bondarenko.orientvibe.ng.gps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.bondarenko.orientvibe.ng.model.*

/**
 * Состояние компонента записи траектории движения.
 */
data class TrackRecorderState(
    val trackPoints: List<TrackPoint> = emptyList(),
    val totalDistance: Double = 0.0,
    val isTracking: Boolean = false,
    val version: Int = 0
)

/**
 * Компонент для циклической записи и накопления точек пройденного трека.
 */
class TrackRecorder {

    private var _totalDistance = 0.0
    private var lastTrackPoint: TrackPoint? = null
    private var _version = 0

    // Плоский кольцевой буфер без объектов-узлов LinkedList
    private val _trackPoints = ArrayDeque<TrackPoint>(1024)

    // Лёгкая view-обёртка (O(1) по памяти и времени, без копирования массива)
    private val _readOnlyPointsView: List<TrackPoint> = object : AbstractList<TrackPoint>() {
        override val size: Int
            get() = _trackPoints.size

        override fun get(index: Int): TrackPoint = _trackPoints[index]
    }

    private val _trackState = MutableStateFlow(TrackRecorderState())
    val trackState: StateFlow<TrackRecorderState> = _trackState.asStateFlow()

    val trackData: TrackRecorderState
        get() = _trackState.value

    fun startTracking() {
        _trackPoints.clear()
        _totalDistance = 0.0
        lastTrackPoint = null
        _version++
        _trackState.value = TrackRecorderState(
            trackPoints = _readOnlyPointsView,
            totalDistance = 0.0,
            isTracking = true,
            version = _version
        )
    }

    /** Включает трекинг без очистки — для начальной калибровки, трек сохраняется */
    fun enableTracking() {
        val prev = _trackState.value
        if (prev.isTracking && prev.version == _version) return // Already enabled, avoid redundant StateFlow emit
        _version++
        _trackState.value = TrackRecorderState(
            trackPoints = _readOnlyPointsView,
            totalDistance = _totalDistance,
            isTracking = true,
            version = _version
        )
    }

    fun stopTracking() {
        _version++
        _trackState.value = _trackState.value.copy(isTracking = false, version = _version)
    }

    fun recordPoint(fix: GpsFix, calibration: MapCalibration?): TrackPoint? {
        if (!_trackState.value.isTracking) return null

        val pairGpsToImage = if (calibration != null) {
            MapCalibrationUtils.gpsToImage(fix.coordinate, calibration)
        } else {
            null
        }

        val imageX = pairGpsToImage?.first ?: 0.0
        val imageY = pairGpsToImage?.second ?: 0.0

        val prev = lastTrackPoint
        val distanceFromLast = if (prev != null) {
            MapCalibrationUtils.haversineDistance(
                prev.gpsFix.coordinate,
                fix.coordinate
            )
        } else {
            0.0
        }

        val actualDistanceFromStart = if (prev == null) {
            0.0
        } else {
            prev.distanceFromStart + distanceFromLast
        }

        val trackPoint = TrackPoint(
            latitude = fix.coordinate.latitude,
            longitude = fix.coordinate.longitude,
            accuracy = fix.accuracy,
            bearing = fix.bearing,
            speed = fix.speed,
            imageX = imageX,
            imageY = imageY,
            distanceFromStart = actualDistanceFromStart,
            timestamp = fix.timestamp
        )

        if (_trackPoints.size >= MAX_TRACK_POINTS) {
            _trackPoints.removeFirst()
        }
        _trackPoints.addLast(trackPoint)

        _totalDistance = actualDistanceFromStart
        lastTrackPoint = trackPoint
        _version++

        // Передаём view на все точки без копирования, а version гарантирует срабатывание StateFlow
        _trackState.value = TrackRecorderState(
            trackPoints = _readOnlyPointsView,
            totalDistance = _totalDistance,
            isTracking = true,
            version = _version
        )

        return trackPoint
    }

    fun clearTrack() {
        _trackPoints.clear()
        _totalDistance = 0.0
        lastTrackPoint = null
        _version++
        _trackState.value = TrackRecorderState(
            trackPoints = emptyList(),
            totalDistance = 0.0,
            isTracking = false,
            version = _version
        )
    }

    /**
     * Возвращает последние n точек трека без копирования всего массива.
     */
    fun getTelemetryPoints(n: Int): List<TrackPoint> {
        val size = _trackPoints.size
        if (size == 0 || n <= 0) return emptyList()
        val count = minOf(size, n)
        val startIdx = size - count
        val result = ArrayList<TrackPoint>(count)
        for (i in startIdx until size) {
            result.add(_trackPoints[i])
        }
        return result
    }

    companion object {
        const val MAX_TRACK_POINTS = 100000
    }
}