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

data class MapState(
    val imageUri: Uri? = null,
    val bitmap: Bitmap? = null,
    val boundingBoxes: List<BoundingBox> = emptyList(),
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val progress: Float = 0f,
    val progressMessage: String? = null
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
                newDetector.setProgressListener(object : ru.bondarenko.orientvibe.ng.yolo.DetectionProgressListener {
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
