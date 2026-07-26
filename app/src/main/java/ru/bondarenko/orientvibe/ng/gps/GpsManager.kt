package ru.bondarenko.orientvibe.ng.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages GPS on/off, location updates, accuracy, and bearing.
 * Exposes GpsState via a StateFlow for reactive UI updates.
 */
class GpsManager(private val context: Context) {

    companion object {
        private const val TAG = "GpsManager"
    }

    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gpsState = MutableStateFlow(GpsState())
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    private var locationListener: LocationListener? = null

    /**
     * Check if GPS is enabled on the device.
     */
    fun isGpsEnabled(): Boolean {
        val enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        Log.d(TAG, "isGpsEnabled: $enabled")
        return enabled
    }

    /**
     * Start listening for GPS updates.
     * Minimum time interval: 1 second.
     * Minimum distance change: 1 meter.
     */
    @SuppressLint("MissingPermission")
    fun startGpsUpdates() {
        Log.d(TAG, "startGpsUpdates called")

        if (!isGpsEnabled()) {
            Log.w(TAG, "startGpsUpdates: GPS provider is disabled on device")
            _gpsState.value = _gpsState.value.copy(isGpsEnabled = false)
            return
        }

        stopGpsUpdates() // remove any existing listener first

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.d(TAG, "onLocationChanged: lat=${location.latitude}, lon=${location.longitude}, " +
                        "accuracy=${location.accuracy}m, bearing=${location.bearing}°, speed=${location.speed}m/s")
                val fix = GpsFix(
                    coordinate = GpsCoordinate(
                        latitude = location.latitude,
                        longitude = location.longitude
                    ),
                    accuracy = location.accuracy,
                    bearing = location.bearing,
                    speed = location.speed,
                    timestamp = System.currentTimeMillis(),
                    altitude = location.altitude
                )
                _gpsState.value = _gpsState.value.copy(
                    isGpsEnabled = true,
                    currentFix = fix
                )
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                Log.d(TAG, "onStatusChanged: provider=$provider, status=$status")
            }

            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "onProviderEnabled: $provider")
                if (provider == LocationManager.GPS_PROVIDER) {
                    _gpsState.value = _gpsState.value.copy(isGpsEnabled = true)
                }
            }

            override fun onProviderDisabled(provider: String) {
                Log.d(TAG, "onProviderDisabled: $provider")
                if (provider == LocationManager.GPS_PROVIDER) {
                    _gpsState.value = _gpsState.value.copy(isGpsEnabled = false)
                }
            }
        }

        locationListener = listener
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,  // 1 second
            1f,     // 1 meter
            listener
        )
        Log.d(TAG, "startGpsUpdates: location updates registered successfully")
    }

    /**
     * Stop listening for GPS updates.
     */
    fun stopGpsUpdates() {
        Log.d(TAG, "stopGpsUpdates called")
        locationListener?.let {
            locationManager.removeUpdates(it)
            Log.d(TAG, "stopGpsUpdates: listener removed")
        }
        locationListener = null
    }

    /**
     * Get the best available location (from any provider) immediately.
     * Returns null if no location is available.
     */
    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): GpsFix? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        for (provider in providers) {
            try {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                Log.d(TAG, "getLastKnownLocation: provider=$provider, lat=${loc.latitude}, lon=${loc.longitude}")
                return GpsFix(
                    coordinate = GpsCoordinate(loc.latitude, loc.longitude),
                    accuracy = loc.accuracy,
                    bearing = loc.bearing,
                    speed = loc.speed,
                    timestamp = System.currentTimeMillis(),
                    altitude = loc.altitude
                )
            } catch (_: SecurityException) {
                Log.w(TAG, "getLastKnownLocation: SecurityException for provider=$provider")
                continue
            }
        }
        Log.d(TAG, "getLastKnownLocation: no location available from any provider")
        return null
    }

    /**
     * Clean up resources.
     */
    fun onCleared() {
        Log.d(TAG, "onCleared")
        stopGpsUpdates()
    }
}