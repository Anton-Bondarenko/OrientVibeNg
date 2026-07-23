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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.bondarenko.orientvibe.ng.model.PanelButton
import ru.bondarenko.orientvibe.ng.model.PanelStep
import ru.bondarenko.orientvibe.ng.ui.components.BottomButtonPanel
import ru.bondarenko.orientvibe.ng.ui.components.MapDisplayArea
import ru.bondarenko.orientvibe.ng.ui.components.MapDragListener
import ru.bondarenko.orientvibe.ng.ui.components.MapTapListener
import ru.bondarenko.orientvibe.ng.ui.components.SubsamplingMapView
import ru.bondarenko.orientvibe.ng.ui.components.TopInfoPanel
import ru.bondarenko.orientvibe.ng.viewmodel.MapViewModel
import ru.bondarenko.orientvibe.ng.viewmodel.PlacingMode
import java.io.File

// Equilateral triangle pointing up (orienteering start symbol)
// Side length = 30, centered at (24, 24)
private val StartIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Start",
        defaultWidth = 48.dp,
        defaultHeight = 48.dp,
        viewportWidth = 48f,
        viewportHeight = 48f
    ).apply {
        // Outlined triangle (lighter appearance for button icon)
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 4f
        ) {
            // Equilateral triangle: side=30, height=25.98, centered vertically
            // top=(24, 11), bottom-left=(9, 37), bottom-right=(39, 37)
            moveTo(24f, 11f)
            lineTo(9f, 37f)
            lineTo(39f, 37f)
            close()
        }
    }.build()

// Double circle (orienteering finish symbol) — outlined style for lighter appearance
private val FinishIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Finish",
        defaultWidth = 48.dp,
        defaultHeight = 48.dp,
        viewportWidth = 48f,
        viewportHeight = 48f
    ).apply {
        // Outer circle outline (approximated as 12-gon)
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 4f
        ) {
            // 12-gon with r=16, cx=24, cy=24
            moveTo(40f, 24f)
            lineTo(37.86f, 32f)
            lineTo(32f, 37.86f)
            lineTo(24f, 40f)
            lineTo(16f, 37.86f)
            lineTo(10.14f, 32f)
            lineTo(8f, 24f)
            lineTo(10.14f, 16f)
            lineTo(16f, 10.14f)
            lineTo(24f, 8f)
            lineTo(32f, 10.14f)
            lineTo(37.86f, 16f)
            close()
        }
        // Inner circle outline
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 4f
        ) {
            // 12-gon with r=10, cx=24, cy=24
            moveTo(34f, 24f)
            lineTo(32.66f, 29f)
            lineTo(29f, 32.66f)
            lineTo(24f, 34f)
            lineTo(19f, 32.66f)
            lineTo(15.34f, 29f)
            lineTo(14f, 24f)
            lineTo(15.34f, 19f)
            lineTo(19f, 15.34f)
            lineTo(24f, 14f)
            lineTo(29f, 15.34f)
            lineTo(32.66f, 19f)
            close()
        }
    }.build()

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
    LaunchedEffect(mapState.isProcessing, mapState.errorMessage, mapState.placingMode) {
        when {
            mapState.placingMode == PlacingMode.PLACING_START -> {
                infoMessage = "Нажмите на карту чтобы поставить точку СТАРТ"
                isInfoVisible = true
            }

            mapState.placingMode == PlacingMode.PLACING_FINISH -> {
                infoMessage = "Нажмите на карту чтобы поставить точку ФИНИШ"
                isInfoVisible = true
            }

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

    // Map tap listener: when user taps the map, place route point
    val tapListener = remember {
        object : MapTapListener {
            override fun onMapTap(relativeX: Float, relativeY: Float) {
                viewModel.placeRoutePoint(relativeX, relativeY)
            }
        }
    }

    // Map drag listener: when user drags start/finish points
    val dragListener = remember {
        object : MapDragListener {
            override fun onStartPointDragged(relativeX: Float, relativeY: Float) {
                viewModel.moveStartPoint(relativeX, relativeY)
            }

            override fun onFinishPointDragged(relativeX: Float, relativeY: Float) {
                viewModel.moveFinishPoint(relativeX, relativeY)
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
                title = "Маршрут",
                buttons = listOf(
                    PanelButton(
                        id = "start",
                        text = "Старт",
                        icon = StartIcon,
                        onClick = {
                            viewModel.setPlacingMode(PlacingMode.PLACING_START)
                            infoMessage = "Нажмите на карту чтобы поставить СТАРТ"
                            isInfoVisible = true
                        }
                    ),
                    PanelButton(
                        id = "finish",
                        text = "Финиш",
                        icon = FinishIcon,
                        onClick = {
                            viewModel.setPlacingMode(PlacingMode.PLACING_FINISH)
                            infoMessage = "Нажмите на карту чтобы поставить ФИНИШ"
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
                    startPoint = mapState.startPoint,
                    finishPoint = mapState.finishPoint,
                    tapListener = tapListener,
                    dragListener = dragListener,
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