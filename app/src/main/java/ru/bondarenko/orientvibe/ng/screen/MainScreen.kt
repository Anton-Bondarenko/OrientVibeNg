package ru.bondarenko.orientvibe.ng.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.bondarenko.orientvibe.ng.model.PanelButton
import ru.bondarenko.orientvibe.ng.model.PanelStep
import ru.bondarenko.orientvibe.ng.ui.components.BottomButtonPanel
import ru.bondarenko.orientvibe.ng.ui.components.MapDisplayArea
import ru.bondarenko.orientvibe.ng.ui.components.SubsamplingMapView
import ru.bondarenko.orientvibe.ng.ui.components.TopInfoPanel
import ru.bondarenko.orientvibe.ng.viewmodel.MapViewModel
import java.io.File

@Composable
fun MainScreen(
    viewModel: MapViewModel = viewModel()
) {
    val context = LocalContext.current
    val mapState by viewModel.mapState.collectAsState()

    var currentStepIndex by remember { mutableStateOf(0) }
    var infoMessage by remember { mutableStateOf("Добро пожаловать в OrientVibe") }
    var isInfoVisible by remember { mutableStateOf(true) }

    // Gallery picker
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.loadImageFromUri(it)
            infoMessage = "Загрузка изображения..."
            isInfoVisible = true
        }
    }

    // Camera URI
    val photoFile = remember {
        File(context.cacheDir, "temp_photo_${System.currentTimeMillis()}.jpg")
    }
    val cameraUri = remember {
        FileProvider.getUriForFile(context, "${context.packageName}.provider", photoFile)
    }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            viewModel.loadImageFromUri(cameraUri)
            infoMessage = "Фото сделано"
            isInfoVisible = true
        }
    }

    // Update info message based on state
    LaunchedEffect(mapState.isProcessing, mapState.errorMessage) {
        when {
            mapState.isProcessing -> {
                infoMessage = "Обработка изображения..."
                isInfoVisible = true
            }

            mapState.errorMessage != null -> {
                infoMessage = "Ошибка: ${mapState.errorMessage}"
                isInfoVisible = true
            }

            mapState.boundingBoxes.isNotEmpty() -> {
                infoMessage = "Найдено ${mapState.boundingBoxes.size} контрольных точек"
                isInfoVisible = true
            }
        }
    }

    val steps = remember {
        listOf(
            PanelStep(
                id = "step1",
                title = "Загрузка карты",
                buttons = listOf(
                    PanelButton(
                        id = "camera",
                        text = "",
                        icon = Icons.Default.CameraAlt,
                        onClick = {
                            infoMessage = "Запуск камеры..."
                            isInfoVisible = true
                            cameraLauncher.launch(cameraUri)
                        }
                    ),
                    PanelButton(
                        id = "gallery",
                        text = "",
                        icon = Icons.Default.PhotoLibrary,
                        onClick = {
                            infoMessage = "Открытие галереи..."
                            isInfoVisible = true
                            galleryLauncher.launch("image/*")
                        }
                    )
                )
            ),
            PanelStep(
                id = "step2",
                title = "Обработка",
                buttons = listOf(
                    PanelButton(
                        id = "process",
                        text = "Обработать",
                        onClick = {
                            infoMessage = "Обработка карты..."
                            isInfoVisible = true
                        }
                    )
                )
            ),
            PanelStep(
                id = "step3",
                title = "Результат",
                buttons = listOf(
                    PanelButton(
                        id = "save",
                        text = "Сохранить",
                        onClick = {
                            infoMessage = "Сохранение результата..."
                            isInfoVisible = true
                        }
                    ),
                    PanelButton(
                        id = "share",
                        text = "Поделиться",
                        onClick = {
                            infoMessage = "Поделиться результатом..."
                            isInfoVisible = true
                        }
                    )
                )
            )
        )
    }

    val currentStep = steps[currentStepIndex]

    // Auto-advance to step 2 after processing completes
    LaunchedEffect(mapState.isProcessing, mapState.boundingBoxes) {
        if (!mapState.isProcessing && mapState.boundingBoxes.isNotEmpty() && currentStepIndex == 0) {
            currentStepIndex = 1
            infoMessage = "Шаг 2: ${steps[1].title}"
            isInfoVisible = true
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Map Display Area (Center)
            if (mapState.bitmap != null) {
                SubsamplingMapView(
                    bitmap = mapState.bitmap!!,
                    boundingBoxes = mapState.boundingBoxes,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = 80.dp,
                            bottom = 120.dp,
                            start = 16.dp,
                            end = 16.dp
                        )
                )
            } else {
                MapDisplayArea(
                    mapImageUri = mapState.imageUri?.toString(),
                    onCameraClick = {
                        infoMessage = "Запуск камеры..."
                        isInfoVisible = true
                        cameraLauncher.launch(cameraUri)
                    },
                    onGalleryClick = {
                        infoMessage = "Открытие галереи..."
                        isInfoVisible = true
                        galleryLauncher.launch("image/*")
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = 80.dp,
                            bottom = 120.dp,
                            start = 16.dp,
                            end = 16.dp
                        )
                )
            }

            // Top Info Panel
            TopInfoPanel(
                message = infoMessage,
                isVisible = isInfoVisible,
                progress = mapState.progress,
                isProcessing = mapState.isProcessing,
                progressMessage = mapState.progressMessage,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            )

            // Bottom Button Panel
            BottomButtonPanel(
                currentStep = currentStep,
                canGoBack = currentStepIndex > 0,
                canGoForward = currentStepIndex < steps.size - 1,
                onPreviousStep = {
                    if (currentStepIndex > 0) {
                        currentStepIndex--
                        infoMessage =
                            "Шаг ${currentStepIndex + 1}: ${steps[currentStepIndex].title}"
                        isInfoVisible = true
                    }
                },
                onNextStep = {
                    if (currentStepIndex < steps.size - 1) {
                        currentStepIndex++
                        infoMessage =
                            "Шаг ${currentStepIndex + 1}: ${steps[currentStepIndex].title}"
                        isInfoVisible = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            )
        }
    }
}
