package hr.sonicpulse.app.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-safe: start()/stop() are idempotent, stop() always removes the callback (so a later
 * start() never registers a duplicate), and a cancelled attempt can never affect a later session
 * (a late success/failure for an already-invalidated callback is detected by identity and
 * discarded — see completeAttempt). A recent cached fix survives across sessions; only its age
 * (evaluated fresh on every read, never cached) determines whether it's still Valid.
 */
@Singleton
class DefaultLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) : LocationProvider {

    private val locationPolicy = LocationPolicy()
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val lock = Any()

    private var callback: LocationCallback? = null
    private var pendingOnResult: ((LocationStartResult) -> Unit)? = null

    @Volatile
    private var lastFix: RawLocationFix? = null

    override val currentSnapshot: LocationSnapshot
        get() = LocationValidator.evaluate(lastFix, locationPolicy, SystemClock.elapsedRealtimeNanos())

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

    override fun areLocationServicesEnabled(): Boolean {
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(locationManager)
    }

    override fun start(onResult: (LocationStartResult) -> Unit) {
        synchronized(lock) {
            if (callback != null) {
                // Already running, or an attempt is already pending. The caller (the service's
                // own lifecycle coordinator) guarantees start() is never invoked again while a
                // session is STARTING/ACTIVE, so this should not happen in practice — reject
                // silently rather than inventing new semantics for a call that should not occur.
                return
            }
            if (permissionLevel() == LocationPermissionLevel.NONE) {
                onResult(LocationStartResult.PermissionDenied)
                return
            }
            // Re-checked here, not just by the caller's own gate: location services can be
            // toggled off by the user at any time, including between the gate's check and now.
            if (!areLocationServicesEnabled()) {
                onResult(LocationStartResult.LocationServicesDisabled)
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

            callback = newCallback
            pendingOnResult = onResult

            try {
                val task = fusedClient.requestLocationUpdates(request, newCallback, Looper.getMainLooper())
                task.addOnSuccessListener {
                    completeAttempt(newCallback) { LocationStartResult.Started }
                }
                task.addOnFailureListener { e ->
                    completeAttempt(newCallback) { LocationStartResult.Failed(e) }
                }
            } catch (e: SecurityException) {
                callback = null
                pendingOnResult = null
                onResult(LocationStartResult.PermissionDenied)
            }
        }
    }

    /** Resolves a pending attempt only if [forCallback] is still the current one; otherwise it
     * was already invalidated by [stop] — remove the now-orphaned callback and report nothing
     * (Cancelled was already reported, exactly once, at invalidation time). */
    private fun completeAttempt(forCallback: LocationCallback, result: () -> LocationStartResult) {
        val onResult: ((LocationStartResult) -> Unit)?
        synchronized(lock) {
            if (callback !== forCallback) {
                fusedClient.removeLocationUpdates(forCallback)
                return
            }
            onResult = pendingOnResult
            pendingOnResult = null
        }
        onResult?.invoke(result())
    }

    override fun stop() {
        val onResult: ((LocationStartResult) -> Unit)?
        synchronized(lock) {
            val current = callback ?: return
            fusedClient.removeLocationUpdates(current)
            callback = null
            onResult = pendingOnResult
            pendingOnResult = null
        }
        onResult?.invoke(LocationStartResult.Cancelled)
    }
}
