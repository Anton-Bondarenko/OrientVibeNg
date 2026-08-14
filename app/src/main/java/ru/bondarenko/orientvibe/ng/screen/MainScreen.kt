package ru.bondarenko.orientvibe.ng.screen

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ru.bondarenko.orientvibe.ng.image.rememberCameraSource
import ru.bondarenko.orientvibe.ng.image.rememberGalleryPicker
import ru.bondarenko.orientvibe.ng.image.ImageCapture
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.rememberCoroutineScope
import ru.bondarenko.orientvibe.ng.gps.GpsFix
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils

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
import ru.bondarenko.orientvibe.ng.image.ImageLoader
import ru.bondarenko.orientvibe.ng.model.PlacingMode

// РњРёРЅРёРјР°Р»СЊРЅР°СЏ С‚РѕС‡РЅРѕСЃС‚СЊ GPS РґР»СЏ РїСЂРёРІСЏР·РєРё Рє РєР°СЂС‚Рµ (30 РјРµС‚СЂРѕРІ)
private const val GPS_ACCURACY_LOW_THRESHOLD = 30f

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

// Target/crosshair icon for "РЇ Р·РґРµСЃСЊ" (I am here)
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

// Double circle (orienteering finish symbol) вЂ” outlined style for lighter appearance
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
    val mapState by viewModel.mapState.collectAsState()
    val gpsState by navViewModel.gpsState.collectAsState()

    var currentStepIndex by remember { mutableStateOf(0) }
    var infoMessage by remember { mutableStateOf("Добро пожаловать в OrientVibe") }
    var isInfoVisible by remember { mutableStateOf(true) }
    var showLowAccuracyDialog by remember { mutableStateOf(false) }
    var lowAccuracyCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Auto-bind GPS state
    var autoBindActive by remember { mutableStateOf(gpsState.autoBindActive) }
    var gpsImagePos by remember { mutableStateOf(Pair(0f, 0f)) }

    // Sync autoBindActive from gpsState
    LaunchedEffect(gpsState.autoBindActive) {
        autoBindActive = gpsState.autoBindActive
    }

    // Compute current GPS position in image-space for green circle indicator
    LaunchedEffect(gpsState.currentFix, gpsState.calibration, mapState.bitmap, mapState.northAngle, autoBindActive) {
        if (autoBindActive && mapState.bitmap != null && gpsState.calibration != null && gpsState.currentFix != null) {
            navViewModel.getCurrentGpsImageAbs(mapState.northAngle)?.let { pos ->
                gpsImagePos = pos
                // Also update kpBoxes for hit testing
                navViewModel.setAutoBindKpBoxes(mapState.controlsBoundingBoxes)
                navViewModel.setAutoBindImageDimensions(mapState.bitmap!!.width.toFloat() to mapState.bitmap!!.height.toFloat())
            } ?: run { gpsImagePos = Pair(0f, 0f) }
        } else {
            gpsImagePos = Pair(0f, 0f)
        }
    }

    // Состояние навигации читаем из gpsState (канонический источник — NavViewModel)

    // Current distance from start point to current GPS position (используем канонический source из NavViewModel)
    val currentDistanceFromStart = remember(gpsState.originalStartGps, gpsState.currentFix) {
        if (gpsState.originalStartGps != null && gpsState.currentFix != null) {
            navViewModel.distanceBetween(gpsState.originalStartGps!!, gpsState.currentFix!!.coordinate)
        } else {
            null
        }
    }

    var autoPlacementDone by remember { mutableStateOf(false) }

    // Reset auto-placement and auto-bind flags when a new image starts loading
    LaunchedEffect(mapState.isProcessing) {
        if (mapState.isProcessing) {
            autoPlacementDone = false
            autoBindActive = false
        }
    }

    // Auto-place start after image load (only once)
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

    val imageLoader = ImageLoader(
        context = LocalContext.current,
        detector = viewModel.detector,
    )

    // Состояние выбранного из галереи URI — запускает загрузку через LaunchedEffect
    var pendingGalleryUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val localContext = LocalContext.current

    // При появлении URI — загружаем в viewModel + запускаем детекцию
    LaunchedEffect(pendingGalleryUri) {
        val uri = pendingGalleryUri ?: return@LaunchedEffect
        infoMessage = "Загрузка изображения..."
        isInfoVisible = true

        try {
            // 1. Raw bitmap (без EXIF) — MapViewModel применит коррекцию
            val rawBm = localContext.contentResolver.openInputStream(uri).use { stream ->
                BitmapFactory.decodeStream(stream)
                    ?: throw IllegalStateException("Bitmap decode failed")
            }
            viewModel.loadImageFromBitmap(rawBm, uri)

            // 2. EXIF-повёрнутый bitmap для детекции
            imageLoader.loadImageForGallery(uri, { /* уже обновлён MapState */ }) { result ->
                viewModel.updateDetectionResults(result)
            }
        } catch (e: Exception) {
            infoMessage = "Ошибка загрузки изображения"
            isInfoVisible = true
            e.printStackTrace()
        } finally {
            pendingGalleryUri = null
        }
    }

    val galleryPicker = rememberGalleryPicker { uri -> pendingGalleryUri = uri }
    // Камера — только камера, без галереи
    val camera = rememberCameraSource(
        context = LocalContext.current,
        onImageCaptured = { imageCapture ->
            infoMessage = "Фото сделано"
            isInfoVisible = true
            viewModel.loadImageFromBitmap(imageCapture.bitmap, imageCapture.uri)
        },
    )

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

            mapState.controlsBoundingBoxes.isNotEmpty() && currentStepIndex != 2 -> {
                infoMessage = "Найдено ${mapState.controlsBoundingBoxes.size} контрольных точек"
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
        val bmpW = mapState.bitmap?.width?.toFloat() ?: 1f
        val bmpH = mapState.bitmap?.height?.toFloat() ?: 1f

        // Store original start GPS for later recalibration — канонический source в NavViewModel
        navViewModel.setOriginalStartGps(fix.coordinate)

        // Одно-точечная калибровка (placeholder для будущей двух-точечной через "Здесь финиш")
        val cal = MapCalibrationUtils.calibrateSinglePoint(
            startGPS = fix.coordinate,
            startPointImageX = (mapState.startPoint?.x ?: 0f) * bmpW,
            startPointImageY = (mapState.startPoint?.y ?: 0f) * bmpH
        )
        navViewModel.applyStartCalibration(cal)
        navViewModel.startTracking()
        infoMessage = "Старт привязан к GPS"
        isInfoVisible = true
    }

    // Привязка финиша: создаёт синтетический GPS финиша по направлению и длине трека,
    // пересчитывает масштаб карты и northAngle
    val bindGpsToFinish: () -> Unit = bindFinish@{
        val fix = gpsState.currentFix ?: return@bindFinish
        val startGPS = navViewModel.getOriginalStartGps() ?: return@bindFinish

        val bmpW = mapState.bitmap?.width?.toFloat() ?: 1f
        val bmpH = mapState.bitmap?.height?.toFloat() ?: 1f
        val result = MapCalibrationUtils.bindGpsToFinishWithTrack(
            startGPS = startGPS,
            startPointImageX = (mapState.startPoint?.x ?: 0f) * bmpW,
            startPointImageY = (mapState.startPoint?.y ?: 0f) * bmpH,
            finishPointImageX = (mapState.finishPoint?.x ?: 0f) * bmpW,
            finishPointImageY = (mapState.finishPoint?.y ?: 0f) * bmpH,
            currentFixGPS = fix.coordinate
        )

        navViewModel.applyNewCalibration(result.calibration)
        viewModel.updateNorthAngle(result.northAngleDegrees)

        infoMessage = "Масштаб: ${String.format("%.0f", result.calibration.scaleMetersPerPixel)} м/px"
        isInfoVisible = true
    }

    // Когда обе привязки выполнены, показываем дистанцию и масштаб из gpsState
    LaunchedEffect(gpsState.startCalibrated, gpsState.finishCalibrated) {
        if (gpsState.startCalibrated && gpsState.finishCalibrated) {
            // Return to step 2 (route selection) after calibration
            infoMessage = "Привязка: ${String.format("%.0f", gpsState.routeDistance ?: 0.0)} м"
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
                            camera.launchCamera()
                        }
                    ),
                    PanelButton(
                        id = "gallery",
                        text = "",
                        icon = Icons.Default.PhotoLibrary,
                        onClick = {
                            infoMessage = "Открытие галереи..."
                            isInfoVisible = true
                            galleryPicker()
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
                        isActive = gpsState.startCalibrated,
                        onClick = {
                            val fix = gpsState.currentFix
                            if (fix != null && fix.accuracy < GPS_ACCURACY_LOW_THRESHOLD) {
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
                        isActive = gpsState.finishCalibrated,
                        onClick = {
                            val fix = gpsState.currentFix
                            if (fix != null && fix.accuracy < GPS_ACCURACY_LOW_THRESHOLD) {
                                bindGpsToFinish()
                            } else {
                                lowAccuracyCallback = bindGpsToFinish
                                showLowAccuracyDialog = true
                            }
                        }
                    )
                )
            ),
            PanelStep(
                id = "stepAuto",
                title = "Авто-режим",
                buttons = listOf(
                    PanelButton(
                        id = "auto_here",
                        text = "Здесь",
                        icon = TargetIcon,
                        isActive = autoBindActive,
                        onClick = {
                            navViewModel.setAutoBindActive(!autoBindActive)
                            infoMessage = if (navViewModel.getAutoBindActive()) "Режим привязки включен" else "Режим привязки выключен"
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
        mapState.controlsBoundingBoxes,
        mapState.startPoint,
        mapState.finishPoint
    ) {
        if (!mapState.isProcessing && mapState.controlsBoundingBoxes.isNotEmpty() && currentStepIndex == 0) {
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
                    controlsBoundingBoxes = mapState.controlsBoundingBoxes,
                    numbersBoundingBoxes = mapState.numbersBoundingBoxes,
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
                    autoBindActive = autoBindActive,
                    gpsFixImagePos = gpsImagePos.takeIf { it != Pair(0f, 0f) },
                    calibrationPointBGps = gpsState.calibration?.pointB?.gps,
                    calibrationImageDims = mapState.bitmap?.let { bm -> Pair(bm.width.toFloat(), bm.height.toFloat()) },
                    onAutoBindTap = { relX, relY ->
                        if (!autoBindActive) return@SubsamplingMapView false
                        val fix = gpsState.currentFix ?: return@SubsamplingMapView false

                        val sWidth = mapState.bitmap?.width?.toFloat() ?: return@SubsamplingMapView false
                        val sHeight = mapState.bitmap?.height?.toFloat() ?: return@SubsamplingMapView false
                        val tapAbsX = relX * sWidth
                        val tapAbsY = relY * sHeight

                        // Check proximity to KP circles using the current GPS position (not the tap)
                        val gpsPos = gpsImagePos
                        if (gpsPos == Pair(0f, 0f)) return@SubsamplingMapView false

                        val kpIdx = navViewModel.checkKpHit(tapAbsX, tapAbsY)
                        if (kpIdx >= 0 && mapState.controlsBoundingBoxes.isNotEmpty()) {
                            // Verify the KP is close to current GPS position on image
                            val kpBox = mapState.controlsBoundingBoxes[kpIdx]
                            val kpCenterRelX = kpBox.centerX
                            val kpCenterRelY = kpBox.centerY

                            // Check if tap is near both KP center AND near current GPS position
                            val dx = kpCenterRelX - relX
                            val dy = kpCenterRelY - relY
                            val distSq = dx * dx + dy * dy

                            // Hit-test radius in relative coords (~80 view-pixels)
                            if (distSq < 0.05 * 0.05) {
                                // KP is near tap — now verify it's also near current GPS fix position
                                val gpsAbsX = gpsPos.first
                                val gpsAbsY = gpsPos.second
                                val imageW = mapState.bitmap!!.width.toFloat()
                                val imageH = mapState.bitmap!!.height.toFloat()
                                val gpsRelX = gpsAbsX / imageW
                                val gpsRelY = gpsAbsY / imageH

                                val dx2 = kpCenterRelX - gpsRelX
                                val dy2 = kpCenterRelY - gpsRelY
                                val distToGpsSq = dx2 * dx2 + dy2 * dy2

                                // Within ~0.06 relative distance (~120 view-pixels) of GPS fix → it's a match!
                                if (distToGpsSq < 0.06 * 0.06) {
                                    // Bind current GPS to this KP — convert relative to absolute pixels
                                val kpAbsX = kpBox.centerX * imageW
                                val kpAbsY = kpBox.centerY * imageH
                                    // Bind current GPS to this KP using single-point calibration
                                    val newCal = MapCalibrationUtils.calibrateSinglePoint(
                                        startGPS = fix.coordinate,
                                        startPointImageX = kpAbsX,
                                        startPointImageY = kpAbsY
                                    )
                                    navViewModel.applyNewCalibration(newCal)
                                    infoMessage = "КП ${kpIdx + 1} привязан к GPS"
                                    isInfoVisible = true
                                    navViewModel.setAutoBindActive(false)
                                    return@SubsamplingMapView true
                                }
                            }
                        }
                        false
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
            } else {
                MapDisplayArea(
                    mapImageUri = mapState.imageUri?.toString(),
                    onCameraClick = {
                        infoMessage = "Запрос разрешения камеры..."
                        isInfoVisible = true
                        camera.launchCamera()
                    },
                    onGalleryClick = {
                        infoMessage = "Открытие галереи..."
                        isInfoVisible = true
                        galleryPicker()
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
                routeDistance = gpsState.routeDistance,
                currentDistanceFromStart = currentDistanceFromStart,
                mapScale = gpsState.mapScale,
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

