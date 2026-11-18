package ru.bondarenko.orientvibe.ng.auto

import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import kotlin.math.abs

/**
 * Точка GPS-трека с рассчитанным мгновенным и усредненными азимутами движения.
 */
data class TimedGpsPrivate(
    val coordinate: GpsCoordinate,
    val timestamp: Long,
    val realBearing: Double,
    val average3Bearing: Double,
    val average10Bearing: Double
)

/**
 * Обработчик событий TrackCalculation — ведёт историю GPS-позиций
 * и рассчитывает мгновенный и усредненные азимуты движения.
 */
object TrackCalculation {

    private const val TRACK_HISTORY_SECONDS = 90
    private const val HEADING_WINDOW_SECONDS = 4
    private const val HEADING_WINDOW_LONG_SECONDS = 10

    private val _trackHistory = ArrayDeque<TimedGpsPrivate>(128)

    val trackHistory: List<TimedGpsPrivate>
        get() = _trackHistory.toList()

    /**
     * Обработка события ON_MOVE — добавляет новую GPS-точку в историю.
     * Вызывается каждый кадр без исключений.
     */
    fun onMove(gpsFix: GpsFix) {
        val lastVector = getLastVector(gpsFix.coordinate, 0L) ?: 0.0
        val lastVector3 = getLastVector(gpsFix.coordinate, HEADING_WINDOW_SECONDS * 1000L) ?: 0.0
        val lastVector10 = getLastVector(gpsFix.coordinate, HEADING_WINDOW_LONG_SECONDS * 1000L) ?: 0.0

        _trackHistory.addLast(
            TimedGpsPrivate(
                coordinate = gpsFix.coordinate,
                timestamp = gpsFix.timestamp,
                realBearing = lastVector,
                average3Bearing = lastVector3,
                average10Bearing = lastVector10
            )
        )

        val cutoff = gpsFix.timestamp - (TRACK_HISTORY_SECONDS * 1000L)
        while (_trackHistory.isNotEmpty() && _trackHistory.first().timestamp < cutoff) {
            _trackHistory.removeFirst()
        }
    }

    /**
     * Возвращает последний усредненный азимут (за ~2 сек).
     */
    fun getLastAverage3Bearing(): Double? {
        return if (_trackHistory.size >= 2) _trackHistory.last().average3Bearing else null
    }

    // ──────────── Private helpers ────────────

    private fun getLastVector(gps: GpsCoordinate, time: Long): Double? {
        if (_trackHistory.size < 2) return null

        if (time == 0L) {
            return MapGeometry.bearing(_trackHistory.last().coordinate, gps)
        }

        val windowEnd = _trackHistory.last().timestamp - time
        var startIdx = 0
        while (startIdx < _trackHistory.size && _trackHistory[startIdx].timestamp < windowEnd) {
            startIdx++
        }

        if (startIdx >= _trackHistory.size) {
            startIdx = _trackHistory.size - 1
        }

        return MapGeometry.bearing(_trackHistory[startIdx].coordinate, gps)
    }
}
