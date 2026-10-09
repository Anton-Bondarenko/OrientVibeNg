package ru.bondarenko.orientvibe.ng.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import ru.bondarenko.orientvibe.ng.R


import androidx.lifecycle.viewmodel.compose.viewModel
import ru.bondarenko.orientvibe.ng.gps.NavViewModel
import ru.bondarenko.orientvibe.ng.image.rememberCameraSource
import ru.bondarenko.orientvibe.ng.model.AccuracyLevel
import ru.bondarenko.orientvibe.ng.ui.components.MapDisplayArea
import ru.bondarenko.orientvibe.ng.ui.components.MapDragListener
import ru.bondarenko.orientvibe.ng.ui.components.MapTapListener
import ru.bondarenko.orientvibe.ng.ui.components.SubsamplingMapView
import ru.bondarenko.orientvibe.ng.viewmodel.AutoModeViewModel

private val GreenReady = Color(0xFF4CAF50)
private val GreenReadyDark = Color(0xFF2E7D32)
private val TelemetryBg = Color(0xFF1B5E20)

@Composable
fun AutoModeScreen() {
    val context = LocalContext.current
    val navVm = viewModel<NavViewModel>(factory = NavViewModel.Factory(context))
    val autoVm = viewModel<AutoModeViewModel>(factory = AutoModeViewModel.Factory(context))

    // Передаём NavViewModel в AutoModeViewModel и запускаем мониторинг GPS
    LaunchedEffect(Unit) {
        autoVm.navVm = navVm  // устанавливаем поле экземпляра, а не static
        autoVm.startMonitoringIfNeeded()
    }

    val mapState by autoVm.mapState.collectAsState()
    val moveReadyAlert by autoVm.moveReadyAlert.collectAsState()
    val telemetryPoints by autoVm.telemetryPoints.collectAsState()
    val movementState by autoVm.movementState.collectAsState()

    // GPS состояние — источник для трека, калибровки и текущего фиксa
    val gps by navVm.gpsState.collectAsState()

    // Версия калибровки — триггер для recomposition при повторной калибровке
    val calibrationVersion by autoVm.calibrationVersion.collectAsState()
    var infoMessage by remember { mutableStateOf("Авто-режим: выберите карту") }
    var isInfoVisible by remember { mutableStateOf(true) }

    var pendingBind by remember { mutableStateOf<Int?>(null) }
    var isBinding by remember { mutableStateOf(false) }

    var pendingScale by remember { mutableStateOf(false) }
    var isScaling by remember { mutableStateOf(false) }

    // Кнопка "Здесь" активна только когда currentControl совпадает с номером найденного CP
    val hasMatchingCp = mapState.controlsBoundingBoxes.any { it.number == movementState.currentControl.num }
    val isBindEnabled = !isBinding && hasMatchingCp

    // Состояние первой привязки и кнопки "масштаб"
    val hasBoundCp = autoVm.hasBoundCp
    val detectedCpNumbers = autoVm.getDetectedCpNumbers()
    val targetExistsInDetected = detectedCpNumbers.contains(movementState.currentControl.num)

    // "масштаб" активна когда: есть привязка, текущий CP ≠ привязанный CP, и выбранный CP найден на карте
    val isScaleEnabled =
        hasBoundCp && targetExistsInDetected && !isScaling

    // Обработка привязки GPS → контрольная точка
    LaunchedEffect(pendingBind) {
        if (pendingBind == null) return@LaunchedEffect
        pendingBind = null
        isBinding = true
        val (_, msg) = autoVm.bindGpsToCurrentControl()
        infoMessage = msg
        isInfoVisible = true
        isBinding = false
    }

    // Обработка перекалибровки (масштаб) по второму КП
    LaunchedEffect(pendingScale) {
        if (!pendingScale) return@LaunchedEffect
        pendingScale = false
        isScaling = true
        val (_, msg) = autoVm.recalibrateToTargetControl()
        infoMessage = msg
        isInfoVisible = true
        isScaling = false
    }

    var pendingGalleryUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var loadTestMapTrigger by remember { mutableStateOf(false) }

    // Загрузка тестовой карты из /storage/emulated/0/Pictures/test_map.jpg — обход системного пикера
    LaunchedEffect(loadTestMapTrigger) {
        if (!loadTestMapTrigger) return@LaunchedEffect
        loadTestMapTrigger = false
        infoMessage = "Загрузка тестовой карты..."
        isInfoVisible = true
        try {
            val mapFile = java.io.File("/storage/emulated/0/Pictures/test_map.jpg")
            if (mapFile.exists()) {
                val rawBm = android.graphics.BitmapFactory.decodeStream(mapFile.inputStream())
                    ?: throw IllegalStateException("Bitmap decode failed")
                infoMessage = "Тестовая карта загружена"
                isInfoVisible = true
                autoVm.loadImageFromBitmap(
                    rawBm.copy(
                        android.graphics.Bitmap.Config.ARGB_8888,
                        false
                    )
                )
            } else {
                infoMessage = "test_map.jpg не найден в Pictures"
                isInfoVisible = true
            }
        } catch (e: Exception) {
            infoMessage = "Ошибка загрузки: ${e.message}"
            isInfoVisible = true
            e.printStackTrace()
        }
    }

    // Загрузка из галереи — при появлении URI загружаем в viewModel
    LaunchedEffect(pendingGalleryUri) {
        val uri = pendingGalleryUri ?: return@LaunchedEffect
        infoMessage = "Загрузка изображения..."
        isInfoVisible = true
        try {
            autoVm.loadImageFromUri(uri)
        } catch (e: Exception) {
            infoMessage = "Ошибка загрузки изображения"
            isInfoVisible = true
            e.printStackTrace()
        } finally {
            pendingGalleryUri = null
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { pendingGalleryUri = it }
    }

    // Камера — только камера, без галереи
    var cameraLoaded by remember { mutableStateOf(false) }
    val camera = rememberCameraSource(
        context = context,
        onImageCaptured = { imageCapture ->
            if (!cameraLoaded) {
                infoMessage = "Фото сделано"
                isInfoVisible = true
                autoVm.loadImageFromBitmap(imageCapture.bitmap, imageCapture.uri)
                cameraLoaded = true
            }
        },
    )

    // Update info message based on state
    LaunchedEffect(
        mapState.isProcessing,
        mapState.errorMessage,
        mapState.northAngle
    ) {
        when {
            mapState.isProcessing -> {
                infoMessage = "Обработка изображения..."
                isInfoVisible = true
            }

            mapState.errorMessage != null -> {
                infoMessage = "Ошибка: ${mapState.errorMessage}"
                isInfoVisible = true
            }

            mapState.controlsBoundingBoxes.isNotEmpty() && mapState.numbersBoundingBoxes.isNotEmpty() -> {
                infoMessage =
                    "Найдено CP: ${mapState.controlsBoundingBoxes.size}, номеров: ${mapState.numbersBoundingBoxes.size}"
                isInfoVisible = true
            }

            mapState.controlsBoundingBoxes.isNotEmpty() -> {
                infoMessage = "Найдено ${mapState.controlsBoundingBoxes.size} контрольных точек"
                isInfoVisible = true
            }

            else -> {
                isInfoVisible = false
            }
        }
    }

    // Map tap listener (для ручной корректировки точек)
    val tapListener = remember {
        object : MapTapListener {
            override fun onMapTap(relativeX: Float, relativeY: Float) {
                // Auto mode doesn't use route points - tap is no-op
            }
        }
    }

    // Map drag listener (для перемещения точек маршрута)
    val dragListener = remember {
        object : MapDragListener {
            override fun onStartPointDragged(relativeX: Float, relativeY: Float) {}
            override fun onFinishPointDragged(relativeX: Float, relativeY: Float) {}
        }
    }

    // ── Зелёный баннер "можно двигаться" ─────────────────────────────────────
    val alertActive = moveReadyAlert.active
    val progressColor by animateColorAsState(
        targetValue = if (moveReadyAlert.progress >= 1f) GreenReadyDark else GreenReady,
        animationSpec = tween(durationMillis = 300),
        label = "alertProgressColor"
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // ── Map Display Area (with overlay) ──
        if (mapState.bitmap != null) {
            SubsamplingMapView(
                bitmap = mapState.bitmap!!,
                controlsBoundingBoxes = mapState.controlsBoundingBoxes,
                numbersBoundingBoxes = mapState.numbersBoundingBoxes,
                tapListener = tapListener,
                dragListener = dragListener,
                northAngle = mapState.northAngle,
                onNorthAngleChanged = { angle -> autoVm.updateNorthAngle(angle) },
                onNorthAngleReset = { autoVm.resetNorthAngle() },
                mapRotation = 0f,
                // Трек отображаем из telemetryPoints авто-режима — они не сбрасываются при повторной калибровке
                trackPoints = autoVm.getTelemetryTrackPoints(),
                calibrationVersionTrigger = calibrationVersion, // триггер пересчёта на каждом recalibration
                calibration = gps.calibration,
                currentFix = gps.currentFix,
                autoBindActive = false,
                gpsFixImagePos = null,
                calibrationPointBGps = gps.calibration?.pointB?.gps,
                calibrationImageDims = mapState.bitmap?.let { bm ->
                    Pair(
                        bm.width.toFloat(),
                        bm.height.toFloat()
                    )
                },
                onAutoBindTap = { relX, relY -> false },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            MapDisplayArea(
                mapImageUri = null,
                onCameraClick = { camera.launchCamera() },
                onGalleryClick = {
                    galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── Lock icon in top-right when navigating ──
        val mapRotation = 0f // Auto mode doesn't support rotation yet
        if (mapRotation != 0f) {
            Icon(
                imageVector = Icons.Default.AutoFixNormal,
                contentDescription = "Авто-режим",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = 24.dp, end = 24.dp)
            )
        }

        // ── Зелёный баннер "можно двигаться" (поверх карты, снизу) ──
        if (alertActive && moveReadyAlert.elapsedMs < 10000L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(bottom = if (telemetryPoints.isNotEmpty()) 16.dp else 16.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(80.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = GreenReady.copy(alpha = 0.95f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text(
                            text = "Можно двигаться!",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                        LinearProgressIndicator(
                            progress = { moveReadyAlert.progress },
                            color = progressColor,
                            trackColor = GreenReadyDark,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // ── GPS accuracy indicator (top-left) ──
        val fix = gps.currentFix
        if (fix != null) {
            val accuracyLevel = gps.accuracyLevel
            val (accuracyColor, accuracyText) = when (accuracyLevel) {
                AccuracyLevel.HIGH_ACCURACY -> GreenReady to "GPS: ${
                    String.format(
                        java.util.Locale.ROOT,
                        "%.0f",
                        fix.accuracy
                    )
                }м"

                AccuracyLevel.LOW_ACCURACY -> Color(0xFFFFC107) to "GPS: ${
                    String.format(
                        java.util.Locale.ROOT,
                        "%.0f",
                        fix.accuracy
                    )
                }м"

                AccuracyLevel.NO_FIX -> Color.Red to "GPS: нет сигнала"
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 16.dp, start = 16.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(accuracyColor.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(accuracyColor, RoundedCornerShape(5.dp))
                    )
                    Text(
                        text = accuracyText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }

        // ── Top Info Panel ──
        if (isInfoVisible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .align(Alignment.TopCenter)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = infoMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (mapState.isProcessing) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = mapState.progressMessage ?: "Обработка...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Control Selector (bottom bar with editable number, +/-, "Здесь") ──
        if (mapState.bitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.95f)
                    )
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        // Кнопка минус
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = { autoVm.decrementCurrentControl() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Text(
                                text = "−",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }

                        // Редактируемое число — отображаем currentControl.value, вводим вручную или кнопками +/−
                        OutlinedTextField(
                            value = movementState.currentControl.num.toString(),
                            onValueChange = { raw ->
                                val filtered = raw.filter { it.isDigit() }
                                if (filtered.isEmpty() || filtered.toIntOrNull() != null) {
                                    autoVm.setCurrentControl(filtered.toInt())
                                }
                            },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            ),
                            modifier = Modifier
                                .width(80.dp)
                                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                                .padding(4.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.White,
                                unfocusedContainerColor = Color.White,
                                focusedBorderColor = GreenReadyDark,
                                unfocusedBorderColor = Color.Gray
                            )
                        )

                        // Кнопка плюс
                        androidx.compose.material3.FilledTonalIconButton(
                            onClick = { autoVm.incrementCurrentControl() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Text(
                                text = "+",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }

                        // ── Квадратные кнопки — две равные на всю ширину панели ──
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Кнопка "Здесь" — зелёная когда привязка ещё не сделана
                            val bindGreen = !hasBoundCp
                            androidx.compose.material3.Button(
                                onClick = {
                                    autoVm.setCurrentControl(movementState.currentControl.num)
                                    isBindEnabled && run {
                                        pendingBind = movementState.currentControl.num
                                        true
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                enabled = isBindEnabled,
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = if (bindGreen) GreenReadyDark else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                ),
                                elevation = androidx.compose.material3.ButtonDefaults.buttonElevation(
                                    defaultElevation = 6.dp,
                                    pressedElevation = 12.dp,
                                    disabledElevation = 0.dp
                                ),
                                modifier = Modifier.weight(0.5f).aspectRatio(1f)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(R.drawable.icon_here),
                                        contentDescription = "Здесь (A)",
                                        modifier = Modifier.fillMaxSize().padding(6.dp),
                                        tint = Color.Unspecified
                                    )
                                }
                            }

                            // Кнопка "Масштаб" — зелёная когда привязка уже сделана
                            val scaleGreen = hasBoundCp && isScaleEnabled
                            androidx.compose.material3.Button(
                                onClick = {
                                    autoVm.setCurrentControl(movementState.currentControl.num)
                                    isScaleEnabled && run {
                                        pendingScale = true
                                        true
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                enabled = isScaleEnabled,
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = if (scaleGreen) GreenReadyDark else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                ),
                                elevation = androidx.compose.material3.ButtonDefaults.buttonElevation(
                                    defaultElevation = 6.dp,
                                    pressedElevation = 12.dp,
                                    disabledElevation = 0.dp
                                ),
                                modifier = Modifier.weight(0.5f).aspectRatio(1f)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Icon(
                                        painter = androidx.compose.ui.res.painterResource(R.drawable.icon_scale),
                                        contentDescription = "Масштаб (B)",
                                        modifier = Modifier.fillMaxSize().padding(6.dp),
                                        tint = Color.Unspecified
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

