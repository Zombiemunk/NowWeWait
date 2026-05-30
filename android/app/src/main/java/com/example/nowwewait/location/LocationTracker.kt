package com.example.nowwewait.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Pair<Double, Double>? {
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCoarse && !hasFine) {
            return null
        }

        // 1. Try to get last known location first (extremely fast, ~0ms)
        val lastLocation = suspendCancellableCoroutine<Location?> { continuation ->
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location -> continuation.resume(location) }
                .addOnFailureListener { continuation.resume(null) }
                .addOnCanceledListener { continuation.resume(null) }
        }

        if (lastLocation != null && isLocationRecent(lastLocation)) {
            return lastLocation.latitude to lastLocation.longitude
        }

        // 2. Fall back to one-shot active location request
        val activeLocation = suspendCancellableCoroutine<Location?> { continuation ->
            val cts = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cts.token
            )
                .addOnSuccessListener { location -> continuation.resume(location) }
                .addOnFailureListener { continuation.resume(null) }
                .addOnCanceledListener { continuation.resume(null) }
            
            continuation.invokeOnCancellation {
                cts.cancel()
            }
        }

        if (activeLocation != null) {
            return activeLocation.latitude to activeLocation.longitude
        }

        // 3. Fallback to standard LocationManager if Google Play Services is absent or fails
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager != null) {
            try {
                val providers = locationManager.getProviders(true)
                for (provider in providers) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        return loc.latitude to loc.longitude
                    }
                }
            } catch (e: SecurityException) {
                // Ignore security exception
            }
        }

        return lastLocation?.let { it.latitude to it.longitude }
    }

    private fun isLocationRecent(location: Location): Boolean {
        // Less than 30 seconds old is considered recent enough — ensures
        // the app always re-locks GPS when the user has moved to a new area.
        val age = System.currentTimeMillis() - location.time
        return age < 30 * 1000
    }
}
