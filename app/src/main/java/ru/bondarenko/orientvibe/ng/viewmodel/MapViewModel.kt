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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.MapState
import ru.bondarenko.orientvibe.ng.model.PlacingMode
import ru.bondarenko.orientvibe.ng.model.RoutePoint
import ru.bondarenko.orientvibe.ng.yolo.OnnxObjectDetector
import java.io.InputStream

class MapViewModel(
    private val context: Context
) : ViewModel() {

    private val _mapState = MutableStateFlow(MapState())
    val mapState: StateFlow<MapState> = _mapState

    private var detector: OnnxObjectDetector? = null

    init {
        initializeDetector()
    }

    private fun initializeDetector() {
        viewModelScope.launch {
            try {
                val newDetector = OnnxObjectDetector(context)
                newDetector.setProgressListener(object :
                    ru.bondarenko.orientvibe.ng.yolo.DetectionProgressListener {
                    override fun onProgressUpdate(current: Int, total: Int, message: String) {
                        _mapState.value = _mapState.value.copy(
                            progress = current.toFloat() / total.toFloat(),
                            progressMessage = message
                        )
                    }
                })
                val modelLoaded = newDetector.loadModel("yolov8n.onnx")
                if (modelLoaded) {
                    detector = newDetector
                } else {
                    _mapState.value = _mapState.value.copy(
                        errorMessage = "Failed to load ONNX model"
                    )
                }
            } catch (e: Exception) {
                _mapState.value = _mapState.value.copy(
                    errorMessage = "Failed to initialize detector: ${e.message}"
                )
            }
        }
    }

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
                    BitmapFactory.decodeStream(inputStream)
                }

                _mapState.value = _mapState.value.copy(bitmap = bitmap)

                detectObjects(bitmap)
            } catch (e: Exception) {
                _mapState.value = _mapState.value.copy(
                    isProcessing = false,
                    errorMessage = "Failed to load image: ${e.message}"
                )
            }
        }
    }

    private fun detectObjects(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                val detections = withContext(Dispatchers.IO) {
                    detector?.detect(bitmap) ?: emptyList()
                }

                val filteredDetections = detections.filter { it.classId == 0 }

                val boundingBoxes = filteredDetections.map { detection ->
                    val bw = (detection.boundingBox.right - detection.boundingBox.left) / bitmap.width
                    val bh = (detection.boundingBox.bottom - detection.boundingBox.top) / bitmap.height
                    val cx = detection.boundingBox.left / bitmap.width + bw / 2f
                    val cy = detection.boundingBox.top / bitmap.height + bh / 2f
                    BoundingBox(
                        centerX = cx,
                        centerY = cy,
                        width = bw,
                        height = bh,
                        confidence = detection.confidence,
                        label = "control_point"
                    )
                }

                _mapState.value = _mapState.value.copy(
                    boundingBoxes = boundingBoxes,
                    isProcessing = false
                )
            } catch (e: Exception) {
                _mapState.value = _mapState.value.copy(
                    isProcessing = false,
                    errorMessage = "Detection failed: ${e.message}"
                )
            }
        }
    }

    fun setPlacingMode(mode: PlacingMode) {
        _mapState.value = _mapState.value.copy(placingMode = mode)
    }

    private fun snapToControlPoint(relativeX: Float, relativeY: Float): RoutePoint {
        val boxes = _mapState.value.boundingBoxes
        val snapped = boxes.minByOrNull { box ->
            val dx = box.centerX - relativeX
            val dy = box.centerY - relativeY
            dx * dx + dy * dy
        }

        val threshold = 0.03f
        if (snapped != null) {
            val dx = snapped.centerX - relativeX
            val dy = snapped.centerY - relativeY
            if (dx * dx + dy * dy < threshold * threshold) {
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

    fun clearError() {
        _mapState.value = _mapState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        detector?.close()
    }

    companion object {
        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MapViewModel(context) as T
            }
        }
    }
}