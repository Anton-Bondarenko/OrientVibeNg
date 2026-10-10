package ru.bondarenko.orientvibe.ng.auto

import android.util.Log
import ru.bondarenko.orientvibe.ng.gps.CalibrationPoint
import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.AutoMapState
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.CurrentControl
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MovementState

/** Дистанция до КП, при которой считается что пришли (м) */
const val AUTO_APPROACHED_DIST = 100f
const val DIST_GAP = 3f

/** Минимальная дистанция от начальной точки, чтобы подтвердить уход (м) */
private const val AUTO_MIN_MOVE_METERS = 3f

/** Время стабильного увеличения дистанции (мс) */
private const val AUTO_CONFIRM_TIMEOUT_MS = 10_000L

private const val TAG = "AutoMoveManager"

/** Результат doMove — immutable, ViewModel делает reassignment и StateFlow нотифицирует наблюдателей. */
data class DoMoveResult(
    val distanceToTarget: Double,
    val switchedControl: Boolean = false,
) {
    companion object {
        fun noSwitch(dist: Double) = DoMoveResult(distanceToTarget = dist)
    }
}

class AutoMoveManager {
    /** Момент первого приближения к текущей КП, когда начали уходить */
    private var _approachTimeMs: Long? = null

    /** Дистанция в момент первого приближения (baseline) */
    private var _approachDistance: Double = 0.0
    private var _approachDetected: Boolean = false
    private var _supposeControlHere: ru.bondarenko.orientvibe.ng.gps.GpsCoordinate? = null

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
        gpsFix: GpsFix,
        proportion: Double
    ): MovementState {
        val boundingBox = movementState.currentControl.boundingBox
        if (boundingBox != null) {
            val currentControlCoordinates =
                getBoundingBoxCoordinates(boundingBox, mapCalibration, autoMapState)
                    ?: gpsFix.coordinate
            val distance =
                MapGeometry.haversineDistance(gpsFix.coordinate, currentControlCoordinates)
                    .toDouble()

            if (shouldSwitch(distance, gpsFix) && mapCalibration.pointB != null) {
                movementState.newMapCalibration = switchControl(
                    autoMapState,
                    movementState,
                    mapCalibration,
                    autoMapState,
                    proportion
                )
            } else {
                movementState.newMapCalibration = null
            }
            _approachDistance = distance
            return movementState
        }
        return movementState
    }

    /**
     * @return true если прошло ≥ 5с и дистанция стабильно выросла от baseline
     */
    private fun shouldSwitch(distance: Double, gpsFix: GpsFix): Boolean {
        // Первый вход в зону КП — фиксируем baseline
        if (distance < AUTO_APPROACHED_DIST && !_approachDetected) {
            _approachDetected = true
        }

        // удаляемся от КП
        if (_approachDistance < (distance + DIST_GAP) && _approachDetected) {
            if (_approachTimeMs == null) {
                _approachTimeMs = System.currentTimeMillis()
                _supposeControlHere = gpsFix.coordinate
            }

            val elapsed = System.currentTimeMillis() - _approachTimeMs!!
            if (elapsed >= AUTO_CONFIRM_TIMEOUT_MS) { // переключаемся на следующий
                return true
            }
        } else {
            _approachTimeMs = null
            _supposeControlHere = null
        }
        return false
    }

    private fun detectionReset() {
        _approachTimeMs = null
        _approachDetected = false
        _approachDistance = Double.MAX_VALUE
        _supposeControlHere = null
    }

    private fun switchControl(
        mapState: AutoMapState,
        ms: MovementState,
        mapCalibration: MapCalibration,
        autoMapState: AutoMapState,
        proportion: Double
    ): MapCalibration? {
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
        var newCalibration: MapCalibration? = null
        val supposeControlHere = _supposeControlHere
        if (oldCurrentBox != null && supposeControlHere != null && mapCalibration.pointB != null) {
            newCalibration = MapGeometry.computeCalibrationSoft(
                mapCalibration.pointA,
                CalibrationPoint(supposeControlHere, oldCurrentBox.centerX, oldCurrentBox.centerY),
                autoMapState.northAngle, mapCalibration, proportion
            )
        }
        detectionReset()

        return newCalibration
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
