package ru.bondarenko.orientvibe.ng.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
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
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.gps.NavViewModel
import ru.bondarenko.orientvibe.ng.model.AutoMapState
import ru.bondarenko.orientvibe.ng.model.AutoModeTelemetryPoint
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.CurrentControl
import ru.bondarenko.orientvibe.ng.model.GpsFix
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

    /** Первая привязанная контрольная точка (GPS + image pixels). Используется для второй калибровки. */
    private var _boundGps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate? = null
    private var _calibration: ru.bondarenko.orientvibe.ng.gps.MapCalibration? = null
    private var _firstGps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate? = null
    private var _boundImagePos: Pair<Float, Float>? = null

    // Текущая выбранная контрольная точка
    private val _currentControl = MutableStateFlow(CurrentControl(1))
    val currentControl: StateFlow<CurrentControl> = _currentControl.asStateFlow()

    fun incrementCurrentControl() {
        val num = (_currentControl.value.num + 1).coerceAtMost(999)
        _currentControl.value = getControlByNumber(num)

    }

    fun decrementCurrentControl() {
        val num = (_currentControl.value.num - 1).coerceAtLeast(0)
        _currentControl.value = getControlByNumber(num)
    }

    fun setCurrentControl(value: Int) {
        var num = value.coerceIn(0, 999);
        _currentControl.value = getControlByNumber(num)
    }

    /** Результат привязки GPS-координаты к контрольной точке. */
    private data class BindResult(
        val calibration: ru.bondarenko.orientvibe.ng.gps.MapCalibration,
        val boundGps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate,
        val boundImagePos: Pair<Float, Float>
    )


    private fun getControlBoxByNumber(cpNumber: Int): BoundingBox? {
        // Ищем BoundingBox с нужным номером
        val cpBox = _mapState.value.controlsBoundingBoxes.find { it.number == cpNumber }
            ?: run {
                Log.w(tag, "bindGpsToCp: CP #$cpNumber not found in controlsBoundingBoxes")
                return null
            }
        return cpBox
    }

    private fun getControlByNumber(cpNumber: Int): CurrentControl {
        val controlBox = getControlBoxByNumber(cpNumber)
        val control = CurrentControl(
            num = cpNumber,
            boundingBox = controlBox,
            gpsCoordinate = if (controlBox != null && _mapState.value.bitmap != null) MapGeometry.imageAbsToGps(
                PointF(
                    controlBox.centerX,
                    controlBox.centerY
                ), _calibration, _calibration?.magneticDeclination ?: 0f
            ) else null
        )
        return control
    }

    /**
     * Привязывает GPS-координату к детектированной контрольной точке по номеру.
     * Ищет bounding box в _mapState.controlsBoundingBoxes с совпадающим number,
     * конвертирует нормализованные [0,1] координаты YOLO в абсолютные пиксели,
     * создаёт синтетическую калибровку и валидирует что точка попадает на карту.
     *
     * @return BindResult при успехе, null при ошибке (сообщение логируется)
     */
    private fun bindGpsToCp(
        gps: ru.bondarenko.orientvibe.ng.model.GpsCoordinate,
        cpNumber: Int
    ): BindResult? {
        val bmp = _mapState.value.bitmap ?: run {
            Log.w(tag, "bindGpsToCp(cp#$cpNumber): bitmap is null")
            return null
        }

        val cpBox = getControlBoxByNumber(cpNumber) ?: return null
        val imageX = cpBox.centerX
        val imageY = cpBox.centerY

        Log.d(tag, "===== bindGpsToCp(cp#$cpNumber) START =====")
        Log.d(
            tag,
            "GPS: (${gps.latitude}, ${gps.longitude}), CP# $cpNumber pixel=($imageX, $imageY)"
        )

        // Синтетическая калибровка по одной точке
        val cal = MapCalibrationUtils.calibrateSinglePoint(gps, imageX, imageY)

        // Преобразуем GPS → пиксели для валидации
        val projected = MapCalibrationUtils.gpsToImageAbs(gps, cal, 0f)

        Log.d(tag, "bindGpsToCp: scale=${cal.scaleMetersPerPixel}m/px, projected=($projected)")

        // Валидация: точка внутри границ изображения
        if (projected == null) {
            Log.w(tag, "bindGpsToCp(cp#$cpNumber): GPS и карта несовместимы")
            return null
        }
        val padding = 2f
        if (projected.first < -padding || projected.first > 1 + padding ||
            projected.second < -padding || projected.second > 1 + padding
        ) {
            Log.w(
                tag,
                "bindGpsToCp(cp#$cpNumber): точка вне границ imagePt=($projected)"
            )
            return null
        }

        val result = BindResult(cal, gps, Pair(imageX, imageY))
        Log.d(
            tag,
            "bindGpsToCp: SUCCESS — saved boundGps=${result.boundGps}, boundImagePos=${result.boundImagePos}"
        )
        Log.d(tag, "===== bindGpsToCp(cp#$cpNumber) END =====")
        return result
    }

    /** Применяет результат привязки к состоянию viewModel: сохраняет точку, применяет калибровку. */
    private fun applyBind(result: BindResult, navVm: NavViewModel) {
        _calibration = result.calibration
        _boundGps = result.boundGps
        _boundImagePos = result.boundImagePos

        navVm.applyNewCalibration(result.calibration)
        _calibrationVersion.value++
        navVm.startTracking()
    }

    /** Привязка текущего GPS fix к найденной контрольной точке (поле number). */
    suspend fun bindGpsToCurrentControl(): Pair<Boolean, String> {
        val navVm = navVm ?: return Pair(false, "NavViewModel не подключён")

        val gpsState = navVm.gpsState.value
        val fix = gpsState.currentFix ?: return Pair(false, "Нет GPS fix")
        val currentNumber = _currentControl.value.num

        val bindResult = bindGpsToCp(fix.coordinate, currentNumber)
            ?: return Pair(false, "CP #$currentNumber не найдена на карте")

        applyBind(bindResult, navVm)

        val scaleStr = String.format("%.1f", bindResult.calibration.scaleMetersPerPixel)
        return Pair(true, "CP #$currentNumber привязана: масштаб $scaleStr м/px")
    }

    /** Есть ли первая привязанная точка (для включения кнопки «масштаб») */
    val hasBoundCp: Boolean get() = _boundGps != null && _boundImagePos != null && _boundImagePos != null

    /** Номера детектированных КП — для проверки, что выбранный CP существует на карте */
    fun getDetectedCpNumbers(): Set<Int?> =
        _mapState.value.controlsBoundingBoxes.mapNotNullTo(mutableSetOf()) { it.number }

    /**
     * Двухточечная перекалибровка (масштаб):
     * точка A — уже привязанная, точка B — центр выбранного КП.
     * Пересчитывает масштаб и угол истинного севера так, чтобы текущая GPS позиция на треке
     * оказалась в центре целевого КП.
     */
    suspend fun recalibrateToTargetControl(): Pair<Boolean, String> {
        val navVm = navVm ?: return Pair(false, "NavViewModel не подключён")

        // Должна быть первая привязанная точка
        val boundGps = _boundGps ?: return Pair(false, "Сначала выполните привязку «Здесь»")
        val boundImagePos = _boundImagePos ?: return Pair(false, "Нет данных первой привязки")

        // Текущий GPS fix — для вычисления текущего направления
        val gpsState = navVm.gpsState.value
        val currentFix = gpsState.currentFix ?: return Pair(false, "Нет GPS fix")

        // Выбранный CP
        val targetNumber = _currentControl.value.num
        val targetBox = _mapState.value.controlsBoundingBoxes.find { it.number == targetNumber }
            ?: return Pair(false, "CP #$targetNumber не найдена на карте")

        // Конвертируем нормализованные координаты CP в абсолютные пиксели
//        val bmp = _mapState.value.bitmap ?: return Pair(false, "Изображение не загружено")
        val targetImageX = targetBox.centerX
        val targetImageY = targetBox.centerY

        Log.d(tag, "===== recalibrateToTargetControl START =====")
        Log.d(tag, "POINT A (bound): GPS=($boundGps), image=($boundImagePos)")
        Log.d(
            tag,
            "POINT B (target CP#$targetNumber): GPS=(${currentFix.coordinate.latitude}, ${currentFix.coordinate.longitude}), image=($targetImageX, $targetImageY)"
        )
        Log.d(tag, "All CP bounding boxes:")
        for (cp in _mapState.value.controlsBoundingBoxes) {
            Log.d(
                tag,
                "  CP#${cp.number} -> pixel(${cp.centerX}, ${cp.centerY}), w=${cp.width}, h=${cp.height}"
            )
        }

        // Создаём точки калибровки и вызываем двухточечную калибровку
        val pointA = ru.bondarenko.orientvibe.ng.model.CalibrationPoint(
            gps = boundGps, imageX = boundImagePos.first, imageY = boundImagePos.second
        )
        val pointB = ru.bondarenko.orientvibe.ng.model.CalibrationPoint(
            gps = currentFix.coordinate, imageX = targetImageX, imageY = targetImageY
        )

        // Вычисляем новую калибровку с магнитным склонением
        val declinationAuto = _calibration?.magneticDeclination ?: 0f
        val newCal = ru.bondarenko.orientvibe.ng.gps.MapGeometry.computeCalibrationRaw(
            pointA,
            pointB,
            declinationAuto
        )
            ?: return Pair(false, "Точки слишком близко — нельзя рассчитать масштаб")

        // Вычисляем true bearing между точками для коррекции угла севера
        val trueBearing =
            MapGeometry.bearing(pointA.gps, pointB.gps)
        val magneticDeclination = declinationAuto
        val trackBearing = MapGeometry.magneticBearing(
            trueBearing,
            magneticDeclination
        )

        // теперь измерим угол на изображении
        val screenBearing =
            MapGeometry.screenBearing(pointA.imageX, pointA.imageY, pointB.imageX, pointB.imageY)
        val errNorthAngle = screenBearing - trackBearing;

        // Создаём полную MapCalibration с обоими точками
        val fullCal = ru.bondarenko.orientvibe.ng.gps.MapCalibration(
            pointA = pointA,
            pointB = pointB,
            scaleMetersPerPixel = newCal.scaleMetersPerPixel,
            bearingDegrees = trackBearing,
            magneticDeclination = magneticDeclination,
            physicalDeclination = magneticDeclination,
            hasXYFlip = newCal.hasXYFlip
        )

        Log.d(
            tag,
            "recalibrateToTargetControl: NEW scale=${newCal.scaleMetersPerPixel}m/px, bearing=$trackBearing°, northAngle=$errNorthAngle°"
        )
        Log.d(
            tag,
            "recalibrateToTargetControl: VALIDATION — CP#1 image=($boundImagePos), current GPS on track → target CP#$targetNumber at ($targetImageX, $targetImageY)"
        )
        Log.d(tag, "===== recalibrateToTargetControl END =====")

        // Применяем новую калибровку через NavViewModel
        navVm.applyNewCalibration(fullCal)
        applyBind(BindResult(fullCal, boundGps, boundImagePos), navVm)

        // Обновляем северный индикатор (raw, без коэрции [-45,45])
        setNorthAngleRaw(errNorthAngle)

        // Инкрементируем версию калибровки — перерисовать трек
        _calibrationVersion.value++

        val scaleStr = String.format("%.1f", newCal.scaleMetersPerPixel)

        return Pair(true, "Масштаб обновлён: $scaleStr м/px")
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
                Log.d(
                    tag,
                    "handleGpsUpdate: accuracyLevel=${gpsState.accuracyLevel}, fix=${gpsState.currentFix != null}"
                )
                handleGpsUpdate(gpsState)
            }
        }
    }

    private fun handleGpsUpdate(gpsState: GpsState) {
        val currentLevel = gpsState.accuracyLevel
        val fix = gpsState.currentFix

        when (currentLevel) {
            ru.bondarenko.orientvibe.ng.model.AccuracyLevel.HIGH_ACCURACY -> {
                accuracyLevelTransitionCount++
                val count = accuracyLevelTransitionCount

                // Переход HIGH_ACCURACY — показываем "можно двигаться" если это новый переход
                if (count == 1) {
                    showMoveReadyAlert(count)
                    _firstGps = fix?.coordinate
                }

                // Добавляем точку телеметрии если есть достоверный fix
                if (fix != null) {
                    // Если привязки нет, но детекция уже завершена, делаем привязку к первому КП
                    if (_boundGps == null) {
                        onNoBind(fix)
                    }

                    val point = AutoModeTelemetryPoint(
                        latitude = fix.coordinate.latitude,
                        longitude = fix.coordinate.longitude,
                        accuracyMeters = fix.accuracy,
                        speedMs = fix.speed,
                        bearingDegrees = fix.bearing,
                        timestamp = fix.timestamp
                    )
                    addTelemetryPoint(point)
                    onMove(fix)
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
                bindGpsToCurrentControl()
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
                coordinate = ru.bondarenko.orientvibe.ng.model.GpsCoordinate(
                    tp.latitude,
                    tp.longitude
                ),
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

    /**
     * Раздел событий в навигации
     */

    /**
     * Вызывается, когда нет привязки
     */
    private fun onNoBind(fix: GpsFix) {
        // Подстраховка. Запомним достоверную точку для первой авто привязки
        val firstGps = _firstGps ?: fix.coordinate
        val navVm = navVm
        val bindResult = bindGpsToCp(firstGps, 1)
        if (bindResult != null && navVm != null) {
            applyBind(bindResult, navVm)
            setCurrentControl(2)
        }
    }

    /**
     * Во время движения
     */
    private fun onMove(fix: GpsFix) {
        val currentControl = currentControl.value
        if (currentControl.gpsCoordinate != null) {
            val dist = MapGeometry.haversineDistance(fix.coordinate, currentControl.gpsCoordinate)
            Log.d(tag, "Distance to CP#${currentControl.num}=${dist}")

        }
    }

    /**
     * Когда близко к цели
     */
    private fun onCloseToCurrent() {

    }

    /**
     * Когда двигается к следующему пункту от цели
     */
    private fun onMoveToNext() {

    }


    /**
     *
     */

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

    /** Устанавливает северный угол без ограничения диапазона (для калибровки). */
    private fun setNorthAngleRaw(angle: Float) {
        _mapState.value = _mapState.value.copy(northAngle = angle)
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
