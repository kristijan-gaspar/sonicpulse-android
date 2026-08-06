package hr.sonicpulse.app.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultInstallationIdRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newDataStore(fileName: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile(fileName) }
        )

    /** A read that always fails — models a genuinely unavailable backing file. */
    private class FailingReadDataStore(private val error: IOException) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
    }

    /** Reads succeed (no id stored yet) but persisting a newly generated one fails. */
    private class FailingWriteDataStore(private val error: IOException) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
    }

    @Test
    fun `generates a valid UUID when none is stored yet`() = runTest {
        val repository = DefaultInstallationIdRepository(newDataStore("a.preferences_pb"))

        val id = repository.getOrCreate()

        assertNotNull(UUID.fromString(id))
    }

    @Test
    fun `returns the same id on repeated calls`() = runTest {
        val repository = DefaultInstallationIdRepository(newDataStore("b.preferences_pb"))

        val first = repository.getOrCreate()
        val second = repository.getOrCreate()

        assertEquals(first, second)
    }

    @Test
    fun `id survives across a fresh repository instance backed by the same store`() = runTest {
        val dataStore = newDataStore("c.preferences_pb")
        val first = DefaultInstallationIdRepository(dataStore).getOrCreate()

        val second = DefaultInstallationIdRepository(dataStore).getOrCreate()

        assertEquals(first, second)
    }

    @Test
    fun `a read failure propagates as IOException instead of falling back to a freshly generated id`() = runTest {
        val repository = DefaultInstallationIdRepository(FailingReadDataStore(IOException("disk unavailable")))

        // DetectionSubmitter relies on exactly this contract to classify the failure as
        // LocalStorageError — getOrCreate() must never silently invent an id on a read failure.
        var thrown: IOException? = null
        try {
            repository.getOrCreate()
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull(thrown)
    }

    @Test
    fun `a write failure while persisting a newly generated id propagates as IOException`() = runTest {
        val repository = DefaultInstallationIdRepository(FailingWriteDataStore(IOException("disk full")))

        var thrown: IOException? = null
        try {
            repository.getOrCreate()
        } catch (e: IOException) {
            thrown = e
        }
        assertNotNull(thrown)
    }
}
