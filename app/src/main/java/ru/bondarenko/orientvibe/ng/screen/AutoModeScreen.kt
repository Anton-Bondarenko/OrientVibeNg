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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val currentControl by autoVm.currentControl.collectAsState()

    var infoMessage by remember { mutableStateOf("Авто-режим: выберите карту") }
    var isInfoVisible by remember { mutableStateOf(true) }

    var pendingGalleryUri by remember { mutableStateOf<android.net.Uri?>(null) }

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
                trackPoints = emptyList(),
                calibration = null,
                currentFix = null,
                autoBindActive = false,
                gpsFixImagePos = null,
                calibrationPointBGps = null,
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
        val gps by navVm.gpsState.collectAsState()
        val fix = gps.currentFix
        if (fix != null) {
            val accuracyLevel = gps.accuracyLevel
            val (accuracyColor, accuracyText) = when (accuracyLevel) {
                AccuracyLevel.HIGH_ACCURACY -> GreenReady to "GPS: ${
                    String.format(
                        "%.0f",
                        fix.accuracy
                    )
                }м"

                AccuracyLevel.LOW_ACCURACY -> Color(0xFFFFC107) to "GPS: ${
                    String.format(
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
                            value = currentControl.value.toString(),
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

                        // Разделитель
                        Spacer(modifier = Modifier.width(8.dp))

                        // Кнопка "Здесь" — применяем и отмечаем текущее число
                        androidx.compose.material3.Button(
                            onClick = {
                                autoVm.setCurrentControl(currentControl.value)
                                infoMessage = "CP #${currentControl.value} отмечена здесь"
                                isInfoVisible = true
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Здесь",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
