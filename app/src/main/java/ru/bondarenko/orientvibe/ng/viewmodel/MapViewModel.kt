package ru.bondarenko.orientvibe.ng.viewmodel

import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
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
import ru.bondarenko.orientvibe.ng.yolo.MapDetectionResult
import ru.bondarenko.orientvibe.ng.yolo.MapDetector
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.math.atan2
import kotlin.reflect.KClass

/**
 * ViewModel для управления интерактивной картой, разметкой стартовых/финишных точек
 * и асинхронным распознаванием КП с помощью нейросети YOLO.
 */
class MapViewModel(private val context: Context) : ViewModel(), MapDetectionProgressListener {

    private val tag = "MapViewModel"

    private val _mapState = MutableStateFlow(MapState())
    val mapState: StateFlow<MapState> = _mapState.asStateFlow()

    private val mapDetector = MapDetector(context)
    val detector: MapDetector = mapDetector

    val isLoading: Boolean
        get() = _mapState.value.bitmap == null

    init {
        mapDetector.progressListener = this

        viewModelScope.launch {
            val ok = mapDetector.init()
            if (!ok) {
                _mapState.value = _mapState.value.copy(
                    errorMessage = "Failed to load orientmapv8n.onnx model"
                )
            }
        }
    }

    private fun updateDetectionResults(result: MapDetectionResult) {
        val controlsBoundingBoxes = result.controlsBoundingBoxes
        val totalControls = controlsBoundingBoxes.size

        val withNumbers = controlsBoundingBoxes.count { it.number != null }
        val withoutNumbers = controlsBoundingBoxes.filter { it.number == null }
            .mapIndexed { idx, _ -> (idx + 1).toString() }

        val ocrCount = result.numbersBoundingBoxes.count { it.number != null }
        val stats = "CP:$totalControls(+$withNumbers#) OCR:$ocrCount/${result.numbersBoundingBoxes.size}"

        Log.d(tag, "Attachment: $stats | no-num CPs: ${withoutNumbers.take(8)}${if (withoutNumbers.size > 8) "+${withoutNumbers.size - 8}" else ""}")
        Log.d(tag, "=== CONTROLS (${controlsBoundingBoxes.size}) ===")

        controlsBoundingBoxes.forEachIndexed { index, cp ->
            Log.d(tag, String.format(Locale.ROOT, "  CP#%d w=%.4f h=%.4f cx=%.4f cy=%.4f num=%s", index, cp.width, cp.height, cp.centerX, cp.centerY, cp.number.toString()))
        }

        Log.d(tag, "=== NUMBERS (${result.numbersBoundingBoxes.size}) ===")
        result.numbersBoundingBoxes.forEachIndexed { index, nb ->
            Log.d(tag, String.format(Locale.ROOT, "  NUM#%d w=%.4f h=%.4f cx=%.4f cy=%.4f", index, nb.width, nb.height, nb.centerX, nb.centerY))
        }

        _mapState.value = _mapState.value.copy(
            controlsBoundingBoxes = controlsBoundingBoxes,
            numbersBoundingBoxes = result.numbersBoundingBoxes,
            showAllBoundingBoxes = false,
            isProcessing = false
        )
    }

