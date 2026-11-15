package ru.bondarenko.orientvibe.ng.screen

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.bondarenko.orientvibe.ng.gps.NavViewModel
import ru.bondarenko.orientvibe.ng.image.rememberCameraSource
import ru.bondarenko.orientvibe.ng.model.AccuracyLevel
import ru.bondarenko.orientvibe.ng.model.MovementState
import ru.bondarenko.orientvibe.ng.ui.components.MapDisplayArea
import ru.bondarenko.orientvibe.ng.ui.components.MapDragListener
import ru.bondarenko.orientvibe.ng.ui.components.MapTapListener
import ru.bondarenko.orientvibe.ng.ui.components.SubsamplingMapView
import ru.bondarenko.orientvibe.ng.viewmodel.AutoModeViewModel
import java.util.Locale
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.ui.res.painterResource
import ru.bondarenko.orientvibe.ng.R


private val GreenReady = Color(0xFF4CAF50)      // Восстановлено из 4283215696L
private val GreenReadyDark = Color(0xFF388E3C)  // Восстановлено из 4281236786L
private val BasePanelColor = Color(0xFF1E1E1E)  // Восстановлено из 4280163870L

@Composable
fun AutoModeScreen() {
    val context = LocalContext.current

    // Инициализация ViewModels через фабрики
    val navVm: NavViewModel = viewModel(factory = NavViewModel.Companion.Factory(context))
    val autoVm: AutoModeViewModel =
        viewModel(factory = AutoModeViewModel.Companion.Factory(context))

    // Запрос пермишенов локации при старте экрана
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            navVm.startGps()
        }
    }

    LaunchedEffect(Unit) {
        autoVm.navVm = navVm
        autoVm.startMonitoringIfNeeded()
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // Сбор реактивных состояний (StateFlow -> Compose State)
    val mapState by autoVm.mapState.collectAsState()
    val moveReadyAlert by autoVm.moveReadyAlert.collectAsState()
    val movementState by autoVm.movementState.collectAsState()
    val gpsState by navVm.gpsState.collectAsState()
    val calibrationVersion by autoVm.calibrationVersion.collectAsState()

    // Внутреннее интерактивное состояние UI на основе делегатов `by remember`
    var infoMessage by remember { mutableStateOf("Авто-режим: выберите карту") }
    var isInfoVisible by remember { mutableStateOf(true) }
    var pendingBind by remember { mutableStateOf<Int?>(null) }
    var isBinding by remember { mutableStateOf(false) }
    var pendingScale by remember { mutableStateOf(false) }
    var isScaling by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val sharedPreferences =
        remember { context.getSharedPreferences("orientvibe_settings", Context.MODE_PRIVATE) }
    var showAllBoxes by remember {
        mutableStateOf(
            sharedPreferences.getBoolean(
                "show_all_bounding_boxes",
                false
            )
        )
    }

    var pendingGalleryUri by remember { mutableStateOf<Uri?>(null) }
    var loadTestMapTrigger by remember { mutableStateOf(false) }
    var textControlLandscape by remember { mutableStateOf("1") }
    var textControlPortrait by remember { mutableStateOf("1") }
    var cameraLoaded by remember { mutableStateOf(false) }

    // Реакция на изменение целевого КП на треке
    LaunchedEffect(movementState.currentControl.num) {
        val currentNum = movementState.currentControl.num
        textControlLandscape = currentNum.toString()
        textControlPortrait = currentNum.toString()
    }

    // Реакция на изменение флагов калибровки
    val hasMatchingCp =
        mapState.controlsBoundingBoxes.any { it.number == movementState.currentControl.num }
    val isBindEnabled = !isBinding && hasMatchingCp
    val hasBoundCp = autoVm.hasBoundCp
    val needsScaleCalibration = hasBoundCp && mapState.mapCalibration?.pointB == null
    val targetExistsInDetected = autoVm.detectedCpNumbers.contains(movementState.currentControl.num)
    val isScaleEnabled = hasBoundCp && targetExistsInDetected && !isScaling

    LaunchedEffect(needsScaleCalibration) {
        if (needsScaleCalibration) {
            infoMessage = "Найдите следующее КП и сделайте калибровку B"
            isInfoVisible = true
        }
    }

    // Фото-видео и Галерея лаунчеры
    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingGalleryUri = uri
        }
    }

    // Перехват выбора изображения из галереи
    LaunchedEffect(pendingGalleryUri) {
        pendingGalleryUri?.let { uri ->
            pendingGalleryUri = null // Сбрасываем, чтобы при перекомпозиции не сработало повторно
            infoMessage = "Загрузка карты..."
            isInfoVisible = true
            autoVm.loadImageFromUri(uri)
        }
    }

    LaunchedEffect(pendingBind) {
        pendingBind?.let {
            isBinding = true
            val (_, msg) = autoVm.bindGpsToCurrentControl()
            infoMessage = msg
            isInfoVisible = true
            isBinding = false
            pendingBind = null
        }
    }

    LaunchedEffect(isScaling) {
        if (isScaling) {
            val (_, msg) = autoVm.recalibrateToTargetControl()
            infoMessage = msg
            isInfoVisible = true
            isScaling = false
        }
    }

    // Реакция на прогресс детекции YOLO и обновление верхней панели
    LaunchedEffect(
        mapState.isProcessing,
        mapState.progressMessage,
        mapState.errorMessage
    ) {
        when {
            mapState.errorMessage != null -> {
                infoMessage = "Ошибка: ${mapState.errorMessage}"
                isInfoVisible = true
            }

            mapState.isProcessing -> {
                infoMessage = mapState.progressMessage ?: "Обработка карты..."
                isInfoVisible = true
            }

            mapState.bitmap != null && !mapState.isProcessing -> {
                val cpCount = mapState.controlsBoundingBoxes.size
                val numCount = mapState.controlsBoundingBoxes.count { it.number != null }
                infoMessage = "Найдено КП: $cpCount (с номерами: $numCount)"
                isInfoVisible = true
            }
        }
    }

    val camera = rememberCameraSource(context) { imageCapture ->
        if (!cameraLoaded) {
            infoMessage = "Фото сделано"
            isInfoVisible = true
            autoVm.loadImageFromBitmap(imageCapture.bitmap, imageCapture.uri)
            cameraLoaded = true
        }
    }

    // Анимация цвета панели уведомления
    val alertColor by animateColorAsState(
        targetValue = if (moveReadyAlert.progress >= 1.0) GreenReadyDark else GreenReady,
        animationSpec = tween(durationMillis = 300),
        label = "alertProgressColor"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Отображение карты или области выбора источника данных
        if (mapState.bitmap != null) {
            val pointB = gpsState.calibration?.pointB
            SubsamplingMapView(
                bitmap = mapState.bitmap!!,
                controlsBoundingBoxes = mapState.controlsBoundingBoxes,
                numbersBoundingBoxes = mapState.numbersBoundingBoxes,
                tapListener = object : MapTapListener {
                    override fun onMapTap(relativeX: Double, relativeY: Double) {}
                },
                dragListener = object : MapDragListener {
                    override fun onStartPointDragged(relativeX: Double, relativeY: Double) {}
                    override fun onFinishPointDragged(relativeX: Double, relativeY: Double) {}
                },
                northAngle = mapState.northAngle,
                onNorthAngleChanged = { angle -> autoVm.updateNorthAngle(angle) },
                onNorthAngleReset = { autoVm.resetNorthAngle() },
                trackPoints = autoVm.getTelemetryTrackPoints(),
                calibrationVersionTrigger = calibrationVersion,
                calibration = gpsState.calibration,
                currentFix = gpsState.currentFix,
                modifier = Modifier.fillMaxSize(),
                showAllBoxes = showAllBoxes,
                calibrationPointBGps = pointB?.gps,
                gpsFixImagePos = null
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

        // 2. Виджет "Можно двигаться"
        if (moveReadyAlert.active) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(bottom = 16.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = alertColor.copy(alpha = 0.95f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Можно двигаться!",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        LinearProgressIndicator(
                            progress = { moveReadyAlert.progress.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            color = GreenReadyDark,
                            trackColor = GreenReady
                        )
                    }
                }
            }
        }

        // 3. Плашка плавающего статуса точности GPS
        gpsState.currentFix?.let { fix ->
            val (statusColor, statusText) = when (gpsState.accuracyLevel) {
                AccuracyLevel.HIGH_ACCURACY -> Pair(
                    GreenReady,
                    "GPS: ${String.format(Locale.ROOT, "%.0f", fix.accuracy)}м"
                )

                AccuracyLevel.LOW_ACCURACY -> Pair(
                    Color(0xFFFFC107),
                    "GPS: ${String.format(Locale.ROOT, "%.0f", fix.accuracy)}м"
                )

                AccuracyLevel.NO_FIX -> Pair(Color.Red, "GPS: нет сигнала")
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 16.dp)
                    .background(statusColor.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(statusColor, RoundedCornerShape(5.dp))
                )
                Text(
                    text = statusText,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
// 4. Панель статуса детекции
        if (isInfoVisible) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = 0.95f
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ДОБАВЛЕНО: weight(1f) заставляет левую часть сжиматься и переносить текст,
                    // оставляя место для кнопки настроек справа
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = infoMessage,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Настройки",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
// 5. Боковая или нижняя панель управления (в зависимости от ориентации)
        if (mapState.bitmap != null) {
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (isLandscape) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(64.dp)
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, top = 8.dp, bottom = 8.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxHeight(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f))
                    ) {
                        ControlButtonsColumn(
                            autoVm = autoVm,
                            movementState = movementState,
                            isBindEnabled = isBindEnabled,
                            isScaleEnabled = isScaleEnabled,
                            onBindClick = {
                                autoVm.setCurrentControl(movementState.currentControl.num)
                                if (isBindEnabled) pendingBind = movementState.currentControl.num
                            },
                            onScaleClick = {
                                autoVm.setCurrentControl(movementState.currentControl.num)
                                if (isScaleEnabled) isScaling = true
                            }
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp, start = 16.dp, end = 16.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f))
                    ) {
                        ControlButtonsRow(
                            autoVm = autoVm,
                            movementState = movementState,
                            isBindEnabled = isBindEnabled,
                            isScaleEnabled = isScaleEnabled,
                            onBindClick = {
                                autoVm.setCurrentControl(movementState.currentControl.num)
                                if (isBindEnabled) pendingBind = movementState.currentControl.num
                            },
                            onScaleClick = {
                                autoVm.setCurrentControl(movementState.currentControl.num)
                                if (isScaleEnabled) isScaling = true
                            }
                        )
                    }
                }
            }
        }
    }
