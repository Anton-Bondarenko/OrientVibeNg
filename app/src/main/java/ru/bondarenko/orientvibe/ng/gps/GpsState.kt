package ru.bondarenko.orientvibe.ng.gps

// All GPS models moved to ru.bondarenko.orientvibe.ng.model.GpsModels
// This file kept for backward compatibility - re-exports all types.
// TODO: Remove this file after updating all imports.

import ru.bondarenko.orientvibe.ng.model.AccuracyLevel as ModelAccuracyLevel
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint as ModelCalibrationPoint
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate as ModelGpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix as ModelGpsFix
import ru.bondarenko.orientvibe.ng.model.GpsState as ModelGpsState
import ru.bondarenko.orientvibe.ng.model.MapCalibration as ModelMapCalibration
import ru.bondarenko.orientvibe.ng.model.TrackPoint as ModelTrackPoint

typealias GpsCoordinate = ModelGpsCoordinate
typealias GpsFix = ModelGpsFix
typealias CalibrationPoint = ModelCalibrationPoint
typealias MapCalibration = ModelMapCalibration
typealias TrackPoint = ModelTrackPoint
typealias AccuracyLevel = ModelAccuracyLevel
typealias GpsState = ModelGpsState