    private fun findFileByDisplayName(displayName: String): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(displayName),
            null
        )

        cursor?.use { c ->
            val idColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                if (c.getString(nameColumn) == displayName) {
                    return Uri.parse("content://media/external/images/media/${c.getLong(idColumn)}")
                }
            }
        }
        return null
    }

    override fun onProgressUpdate(current: Int, total: Int, message: String) {
        viewModelScope.launch {
            val currentHandle = mapDetector.currentTaskRef.get()
            if (currentHandle != null && !currentHandle.job.isCancelled) {
                _mapState.value = _mapState.value.copy(
                    progress = current.toDouble() / total.toDouble(),
                    progressMessage = message
                )
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap {
        val inputStream = if (uri.scheme == "file") {
            val path = uri.path ?: throw IllegalArgumentException("Invalid file path")
            FileInputStream(File(path))
        } else {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Cannot open URI: $uri")
        }

        return inputStream.use { stream ->
            BitmapFactory.decodeStream(stream)
                ?: throw IllegalStateException("Failed to decode bitmap from URI: $uri")
        }
    }

    fun loadImageFromBitmap(bitmap: Bitmap, imageUri: Uri?) {
        var displayBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        if (imageUri != null) {
            try {
                context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        3, 4 -> displayBitmap = rotateBitmap(displayBitmap, 180.0)
                        5, 6 -> displayBitmap = rotateBitmap(displayBitmap, 90.0)
                        7, 8 -> displayBitmap = rotateBitmap(displayBitmap, 270.0)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to read EXIF for orientation correction", e)
            }
        }
        launchDetectionWithBitmap(displayBitmap)
    }

    fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)

                    val rawBm = loadBitmapFromUri(uri)
                    val displayBm = when (orientation) {
                        3, 4 -> rotateBitmap(rawBm, 180.0)
                        5, 6 -> rotateBitmap(rawBm, 90.0)
                        7, 8 -> rotateBitmap(rawBm, 270.0)
                        else -> rawBm.copy(rawBm.config ?: Bitmap.Config.ARGB_8888, false)
                    }

                    withContext(Dispatchers.Main) {
                        launchDetectionWithBitmap(displayBm)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun launchDetectionWithBitmap(bitmap: Bitmap) {
        _mapState.value = MapState()
        _mapState.value = _mapState.value.copy(
            bitmap = bitmap,
            isProcessing = true,
            progressMessage = "Запуск детекции..."
        )

        val task = mapDetector.launchDetection()
        viewModelScope.launch(task.job) {
            val result = withContext(Dispatchers.IO) {
                mapDetector.detect(bitmap)
            }
            val currentHandle = mapDetector.currentTaskRef.get()
            if (currentHandle != null && currentHandle.version == task.version) {
                updateDetectionResults(result)
            }
        }
    }

    fun setPlacingMode(mode: PlacingMode) {
        _mapState.value = _mapState.value.copy(placingMode = mode)
    }

    private fun snapToControlPoint(relativeX: Double, relativeY: Double): RoutePoint {
        var minDistanceSq = Double.MAX_VALUE
        var snappedBox: BoundingBox? = null

        for (box in _mapState.value.controlsBoundingBoxes) {
            val dx = box.centerX - relativeX
            val dy = box.centerY - relativeY
            val distSq = (dx * dx) + (dy * dy)
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq
                snappedBox = box
            }
        }

        if (snappedBox != null && minDistanceSq < 8.999999845400453E-4) {
            return RoutePoint(snappedBox.centerX, snappedBox.centerY)
        }
        return RoutePoint(relativeX, relativeY)
    }

    fun placeRoutePoint(relativeX: Double, relativeY: Double) {
        val state = _mapState.value
        Log.d(tag, "placeRoutePoint x=$relativeX y=$relativeY placingMode=${state.placingMode}")
        if (state.placingMode == PlacingMode.NONE) {
            Log.w(tag, "  >>> EARLY RETURN: placingMode is NONE")
            return
        }

        val finalPoint = snapToControlPoint(relativeX, relativeY)
        _mapState.value = when (state.placingMode) {
            PlacingMode.PLACING_START -> state.copy(startPoint = finalPoint, placingMode = PlacingMode.NONE)
            PlacingMode.PLACING_FINISH -> state.copy(finishPoint = finalPoint, placingMode = PlacingMode.NONE)
            else -> state
        }
    }
    fun moveStartPoint(relativeX: Double, relativeY: Double) {
        _mapState.value = _mapState.value.copy(startPoint = snapToControlPoint(relativeX, relativeY))
    }
    fun moveFinishPoint(relativeX: Double, relativeY: Double) {
        _mapState.value = _mapState.value.copy(finishPoint = snapToControlPoint(relativeX, relativeY))
    }
    fun updateNorthAngle(angle: Double) {
        _mapState.value = _mapState.value.copy(northAngle = angle.coerceIn(-45.0, 45.0))
    }
    fun resetNorthAngle() {
        _mapState.value = _mapState.value.copy(northAngle = 0.0)
    }
    fun updateAzimuth() {
        val sp = _mapState.value.startPoint
        val fp = _mapState.value.finishPoint
        val bmp = _mapState.value.bitmap
        if (sp != null && fp != null && bmp != null) {
            val dx = (fp.x - sp.x) * bmp.width.toDouble()
            val dy = (fp.y - sp.y) * bmp.height.toDouble()
            val routeDir = (Math.toDegrees(atan2(dx, -dy)) + 360.0) % 360.0
            val angle = ((_mapState.value.northAngle + routeDir) + 360.0) % 360.0
            _mapState.value = _mapState.value.copy(azimuth = angle)
        }
    }
    fun clearError() {
        _mapState.value = _mapState.value.copy(errorMessage = null)
    }
    override fun onCleared() {
        mapDetector.close()
    }
    private fun rotateBitmap(src: Bitmap, degrees: Double): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
    companion object {
        fun Factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MapViewModel(context) as T
            }
        }
    }
}