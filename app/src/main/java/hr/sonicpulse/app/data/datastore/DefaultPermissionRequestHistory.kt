package hr.sonicpulse.app.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class DefaultPermissionRequestHistory @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : PermissionRequestHistory {

    override suspend fun hasRequestedBefore(permission: String): Boolean =
        dataStore.data.first()[keyFor(permission)] ?: false

    override suspend fun markRequested(permission: String) {
        dataStore.edit { it[keyFor(permission)] = true }
    }

    private fun keyFor(permission: String) = booleanPreferencesKey("requested_permission_$permission")
}
