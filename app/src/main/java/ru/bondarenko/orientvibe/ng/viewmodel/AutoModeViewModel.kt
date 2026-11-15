package ru.bondarenko.orientvibe.ng.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.bondarenko.orientvibe.ng.audio.StereoAudioPlayer
import ru.bondarenko.orientvibe.ng.auto.AutoMoveManager
import ru.bondarenko.orientvibe.ng.gps.MapCalibrationUtils
import ru.bondarenko.orientvibe.ng.gps.MapGeometry
import ru.bondarenko.orientvibe.ng.gps.NavViewModel
import ru.bondarenko.orientvibe.ng.model.AccuracyLevel
import ru.bondarenko.orientvibe.ng.model.AudioSignalState
import ru.bondarenko.orientvibe.ng.model.AudioSignalType
import ru.bondarenko.orientvibe.ng.model.AutoMapState
import ru.bondarenko.orientvibe.ng.model.BoundingBox
import ru.bondarenko.orientvibe.ng.model.CalibrationPoint
import ru.bondarenko.orientvibe.ng.model.CurrentControl
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.GpsState
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import ru.bondarenko.orientvibe.ng.model.MoveReadyAlert
import ru.bondarenko.orientvibe.ng.model.MovementState
import ru.bondarenko.orientvibe.ng.model.TrackPoint
import ru.bondarenko.orientvibe.ng.service.BackgroundNavigationService
import ru.bondarenko.orientvibe.ng.yolo.MapDetectionProgressListener
import ru.bondarenko.orientvibe.ng.yolo.MapDetectionResult
import ru.bondarenko.orientvibe.ng.yolo.MapDetector
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Внутренний результат жесткой привязки к координате.
 */
data class BindResult(val calibration: MapCalibration)

/**
 * Основная ViewModel управления автоматическим режимом навигации, интеграции нейросети YOLO и аудиоинформирования.
 */
class AutoModeViewModel(private val context: Context) : ViewModel(), MapDetectionProgressListener {

    private val tag = "AutoModeViewModel"

    private val _mapState = MutableStateFlow(AutoMapState())
    val mapState: StateFlow<AutoMapState> = _mapState.asStateFlow()

    private val _movementState = MutableStateFlow(MovementState())
    val movementState: StateFlow<MovementState> = _movementState.asStateFlow()

    private val _moveReadyAlert = MutableStateFlow(MoveReadyAlert())
    val moveReadyAlert: StateFlow<MoveReadyAlert> = _moveReadyAlert.asStateFlow()

    private val _calibrationVersion = MutableStateFlow(0)
    val calibrationVersion: StateFlow<Int> = _calibrationVersion.asStateFlow()

    private val _autoCalibrationsList = MutableStateFlow<List<MapCalibration>>(emptyList())
    val autoCalibrations: StateFlow<List<MapCalibration>> = _autoCalibrationsList.asStateFlow()

    private val _autoMoveManager = AutoMoveManager()
    private val _audioPlayer: StereoAudioPlayer = StereoAudioPlayer.getInstance(context)
    private val mapDetector = MapDetector(context)

    private var _calibration: MapCalibration? = null
    private var arrivedPlayedForControlNum: Int = -1
    private var lastAudioPlayTimestampMs: Long = 0L
    private var lastPlayedSignalType: AudioSignalType = AudioSignalType.NONE
    private var _firstGps: GpsCoordinate? = null
    private var accuracyLevelTransitionCount = 0

    var navVm: NavViewModel? = null

    val isLoading: Boolean
        get() = _mapState.value.bitmap == null

    val hasBoundCp: Boolean
        get() = _calibration != null

    val detectedCpNumbers: Set<Int>
        get() = _mapState.value.controlsBoundingBoxes.mapNotNull { it.number }.toSet()

    private val proportion: Double
        get() {
            val bm = _mapState.value.bitmap ?: return 1.44
            return bm.width.toDouble() / bm.height.toDouble()
        }

