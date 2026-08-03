package hr.sonicpulse.app.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.util.UUID
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
}
