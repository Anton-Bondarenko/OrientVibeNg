package ru.bondarenko.orientvibe.ng.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.Manifest
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.bondarenko.orientvibe.ng.MainActivity
import ru.bondarenko.orientvibe.ng.R
import ru.bondarenko.orientvibe.ng.audio.StereoAudioPlayer
import ru.bondarenko.orientvibe.ng.gps.TrackRecorder
import ru.bondarenko.orientvibe.ng.model.GpsCoordinate
import ru.bondarenko.orientvibe.ng.model.GpsFix
import ru.bondarenko.orientvibe.ng.model.MapCalibration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Foreground-служба: непрерывный GPS-трекинг и аудио в фоне (экран выкл / приложение свёрнуто).
 */
class BackgroundNavigationService : Service() {

    companion object {
        const val TAG = "BackgroundNavService"
        const val ACTION_START_SERVICE = "$TAG.START_SERVICE"
        const val ACTION_PLAY_AUDIO = "$TAG.PLAY_AUDIO"
        const val EXTRA_FREQ = "$TAG.freq"
        const val EXTRA_DUR = "$TAG.dur"
        const val EXTRA_VOL = "$TAG.vol"
        const val EXTRA_BAL = "$TAG.bal"

        fun createStartIntent(context: Context): Intent =
            Intent(context, BackgroundNavigationService::class.java).apply {
                action = ACTION_START_SERVICE
            }
    }

    inner class LocalBinder : Binder() {
        fun getService(): BackgroundNavigationService = this@BackgroundNavigationService
    }
    private val binder = LocalBinder()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var wakeLock: PowerManager.WakeLock
    private var notificationId = 0
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val locationManager: LocationManager by lazy {
        val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            createAttributionContext("gps_navigation")
        } else {
            this
        }
        ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val trackRecorder = TrackRecorder()
    private var currentCalibration: MapCalibration? = null
    private var locationListener: LocationListener? = null

    @Volatile
    private var audioActive = true
    @Volatile
    private var isForegroundActive = false
    @Volatile
    private var isForegroundStarted = false

    private val latestFix = object {
        var coordinate: GpsCoordinate? = null
        var accuracy = 0f
        var bearing = 0f
        var speed = 0f
        var timestamp = 0L
    }

    private val audioPlayer: StereoAudioPlayer by lazy {
        StereoAudioPlayer.getInstance(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${packageName}:nav_wake_lock").apply {
            acquire()
            Log.d(TAG, "WakeLock acquired")
        }

        val channelId = "orientvibe_nav_channel"
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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

            if (hasLocationPermission()) {
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
                isForegroundStarted = true
            } else {
                Log.w(TAG, "Location permission not granted — deferring startForeground")
            }
        } else {
            if (hasLocationPermission()) {
                val notification = NotificationCompat.Builder(this, channelId)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("Навигация активна")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(pendingIntent)
                    .build()
                startForeground(1, notification)
                notificationId = 1
                isForegroundStarted = true
            } else {
                Log.w(TAG, "Location permission not granted — deferring startForeground")
            }
        }

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setOnAudioFocusChangeListener { }
            }.build()
            audioManager?.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
        }

        startBackgroundAudioThread()
        Log.d(TAG, "Service created OK")
    }

    private fun hasLocationPermission(): Boolean {
        val fine = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val coarse = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun startBackgroundAudioThread() {
        serviceScope.launch {
            while (true) {
                delay(1500L.milliseconds)
                if (!audioActive) continue
                val fix = latestFix.coordinate ?: continue
                if (isForegroundActive) continue
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")

        // Defer startForeground() if permissions weren't granted at onCreate time
        if (!isForegroundStarted && hasLocationPermission()) {
            val channelId = "orientvibe_nav_channel"
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
            isForegroundStarted = true
            Log.d(TAG, "startForeground called in onStartCommand after permission grant")
        } else if (!isForegroundStarted) {
            Log.w(TAG, "Location permission not yet granted — skipping GPS tracking")
            return START_NOT_STICKY
        }

        startGpsTracking()

        if (intent?.action == ACTION_PLAY_AUDIO) {
            val freq = intent.getIntExtra(EXTRA_FREQ, 440)
            val dur = intent.getLongExtra(EXTRA_DUR, 200L)
            val vol = intent.getFloatExtra(EXTRA_VOL, 0.45f)
            val bal = intent.getFloatExtra(EXTRA_BAL, 0.5f)
            audioPlayer.playTone(freq, dur, vol, bal)
        }
        return START_STICKY
    }

    private fun startGpsTracking() {
        val lm = locationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "GPS provider disabled")
            return
        }

        locationListener?.let { lm.removeUpdates(it) }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = GpsFix(
                    coordinate = GpsCoordinate(location.latitude, location.longitude),
                    accuracy = location.accuracy.toDouble(),
                    bearing = location.bearing.toDouble(),
                    speed = location.speed.toDouble(),
                    timestamp = System.currentTimeMillis()
                )

                latestFix.coordinate = fix.coordinate
                latestFix.accuracy = location.accuracy
                latestFix.bearing = location.bearing
                latestFix.speed = location.speed
                latestFix.timestamp = fix.timestamp

                trackRecorder.recordPoint(fix, currentCalibration)

                if (!isForegroundActive) {
                    val broadcast = Intent("ru.bondarenko.orientvibe.ng.GPS_FIX").apply {
                        setPackage(packageName)
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

                if (audioActive && !isForegroundActive) {
                    tryPlayBackgroundAudio(fix)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        this.locationListener = listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, 1.0f,
                mainExecutor, listener
            )
        } else {
            @Suppress("DEPRECATION")
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L, 1.0f, listener, null
            )
        }
        Log.d(TAG, "GPS tracking started in background service")
    }
    private fun tryPlayBackgroundAudio(fix: GpsFix) {
        val cal = currentCalibration ?: return
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: UI открепилось")
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        Log.d(TAG, "onDestroy: stopping all background navigation")
        serviceScope.cancel()
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
            Log.d(TAG, "WakeLock released")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (_: Exception) { }
        locationListener?.let {
            try {
                locationManager.removeUpdates(it)
            } catch (_: Exception) { }
            locationListener = null
        }
        audioPlayer.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }
    fun setAudioMuted(muted: Boolean) {
        isForegroundActive = !muted
        Log.d(TAG, "setAudioMuted: foregroundActive=$isForegroundActive")
    }
    fun setCalibration(cal: MapCalibration?) {
        currentCalibration = cal
    }
    fun playTone(freq: Int, durationMs: Long, volume: Float, balance: Float) {
        audioPlayer.playTone(freq, durationMs, volume, balance)
    }
    fun isTracking(): Boolean = trackRecorder.trackData.isTracking
    fun stopNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        Log.d(TAG, "stopNavigation called")
    }
}