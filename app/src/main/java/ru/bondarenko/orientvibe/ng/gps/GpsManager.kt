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
import ru.bondarenko.orientvibe.ng.model.*

class GpsManager(context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _gpsState = MutableStateFlow(
        GpsState(
            isGpsEnabled = false,
            currentFix = null,
            calibration = null,
            isCalibrated = false,
            startCalibrated = false,
            finishCalibrated = false,
            autoBindActive = false,
            trackPoints = emptyList(),
            totalDistance = 0.0,
            isTracking = false,
            routeDistance = null,
            mapScaleX = null,
            mapScaleY = null,
            originalStartGps = null
        )
    )

    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    val isGpsEnabled: Boolean
        get() {
            val enabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            Log.d(TAG, "isGpsEnabled: $enabled")
            return enabled
        }

    private var locationListener: LocationListener? = null

    @SuppressLint("MissingPermission")
    fun startGpsUpdates() {
        Log.d(TAG, "startGpsUpdates called")

        if (!isGpsEnabled) {
            Log.w(TAG, "startGpsUpdates: GPS provider is disabled on device")
            _gpsState.value = _gpsState.value.copy(
                isGpsEnabled = false,
                currentFix = null
            )
            return
        }

        stopGpsUpdates()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = GpsFix(
                    coordinate = GpsCoordinate(location.latitude, location.longitude),
                    accuracy = location.accuracy.toDouble(),
                    bearing = location.bearing.toDouble(),
                    speed = location.speed.toDouble(),
                    timestamp = System.currentTimeMillis(),
                    altitude = location.altitude
                )

                _gpsState.value = _gpsState.value.copy(
                    isGpsEnabled = true,
                    currentFix = fix
                )
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                Log.d(TAG, "onStatusChanged: provider=$provider, status=$status")
            }

            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "onProviderEnabled: $provider")
                if (provider == LocationManager.GPS_PROVIDER) {
                    _gpsState.value = _gpsState.value.copy(
                        isGpsEnabled = true
                    )
                }
            }

            override fun onProviderDisabled(provider: String) {
                Log.d(TAG, "onProviderDisabled: $provider")
                if (provider == LocationManager.GPS_PROVIDER) {
                    _gpsState.value = _gpsState.value.copy(
                        isGpsEnabled = false,
                        currentFix = null
                    )
                }
            }
        }

        this.locationListener = listener
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, listener)
        Log.d(TAG, "startGpsUpdates: location updates registered successfully")
    }

    fun stopGpsUpdates() {
        Log.d(TAG, "stopGpsUpdates called")
        locationListener?.let {
            locationManager.removeUpdates(it)
            Log.d(TAG, "stopGpsUpdates: listener removed")
        }
        locationListener = null
    }

    val lastKnownLocation: GpsFix?
        @SuppressLint("MissingPermission")
        get() {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            for (provider in providers) {
                try {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        Log.d(TAG, "getLastKnownLocation: provider=$provider, lat=${loc.latitude}, lon=${loc.longitude}")
                        return GpsFix(
                            coordinate = GpsCoordinate(loc.latitude, loc.longitude),
                            accuracy = loc.accuracy.toDouble(),
                            bearing = loc.bearing.toDouble(),
                            speed = loc.speed.toDouble(),
                            timestamp = System.currentTimeMillis(),
                            altitude = loc.altitude
                        )
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "getLastKnownLocation: SecurityException for provider=$provider")
                }
            }
            Log.d(TAG, "getLastKnownLocation: no location available from any provider")
            return null
        }

    fun onCleared() {
        Log.d(TAG, "onCleared")
        stopGpsUpdates()
    }

    companion object {
        private const val TAG = "GpsManager"
    }
}
