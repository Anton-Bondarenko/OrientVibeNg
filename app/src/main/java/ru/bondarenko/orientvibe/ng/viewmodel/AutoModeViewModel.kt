package ru.bondarenko.orientvibe.ng.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.bondarenko.orientvibe.ng.gps.NavViewModel
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.model.AutoMapState
import ru.bondarenko.orientvibe.ng.model.AutoModeTelemetryPoint
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.CurrentControl
import ru.bondarenko.orientvibe.ng.model.GpsState
import ru.bondarenko.orientvibe.ng.model.MoveReadyAlert
import ru.bondarenko.orientvibe.ng.yolo.MapDetectionProgressListener
import ru.bondarenko.orientvibe.ng.yolo.MapDetectionResult
import ru.bondarenko.orientvibe.ng.yolo.MapDetector

/** Поворачивает bitmap на заданный угол. */
private fun Bitmap.rotateBitmap(degrees: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees)
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/**
 * ViewModel для авто-режима — только загрузка карты + отображение.
 * Не содержит логики маршрутизации (start/finish points, placingMode).
 */
class AutoModeViewModel(
    private val context: Context,
) : ViewModel(), MapDetectionProgressListener {

    var navVm: NavViewModel? = null  // устанавливается из AutoModeScreen LaunchedEffect

    private val tag = "AutoModeViewModel"

    companion object {
        /** Количество точек трека для отображения на карте — последние 15 минут. */
        const val DISPLAY_TRACK_POINTS = 900

        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AutoModeViewModel(context) as T
            }
        }

        // Устанавливается из AutoModeScreen composable сразу после создания viewModel
        var sharedNavViewModel: NavViewModel? = null
    }

    private val _mapState = MutableStateFlow(AutoMapState())
    val mapState: StateFlow<AutoMapState> = _mapState.asStateFlow()

    // Зелёный баннер "можно двигаться"
    private val _moveReadyAlert = MutableStateFlow(MoveReadyAlert())
    val moveReadyAlert: StateFlow<MoveReadyAlert> = _moveReadyAlert.asStateFlow()

    // Коллекция телеметрии (текущие координаты, скорость, курс)
    private val _telemetryPoints = MutableStateFlow<List<AutoModeTelemetryPoint>>(emptyList())
    val telemetryPoints: StateFlow<List<AutoModeTelemetryPoint>> = _telemetryPoints.asStateFlow()

    // Счётчик калибровки — каждый раз при recalibration инкрементируется, чтобы вызвать recomposition Compose
    private val _calibrationVersion = MutableStateFlow(0)
    val calibrationVersion: StateFlow<Int> = _calibrationVersion.asStateFlow()

    // Текущая выбранная контрольная точка
    private val _currentControl = MutableStateFlow(CurrentControl(1))
    val currentControl: StateFlow<CurrentControl> = _currentControl.asStateFlow()

    fun incrementCurrentControl() {
        _currentControl.value = _currentControl.value.copy(value = (_currentControl.value.value + 1).coerceAtMost(999))
    }

    fun decrementCurrentControl() {
        _currentControl.value = _currentControl.value.copy(value = (_currentControl.value.value - 1).coerceAtLeast(0))
    }

    fun setCurrentControl(value: Int) {
        _currentControl.value = _currentControl.value.copy(value = value.coerceIn(0, 999))
    }

    /** Привязка текущего GPS fix к найденной контрольной точке (поле number). */
    suspend fun bindGpsToCurrentControl(): Pair<Boolean, String> {
        val navVm = navVm ?: return Pair(false, "NavViewModel не подключён")

        // Получаем текущий GPS fix
        val gpsState = navVm.gpsState.value
        val fix = gpsState.currentFix ?: return Pair(false, "Нет GPS fix")

        val currentNumber = _currentControl.value.value

        // Ищем BoundingBox с нужным номером в controlsBoundingBoxes
        val cpBox = _mapState.value.controlsBoundingBoxes.find { it.number == currentNumber }
        if (cpBox == null) {
            return Pair(false, "CP #$currentNumber не найдена на карте")
        }

        // Конвертируем нормализованные [0,1] координаты YOLO в абсолютные пиксели изображения
        val bmp = _mapState.value.bitmap ?: return Pair(false, "Изображение не загружено")
        val imageX = cpBox.centerX * bmp.width.toFloat()
        val imageY = cpBox.centerY * bmp.height.toFloat()

        Log.d(tag, "===== bindGpsToCurrentControl START =====")
        Log.d(tag, "CURRENT GPS FIX: lat=${fix.coordinate.latitude}, lon=${fix.coordinate.longitude}, accuracy=${fix.accuracy}m")
        Log.d(tag, "CP #$currentNumber IMAGE POS: pixel=($imageX, $imageY), confidence=${cpBox.confidence}")
        Log.d(tag, "All CP bounding boxes:")
        for (cp in _mapState.value.controlsBoundingBoxes) {
            Log.d(tag, "  CP#${cp.number} -> pixel(${cp.centerX}, ${cp.centerY}), w=${cp.width}, h=${cp.height}")
        }
        Log.d(tag, "IMAGE dims: ${bmp.width}x${bmp.height}, controlsDetected=${_mapState.value.controlsBoundingBoxes.size}")
        Log.d(tag, "===== bindGpsToCurrentControl END =====")

        // Создаём калибровку по одной точке
        val cal = MapCalibrationUtils.calibrateSinglePoint(
            startGPS = fix.coordinate,
            startPointImageX = imageX,
            startPointImageY = imageY
        )

        // Преобразуем GPS координату через калибровку в пиксели изображения
        val imgW = bmp.width.toFloat()
        val imgH = bmp.height.toFloat()
        val firstImagePt = MapCalibrationUtils.gpsToImageAbs(
            fix.coordinate, cal, Pair(imgW, imgH), 0f
        )

        Log.d(tag, "bindGpsToCurrentControl: calibration created — scale=${cal.scaleMetersPerPixel}m/px, bearing=${cal.bearingDegrees}")
        Log.d(tag, "bindGpsToCurrentControl: calibration pointA(GPS) = (${cal.pointA.gps.latitude}, ${cal.pointA.gps.longitude})")
        Log.d(tag, "bindGpsToCurrentControl: firstImagePt (from GPS via cal) = (${firstImagePt?.first}, ${firstImagePt?.second})")

        // ВАЛИДАЦИЯ: проверяем что первая GPS точка преобразуется в допустимые пиксели изображения
        if (firstImagePt == null) {
            Log.w(tag, "bindGpsToCurrentControl: gpsToImageAbs returned NULL — GPS и карта несовместимы")
            return Pair(false, "GPS координаты не соответствуют положению CP #$currentNumber на карте. Убедитесь что вы стоите рядом с пунктом.")
        }

        // Проверяем что точка внутри границ изображения (с небольшим запасом)
        val padding = 50f // 50px padding для погрешности GPS
        if (firstImagePt.first < -padding || firstImagePt.first > imgW + padding ||
            firstImagePt.second < -padding || firstImagePt.second > imgH + padding) {
            Log.w(tag, "bindGpsToCurrentControl: FIRST POINT OUT OF BOUNDS imagePt=(${firstImagePt.first}, ${firstImagePt.second}) dims=$imgW x $imgH")
            return Pair(false, "Вы далеко от CP #$currentNumber на карте. Подойдите ближе и нажмите «Здесь» снова.")
        }

        Log.d(tag, "bindGpsToCurrentControl: VALIDATION PASSED — firstImagePt inside bounds [${-padding}, ${imgW + padding}] x [${-padding}, ${imgH + padding}]")

        // Применяем калибровку через NavViewModel
        navVm.applyNewCalibration(cal)

        // Инкрементируем версию калибровки, чтобы Compose recomposed и пересчитал координаты трека
        _calibrationVersion.value++

        // Запускаем трек-рекордер — начнём запись точек трека с этой GPS позиции
        navVm.startTracking()

        val scaleStr = String.format("%.1f", cal.scaleMetersPerPixel)
        return Pair(true, "CP #$currentNumber привязана: масштаб $scaleStr м/px")
    }

    // Счётчик для отслеживания переходов точности GPS
    private var accuracyLevelTransitionCount = 0

    private val mapDetector = MapDetector(context).also { it.setProgressListener(this) }

    init {
        viewModelScope.launch {
            val ok = mapDetector.init()
            if (!ok) {
                _mapState.value = _mapState.value.copy(
                    errorMessage = "Failed to load orientmapv8n.onnx model"
                )
            }
        }
    }

    /** Вызывается из AutoModeScreen LaunchedEffect после установки sharedNavViewModel */
    fun startMonitoringIfNeeded() {
        if (navVm != null) {
            Log.d(tag, "startMonitoring: GPS monitoring started")
            startGpsMonitoring(navVm!!)
        } else {
            Log.w(tag, "startMonitoring: sharedNavViewModel is still null")
        }
    }

    // ── GPS monitoring ──────────────────────────────────────────────────────

    private fun startGpsMonitoring(navVm: NavViewModel) {
        viewModelScope.launch {
            Log.d(tag, "startGpsMonitoring: collecting gpsState")
            navVm.gpsState.collectLatest { gpsState ->
                Log.d(tag, "handleGpsUpdate: accuracyLevel=${gpsState.accuracyLevel}, fix=${gpsState.currentFix != null}")
                handleGpsUpdate(gpsState)
            }
        }
    }

    private fun handleGpsUpdate(gpsState: GpsState) {
        val currentLevel = gpsState.accuracyLevel
        val fix = gpsState.currentFix

        Log.d(tag, "handleGpsUpdate: level=$currentLevel accuracy=${fix?.accuracy ?: -1}m bearing=${fix?.bearing ?: -1}°")

        when (currentLevel) {
            ru.bondarenko.orientvibe.ng.model.AccuracyLevel.HIGH_ACCURACY -> {
                accuracyLevelTransitionCount++
                val count = accuracyLevelTransitionCount

                // Переход HIGH_ACCURACY — показываем "можно двигаться" если это новый переход
                if (count == 1) {
                    showMoveReadyAlert(count)
                } else {
                    // Повторный переход (GPS восстановился) — обновляем счётчик
                    Log.d(tag, "GPS accuracy recovered: HIGH_ACCURACY")
                }

                // Добавляем точку телеметрии если есть достоверный fix
                if (fix != null) {
                    val point = AutoModeTelemetryPoint(
                        latitude = fix.coordinate.latitude,
                        longitude = fix.coordinate.longitude,
                        accuracyMeters = fix.accuracy,
                        speedMs = fix.speed,
                        bearingDegrees = fix.bearing,
                        timestamp = fix.timestamp
                    )
                    addTelemetryPoint(point)
                }
            }

            ru.bondarenko.orientvibe.ng.model.AccuracyLevel.LOW_ACCURACY -> {
                // Низкая точность — ничего не делаем, ждём перехода в HIGH
            }

            ru.bondarenko.orientvibe.ng.model.AccuracyLevel.NO_FIX -> {
                // Нет сигнала GPS — сбрасываем счётчик при следующем появлении HIGH
                accuracyLevelTransitionCount = 0
                _moveReadyAlert.value = MoveReadyAlert()
            }
        }
    }

    /** Показывает зелёный баннер "можно двигаться" на 5 секунд. */
    private fun showMoveReadyAlert(count: Int) {
        viewModelScope.launch {
            // Сбрасываем предыдущий баннер
            _moveReadyAlert.value = MoveReadyAlert(
                active = true,
                remainingMs = 5000L,
                elapsedMs = 0L
            )

            // Тикер — обновляем elapsedMs каждые 100мс (завершается через 5 сек)
            var elapsed = 0L
            while (elapsed < 5000L && _moveReadyAlert.value.active) {
                kotlinx.coroutines.delay(100L)
                elapsed += 100L
                _moveReadyAlert.value = MoveReadyAlert(
                    active = true,
                    remainingMs = 5000L - elapsed,
                    elapsedMs = elapsed
                )
            }

            // Баннер истёк
            if (_moveReadyAlert.value.active && _moveReadyAlert.value.elapsedMs >= 5000L) {
                _moveReadyAlert.value = MoveReadyAlert(
                    active = false,
                    remainingMs = 0L,
                    elapsedMs = 5000L
                )
            }
        }
    }

    /** Добавляет точку телеметрии в коллекцию (без обрезки — храним все). */
    private fun addTelemetryPoint(point: AutoModeTelemetryPoint) {
        val current = _telemetryPoints.value.toMutableList()
        current.add(point)
        _telemetryPoints.value = current.toList()
    }

    /** Конвертирует последние DISPLAY_TRACK_POINTS точек telemetry в List<TrackPoint> для отображения трека на карте. */
    fun getTelemetryTrackPoints(): List<ru.bondarenko.orientvibe.ng.gps.TrackPoint> {
        val all = _telemetryPoints.value
        if (all.isEmpty()) return emptyList()

        // Берём только последние DISPLAY_TRACK_POINTS точек для отображения
        val display = if (all.size > DISPLAY_TRACK_POINTS) {
            all.subList(all.size - DISPLAY_TRACK_POINTS, all.size)
        } else {
            all
        }

        // Вычисляем кумулятивное расстояние начиная с первого отображаемого индекса
        val baseIdx = if (all.size > DISPLAY_TRACK_POINTS) all.size - DISPLAY_TRACK_POINTS else 0
        val result = mutableListOf<ru.bondarenko.orientvibe.ng.gps.TrackPoint>()
        var totalDist = 0.0
        for (i in display.indices) {
            val actualIdx = baseIdx + i
            val tp = all[actualIdx]
            val gpsFix = ru.bondarenko.orientvibe.ng.model.GpsFix(
                coordinate = ru.bondarenko.orientvibe.ng.model.GpsCoordinate(tp.latitude, tp.longitude),
                accuracy = tp.accuracyMeters,
                bearing = tp.bearingDegrees,
                speed = tp.speedMs,
                timestamp = tp.timestamp
            )

            if (i == 0) {
                totalDist = 0.0
            } else {
                val prevGps = ru.bondarenko.orientvibe.ng.model.GpsCoordinate(
                    all[actualIdx - 1].latitude, all[actualIdx - 1].longitude
                )
                totalDist += MapCalibrationUtils.haversineDistance(prevGps, gpsFix.coordinate)
            }

            result.add(
                ru.bondarenko.orientvibe.ng.gps.TrackPoint(
                    gpsFix = gpsFix,
                    imageX = 0f, // не используется — пересчитывается через calibration в TrackOverlay
                    imageY = 0f,
                    distanceFromStart = totalDist,
                    timestamp = tp.timestamp
                )
            )
        }
        return result
    }

    // ── Progress (from MapDetectionProgressListener) ───────────────────────

    override fun onProgressUpdate(current: Int, total: Int, message: String) {
        viewModelScope.launch {
            try {
                val currentHandle = mapDetector.currentTaskRef.get()
                if (currentHandle != null && !currentHandle.job.isCancelled) {
                    currentHandle.job.ensureActive()
                } else {
                    return@launch
                }

                _mapState.value = _mapState.value.copy(
                    progressMessage = message
                )
            } catch (e: Exception) {
                Log.d(tag, "Detection cancellation requested via progress update")
            }
        }
    }

    private fun applyDetectionResult(result: MapDetectionResult) {
        viewModelScope.launch {
            val currentHandle = mapDetector.currentTaskRef.get()
            if (currentHandle != null && !currentHandle.job.isCancelled) {
                currentHandle.job.ensureActive()
                _mapState.value = _mapState.value.copy(
                    controlsBoundingBoxes = result.controlsBoundingBoxes,
                    numbersBoundingBoxes = result.numbersBoundingBoxes,
                    isProcessing = false,
                    progressMessage = null,
                )
            }
        }
    }

    // ── Image loading + detection entry point ──────────────────────────────

    suspend fun loadImageFromUri(uri: Uri) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open URI for image loading")

        val exif = ExifInterface(inputStream)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        inputStream.close()

        val rawStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open URI for image loading")
        val rawBm = android.graphics.BitmapFactory.decodeStream(rawStream)
            ?: throw IllegalStateException("Bitmap decode failed")
        rawStream.close()

        val displayBm = if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> rawBm.rotateBitmap(
                    90f
                )

                ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> rawBm.rotateBitmap(
                    180f
                )

                ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> rawBm.rotateBitmap(
                    270f
                )

                else -> rawBm.copy(rawBm.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
            }
        } else {
            rawBm.copy(rawBm.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
        }

        launchDetectionWithBitmap(displayBm)
    }

    fun loadImageFromBitmap(bitmap: android.graphics.Bitmap, imageUri: Uri? = null) {
        var displayBitmap =
            bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)

        if (imageUri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(imageUri)
                    ?: throw Exception("Cannot open URI for EXIF read")
                val exif = ExifInterface(inputStream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )

                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> displayBitmap =
                        displayBitmap.rotateBitmap(90f)

                    ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> displayBitmap =
                        displayBitmap.rotateBitmap(180f)

                    ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> displayBitmap =
                        displayBitmap.rotateBitmap(270f)

                    else -> {}
                }
                inputStream.close()
            } catch (e: Exception) {
                Log.w(tag, "Failed to read EXIF for orientation correction", e)
            }
        }

        launchDetectionWithBitmap(displayBitmap)
    }

    /** Запускает детекцию на уже повернутом bitmap. */
    private fun launchDetectionWithBitmap(bitmap: Bitmap) {
        _mapState.value = AutoMapState()
        _mapState.value = _mapState.value.copy(
            bitmap = bitmap,
            isProcessing = true,
            progressMessage = "Запуск детекции..."
        )

        val task = mapDetector.launchDetection()

        viewModelScope.launch(task.job) {
            val result = withContext(Dispatchers.IO) {
                mapDetector.detect(bitmap)
            }

            mapDetector.currentTaskRef.get()?.takeIf { it.version == task.version }
                ?.let { current ->
                    current.job.ensureActive()
                    applyDetectionResult(result)
                }
        }
    }

    /** Показывать overlay загрузки когда изображение ещё не загружено. */
    val isLoading: Boolean get() = _mapState.value.bitmap == null

    // ── Map orientation ────────────────────────────────────────────────────

    fun updateNorthAngle(angle: Float) {
        _mapState.value = _mapState.value.copy(northAngle = angle.coerceIn(-45f, 45f))
    }

    fun resetNorthAngle() {
        _mapState.value = _mapState.value.copy(northAngle = 0f)
    }

    // ── Utilities ──────────────────────────────────────────────────────────

    fun clearError() {
        _mapState.value = _mapState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        mapDetector.close()
    }
}