    init {
        mapDetector.progressListener = this
        startBackgroundService()

        viewModelScope.launch {
            val ok = mapDetector.init()
            if (!ok) {
                _mapState.value = _mapState.value.copy(
                    errorMessage = "Failed to load orientmapv8n.onnx model"
                )
            }
        }
    }

    fun incrementCurrentControl() {
        val ms = _movementState.value
        val nextNum = ms.currentControl.num + 1
        val nextBoundingBox = _mapState.value.controlsBoundingBoxes
            .firstOrNull { it.number == nextNum }

        _movementState.value = ms.copy(
            currentControl = CurrentControl(nextNum, nextBoundingBox),
            prevControl = ms.currentControl
        )
    }

    fun decrementCurrentControl() {
        val ms = _movementState.value
        val num = (ms.currentControl.num - 1).coerceAtLeast(0)

        val curBb = _mapState.value.controlsBoundingBoxes.firstOrNull { it.number == num }
        val prevBb = _mapState.value.controlsBoundingBoxes.firstOrNull { it.number == num - 1 }

        _movementState.value = ms.copy(
            currentControl = CurrentControl(num, curBb),
            prevControl = CurrentControl(num - 1, prevBb)
        )
    }

    fun setCurrentControl(value: Int) {
        val ms = _movementState.value.copy()
        val num = value.coerceIn(0, 999)
        ms.setControl(num, _mapState.value)
        _movementState.value = ms
    }

    private fun getControlBoxByNumber(cpNumber: Int): BoundingBox? {
        val cpBox = _mapState.value.controlsBoundingBoxes.firstOrNull { it.number == cpNumber }
        if (cpBox == null) {
            Log.w(tag, "bindGpsToCp: CP #$cpNumber not found in controlsBoundingBoxes")
        }
        return cpBox
    }

    private fun bindGpsToCp(gps: GpsCoordinate, cpNumber: Int): BindResult? {
        val bitmap = _mapState.value.bitmap ?: run {
            Log.w(tag, "bindGpsToCp(cp#$cpNumber): bitmap is null")
            return null
        }
        val cpBox = getControlBoxByNumber(cpNumber) ?: return null

        val imageX = cpBox.centerX
        val imageY = cpBox.centerY

        Log.d(tag, "===== bindGpsToCp START =====")
        Log.d(tag, "current fix lat=${gps.latitude} lon=${gps.longitude}")
        Log.d(tag, "current img x=$imageX y=$imageY")

        val width = bitmap.width.toDouble() / bitmap.height.toDouble()
        val cal = MapCalibrationUtils.calibrateSinglePoint(
            startGPS = gps,
            startPointImageX = imageX,
            startPointImageY = imageY,
            northAngle = _mapState.value.northAngle,
            magneticDeclination = _calibration?.magneticDeclination,
            imageProportion = width,
            scaleMetersPerMapX = _calibration?.scaleMetersPerMapX,
            scaleMetersPerMapY = _calibration?.scaleMetersPerMapY,
            confidence = 1.0
        )

        val pairGpsToImage =
            MapCalibrationUtils.gpsToImage(gps, cal, _mapState.value.northAngle) ?: return null
        val imgX = pairGpsToImage.first
        val imgY = pairGpsToImage.second
        if (imgX == null || imgY == null) {
            Log.w(tag, "bindGpsToCp(cp#$cpNumber): GPS и карта несовместимы")
            return null
        }
        if (imgX < -2.0 || imgX > 3.0 || imgY < -2.0 || imgY > 3.0) {
            Log.w(tag, "bindGpsToCp(cp#$cpNumber): точка вне границ imagePt=($imgX, $imgY)")
            return null
        }

        Log.d(tag, "===== bindGpsToCp(cp#$cpNumber) END =====")
        return BindResult(cal)
    }

    private fun applyBind(result: BindResult, navViewModel: NavViewModel) {
        _calibration = result.calibration
        _mapState.value = _mapState.value.copy(mapCalibration = result.calibration)
        navViewModel.applyNewCalibration(result.calibration)
        _calibrationVersion.value += 1
        // Трекинг включаем без очистки — трек очищается только в reset()
        navVm?.enableTrackingWithoutClearing()
    }

