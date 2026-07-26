package ru.bondarenko.orientvibe.ng.gps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records a track of GPS positions mapped to image coordinates.
 * Tracks total distance and provides per-point distance from start.
 */
class TrackRecorder {

    private val _trackPoints = mutableListOf<TrackPoint>()
    private var _totalDistance = 0.0

    private val _trackState = MutableStateFlow(
        TrackRecorderState(
            trackPoints = emptyList(),
            totalDistance = 0.0,
            isTracking = false
        )
    )
    val trackState: StateFlow<TrackRecorderState> = _trackState.asStateFlow()

    private var lastTrackPoint: TrackPoint? = null

    /**
     * Start recording a new track. Clears any previous track data.
     */
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

    /**
     * Stop recording.
     */
    fun stopTracking() {
        _trackState.value = _trackState.value.copy(isTracking = false)
    }

    /**
     * Record a new GPS fix mapped to image coordinates.
     * Uses the provided calibration to convert GPS -> image coordinates.
     *
     * @param fix The current GPS fix
     * @param calibration The map calibration (must be non-null)
     * @return The recorded TrackPoint, or null if calibration is null
     */
    fun recordPoint(
        fix: GpsFix,
        calibration: MapCalibration?
    ): TrackPoint? {
        if (!_trackState.value.isTracking) return null
        if (calibration == null) return null

        // Convert GPS to image coordinates
        val imageCoords = MapCalibrationUtils.gpsToImage(fix.coordinate, calibration)
            ?: return null

        // Compute distance from the last point
        val distanceFromLast = if (lastTrackPoint != null) {
            MapCalibrationUtils.haversineDistance(
                lastTrackPoint!!.gpsFix.coordinate,
                fix.coordinate
            )
        } else {
            0.0
        }

        // Compute total distance from start
        val actualDistanceFromStart = if (_trackPoints.isEmpty()) {
            0.0
        } else {
            _trackPoints.last().distanceFromStart + distanceFromLast
        }

        val trackPoint = TrackPoint(
            gpsFix = fix,
            imageX = imageCoords.first,
            imageY = imageCoords.second,
            distanceFromStart = actualDistanceFromStart,
            timestamp = fix.timestamp
        )

        _trackPoints.add(trackPoint)
        _totalDistance = actualDistanceFromStart
        lastTrackPoint = trackPoint

        _trackState.value = TrackRecorderState(
            trackPoints = _trackPoints.toList(),
            totalDistance = _totalDistance,
            isTracking = true
        )

        return trackPoint
    }

    /**
     * Get the current track data.
     */
    fun getTrackData(): TrackRecorderState = _trackState.value

    /**
     * Clear all recorded track data.
     */
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
}

/**
 * State of the track recorder exposed to the UI.
 */
data class TrackRecorderState(
    val trackPoints: List<TrackPoint>,
    val totalDistance: Double,
    val isTracking: Boolean
)