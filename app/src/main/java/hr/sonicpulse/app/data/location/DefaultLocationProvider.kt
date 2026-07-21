package hr.sonicpulse.app.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-safe: start()/stop() are idempotent, stop() always removes the callback (so a later
 * start() never registers a duplicate), and start() clears any fix from a previous session so a
 * new session begins as NoFixYet rather than inheriting a fix that may be hours old.
 */
@Singleton
class DefaultLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) : LocationProvider {

    private val locationPolicy = LocationPolicy()
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val lock = Any()

    private var callback: LocationCallback? = null

    @Volatile
    private var lastFix: RawLocationFix? = null

    override val currentSnapshot: LocationSnapshot
        get() = LocationValidator.evaluate(
            fix = lastFix,
            permissionLevel = permissionLevel(),
            policy = locationPolicy,
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        )

    override fun permissionLevel(): LocationPermissionLevel {
        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fineGranted) {
            return LocationPermissionLevel.FINE
        }

        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return if (coarseGranted) LocationPermissionLevel.COARSE else LocationPermissionLevel.NONE
    }

    override fun start() {
        synchronized(lock) {
            if (callback != null) {
                return
            }
            lastFix = null
            if (permissionLevel() == LocationPermissionLevel.NONE) {
                return
            }

            val newCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    val fix = try {
                        RawLocationFix(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = location.accuracy,
                            elapsedRealtimeNanos = location.elapsedRealtimeNanos
                        )
                    } catch (e: IllegalArgumentException) {
                        // A malformed fix (e.g. from a mock location app); keep the previous one.
                        return
                    }
                    lastFix = fix
                }
            }

            val request = LocationRequest.Builder(locationPolicy.updateIntervalMillis)
                .setPriority(locationPolicy.priority)
                .build()

            try {
                fusedClient.requestLocationUpdates(request, newCallback, Looper.getMainLooper())
                callback = newCallback
            } catch (e: SecurityException) {
                // Permission revoked between the check above and this call; leave callback unset
                // so a later start() (once permission is granted again) can retry.
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            val current = callback ?: return
            fusedClient.removeLocationUpdates(current)
            callback = null
        }
    }
}
