package ru.bondarenko.orientvibe.ng.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Обёртка над камерой и галереей для Compose. */
data class CameraSource(
    val launchCamera: () -> Unit,
    val launchGallery: () -> Unit,
)

/** Результат съёмки/выбора — bitmap + URI для чтения EXIF orientation. */
data class ImageCapture(val bitmap: Bitmap, val imageUri: Uri?)

/** Создаёт CameraSource внутри Compose. */
@Composable
fun rememberCameraSource(
    context: Context,
    onImageCaptured: (ImageCapture) -> Unit,
    onImageSelected: (Uri) -> Unit,
): CameraSource {
    // Храним пару: сам файл и его валидный Content URI для FileProvider
    var pendingCapture by remember { mutableStateOf<Pair<File, Uri>?>(null) }

    fun prepareCaptureTarget(): Uri {
        val tempFile = File(context.cacheDir, "camera_fullres_${System.currentTimeMillis()}.jpg")
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            tempFile
        )
        pendingCapture = Pair(tempFile, contentUri)
        return contentUri
    }

    // TakePicture(uri) — записывает полный кадр на диск по переданному URI.
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val captureData = pendingCapture
        if (success && captureData != null) {
            val (file, contentUri) = captureData
            if (file.exists()) {
                try {
                    // ИСПРАВЛЕНИЕ 1 и 2: Читаем EXIF через правильный contentUri и автоматически закрываем поток через .use
                    val orientation = context.contentResolver.openInputStream(contentUri).use { inputStream ->
                        if (inputStream != null) {
                            val exif = ExifInterface(inputStream)
                            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        } else {
                            ExifInterface.ORIENTATION_NORMAL
                        }
                    }

                    // ИСПРАВЛЕНИЕ 3: Декодируем bitmap, также безопасно закрывая поток
                    val bitmap = context.contentResolver.openInputStream(contentUri).use { inputStream ->
                        if (inputStream != null) {
                            BitmapFactory.decodeStream(inputStream)
                        } else null
                    } ?: throw Exception("Bitmap decode failed")

                    // Корректируем поворот на основе EXIF
                    val correctedBitmap = when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> bitmap.rotateBitmap(90f, flipHorizontal = false)
                        ExifInterface.ORIENTATION_ROTATE_180 -> bitmap.rotateBitmap(180f, flipHorizontal = false)
                        ExifInterface.ORIENTATION_ROTATE_270 -> bitmap.rotateBitmap(270f, flipHorizontal = false)

                        // Обработка редких случаев зеркалирования (фронтальная камера)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> bitmap.rotateBitmap(0f, flipHorizontal = true)
                        ExifInterface.ORIENTATION_TRANSPOSE -> bitmap.rotateBitmap(90f, flipHorizontal = true)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> bitmap.rotateBitmap(180f, flipHorizontal = true)
                        ExifInterface.ORIENTATION_TRANSVERSE -> bitmap.rotateBitmap(270f, flipHorizontal = true)
                        else -> bitmap
                    }

                    // Передаем правильный contentUri, а не file://
                    onImageCaptured(ImageCapture(correctedBitmap, contentUri))

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            pendingCapture = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = prepareCaptureTarget()
            cameraLauncher.launch(uri)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let(onImageSelected)
    }

    return remember(cameraLauncher, permissionLauncher, galleryLauncher) {
        CameraSource(
            launchCamera = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    val uri = prepareCaptureTarget()
                    cameraLauncher.launch(uri)
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            launchGallery = {
                galleryLauncher.launch("image/*")
            }
        )
    }
}

/** Поворачивает и при необходимости зеркалирует bitmap. */
private fun Bitmap.rotateBitmap(degrees: Float, flipHorizontal: Boolean): Bitmap {
    if (degrees == 0f && !flipHorizontal) return this

    val matrix = Matrix()
    if (degrees != 0f) {
        matrix.postRotate(degrees)
    }
    if (flipHorizontal) {
        // Отражение по горизонтали (вдоль оси X)
        matrix.postScale(-1f, 1f)
    }

    val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    // Если создался новый bitmap, старый нужно утилизировать для экономии RAM
    if (rotated != this) {
        this.recycle()
    }
    return rotated
}
