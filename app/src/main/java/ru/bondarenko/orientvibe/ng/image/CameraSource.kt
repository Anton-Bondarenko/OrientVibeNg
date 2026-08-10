package ru.bondarenko.orientvibe.ng.image

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Обёртка над камерой для Compose. */
data class CameraSource(
    val launchCamera: () -> Unit,
)

/** Создаёт камеру внутри Compose. */
@Composable
fun rememberCameraSource(
    context: Context,
    onImageCaptured: (ImageCapture) -> Unit,
): CameraSource {
    // Храним абсолютный путь к файлу — переживёт пересоздание Activity
    var pendingFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    fun prepareCaptureTarget(): Uri {
        val tempFile = File(context.cacheDir, "camera_fullres_${System.currentTimeMillis()}.jpg")
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            tempFile
        )
        pendingFilePath = tempFile.absolutePath
        return contentUri
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val localPath = pendingFilePath
        if (success && localPath != null) {
            val file = File(localPath)
            if (file.exists()) {
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
                try {
                    // Декодируем bitmap без EXIF-поворота — коррекция ориентации выполняется в MapViewModel.
                    val bitmap =
                        context.contentResolver.openInputStream(contentUri).use { inputStream ->
                            BitmapFactory.decodeStream(inputStream)
                        } ?: throw Exception("Bitmap decode failed")

                    onImageCaptured(ImageCapture(bitmap, contentUri))

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            pendingFilePath = null
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

    return remember(cameraLauncher, permissionLauncher) {
        CameraSource(
            launchCamera = {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val uri = prepareCaptureTarget()
                    cameraLauncher.launch(uri)
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
        )
    }
}
