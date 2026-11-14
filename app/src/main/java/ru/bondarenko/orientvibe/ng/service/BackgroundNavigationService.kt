package ru.bondarenko.orientvibe.ng.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import ru.bondarenko.orientvibe.ng.gps.TrackRecorder
import ru.bondarenko.orientvibe.ng.audio.StereoAudioPlayer
import java.util.Locale

import ru.bondarenko.orientvibe.ng.MainActivity
import ru.bondarenko.orientvibe.ng.R

/**
 * Foreground-служба: непрерывный GPS-трекинг и аудио в фоне (экран выкл / приложение свёрнуто).
 */
class BackgroundNavigationService : Service() {

    companion object {
        const val TAG = "BackgroundNavService"
        const val ACTION_PLAY_AUDIO = "$TAG.PLAY_AUDIO"
        const val ACTION_MUTE_AUDIO = "$TAG.MUTE_AUDIO"
        const val EXTRA_FREQ = "$TAG.freq"
        const val EXTRA_DUR = "$TAG.dur"
        const val EXTRA_VOL = "$TAG.vol"
        const val EXTRA_BAL = "$TAG.bal"
        const val EXTRA_TYPE = "$TAG.type"

        fun createStartIntent(context: Context): Intent =
            Intent(context, BackgroundNavigationService::class.java).apply { action = ACTION_PLAY_AUDIO }
    }

    // — Binder для привязки из Activity/ViewModel —
    inner class LocalBinder : Binder() {
        fun getService(): BackgroundNavigationService = this@BackgroundNavigationService
    }
    private val binder = LocalBinder()

    // — WakeLock (CPU awake, screen off) —
    private lateinit var wakeLock: PowerManager.WakeLock

    // — Notification channel + ongoing notification —
    private var notificationId = 0
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    // — GPS via LocationManager (продолжает работать в фоне) —
    private val trackRecorder = TrackRecorder()
    private var currentCalibration: MapCalibration? = null
    private var locationListener: android.location.LocationListener? = null

    @Volatile
    private var audioActive = true        // false = отключаем звук, если играет UI-слой
    @Volatile
    private var isForegroundActive = false // Activity в foreground — оно само играет аудио
    @Volatile
    private var lastMuteUntil = 0L         // mute до этого timestamp

    private val latestFix = object {
        var coordinate: GpsCoordinate? = null
        var accuracy = 0f
        var bearing = 0f
        var speed = 0f
        var timestamp = 0L
    }

    // — AudioPlayer (создаём один раз, используем в фоновом потоке) —
    private val audioPlayer: StereoAudioPlayer? by lazy {
        try { StereoAudioPlayer.create() } catch (e: Exception) {
            Log.w(TAG, "Failed to create StereoAudioPlayer", e)
            null
        }
    }

    // — BroadcastReceiver для play/mute из ViewModel —
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_PLAY_AUDIO -> {
                    val freq = intent.getIntExtra(EXTRA_FREQ, 440)
                    val dur = intent.getLongExtra(EXTRA_DUR, 200L)
                    val vol = intent.getFloatExtra(EXTRA_VOL, 0.45f)
                    val bal = intent.getFloatExtra(EXTRA_BAL, 0.5f)
                    if (isForegroundActive || System.currentTimeMillis() > lastMuteUntil) {
                        audioPlayer?.playTone(freq, dur, vol, bal)
                        Log.d(TAG, "PLAY_AUDIO f=$freq Hz dur=${dur}ms vol=$vol bal=$bal")
                    } else {
                        Log.d(TAG, "PLAY_AUDIO skipped (mute until $lastMuteUntil)")
                    }
                }
                ACTION_MUTE_AUDIO -> {
                    val muteUntil = System.currentTimeMillis() + 2000
                    lastMuteUntil = muteUntil
                    isForegroundActive = false
                    Log.d(TAG, "MUTE_AUDIO until $muteUntil")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // — WakeLock: частичный (CPU работает при выключенном экране) —
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:nav_wake_lock").apply {
            acquire()
            Log.d(TAG, "WakeLock acquired")
        }

        // — Notification channel (API 26+) + ongoing notification —
        val channelId = "orientvibe_nav_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.nav_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.nav_service_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)

            // PendingIntent на MainActivity для тапа по уведомлению
            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle(getString(R.string.app_name) + getString(R.string.nav_notification_title))
                .setContentText(getString(R.string.nav_notification_text))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build()

