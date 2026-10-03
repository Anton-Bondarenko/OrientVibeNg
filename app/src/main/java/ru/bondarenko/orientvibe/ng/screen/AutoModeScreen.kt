package ru.bondarenko.orientvibe.ng.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.bondarenko.orientvibe.ng.image.rememberCameraSource
import ru.bondarenko.orientvibe.ng.ui.components.MapDisplayArea
import ru.bondarenko.orientvibe.ng.ui.components.SubsamplingMapView
import ru.bondarenko.orientvibe.ng.ui.components.MapDragListener
import ru.bondarenko.orientvibe.ng.ui.components.MapTapListener
import ru.bondarenko.orientvibe.ng.viewmodel.AutoModeViewModel

@Composable
fun AutoModeScreen() {
    val context = LocalContext.current
    val autoVm = viewModel<AutoModeViewModel>(factory = AutoModeViewModel.Factory(context))

    val mapState by autoVm.mapState.collectAsState()

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
                infoMessage = "Найдено CP: ${mapState.controlsBoundingBoxes.size}, номеров: ${mapState.numbersBoundingBoxes.size}"
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

        // ── Bottom Panel: Detection results ──
        if (mapState.bitmap != null && mapState.controlsBoundingBoxes.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        // Auto-detect start/finish points
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoFixNormal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "Результат детекции",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Auto-detect CPs and numbers
                        if (mapState.controlsBoundingBoxes.isNotEmpty()) {
                            Text(
                                text = "Найдено CP: ${mapState.controlsBoundingBoxes.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Auto-detect numbers
                        if (mapState.numbersBoundingBoxes.isNotEmpty()) {
                            Text(
                                text = "Распознано номеров: ${mapState.numbersBoundingBoxes.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
