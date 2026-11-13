package ru.bondarenko.orientvibe.ng.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import ru.bondarenko.orientvibe.ng.yolo.MapDetectionResult
import ru.bondarenko.orientvibe.ng.yolo.MapDetector

/**
 * Модель данных успешного захвата или загрузки изображения.
 */
data class ImageCapture(
    val bitmap: Bitmap,
    val uri: Uri?
)

/**
 * Менеджер асинхронной загрузки изображений и запуска сессий нейросетевого детектирования объектов YOLO.
 */
class ImageLoader(
    private val context: Context,
    private val detector: MapDetector
) {
    private val coroutineScope: CoroutineScope = MainScope()

    private fun loadImageWithBitmap(
        bitmap: Bitmap,
        onDetectionReady: (MapDetectionResult?) -> Unit
    ) {
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val displayBitmap = bitmap.copy(config, false)
        val task = detector.launchDetection()

        coroutineScope.launch(task.job) {
            try {
                val result = detector.detect(displayBitmap)
                val currentTask = detector.currentTaskRef.get()

                if (currentTask != null && currentTask.version == task.version) {
                    val job = currentTask.job
                    if (job != null) {
                        job.ensureActive()
                        onDetectionReady(result)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadImageForGallery(
        uri: Uri,
        onLoading: (ImageCapture) -> Unit,
        onDetectionReady: (MapDetectionResult?) -> Unit
    ) {
        try {
            val contentResolver = context.contentResolver

            // Читаем ориентацию EXIF метаданных из входного потока
            val orientation = contentResolver.openInputStream(uri)?.use { inputStream ->
                ExifInterface(inputStream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            // Декодируем изображение в Bitmap
            val bitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            } ?: throw Exception("Bitmap decode failed")

            val rotatedBitmap = rotateIfRequired(bitmap, orientation)

            onLoading(ImageCapture(rotatedBitmap, uri))
            loadImageWithBitmap(rotatedBitmap, onDetectionReady)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun rotateIfRequired(bm: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> return bm
        }

        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, matrix, true)
    }
}

/**
 * Объект-расширение, предоставляющий Composable DSL для вызова системной галереи Android.
 */
@Composable
fun rememberGalleryPicker(
    onImageSelected: (Uri) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onImageSelected(uri)
        }
    }

    return remember(launcher) {
        { launcher.launch("image/*") }
    }
}
