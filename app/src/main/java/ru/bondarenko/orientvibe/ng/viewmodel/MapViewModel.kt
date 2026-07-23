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
import ru.bondarenko.orientvibe.ng.yolo.OnnxObjectDetector
import java.io.InputStream

data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val label: String
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
    val imageUri: Uri? = null,
    val bitmap: Bitmap? = null,
    val boundingBoxes: List<BoundingBox> = emptyList(),
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val progress: Float = 0f,
    val progressMessage: String? = null,
    val startPoint: RoutePoint? = null,
    val finishPoint: RoutePoint? = null,
    val placingMode: PlacingMode = PlacingMode.NONE
)

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

                _mapState.value = _mapState.value.copy(
                    bitmap = bitmap
                )

                // Run detection
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

                // Filter only class 0 (control points)
                val filteredDetections = detections.filter { it.classId == 0 }

                val boundingBoxes = filteredDetections.map { detection ->
                    BoundingBox(
                        left = detection.boundingBox.left / bitmap.width,
                        top = detection.boundingBox.top / bitmap.height,
                        right = detection.boundingBox.right / bitmap.width,
                        bottom = detection.boundingBox.bottom / bitmap.height,
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
            val cx = (box.left + box.right) / 2
            val cy = (box.top + box.bottom) / 2
            val dx = cx - relativeX
            val dy = cy - relativeY
            dx * dx + dy * dy
        }

        val threshold = 0.03f // 3% of image dimension
        if (snapped != null) {
            val cx = (snapped.left + snapped.right) / 2
            val cy = (snapped.top + snapped.bottom) / 2
            val dx = cx - relativeX
            val dy = cy - relativeY
            if (dx * dx + dy * dy < threshold * threshold) {
                return RoutePoint(cx, cy)
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
        _mapState.value =
            _mapState.value.copy(startPoint = snapToControlPoint(relativeX, relativeY))
    }

    fun moveFinishPoint(relativeX: Float, relativeY: Float) {
        _mapState.value =
            _mapState.value.copy(finishPoint = snapToControlPoint(relativeX, relativeY))
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