    fun bindGpsToCurrentControl(): Pair<Boolean, String> {
        val navViewModel = navVm ?: return Pair(false, "NavViewModel не подключён")
        val gpsState = navViewModel.gpsState.value
        val fix = gpsState.currentFix ?: return Pair(false, "Нет GPS fix")

        val currentNumber = _movementState.value.currentControl.num
        val bindResult = bindGpsToCp(fix.coordinate, currentNumber)
            ?: return Pair(false, "КП #$currentNumber не найдена на карте")

        applyBind(bindResult, navViewModel)
        return Pair(true, "КП #$currentNumber привязан")
    }

    fun recalibrateToTargetControl(): Pair<Boolean, String> {
        val navViewModel = navVm ?: return Pair(false, "NavViewModel не подключён")
        val calibration = _calibration ?: return Pair(false, "Сначала выполните привязку «Здесь»")

        val gpsState = navViewModel.gpsState.value
        val currentFix = gpsState.currentFix ?: return Pair(false, "Нет GPS fix")

        val targetNumber = _movementState.value.currentControl.num
        val targetBox =
            _mapState.value.controlsBoundingBoxes.firstOrNull { it.number == targetNumber }
                ?: return Pair(false, "CP #$targetNumber не найдена на карте")

        val pointB =
            CalibrationPoint(currentFix.coordinate, targetBox.centerX, targetBox.centerY, 1.0)
        val newCal = MapGeometry.computeCalibrationSoft(
            calibration.pointA,
            pointB,
            _mapState.value.northAngle,
            calibration
        ) ?: return Pair(false, "Точки слишком близко — нельзя рассчитать масштаб")

        Log.d(tag, "===== recalibrateToTargetControl END =====")
        applyNewCalibration(newCal)
        setCurrentControl(targetNumber)
        return Pair(true, "Масштаб обновлён")
    }

    private fun applyNewCalibration(newCalibration: MapCalibration) {
        val navViewModel = navVm ?: return
        // Обновляем только калибровку — НЕ startTracking(), чтобы не очистить трек при перекалибровке
        navViewModel.applyNewCalibration(newCalibration)
        _calibration = newCalibration
        _mapState.value = _mapState.value.copy(mapCalibration = newCalibration)
        _calibrationVersion.value = _calibrationVersion.value + 1
    }

    fun startMonitoringIfNeeded() {
        val navViewModel = navVm
        if (navViewModel != null) {
            Log.d(tag, "startMonitoring: GPS monitoring started")
            startGpsMonitoring(navViewModel)
        } else {
            Log.w(tag, "startMonitoring: sharedNavViewModel is still null")
        }
    }

    private fun startGpsMonitoring(navViewModel: NavViewModel) {
        viewModelScope.launch {
            navViewModel.gpsState.collectLatest { gpsState ->
                if (!isLoading) handleGpsUpdate(gpsState)
            }
        }
    }

    private fun handleGpsUpdate(gpsState: GpsState) {
        val currentLevel = gpsState.accuracyLevel
        val fix = gpsState.currentFix

        when (currentLevel) {
            AccuracyLevel.HIGH_ACCURACY -> {
                accuracyLevelTransitionCount++
                if (accuracyLevelTransitionCount == 1) {
                    showMoveReadyAlert()
                    _firstGps = fix?.coordinate
                }
                if (fix != null) {
                    if (_calibration == null) {
                        onNoBind(fix)
                    }
                    // TrackRecorder записывает точку через NavViewModel.init GPS-коллектор
                    onMove(fix)
                }
            }

            AccuracyLevel.LOW_ACCURACY -> return
            AccuracyLevel.NO_FIX -> {
                accuracyLevelTransitionCount = 0
                _moveReadyAlert.value =
                    MoveReadyAlert(active = false, remainingMs = 0L, elapsedMs = 0L)
            }
        }
    }

