package com.example.navisense

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Manages device location tracking using Google Play Services FusedLocationProviderClient.
 * Emits continuous location updates with high accuracy for walking navigation.
 */
class LocationManager(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    var lastLocation: Location? = null
        private set

    private var isUpdatingLocation: Boolean = false
    private var locationListener: ((Location) -> Unit)? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            lastLocation = location
            Log.d(
                TAG,
                "Location update: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m"
            )
            locationListener?.invoke(location)
        }
    }

    fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(onLocationChanged: ((Location) -> Unit)? = null) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot start location updates: Location permissions not granted.")
            return
        }

        if (isUpdatingLocation) {
            locationListener = onLocationChanged
            return
        }

        locationListener = onLocationChanged

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
            setWaitForAccurateLocation(false)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isUpdatingLocation = true
            Log.i(TAG, "Started fused location updates (interval: ${UPDATE_INTERVAL_MS}ms).")

            // Also request last known location immediately
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    lastLocation = loc
                    Log.i(TAG, "Initial last known location: lat=${loc.latitude}, lon=${loc.longitude}")
                    locationListener?.invoke(loc)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location updates", e)
        }
    }

    fun stopLocationUpdates() {
        if (!isUpdatingLocation) return
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isUpdatingLocation = false
            locationListener = null
            Log.i(TAG, "Stopped fused location updates.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop location updates", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun getLastLocation(onResult: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            onResult(null)
            return
        }

        lastLocation?.let {
            onResult(it)
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { loc ->
                lastLocation = loc
                onResult(loc)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to obtain last location", e)
                onResult(null)
            }
    }

    companion object {
        private const val TAG = "LocationManager"
        private const val UPDATE_INTERVAL_MS = 4000L
        private const val FASTEST_UPDATE_INTERVAL_MS = 2000L
    }
}
