package ru.bondarenko.orientvibe.ng.model

data class BoundingBox(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,
    val label: String,
    val number: Int? = null // распознанное значение (1-3 цифры), если найдено
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
    val azimuth: Float = 0f
)