    private fun showMoveReadyAlert() {
        viewModelScope.launch {
            _moveReadyAlert.value =
                MoveReadyAlert(active = true, remainingMs = 5000L, elapsedMs = 0L)
            var elapsed = 0L
            while (elapsed < 5000L && _moveReadyAlert.value.active) {
                bindGpsToCurrentControl()
                delay(100L)
                elapsed += 100L
                _moveReadyAlert.value = MoveReadyAlert(
                    active = true,
                    remainingMs = 5000L - elapsed,
                    elapsedMs = elapsed
                )
            }
            if (_moveReadyAlert.value.active) {
                _moveReadyAlert.value =
                    MoveReadyAlert(active = false, remainingMs = 0L, elapsedMs = 5000L)
            }
        }
    }

    fun getTelemetryTrackPoints(): List<TrackPoint> {
        val navViewModel = navVm ?: return emptyList()
        return navViewModel.trackData.trackPoints
    }

    override fun onProgressUpdate(current: Int, total: Int, message: String) {
        viewModelScope.launch {
            val currentHandle = mapDetector.currentTaskRef.get()
            if (currentHandle != null && !currentHandle.job.isCancelled) {
                _mapState.value = _mapState.value.copy(progressMessage = message)
            }
        }
    }

    private fun applyDetectionResult(result: MapDetectionResult?, taskVersion: Int) {
        val currentHandle = mapDetector.currentTaskRef.get()
        // Двойная защита: применяем только если версия совпадает с текущей активной задачей
        if (currentHandle != null && currentHandle.version == taskVersion && !currentHandle.job.isCancelled && result != null) {
            _mapState.value = _mapState.value.copy(
                controlsBoundingBoxes = result.controlsBoundingBoxes,
                numbersBoundingBoxes = result.numbersBoundingBoxes,
                isProcessing = false,
                progressMessage = null
            )
        }
    }

