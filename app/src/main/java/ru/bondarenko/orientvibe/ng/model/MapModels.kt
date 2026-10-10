package ru.bondarenko.orientvibe.ng.model

data class BoundingBox(
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val confidence: Double,
    val label: String,
    var number: Int? = null, // распознанное значение (1-3 цифры), если найдено
    val tileId: Int? = null  // номер тайла детекции, null = глобальная детекция
)

data class RoutePoint(
    val x: Double, // relative 0..1
    val y: Double  // relative 0..1
)

enum class PlacingMode {
    NONE,
    PLACING_START,
    PLACING_FINISH
}

data class MapState(
    val imageUri: android.net.Uri? = null,
    val bitmap: android.graphics.Bitmap? = null,
    val controlsBoundingBoxes: List<BoundingBox> = emptyList(),
    val numbersBoundingBoxes: List<BoundingBox> = emptyList(),
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val progress: Double = 0.0,
    val progressMessage: String? = null,
    val startPoint: RoutePoint? = null,
    val finishPoint: RoutePoint? = null,
    val placingMode: PlacingMode = PlacingMode.NONE,
    val northAngle: Double = 0.0, // degrees, 0 = up, positive = CW, range -45..45
    val azimuth: Double = 0.0,
)

/** Состояние авто-режима — только поля для загрузки и отображения карты. */
data class AutoMapState(
    val imageUri: android.net.Uri? = null,
    val bitmap: android.graphics.Bitmap? = null,
    val controlsBoundingBoxes: List<BoundingBox> = emptyList(),
    val numbersBoundingBoxes: List<BoundingBox> = emptyList(),
    val northAngle: Double = 0.0,
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val progressMessage: String? = null,
) {
    val isLoading: Boolean get() = bitmap == null
}

/** Один снимок телеметрии авто-режима (координаты, скорость, курс). */
data class AutoModeTelemetryPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val speedMs: Double,         // м/с
    val bearingDegrees: Double,   // курс в градусах (0-360)
    val timestamp: Long,         // System.currentTimeMillis()
)

/** Состояние зелёного баннера "можно двигаться" */
data class MoveReadyAlert(
    val active: Boolean = false,
    val remainingMs: Long = 5000L,
    val elapsedMs: Long = 0L,
) {
    /** Прогресс от 0.0 до 1.0 (1.0 = баннер истёк) */
    val progress: Double get() = if (remainingMs <= 0f) 1.0 else 1.0 - (elapsedMs.toDouble() / 5000.0)
}

/** Текущая выбранная контрольная точка (редактируемое число). */
data class CurrentControl(
    val num: Int = 1,
    val boundingBox: BoundingBox? = null
)
