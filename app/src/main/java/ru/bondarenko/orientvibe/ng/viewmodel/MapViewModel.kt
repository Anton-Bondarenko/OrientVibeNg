package ru.bondarenko.orientvibe.ng.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.MapState
import ru.bondarenko.orientvibe.ng.model.PlacingMode
import ru.bondarenko.orientvibe.ng.model.RoutePoint
import ru.bondarenko.orientvibe.ng.yolo.MapDetectionProgressListener
import ru.bondarenko.orientvibe.ng.yolo.MapDetector
import ru.bondarenko.orientvibe.ng.yolo.CONTROL_POINT_SNAP_THRESHOLD
import android.util.Log
import java.io.InputStream

/**
 * ViewModel for map image loading, detection results, and route management.
 * All detection logic is delegated to [MapDetector].
 */
class MapViewModel(
    private val context: Context
) : ViewModel(), MapDetectionProgressListener {

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
        _mapState.value = _mapState.value.copy(
            progress = current.toFloat() / total.toFloat(),
            progressMessage = message
        )
    }

    // ── Image loading + detection entry point ──────────────────────────────

    fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch {
            _mapState.value = _mapState.value.copy(
                imageUri = uri,
                isProcessing = true,
                errorMessage = null
            )

            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("Unable to open image")
                    try {
                        BitmapFactory.decodeStream(inputStream)
                    } finally {
                        inputStream.close()
                    }
                }

                _mapState.value = _mapState.value.copy(bitmap = bitmap)

                val result = mapDetector.detect(bitmap)
                _mapState.value = _mapState.value.copy(
                    controlsBoundingBoxes = result.controlsBoundingBoxes,
                    numbersBoundingBoxes = result.numbersBoundingBoxes,
                    isProcessing = false
                )
            } catch (e: Exception) {
                Log.e("MapViewModel", "Failed to load image", e)
                _mapState.value = _mapState.value.copy(
                    isProcessing = false,
                    errorMessage = "Failed to load image: ${e.message}"
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
        if (state.placingMode == PlacingMode.NONE) return

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
        _mapState.value = _mapState.value.copy(startPoint = snapToControlPoint(relativeX, relativeY))
    }

    fun moveFinishPoint(relativeX: Float, relativeY: Float) {
        _mapState.value = _mapState.value.copy(finishPoint = snapToControlPoint(relativeX, relativeY))
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
