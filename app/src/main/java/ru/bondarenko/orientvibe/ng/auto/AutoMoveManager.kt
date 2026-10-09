package ru.bondarenko.orientvibe.ng.auto

import android.util.Log
import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.AutoMapState
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.CurrentControl
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MovementState

/** Дистанция до КП, при которой считается что пришли (м) */
const val AUTO_APPROACHED_DIST = 50f
const val DIST_GAP = 3f

/** Минимальная дистанция от начальной точки, чтобы подтвердить уход (м) */
private const val AUTO_MIN_MOVE_METERS = 3f

/** Время стабильного увеличения дистанции (мс) */
private const val AUTO_CONFIRM_TIMEOUT_MS = 5_000L

private const val TAG = "AutoMoveManager"

/** Результат doMove — immutable, ViewModel делает reassignment и StateFlow нотифицирует наблюдателей. */
data class DoMoveResult(
    val distanceToTarget: Float,
    val switchedControl: Boolean = false,
) {
    companion object {
        fun noSwitch(dist: Float) = DoMoveResult(distanceToTarget = dist)
    }
}

class AutoMoveManager {
    /** Момент первого приближения к текущей КП, когда начали уходить */
    private var _approachTimeMs: Long? = null

    /** Дистанция в момент первого приближения (baseline) */
    private var _approachDistance: Float = 0f
    private var _approachDetected: Boolean = false

    /**
     * Выполняет «движение»: вычисляет дистанцию до текущей КП.
     * Переключает на следующую когда:
     * 1) расстояние <= AUTO_APPROACHED_DIST (20 м), И
     * 2) с момента первого приближения прошло ≥ 5 секунд,
     *    И дистанция увеличилась минимум на AUTO_MIN_MOVE_METERS
     *
     * Возвращает DoMoveResult. ViewModel должен сделать:
     *   _movementState.value = _autoMoveManager.doMove(...)
     * чтобы StateFlow уведомил наблюдателей.
     */
    fun doMove(
        autoMapState: AutoMapState,
        movementState: MovementState,
        mapCalibration: MapCalibration,
        gpsFix: GpsFix
    ): MovementState {
        val boundingBox = movementState.currentControl.boundingBox
        if (boundingBox != null) {
            val currentControlCoordinates =
                getBoundingBoxCoordinates(boundingBox, mapCalibration, autoMapState)
                    ?: gpsFix.coordinate
            val distance =
                MapGeometry.haversineDistance(gpsFix.coordinate, currentControlCoordinates)
                    .toFloat()

            if (shouldSwitch(distance)) {
                switchControl(autoMapState, movementState)
            }
            _approachDistance = distance
            return movementState
        }
        return movementState
    }

    /**
     * @return true если прошло ≥ 5с и дистанция стабильно выросла от baseline
     */
    private fun shouldSwitch(distance: Float): Boolean {
        // Первый вход в зону КП — фиксируем baseline
        if (distance < AUTO_APPROACHED_DIST && !_approachDetected) {
            _approachDetected = true
        }

        // удаляемся от КП
        if (_approachDistance < (distance + DIST_GAP) && _approachDetected) {
            if (_approachTimeMs == null) _approachTimeMs = System.currentTimeMillis()

            val elapsed = System.currentTimeMillis() - _approachTimeMs!!
            if (elapsed >= AUTO_CONFIRM_TIMEOUT_MS) { // переключаемся на следующий
                return true
            }
        } else {
            _approachTimeMs = null
        }
        return false
    }

    private fun detectionReset(){
        _approachTimeMs = null
        _approachDetected = false
        _approachDistance = Float.MAX_VALUE
    }

    private fun switchControl(mapState: AutoMapState, ms: MovementState) {
        val currentNum = ms.currentControl.num
        Log.d(TAG, "дошли до КП #$currentNum, переключение на следующую")

        // Сохраняем старую текущую КП в prev перед обновлением
        val oldCurrentBox = ms.currentControl.boundingBox
        ms.prevControl = CurrentControl(
            num = currentNum,
            boundingBox = oldCurrentBox
        )

        val nextNum = currentNum + 1
        val nextBox = mapState.controlsBoundingBoxes.find { it.number == nextNum }

        ms.currentControl = CurrentControl(
            num = nextNum,
            boundingBox = nextBox
        )
        detectionReset()
    }

    fun getBoundingBoxCoordinates(
        boundingBox: BoundingBox,
        mapCalibration: MapCalibration,
        autoMapState: AutoMapState
    ): GpsCoordinate? {
        return MapGeometry.imageToGps(
            boundingBox.centerX, boundingBox.centerY, mapCalibration, autoMapState.northAngle
        )
    }
}
