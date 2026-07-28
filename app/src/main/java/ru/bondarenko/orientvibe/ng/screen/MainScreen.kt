package ru.bondarenko.orientvibe.ng.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.bondarenko.orientvibe.ng.gps.GpsFix
import ru.bondarenko.orientvibe.ng.gps.NavViewModel
import ru.bondarenko.orientvibe.ng.model.PanelButton
import ru.bondarenko.orientvibe.ng.model.PanelStep
import ru.bondarenko.orientvibe.ng.ui.components.BottomButtonPanel
import ru.bondarenko.orientvibe.ng.ui.components.MapDisplayArea
import ru.bondarenko.orientvibe.ng.ui.components.MapDragListener
import ru.bondarenko.orientvibe.ng.ui.components.MapTapListener
import ru.bondarenko.orientvibe.ng.ui.components.SubsamplingMapView
import ru.bondarenko.orientvibe.ng.ui.components.TopInfoPanel
import ru.bondarenko.orientvibe.ng.viewmodel.MapViewModel
import ru.bondarenko.orientvibe.ng.model.PlacingMode
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

// Target/crosshair icon for "Я здесь" (I am here)
private val TargetIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Target",
        defaultWidth = 48.dp,
        defaultHeight = 48.dp,
        viewportWidth = 48f,
        viewportHeight = 48f
    ).apply {
        // Outer circle
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 4f
        ) {
            // Circle approximated as 12-gon, r=16, cx=24, cy=24
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
        // Crosshair vertical line
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 4f
        ) {
            moveTo(24f, 4f)
            lineTo(24f, 44f)
        }
        // Crosshair horizontal line
        path(
            fill = SolidColor(Color.Transparent),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 4f
        ) {
            moveTo(4f, 24f)
            lineTo(44f, 24f)
        }
        // Center dot
        path(
            fill = SolidColor(Color.Black),
            stroke = null,
        ) {
            // Small circle r=3 at center
            moveTo(24f, 21f)
            lineTo(27f, 24f)
            lineTo(24f, 27f)
            lineTo(21f, 24f)
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
    viewModel: MapViewModel = viewModel(),
    navViewModel: NavViewModel = viewModel(factory = NavViewModel.Factory(LocalContext.current))
) {
    val context = LocalContext.current
    val mapState by viewModel.mapState.collectAsState()
    val gpsState by navViewModel.gpsState.collectAsState()

    var currentStepIndex by remember { mutableStateOf(0) }
    var infoMessage by remember { mutableStateOf("Добро пожаловать в OrientVibe") }
    var isInfoVisible by remember { mutableStateOf(true) }
    var autoPlacementDone by remember { mutableStateOf(false) }
    var showLowAccuracyDialog by remember { mutableStateOf(false) }
    var lowAccuracyCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var startCalibrated by remember { mutableStateOf(false) }
    var finishCalibrated by remember { mutableStateOf(false) }
    var routeDistance by remember { mutableStateOf<Double?>(null) }
    var mapScale by remember { mutableStateOf<Double?>(null) }
    var originalStartGps by remember {
        mutableStateOf<ru.bondarenko.orientvibe.ng.gps.GpsCoordinate?>(
            null
        )
    }
    var autoMode by remember { mutableStateOf(false) }
    var autoCycleActive by remember { mutableStateOf(false) }

    // Current distance from start point to current GPS position
    val currentDistanceFromStart = remember(originalStartGps, gpsState.currentFix) {
        if (originalStartGps != null && gpsState.currentFix != null) {
            navViewModel.distanceBetween(originalStartGps!!, gpsState.currentFix!!.coordinate)
        } else {
            null
        }
    }

    // Reset auto-placement flag when a new image starts loading
    LaunchedEffect(mapState.isProcessing) {
        if (mapState.isProcessing) {
            autoPlacementDone = false
        }
    }

    // Auto-place start after image load (only once)
//    LaunchedEffect(mapState.boundingBoxes, mapState.isProcessing) {
//        if (!autoPlacementDone && !mapState.isProcessing && mapState.boundingBoxes.isNotEmpty() && mapState.placingMode == PlacingMode.NONE && mapState.startPoint == null) {
//            viewModel.setPlacingMode(PlacingMode.PLACING_START)
//        }
//    }

    // Auto-place finish after start (only once)
//    LaunchedEffect(mapState.startPoint) {
//        val start = mapState.startPoint
//        if (!autoPlacementDone && start != null && mapState.placingMode != PlacingMode.PLACING_FINISH && mapState.finishPoint == null) {
//            viewModel.setPlacingMode(PlacingMode.PLACING_FINISH)
//        }
//    }

    // Auto-enter navigation after finish (only once — guards against drag re-triggering)
//    LaunchedEffect(mapState.finishPoint) {
//        if (!autoPlacementDone && mapState.finishPoint != null) {
//            autoPlacementDone = true
//            currentStepIndex = 2
//        }
//    }

    // Compute azimuth: angle from north line to route line, clockwise in degrees
    val azimuth =
        remember(mapState.azimuth) {
            mapState.azimuth
        }

    // Compute map rotation: rotate image so route line is vertical (bottom-to-top)
    val mapRotation =
        remember(mapState.startPoint, mapState.finishPoint, mapState.bitmap, currentStepIndex) {
            if (currentStepIndex == 2) {
                val sp = mapState.startPoint
                val fp = mapState.finishPoint
                val bmp = mapState.bitmap
                if (sp != null && fp != null && bmp != null) {
                    val dx = (fp.x - sp.x) * bmp.width
                    val dy = (fp.y - sp.y) * bmp.height
                    val routeAngle =
                        Math.toDegrees(Math.atan2(dx.toDouble(), -dy.toDouble())).toFloat()
                    (-routeAngle).toFloat()
                } else {
                    0f
                }
            } else {
                0f
            }
        }

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
    LaunchedEffect(mapState.startPoint, mapState.finishPoint, mapState.northAngle, mapState.bitmap) {
        viewModel.updateAzimuth()
    }

    // Update info message based on state
    LaunchedEffect(
        mapState.isProcessing,
        mapState.errorMessage,
        mapState.placingMode,
        mapState.startPoint,
        mapState.finishPoint,
        mapState.northAngle
    ) {
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

            mapState.boundingBoxes.isNotEmpty() && currentStepIndex != 2 -> {
                infoMessage = "Найдено ${mapState.boundingBoxes.size} контрольных точек"
                isInfoVisible = true
            }

            mapState.startPoint != null && mapState.finishPoint != null -> {
                infoMessage = "Маршрут задан"
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

    // Helper: bind current GPS position to a calibration point
    val bindGpsToStart: () -> Unit = bindStart@{

        val fix = gpsState.currentFix ?: return@bindStart
        // Store original start GPS for later recalibration
        originalStartGps = fix.coordinate

        navViewModel.addCalibrationPoint(
            gpsFix = fix,
            imageX = mapState.startPoint?.x ?: 0f,
            imageY = mapState.startPoint?.y ?: 0f
        )
        navViewModel.startTracking()
        startCalibrated = true
        infoMessage = "Старт привязан к GPS"
        isInfoVisible = true

        // Calculate finish coordinates 1km away using magnetic bearing
        val finishCoordinate = navViewModel.calculateDestinationCoordinate(
            start = fix.coordinate,
            bearingMagnetic = fix.bearing.toDouble(),
            distanceMeters = 1000.0 // 1km default distance
        )

        // Create synthetic GPS fix for finish point
        val finishFix = GpsFix(
            coordinate = finishCoordinate,
            accuracy = fix.accuracy,
            bearing = fix.bearing,
            speed = 0f,
            timestamp = System.currentTimeMillis(),
            altitude = fix.altitude
        )

        // Bind finish GPS to image finish point
        navViewModel.addCalibrationPoint(
            gpsFix = finishFix,
            imageX = mapState.finishPoint?.x ?: 0f,
            imageY = mapState.finishPoint?.y ?: 0f
        )
        finishCalibrated = true
        infoMessage = "Финиш привязан к GPS (расчетный, 1км)"
        isInfoVisible = true
    }

    val bindGpsToFinish: () -> Unit = bindFinish@{

        val fix = gpsState.currentFix ?: return@bindFinish

        // Recalibrate with real finish GPS to get actual map scale
        if (originalStartGps != null) {
            // Clear existing calibration
            navViewModel.clearCalibration()
            startCalibrated = false
            finishCalibrated = false

            // Recalibrate using original start GPS and real finish GPS
            val startFix = GpsFix(
                coordinate = originalStartGps!!,
                accuracy = fix.accuracy,
                bearing = fix.bearing,
                speed = 0f,
                timestamp = System.currentTimeMillis(),
                altitude = fix.altitude
            )

            navViewModel.addCalibrationPoint(
                gpsFix = startFix,
                imageX = mapState.startPoint?.x ?: 0f,
                imageY = mapState.startPoint?.y ?: 0f
            )
            startCalibrated = true

            navViewModel.addCalibrationPoint(
                gpsFix = fix,
                imageX = mapState.finishPoint?.x ?: 0f,
                imageY = mapState.finishPoint?.y ?: 0f
            )
            finishCalibrated = true

            // Update north angle from calibration bearing
            val cal = navViewModel.getCalibration()
            if (cal != null) {
                val currentAngle =
                    navViewModel.magneticBearingBetween(startFix.coordinate, fix.coordinate)
                viewModel.updateNorthAngle(currentAngle.minus(mapState.azimuth).toFloat())
            }

            infoMessage = "Масштаб карты и линия севера скорректированы"
            isInfoVisible = true
        } else {
            // Fallback: just bind finish GPS if no original start stored
            navViewModel.addCalibrationPoint(
                gpsFix = fix,
                imageX = mapState.finishPoint?.x ?: 0f,
                imageY = mapState.finishPoint?.y ?: 0f
            )
            finishCalibrated = true
            infoMessage = "Финиш привязан к GPS"
            isInfoVisible = true
        }

        // Auto-mode: exit navigation, set start = old finish, activate finish placement
        if (autoMode && autoCycleActive) {
            autoCycleActive = false
            // Exit navigation step back to step 2
            currentStepIndex = 1
            // Move start point to where finish was
            mapState.finishPoint?.let { fp ->
                viewModel.moveStartPoint(fp.x, fp.y)
            }
            // Calibration still valid, just need a new finish
            viewModel.setPlacingMode(PlacingMode.PLACING_FINISH)
            infoMessage = "Авто: Старт перемещен. Выберите новый финиш."
            isInfoVisible = true
        }
    }

    // Auto-cycle: when new finish is placed during auto-cycle, navigate to it
    LaunchedEffect(mapState.finishPoint) {
        if (autoMode && autoCycleActive && mapState.finishPoint != null && currentStepIndex == 1) {
            currentStepIndex = 2
            infoMessage = "Авто: Маршрут обновлен. Навигация."
            isInfoVisible = true
        }
    }

    // When both start and finish are calibrated, compute distance and scale
    LaunchedEffect(startCalibrated, finishCalibrated) {
        if (startCalibrated && finishCalibrated) {
            val cal = navViewModel.getCalibration()
            if (cal != null) {
                val sp = mapState.startPoint
                val fp = mapState.finishPoint
                if (sp != null && fp != null) {
                    // Compute route distance in meters
                    val startGps = navViewModel.imageToGps(sp.x, sp.y)
                    val finishGps = navViewModel.imageToGps(fp.x, fp.y)
                    if (startGps != null && finishGps != null) {
                        routeDistance = navViewModel.distanceBetween(startGps, finishGps)
                    }
                    // Map scale from calibration
                    mapScale = cal.scaleMetersPerPixel
                }
            }
            // Return to step 2 (route selection) after calibration
            infoMessage =
                "Привязка: ${String.format("%.0f", routeDistance ?: 0.0)} м"
            isInfoVisible = true
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
                        isActive = mapState.placingMode == PlacingMode.PLACING_START,
                        onClick = {
                            if (mapState.placingMode == PlacingMode.PLACING_START) {
                                viewModel.setPlacingMode(PlacingMode.NONE)
                                infoMessage = "Режим выбора СТАРТ отключен"
                                isInfoVisible = true
                            } else {
                                viewModel.setPlacingMode(PlacingMode.PLACING_START)
                                infoMessage = "Нажмите на карту чтобы поставить СТАРТ"
                                isInfoVisible = true
                            }
                        }
                    ),
                    PanelButton(
                        id = "finish",
                        text = "Финиш",
                        icon = FinishIcon,
                        isActive = mapState.placingMode == PlacingMode.PLACING_FINISH,
                        onClick = {
                            if (mapState.placingMode == PlacingMode.PLACING_FINISH) {
                                viewModel.setPlacingMode(PlacingMode.NONE)
                                infoMessage = "Режим выбора ФИНИШ отключен"
                                isInfoVisible = true
                            } else {
                                viewModel.setPlacingMode(PlacingMode.PLACING_FINISH)
                                infoMessage = "Нажмите на карту чтобы поставить ФИНИШ"
                                isInfoVisible = true
                            }
                        }
                    )
                )
            ),
            PanelStep(
                id = "step3",
                title = "Результат",
                buttons = listOf(
                    PanelButton(
                        id = "here_start",
                        text = "Здесь старт",
                        icon = TargetIcon,
                        isActive = startCalibrated,
                        onClick = {
                            val fix = gpsState.currentFix
                            if (fix != null && fix.accuracy < 30f) {
                                bindGpsToStart()
                            } else {
                                lowAccuracyCallback = bindGpsToStart
                                showLowAccuracyDialog = true
                            }
                        }
                    ),
                    PanelButton(
                        id = "here_finish",
                        text = "Здесь финиш",
                        icon = TargetIcon,
                        isActive = finishCalibrated,
                        onClick = {
                            if (autoMode && finishCalibrated) {
                                autoCycleActive = true
                            }
                            val fix = gpsState.currentFix
                            if (fix != null && fix.accuracy < 30f) {
                                bindGpsToFinish()
                            } else {
                                lowAccuracyCallback = bindGpsToFinish
                                showLowAccuracyDialog = true
                            }
                        }
                    ),
                    PanelButton(
                        id = "auto",
                        text = "Авто",
                        icon = null,
                        isActive = autoMode,
                        onClick = {
                            autoMode = !autoMode
                            infoMessage = if (autoMode) "Авто-режим включен" else "Авто-режим выключен"
                            isInfoVisible = true
                        }
                    )
                )
            )
        )
    }

    val currentStep = steps[currentStepIndex]

    // Location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.getOrDefault(android.Manifest.permission.ACCESS_FINE_LOCATION, false) ||
            permissions.getOrDefault(android.Manifest.permission.ACCESS_COARSE_LOCATION, false)
        ) {
            navViewModel.startGps()
        }
    }

    // Request location permission and start GPS on app launch
    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // Keep screen awake during navigation (step index 2)
    val view = LocalView.current
    LaunchedEffect(currentStepIndex) {
        if (currentStepIndex == 2) {
            view.keepScreenOn = true
        } else {
            view.keepScreenOn = false
        }
    }

    // Auto-advance to step 2 after processing completes
    LaunchedEffect(
        mapState.isProcessing,
        mapState.boundingBoxes,
        mapState.startPoint,
        mapState.finishPoint
    ) {
        if (!mapState.isProcessing && mapState.boundingBoxes.isNotEmpty() && currentStepIndex == 0) {
            currentStepIndex = 1
            infoMessage = "Шаг 2: ${steps[1].title}"
            isInfoVisible = true
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
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
                    northAngle = mapState.northAngle,
                    onNorthAngleChanged = { angle -> viewModel.updateNorthAngle(angle) },
                    onNorthAngleReset = { viewModel.resetNorthAngle() },
                    mapRotation = mapRotation,
                    trackPoints = gpsState.trackPoints,
                    calibration = gpsState.calibration,
                    currentFix = gpsState.currentFix,
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

            // Lock icon in top-right when navigating
            if (mapRotation != 0f) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Навигация",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 24.dp, end = 24.dp)
                )
            }

            // Top Info Panel
            TopInfoPanel(
                message = infoMessage,
                isVisible = isInfoVisible,
                progress = mapState.progress,
                isProcessing = mapState.isProcessing,
                progressMessage = mapState.progressMessage,
                azimuth = azimuth,
                gpsState = gpsState,
                routeDistance = routeDistance,
                currentDistanceFromStart = currentDistanceFromStart,
                mapScale = mapScale,
                magneticBearing = gpsState.currentFix?.bearing?.minus(
                    gpsState.calibration?.bearingDegrees?.toFloat() ?: 0f
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
            )

            // Low accuracy dialog
            if (showLowAccuracyDialog) {
                Dialog(
                    onDismissRequest = { showLowAccuracyDialog = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(32.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.medium
                            )
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val accuracyText = gpsState.currentFix?.let {
                                "${String.format("%.0f", it.accuracy)} м"
                            } ?: "—"
                            Text(
                                text = "Точность позиционирования: $accuracyText. Продолжить?",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        showLowAccuracyDialog = false
                                        lowAccuracyCallback?.invoke()
                                        lowAccuracyCallback = null
                                    }
                                ) {
                                    Text("Да")
                                }
                                Button(
                                    onClick = { showLowAccuracyDialog = false }
                                ) {
                                    Text("Отмена")
                                }
                            }
                        }
                    }
                }
            }

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
                    .padding(bottom = 16.dp),
                placingMode = mapState.placingMode
            )
        }
    }
}
