package ru.bondarenko.orientvibe.ng.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.bondarenko.orientvibe.ng.model.MapState
import ru.bondarenko.orientvibe.ng.model.PlacingMode
import ru.bondarenko.orientvibe.ng.model.RoutePoint
import ru.bondarenko.orientvibe.ng.yolo.CONTROL_POINT_SNAP_THRESHOLD
import ru.bondarenko.orientvibe.ng.yolo.MapDetectionProgressListener
import ru.bondarenko.orientvibe.ng.yolo.MapDetector

/** Поворачивает bitmap на заданный угол. */
private fun Bitmap.rotateBitmap(degrees: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees)
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/**
 * ViewModel for map image loading, detection results, and route management.
 * All detection logic is delegated to [MapDetector].
 */
class MapViewModel(
    private val context: Context
) : ViewModel(), MapDetectionProgressListener {

    private val tag = "MapViewModel"

    companion object {
        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MapViewModel(context) as T
            }
        }
    }

    private val _mapState = MutableStateFlow(MapState())
    val mapState: StateFlow<MapState> = _mapState.asStateFlow()

    private val mapDetector = MapDetector(context).also { it.setProgressListener(this) }

    /** Экспонирует детектор для ImageLoader. */
    val detector: MapDetector = mapDetector

    fun updateDetectionResults(result: ru.bondarenko.orientvibe.ng.yolo.MapDetectionResult) {
        _mapState.value = _mapState.value.copy(
            controlsBoundingBoxes = result.controlsBoundingBoxes,
            numbersBoundingBoxes = result.numbersBoundingBoxes,
            isProcessing = false,
            progressMessage = null
        )
    }

    init {
        viewModelScope.launch {
            val ok = mapDetector.init()
            if (!ok) {
                _mapState.value = _mapState.value.copy(
                    errorMessage = "Failed to load orientmapv8n.onnx model"
                )
            }
        }
    }

    // ── Progress (from MapDetectionProgressListener) ───────────────────────

    override fun onProgressUpdate(current: Int, total: Int, message: String) {
        viewModelScope.launch {
            try {
                // Check if any active task is still alive via the shared atomic reference.
                val currentHandle = mapDetector.currentTaskRef.get()
                if (currentHandle != null && !currentHandle.job.isCancelled) {
                    currentHandle.job.ensureActive()
                } else {
                    return@launch  // No active task — progress update is stale
                }

                _mapState.value = _mapState.value.copy(
                    progress = current.toFloat() / total.toFloat(),
                    progressMessage = message
                )
            } catch (e: Exception) {
                // Перехватываем отмену корутины, чтобы логика внутри MapDetector тоже прервалась
                Log.d("MapViewModel", "Detection cancellation requested via progress update")
            }
        }
    }

    // ── Image loading + detection entry point ──────────────────────────────

    /** Загрузка Bitmap напрямую (для TakePicturePreview — без FileProvider). */
    fun loadImageFromBitmap(bitmap: android.graphics.Bitmap, imageUri: Uri? = null) {
        var displayBitmap =
            bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)

        // Корректируем ориентацию если передан URI с EXIF metadata
        if (imageUri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(imageUri)
                    ?: throw Exception("Cannot open URI for EXIF read")
                val exif = ExifInterface(inputStream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )

                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_TRANSPOSE -> displayBitmap =
                        displayBitmap.rotateBitmap(90f)

                    ExifInterface.ORIENTATION_ROTATE_180,
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> displayBitmap =
                        displayBitmap.rotateBitmap(180f)

                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSVERSE -> displayBitmap =
                        displayBitmap.rotateBitmap(270f)

                    else -> { /* Ориентация корректная */
                    }
                }
                inputStream.close()
            } catch (e: Exception) {
                Log.w("MapViewModel", "Failed to read EXIF for orientation correction", e)
            }
        }

        _mapState.value = MapState()

        _mapState.value = _mapState.value.copy(
            bitmap = displayBitmap,
            isProcessing = true,
            progressMessage = "Запуск детекции..."
        )

        // Launch via shared atomic task mechanism — any caller cancelling the handle
        // instantly kills detection launched by any other caller (they share one AtomicReference).
        val task = mapDetector.launchDetection()

        viewModelScope.launch(task.job) {
            val result = withContext(Dispatchers.IO) {
                mapDetector.detect(displayBitmap)
            }

            // Only apply results if this task is still the current one — prevents stale updates.
            mapDetector.currentTaskRef.get()?.takeIf { it.version == task.version }?.let { current ->
                current.job.ensureActive()  // throw if cancelled
                _mapState.value = _mapState.value.copy(
                    controlsBoundingBoxes = result.controlsBoundingBoxes,
                    numbersBoundingBoxes = result.numbersBoundingBoxes,
                    isProcessing = false,
                    progressMessage = null
                )
            }
        }
    }

    // ── Route management ───────────────────────────────────────────────────

    fun setPlacingMode(mode: PlacingMode) {
        _mapState.value = _mapState.value.copy(placingMode = mode)
    }

    private fun snapToControlPoint(relativeX: Float, relativeY: Float): RoutePoint {
        val boxes = _mapState.value.controlsBoundingBoxes
        val snapped = boxes.minByOrNull { box ->
            val dx = box.centerX - relativeX
            val dy = box.centerY - relativeY
            dx * dx + dy * dy
        }

        if (snapped != null) {
            val dx = snapped.centerX - relativeX
            val dy = snapped.centerY - relativeY
            if (dx * dx + dy * dy < CONTROL_POINT_SNAP_THRESHOLD * CONTROL_POINT_SNAP_THRESHOLD) {
                return RoutePoint(snapped.centerX, snapped.centerY)
            }
        }
        return RoutePoint(relativeX, relativeY)
    }

    fun placeRoutePoint(relativeX: Float, relativeY: Float) {
        val state = _mapState.value
        Log.d(tag, "placeRoutePoint x=$relativeX y=$relativeY placingMode=${state.placingMode}")
        if (state.placingMode == PlacingMode.NONE) {
            Log.w(tag, "  >>> EARLY RETURN: placingMode is NONE")
            return
        }

        val finalPoint = snapToControlPoint(relativeX, relativeY)

        when (state.placingMode) {
            PlacingMode.PLACING_START -> {
                _mapState.value = state.copy(
                    startPoint = finalPoint,
                    placingMode = PlacingMode.NONE
                )
            }

            PlacingMode.PLACING_FINISH -> {
                _mapState.value = state.copy(
                    finishPoint = finalPoint,
                    placingMode = PlacingMode.NONE
                )
            }

            else -> {}
        }
    }

    fun moveStartPoint(relativeX: Float, relativeY: Float) {
        _mapState.value =
            _mapState.value.copy(startPoint = snapToControlPoint(relativeX, relativeY))
    }

    fun moveFinishPoint(relativeX: Float, relativeY: Float) {
        _mapState.value =
            _mapState.value.copy(finishPoint = snapToControlPoint(relativeX, relativeY))
    }

    // ── Map orientation ────────────────────────────────────────────────────

    fun updateNorthAngle(angle: Float) {
        _mapState.value = _mapState.value.copy(northAngle = angle.coerceIn(-45f, 45f))
    }

    fun resetNorthAngle() {
        _mapState.value = _mapState.value.copy(northAngle = 0f)
    }

    fun updateAzimuth() {
        val sp = _mapState.value.startPoint
        val fp = _mapState.value.finishPoint
        val bmp = _mapState.value.bitmap
        if (sp != null && fp != null && bmp != null) {
            val dx = (fp.x - sp.x) * bmp.width
            val dy = (fp.y - sp.y) * bmp.height
            val routeDir = (Math.toDegrees(Math.atan2(dx.toDouble(), -dy.toDouble())) + 360) % 360
            val angle = ((routeDir + _mapState.value.northAngle + 360) % 360).toFloat()
            _mapState.value = _mapState.value.copy(azimuth = angle)
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    fun clearError() {
        _mapState.value = _mapState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        mapDetector.close()
    }
}
