package hr.sonicpulse.app.data.datastore

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Both operations are best-effort against DataStore's own [IOException] (only, per §8 — coroutine
 * cancellation and any other [Throwable] still propagate): a failed read is indistinguishable
 * from "never requested" (the same ambiguity this history exists to resolve for a genuinely fresh
 * install, so it is a safe default), and a failed write simply means this request won't be
 * remembered next time — neither may crash the permission flow that depends on this class.
 */
@Singleton
class DefaultPermissionRequestHistory @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : PermissionRequestHistory {

    override suspend fun hasRequestedBefore(permission: String): Boolean =
        try {
            dataStore.data.first()[keyFor(permission)] ?: false
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read permission request history; treating as not yet requested")
            false
        }

    override suspend fun markRequested(permission: String) {
        try {
            dataStore.edit { it[keyFor(permission)] = true }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to persist permission request history")
        }
    }

    private fun keyFor(permission: String) = booleanPreferencesKey("requested_permission_$permission")

    private companion object {
        const val TAG = "PermissionRequestHistory"
    }
}
