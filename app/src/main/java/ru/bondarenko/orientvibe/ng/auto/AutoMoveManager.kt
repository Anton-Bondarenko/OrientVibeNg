package ru.bondarenko.orientvibe.ng.auto

import ru.bondarenko.orientvibe.ng.gps.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.model.AutoMapState
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MovementState

class AutoMoveManager {
    fun doMove(
        autoMapState: AutoMapState,
        movementState: MovementState,
        mapCalibration: MapCalibration,
        gpsFix: GpsFix
    ) {
        val boundingBox = movementState.currentControl.boundingBox
        if (boundingBox != null) {
            val currentControlCoordinates = getBoundingBoxCoordinates(boundingBox, mapCalibration, autoMapState) ?: gpsFix.coordinate
            val distance = MapGeometry.haversineDistance(gpsFix.coordinate, currentControlCoordinates)
        }
    }

    fun getBoundingBoxCoordinates(boundingBox: BoundingBox, mapCalibration: MapCalibration, autoMapState: AutoMapState): GpsCoordinate? {
        return MapGeometry.imageToGps(
                boundingBox.centerX, boundingBox.centerY, mapCalibration, autoMapState.northAngle
            )
    }
}