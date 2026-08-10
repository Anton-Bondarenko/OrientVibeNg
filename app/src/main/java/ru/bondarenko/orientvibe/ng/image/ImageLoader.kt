package ru.bondarenko.orientvibe.ng.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import ru.bondarenko.orientvibe.ng.yolo.MapDetectionResult
import ru.bondarenko.orientvibe.ng.yolo.MapDetector

/** Захваченное изображение: bitmap (уже с EXIF-поворотом) + URI для чтения метаданных. */
data class ImageCapture(
    val bitmap: Bitmap,
    val uri: Uri?
)

/** Создаёт launcher для выбора изображения из галереи. Возвращает функцию, которую можно повесить на кнопку. */
@Composable
fun rememberGalleryPicker(onImageSelected: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onImageSelected)
    }
    return remember {
        { launcher.launch("image/*") }
    }
}

/** Детекция контуров/номеров. EXIF-коррекция — caller'ом (для камеры в MapViewModel, для галереи здесь). */
class ImageLoader(
    private val context: Context,
    private val detector: MapDetector,
) {
    /** Загрузка Bitmap напрямую — только детекция (EXIF уже применён caller'ом). Используется для камеры. */
    fun loadImageWithBitmap(bitmap: Bitmap, onDetectionReady: (MapDetectionResult) -> Unit) {
        val displayBitmap = bitmap.copy(
            bitmap.config ?: Bitmap.Config.ARGB_8888, false
        )

        // Shared atomic task handle — any caller cancelling its own handle instantly kills
        // detection launched by any other caller. Version check prevents stale results.
        val task = detector.launchDetection()

        kotlinx.coroutines.MainScope().launch(task.job) {
            val result = detector.detect(displayBitmap)
            detector.currentTaskRef.get()?.takeIf { it.version == task.version }?.job?.let { current ->
                current.ensureActive()
                onDetectionReady(result)
            }
        }
    }

    /** Загрузка по URI с EXIF-коррекцией → детекция. Для галереи. */
    fun loadImageForGallery(
        uri: Uri,
        onLoading: (ImageCapture) -> Unit,
        onDetectionReady: (MapDetectionResult) -> Unit,
    ) {
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri).use { inputStream ->
                if (inputStream != null) {
                    val exif = ExifInterface(inputStream)
                    val orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                    context.contentResolver.openInputStream(uri).use { inputStream2 ->
                        val bm = BitmapFactory.decodeStream(inputStream2)
                            ?: throw IllegalStateException("Bitmap decode failed")
                        rotateIfRequired(bm, orientation)
                    }
                } else {
                    throw IllegalStateException("Unable to open image")
                }
            }
        }.getOrElse { error ->
            error.printStackTrace()
            null
        }

        if (bitmap == null) return

        // Передаём bitmap caller'у для обновления MapState.bitmap + EXIF в MapViewModel
        onLoading(ImageCapture(bitmap, uri))

        loadImageWithBitmap(bitmap, onDetectionReady)
    }

    private fun rotateIfRequired(bm: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE -> 90

            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180

            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE -> 270

            else -> return bm
        }

        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, matrix, true)
    }
}
