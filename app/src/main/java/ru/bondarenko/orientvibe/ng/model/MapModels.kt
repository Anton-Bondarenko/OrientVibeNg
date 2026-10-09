package ru.bondarenko.orientvibe.ng.model

data class BoundingBox(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val label: String,
    var number: Int? = null, // распознанное значение (1-3 цифры), если найдено
    val tileId: Int? = null  // номер тайла детекции, null = глобальная детекция
)

data class RoutePoint(
    val x: Float, // relative 0..1
    val y: Float  // relative 0..1
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
    val progress: Float = 0f,
    val progressMessage: String? = null,
    val startPoint: RoutePoint? = null,
    val finishPoint: RoutePoint? = null,
    val placingMode: PlacingMode = PlacingMode.NONE,
    val northAngle: Float = 0f, // degrees, 0 = up, positive = CW, range -45..45
    val azimuth: Float = 0f,
)

/** Состояние авто-режима — только поля для загрузки и отображения карты. */
data class AutoMapState(
    val imageUri: android.net.Uri? = null,
    val bitmap: android.graphics.Bitmap? = null,
    val controlsBoundingBoxes: List<BoundingBox> = emptyList(),
    val numbersBoundingBoxes: List<BoundingBox> = emptyList(),
    val northAngle: Float = 0f,
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
    val accuracyMeters: Float,
    val speedMs: Float,         // м/с
    val bearingDegrees: Float,   // курс в градусах (0-360)
    val timestamp: Long,         // System.currentTimeMillis()
)

/** Состояние зелёного баннера "можно двигаться" */
data class MoveReadyAlert(
    val active: Boolean = false,
    val remainingMs: Long = 5000L,
    val elapsedMs: Long = 0L,
) {
    /** Прогресс от 0.0 до 1.0 (1.0 = баннер истёк) */
    val progress: Float get() = if (remainingMs <= 0f) 1f else 1f - (elapsedMs.toFloat() / 5000f)
}

/** Текущая выбранная контрольная точка (редактируемое число). */
data class CurrentControl(
    val num: Int = 1,
    val boundingBox: BoundingBox? = null
)
