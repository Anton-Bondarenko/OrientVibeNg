package ru.bondarenko.orientvibe.ng.image

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Обертка для запуска системной камеры.
 */
data class CameraSource(val launchCamera: () -> Unit)

/**
 * Функция Composable для инициализации и удержания жизненного цикла источников камеры с проверкой пермишенов.
 */
@Composable
fun rememberCameraSource(
    context: Context,
    onImageCaptured: (ImageCapture) -> Unit
): CameraSource {

    // Сохраняем путь к временному файлу при уничтожении активити/повороте экрана
    val pendingFilePath = rememberSaveable { mutableStateOf<String?>(null) }

    // Лаунчер для непосредственной фотосъемки
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val localPath = pendingFilePath.value
        if (success && localPath != null) {
            val file = File(localPath)
            if (file.exists()) {
                val contentUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
                try {
                    context.contentResolver.openInputStream(contentUri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                            ?: throw Exception("Bitmap decode failed")
                        onImageCaptured(ImageCapture(bitmap, contentUri))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            pendingFilePath.value = null
        }
    }

    // Лаунчер для запроса пермишена на доступ к камере
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = prepareCaptureTarget(context, pendingFilePath)
            cameraLauncher.launch(uri)
        }
    }

    // Возвращаем мемоизированный объект CameraSource с лямбдой запуска
    return remember(cameraLauncher, permissionLauncher, context) {
        CameraSource {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val uri = prepareCaptureTarget(context, pendingFilePath)
                cameraLauncher.launch(uri)
            } else {
                permissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }
    }
}

/**
 * Создание временного файла и регистрация его URI через FileProvider
 */
private fun prepareCaptureTarget(
    context: Context,
    pendingFilePathState: MutableState<String?>
): Uri {
    val tempFile = File(
        context.cacheDir,
        "camera_fullres_${System.currentTimeMillis()}.jpg"
    )
    val contentUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider",
        tempFile
    )
    pendingFilePathState.value = tempFile.absolutePath
    return contentUri
}
