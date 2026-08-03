package hr.sonicpulse.app.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first

@Singleton
class DefaultInstallationIdRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : InstallationIdRepository {

    private val mutex = Mutex()

    override suspend fun getOrCreate(): String = mutex.withLock {
        dataStore.data.first()[INSTALLATION_ID_KEY]
            ?: UUID.randomUUID().toString().also { generated ->
                dataStore.edit { it[INSTALLATION_ID_KEY] = generated }
            }
    }

    private companion object {
        val INSTALLATION_ID_KEY = stringPreferencesKey("installation_id")
    }
}