// Настройки
    if (showSettingsDialog) {
        var autoCalibrationPref by remember {
            mutableStateOf(
                sharedPreferences.getBoolean(
                    "auto_calibration",
                    true
                )
            )
        }
        var showAllBoxesPref by remember {
            mutableStateOf(
                sharedPreferences.getBoolean(
                    "show_all_bounding_boxes",
                    false
                )
            )
        }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Настройки авто-режима") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Автокалибровка")
                        Switch(
                            checked = autoCalibrationPref,
                            onCheckedChange = { autoCalibrationPref = it })
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Показать bounding boxes")
                        Switch(
                            checked = showAllBoxesPref,
                            onCheckedChange = { showAllBoxesPref = it })
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            showSettingsDialog = false
                            showResetConfirm = true
                        }
                    ) {
                        Text("Сбросить состояние")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    sharedPreferences.edit().apply {
                        putBoolean("auto_calibration", autoCalibrationPref)
                        putBoolean("show_all_bounding_boxes", showAllBoxesPref)
                        apply()
                    }
                    showAllBoxes = showAllBoxesPref
                    showSettingsDialog = false
                }) {
                    Text("Сохранить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
// Полный сброс
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Подтверждение") },
            text = { Text("Вы уверены, что хотите полностью сбросить текущую калибровку и трек навигации?") },
            confirmButton = {
                TextButton(onClick = {
                    autoVm.reset()
                    showResetConfirm = false
                    infoMessage = "Состояние сброшено"
                    isInfoVisible = true
                }) {
                    Text("Сбросить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun ControlButtonsRow(
    autoVm: AutoModeViewModel,
    movementState: MovementState,
    isBindEnabled: Boolean,
    isScaleEnabled: Boolean,
    onBindClick: () -> Unit,
    onScaleClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Кнопка "-"
        FilledTonalIconButton(
            onClick = { autoVm.decrementCurrentControl() },
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Предыдущий КП"
            )
        }

        // Номер текущего КП
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(48.dp)
                .background(Color.White, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = movementState.currentControl.num.toString(),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                textAlign = TextAlign.Center
            )
        }

        // Кнопка "+"
        FilledTonalIconButton(
            onClick = { autoVm.incrementCurrentControl() },
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Следующий КП"
            )
        }

        // Кнопка "Привязать А" (иконка icon_here)
        Button(
            onClick = onBindClick,
            enabled = isBindEnabled,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.icon_here),
                contentDescription = "Привязать А",
                modifier = Modifier.size(24.dp),
                tint = Color.Unspecified // Используйте Color.White, если иконка монохромная
            )
        }

        // Кнопка "Калибровать B" (иконка icon_scale)
        Button(
            onClick = onScaleClick,
            enabled = isScaleEnabled,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GreenReadyDark
            ),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.icon_scale),
                contentDescription = "Калибровать B",
                modifier = Modifier.size(24.dp),
                tint = Color.Unspecified // Используйте Color.White, если иконка монохромная
            )
        }
    }
}

@Composable
private fun ControlButtonsColumn(
    autoVm: AutoModeViewModel,
    movementState: MovementState,
    isBindEnabled: Boolean,
    isScaleEnabled: Boolean,
    onBindClick: () -> Unit,
    onScaleClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Кнопка "+"
        FilledTonalIconButton(
            onClick = { autoVm.incrementCurrentControl() },
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Следующий КП"
            )
        }

        // Номер текущего КП
        Text(
            text = movementState.currentControl.num.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            textAlign = TextAlign.Center
        )

        // Кнопка "-"
        FilledTonalIconButton(
            onClick = { autoVm.decrementCurrentControl() },
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Предыдущий КП"
            )
        }
        // Кнопка "Привязать А" (сверху, как в оригинальном декомпиляте)
        Button(
            onClick = onBindClick,
            enabled = isBindEnabled,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            painterResource(id = R.drawable.icon_here)
        }

        // Кнопка "Калибровать B" (зеленая, снизу)
        Button(
            onClick = onScaleClick,
            enabled = isScaleEnabled,
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GreenReadyDark
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            painterResource(id = R.drawable.icon_scale)
        }
    }
}