            startForeground(1, notification)
            notificationId = 1
            Log.d(TAG, "Notification started")
        } else {
            // Pre-O: startForeground без channel не работает — просто ставим приоритетный статус
            // Для API < 26 foreground-уведомление необязательно для работы GPS
            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Навигация активна")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build()
            startForeground(1, notification)
        }

        // — Audio focus —
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnAudioFocusChangeListener { /* ignore */ }
            }.build()
            audioManager?.requestAudioFocus(focusRequest!!)
            Log.d(TAG, "Audio focus gained")
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
        }

        // — Запуск фонового трекинга аудио (каждые 1.5 сек) —
        startBackgroundAudioThread()

        Log.d(TAG, "Service created OK")
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startBackgroundAudioThread() {
        GlobalScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1500L)
                if (!audioActive || audioPlayer == null) continue

                // Получаем текущий fix и анализируем сигнал как AutoModeViewModel.analyzeAudioSignal
                val fix = latestFix.coordinate ?: continue

                // Проверяем: если в foreground — пропускаем, UI сам играет аудио
                if (isForegroundActive) continue

                // Если экран выключен или приложение в фоне — воспроизводим через сервис
                try {
                    audioPlayer?.playTone(440, 100L, 0.3f, 0.5f)
                    Log.d(TAG, "Background audio tick f=440Hz")
                } catch (e: Exception) {
                    Log.w(TAG, "Error in background audio thread", e)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // Запускаем GPS-трекинг всегда
        startGpsTracking()

        // Если intent содержит play-запрос — обработать немедленно
        intent?.action?.let { action ->
            if (action == ACTION_PLAY_AUDIO) {
                val freq = intent.getIntExtra(EXTRA_FREQ, 440)
                val dur = intent.getLongExtra(EXTRA_DUR, 200L)
                val vol = intent.getFloatExtra(EXTRA_VOL, 0.45f)
                val bal = intent.getFloatExtra(EXTRA_BAL, 0.5f)
                audioPlayer?.playTone(freq, dur, vol, bal)
            }
        }
        return START_STICKY
    }

    private fun startGpsTracking() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (!lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "GPS provider disabled")
            return
        }

        // Убираем старый listener перед добавлением нового
        locationListener?.let { lm.removeUpdates(it) }

        val listener = object : android.location.LocationListener {
            @Deprecated("Deprecated in Java")
            override fun onLocationChanged(location: android.location.Location) {
                val fix = GpsFix(
                    coordinate = GpsCoordinate(location.latitude, location.longitude),
                    accuracy = location.accuracy.toDouble(),
                    bearing = location.bearing.toDouble(),
                    speed = location.speed.toDouble(),
                    timestamp = System.currentTimeMillis()
                )

                // Обновляем latest fix
                latestFix.coordinate = fix.coordinate
                latestFix.accuracy = location.accuracy
                latestFix.bearing = location.bearing
                latestFix.speed = location.speed
                latestFix.timestamp = fix.timestamp

                // Записываем в TrackRecorder (независимо от UI)
                trackRecorder.recordPoint(fix, currentCalibration)

                // — Если сервис активен в фоне — отправляем broadcast для UI —
                if (!isForegroundActive) {
                    val broadcast = Intent("ru.bondarenko.orientvibe.ng.GPS_FIX").apply {
                        putExtra("lat", fix.coordinate.latitude)
                        putExtra("lon", fix.coordinate.longitude)
                        putExtra("accuracy", fix.accuracy.toFloat())
                        putExtra("bearing", fix.bearing.toFloat())
                        putExtra("speed", fix.speed.toFloat())
                        putExtra("timestamp", fix.timestamp)
                    }
                    try {
                        sendBroadcast(broadcast)
                    } catch (e: Exception) {
                        Log.w(TAG, "GPS broadcast failed", e)
                    }
                }

                // — Звук в фоне: анализируем навигацию и воспроизводим сигналы —
                if (audioActive && audioPlayer != null && !isForegroundActive) {
                    tryPlayBackgroundAudio(fix)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "GPS provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.w(TAG, "GPS provider disabled: $provider")
            }
        }

        this.locationListener = listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            lm.requestLocationUpdates(
                android.location.LocationManager.GPS_PROVIDER,
                1000L, 1.0f,
                mainExecutor, listener
            )
        } else {
            @Suppress("DEPRECATION")
            lm.requestLocationUpdates(
                android.location.LocationManager.GPS_PROVIDER,
                1000L, 1.0f, listener, null
            )
        }
        Log.d(TAG, "GPS tracking started in background service")
    }

    private fun tryPlayBackgroundAudio(fix: GpsFix) {
        // Если нет калибровки — пропускаем звуковые сигналы
        val cal = currentCalibration ?: return

        // Простая логика: отклонение от курса -> тон; приближение к КП -> бип
        // Детальная логика делегируется UI-слою через broadcast PLAY_AUDIO
        // Здесь — базовый "heartbeat" для подтверждения что навигация жива
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: UI открепилось")
        return super.onUnbind(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: stopping all background navigation")

        // — WakeLock —
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "WakeLock released")
        }

        // — Audio focus —
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) { /* ignore */ }

        // — Location updates —
        locationListener?.let {
            try {
                getSystemService(android.location.LocationManager::class.java)
                    .removeUpdates(it)
            } catch (e: Exception) { /* ignore */ }
            locationListener = null
        }

        // — AudioPlayer —
        audioPlayer?.release()

        // — Notification —
        stopForeground(true)

        Log.d(TAG, "Service destroyed")
    }

    // — Публичные методы для управления из Activity/ViewModel —
    fun setAudioMuted(muted: Boolean) {
        isForegroundActive = !muted
        Log.d(TAG, "setAudioMuted: foregroundActive=$isForegroundActive")
    }

    fun setCalibration(cal: MapCalibration?) {
        currentCalibration = cal
    }

    fun playTone(freq: Int, durationMs: Long, volume: Float, balance: Float) {
        audioPlayer?.playTone(freq, durationMs, volume, balance)
    }

    fun isTracking(): Boolean = trackRecorder.trackData.isTracking

    /**
     * Останавливаем службу — вызывает stopForeground + stopSelf.
     */
    fun stopNavigation() {
        stopForeground(true)
        stopSelf()
        Log.d(TAG, "stopNavigation called")
    }
}
