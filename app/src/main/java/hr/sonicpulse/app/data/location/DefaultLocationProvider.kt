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
 * Session-safe: start()/stop() are idempotent, stop() always removes the active callback (so a
 * later start() never registers a duplicate), and every accepted start attempt (see
 * [LocationStartTracker]) resolves its onResult exactly once — including a restart after an
 * async failure/cancellation, and discarding a late Task result or location update that belongs
 * to an already-invalidated attempt. A recent cached fix survives across sessions; only its age
 * (evaluated fresh on every read, never cached) determines whether it's still Valid.
 */
@Singleton
class DefaultLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context
) : LocationProvider {

    private val locationPolicy = LocationPolicy()
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val lock = Any()
    private val tracker = LocationStartTracker()

    /** The callback currently registered with fusedClient, if any — tracked separately from
     * [tracker] because only DefaultLocationProvider knows the real Play Services object to
     * call removeLocationUpdates() on; [tracker] only reasons about opaque token identity. */
    private var activeCallback: LocationCallback? = null

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
            val newCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    // Only the currently active attempt may update lastFix. removeLocationUpdates()
                    // is asynchronous, so a callback belonging to a stopped or superseded session
                    // can still be delivered after the fact — both this check and every mutation
                    // of the tracker/activeCallback happen exclusively on the main thread
                    // (Looper.getMainLooper() below), so no extra synchronization is needed here.
                    if (!tracker.isCurrent(this)) {
                        return
                    }
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

            if (tracker.begin(newCallback, onResult) !is LocationStartTracker.BeginResult.Accepted) {
                // Already running or an attempt is already pending. The caller (the service's
                // own lifecycle coordinator) guarantees start() is never invoked again while a
                // session is STARTING/ACTIVE, so this should not happen in practice — reject
                // silently rather than inventing new semantics for a call that should not occur.
                return
            }

            if (permissionLevel() == LocationPermissionLevel.NONE) {
                resolve(newCallback, LocationStartResult.PermissionDenied)
                return
            }
            // Re-checked here, not just by the caller's own gate: location services can be
            // toggled off by the user at any time, including between the gate's check and now.
            if (!areLocationServicesEnabled()) {
                resolve(newCallback, LocationStartResult.LocationServicesDisabled)
                return
            }

            val request = LocationRequest.Builder(locationPolicy.updateIntervalMillis)
                .setPriority(locationPolicy.priority)
                .build()

            try {
                val task = fusedClient.requestLocationUpdates(request, newCallback, Looper.getMainLooper())
                activeCallback = newCallback
                task.addOnSuccessListener { resolve(newCallback, LocationStartResult.Started) }
                task.addOnFailureListener { e -> resolve(newCallback, LocationStartResult.Failed(e)) }
                // A Task can also finish cancelled (neither success nor failure) — e.g. Play
                // Services itself giving up on the request. Without this, that outcome would
                // never call resolve() at all, leaving the tracker (and the caller waiting on
                // onResult) stuck indefinitely.
                task.addOnCanceledListener { resolve(newCallback, LocationStartResult.Cancelled) }
            } catch (e: SecurityException) {
                resolve(newCallback, LocationStartResult.PermissionDenied)
            }
        }
    }

    /**
     * Resolves [forCallback]'s attempt via [tracker], and removes it from fusedClient whenever
     * either: (a) [tracker] reports the attempt as [LocationStartTracker.CompleteResult.Stale] —
     * meaning it was already invalidated by [stop] or superseded, regardless of what [result]
     * claims (this is what catches a *stale Started* specifically: a late success arriving after
     * Stop must still have its registration torn down, not be treated as "started, nothing to
     * clean up" just because the result says Started); or (b) [result] genuinely isn't Started
     * for the still-current attempt. Safe (and a documented no-op) even if [forCallback] was
     * never actually registered (the synchronous permission/location-services rejection paths)
     * or was already removed by [stop].
     */
    private fun resolve(forCallback: LocationCallback, result: LocationStartResult) {
        val outcome: LocationStartTracker.CompleteResult
        synchronized(lock) {
            outcome = tracker.complete(forCallback, result)
            val isStale = outcome is LocationStartTracker.CompleteResult.Stale
            if (isStale || result !is LocationStartResult.Started) {
                fusedClient.removeLocationUpdates(forCallback)
                if (activeCallback === forCallback) {
                    activeCallback = null
                }
            }
        }
        (outcome as? LocationStartTracker.CompleteResult.Deliver)?.onResult?.invoke(result)
    }

    override fun stop() {
        val onResult: ((LocationStartResult) -> Unit)?
        synchronized(lock) {
            val current = activeCallback
            onResult = tracker.stop()
            if (current != null) {
                fusedClient.removeLocationUpdates(current)
                activeCallback = null
            }
        }
        onResult?.invoke(LocationStartResult.Cancelled)
    }
}