    fun loadImageFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)
                    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                    context.contentResolver.openInputStream(uri)?.use { rawStream ->
                        val rawBm = BitmapFactory.decodeStream(rawStream) ?: return@launch
                        val displayBm = when (orientation) {
                            3, 4 -> rotateBitmap(rawBm, 180.0)
                            5, 6 -> rotateBitmap(rawBm, 90.0)
                            7, 8 -> rotateBitmap(rawBm, 270.0)
                            else -> rawBm.copy(rawBm.config ?: Bitmap.Config.ARGB_8888, false)
                        }
                        withContext(Dispatchers.Main) {
                            launchDetectionWithBitmap(displayBm)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun loadImageFromBitmap(bitmap: Bitmap, imageUri: Uri?) {
        var displayBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        if (imageUri != null) {
            try {
                context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
                    val exif = ExifInterface(inputStream)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                        3, 4 -> displayBitmap = rotateBitmap(displayBitmap, 180.0)
                        5, 6 -> displayBitmap = rotateBitmap(displayBitmap, 90.0)
                        7, 8 -> displayBitmap = rotateBitmap(displayBitmap, 270.0)
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to read EXIF for orientation correction", e)
            }
        }
        launchDetectionWithBitmap(displayBitmap)
    }

    private fun onNoBind(fix: GpsFix) {
        val firstGps = _firstGps ?: fix.coordinate
        val navViewModel = navVm
        val bindResult = bindGpsToCp(firstGps, 1)
        if (bindResult != null && navViewModel != null) {
            applyBind(bindResult, navViewModel)
            setCurrentControl(2)
        }
    }

    private fun onMove(gpsFix: GpsFix?) {
        val ms = _movementState.value
        val mapCalibration = _calibration ?: return
        val fix = gpsFix ?: return

        val workingState = ms.copy(
            currentControl = ms.currentControl.copy(),
            prevControl = ms.prevControl.copy()
        )

        val result2 = _autoMoveManager.doMove(_mapState.value, workingState, mapCalibration, fix)
        val list = _autoMoveManager.autoCalibrations
        _autoCalibrationsList.value = list

        val navData = _calibration?.let {
            AutoMoveManager.analyzeNavigation(
                result2.currentControl.num,
                fix,
                it,
                _mapState.value,
                _autoMoveManager.getLastAverage3Bearing()
            )
        }

        val updatedState = result2.copy(
            currentControl = result2.currentControl.copy(),
            prevControl = result2.prevControl.copy(),
            autoCalibrations = list.toMutableList()
        )

        val prevSignal = updatedState.audioSignalState.type
        val audioState = navData?.let { AutoMoveManager.analyzeAudioSignal(it) }
            ?: AudioSignalState(type = AudioSignalType.NONE)

        // Полная тишина в радиусе 40 метров от КП
        // В радиусе 50 м от КП: один раз проигрываем трель прибытия, затем молчим
        if (audioState.distanceToTargetMeters < 50.0) {
            val currentCpNum = updatedState.currentControl.num
            if (arrivedPlayedForControlNum != currentCpNum) {
                Log.d("AudioNav", "▶ ARRIVED trill for CP #$currentCpNum")
                _audioPlayer.playTrill(1300, 15.0f, 600L, 1.0f, audioState.leftBalance)
                arrivedPlayedForControlNum = currentCpNum
                lastAudioPlayTimestampMs = System.currentTimeMillis()
                lastPlayedSignalType = AudioSignalType.ARRIVED
            }
            updatedState.audioSignalState = AudioSignalState(type = AudioSignalType.NONE)
            updatedState.approachControlNum = 0
            updatedState.approachDistKey = 0
            _movementState.value = updatedState
            return
        }

        updatedState.audioSignalState = audioState

        val now = System.currentTimeMillis()
        val elapsedMs = now - lastAudioPlayTimestampMs
        val canPlayByTimer = elapsedMs >= audioState.intervalMs
        val isSignalChanged = audioState.type != lastPlayedSignalType

        // Сигналы приближения (APPROACH) срабатывают сразу при входе в зону 100м/50м,
        // а сигналы отклонения (DEVIATION) — строго по таймеру intervalMs
        val shouldPlay = when (audioState.type) {
            AudioSignalType.NONE -> false
            AudioSignalType.APPROACH_SINGLE, AudioSignalType.APPROACH_DOUBLE -> true
            else -> canPlayByTimer || (isSignalChanged && lastPlayedSignalType != AudioSignalType.ARRIVED && elapsedMs >= 3000L)
        }

        if (shouldPlay) {
            Log.d(
                "AudioNav",
                String.format(
                    Locale.US,
                    "[PLAY] type=%s | dist=%.1fm | dev=%.1f° | pause=%dms (elapsed=%dms)",
                    audioState.type,
                    audioState.distanceToTargetMeters,
                    audioState.deviationDeg,
                    audioState.intervalMs,
                    elapsedMs
                )
            )
            when (audioState.type) {
                AudioSignalType.APPROACH_SINGLE, AudioSignalType.APPROACH_DOUBLE -> {
                    val distKey = if (audioState.type == AudioSignalType.APPROACH_SINGLE) 100 else 50
                    if (updatedState.approachControlNum != updatedState.currentControl.num || updatedState.approachDistKey != distKey) {
                        playDoubleBeep(audioState)
                        updatedState.approachControlNum = updatedState.currentControl.num
                        updatedState.approachDistKey = distKey
                        lastAudioPlayTimestampMs = now
                        lastPlayedSignalType = audioState.type
                    }
                }

                AudioSignalType.DEVIATION -> {
                    val durationMs = (200.0 + (5.0 * abs(audioState.deviationDeg))).toLong().coerceAtMost(600L)
                    _audioPlayer.playTone(audioState.frequencyHz, durationMs, 1.0f, audioState.leftBalance)
                    lastAudioPlayTimestampMs = now
                    lastPlayedSignalType = AudioSignalType.DEVIATION
                }

                else -> {}
            }
        }

        // Сохраняем в StateFlow уже ПОСЛЕ обновления lastAudioPlayMs и audioSignalState
        _movementState.value = updatedState

        result2.newMapCalibration?.let { newCalib ->
            val prefs = context.getSharedPreferences("orientvibe_settings", 0)
            if (prefs.getBoolean("auto_calibration", true)) {
                applyNewCalibration(newCalib)
            } else {
                Log.d("AutoModeVM", "Автокалибровка отключена — newMapCalibration пропущен")
            }
        }
    }

    private fun launchDetectionWithBitmap(bitmap: Bitmap?) {
        if (bitmap == null) return

        // 1. Сначала регистрируем новую задачу — это автоматически вызовет .cancel() у предыдущей!
        val task = mapDetector.launchDetection()

        _mapState.value = AutoMapState(
            bitmap = bitmap,
            isProcessing = true,
            progressMessage = "Запуск детекции..."
        )

        // 2. Запускаем корутину, привязанную к task.job, чтобы при его отмене
        // мгновенно прервались все вложенные циклы в OnnxObjectDetector
        viewModelScope.launch(task.job + Dispatchers.IO) {
            try {
                val result = mapDetector.detect(bitmap)

                // 3. Перед применением результата проверяем, что эта задача всё ещё актуальна
                val currentHandle = mapDetector.currentTaskRef.get()
                if (currentHandle != null && currentHandle.version == task.version && task.job.isActive) {
                    withContext(Dispatchers.Main) {
                        applyDetectionResult(result, task.version)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(tag, "Детекция версии #${task.version} успешно отменена")
            }
        }
    }


    fun updateNorthAngle(angle: Double) {
        _mapState.value = _mapState.value.copy(northAngle = angle.coerceIn(-45.0, 45.0))
    }

    fun resetNorthAngle() {
        _mapState.value = _mapState.value.copy(northAngle = 0.0)
    }

    fun clearError() {
        _mapState.value = _mapState.value.copy(errorMessage = null)
    }

    fun reset() {
        navVm?.clearTrack()
        navVm?.resetCalibration()
        _mapState.value = AutoMapState()
        _movementState.value = MovementState()
        _calibration = null
        _firstGps = null
        _autoCalibrationsList.value = emptyList()
        _calibrationVersion.value = _calibrationVersion.value + 1
        arrivedPlayedForControlNum = -1
        lastAudioPlayTimestampMs = 0L
        lastPlayedSignalType = AudioSignalType.NONE
    }

    private fun playDoubleBeep(audioState: AudioSignalState) {
        val isDoublePair = audioState.type == AudioSignalType.APPROACH_DOUBLE
        Log.d(
            "AudioPlayer",
            "playDoubleBeep: " + if (isDoublePair) "double-pair(4beeps)" else "single-pair(2beeps)"
        )
        _audioPlayer.playTone(1400, 120L, 1.0f, 0.5f)
        thread(start = true) {
            Thread.sleep(180L)
            _audioPlayer.playTone(1100, 120L, 1.0f, 0.5f)
        }
        if (isDoublePair) {
            thread(start = true) {
                Thread.sleep(900L)
                _audioPlayer.playTone(1400, 120L, 1.0f, 0.5f)
                Thread.sleep(180L)
                _audioPlayer.playTone(1100, 120L, 1.0f, 0.5f)
            }
        }
    }

    private fun rotateBitmap(src: Bitmap, degrees: Double): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    // --- Lifecycle foreground-службы для фонового GPS/аудио ---
    private fun startBackgroundService() {
        val intent = BackgroundNavigationService.createStartIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        Log.d(tag, "Background navigation service started")
    }

    private fun stopBackgroundService() {
        val intent = BackgroundNavigationService.createStartIntent(context)
        context.stopService(intent)
        Log.d(tag, "Background navigation service stopped")
    }

    override fun onCleared() {
        mapDetector.close()
        _audioPlayer?.release()
        // --- Останавливаем foreground-службу, выходя из AutoModeScreen ---
        stopBackgroundService()
        Log.d(tag, "AutoModeViewModel cleared — background service stopped")
    }

    companion object {
        const val DISPLAY_TRACK_POINTS = 900
        fun Factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AutoModeViewModel(context) as T
                }
            }
    }
}