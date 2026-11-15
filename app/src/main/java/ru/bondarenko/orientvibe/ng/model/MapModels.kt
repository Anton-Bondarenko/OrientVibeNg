package ru.bondarenko.orientvibe.ng.model

import android.graphics.Bitmap
import android.net.Uri

/**
 * Режимы расстановки контрольных точек на карте.
 */
enum class PlacingMode {
    NONE,
    PLACING_START,
    PLACING_FINISH
}

/**
 * Ограничивающая рамка распознанного на карте объекта (YOLO Bounding Box).
 */
data class BoundingBox(
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val confidence: Double,
    val label: String,
    var number: Int? = null,
    val tileId: Int? = null
)

/**
 * Координатная точка маршрута на изображении карты.
 */
data class RoutePoint(
    val x: Double,
    val y: Double
)

/**
 * Модель текущего целевого контрольного пункта.
 */
data class CurrentControl(
    val num: Int = 1,
    val boundingBox: BoundingBox? = null
)
/**
 * Модель оповещения о готовности к переходу/перемещению.
 */
data class MoveReadyAlert(
    val active: Boolean = false,
    val remainingMs: Long = 5000L, // Дефолтный системный таймаут корутин
    val elapsedMs: Long = 0L
) {
    val progress: Double
        get() {
            if (remainingMs <= 0) return 1.0
            return 1.0 - (elapsedMs.toDouble() / 5000.0)
        }
}

/**
 * Глобальное состояние автоматической обработки карты.
 */
data class AutoMapState(
    val imageUri: Uri? = null,
    val bitmap: Bitmap? = null,
    val controlsBoundingBoxes: List<BoundingBox> = emptyList(),
    val numbersBoundingBoxes: List<BoundingBox> = emptyList(),
    val northAngle: Double = 0.0,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val progressMessage: String? = null,
    val mapCalibration: MapCalibration? = null
) {
    val isLoading: Boolean
        get() = bitmap == null
}

/**
 * Основное комплексное состояние интерактивного экрана карты.
 */
data class MapState(
    val imageUri: Uri? = null,
    val bitmap: Bitmap? = null,
    val controlsBoundingBoxes: List<BoundingBox> = emptyList(),
    val numbersBoundingBoxes: List<BoundingBox> = emptyList(),
    val showAllBoundingBoxes: Boolean = false,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val progress: Double = 0.0,
    val progressMessage: String? = null,
    val startPoint: RoutePoint? = null,
    val finishPoint: RoutePoint? = null,
    val placingMode: PlacingMode = PlacingMode.NONE,
    val northAngle: Double = 0.0,
    val azimuth: Double = 0.0
)

