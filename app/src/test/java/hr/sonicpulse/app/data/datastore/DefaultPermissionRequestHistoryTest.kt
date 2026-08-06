package hr.sonicpulse.app.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultPermissionRequestHistoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newDataStore(fileName: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { tempFolder.newFile(fileName) })

    /** A [DataStore] whose every operation fails with [error] — models a genuinely unavailable
     * backing file (permissions, disk full, corruption) without needing a mocking framework. */
    private class FailingDataStore(private val error: IOException) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
    }

    @Test
    fun `a permission never requested before returns false`() = runTest {
        val history = DefaultPermissionRequestHistory(newDataStore("a.preferences_pb"))

        assertFalse(history.hasRequestedBefore("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun `markRequested makes hasRequestedBefore return true for that permission`() = runTest {
        val history = DefaultPermissionRequestHistory(newDataStore("b.preferences_pb"))

        history.markRequested("android.permission.RECORD_AUDIO")

        assertTrue(history.hasRequestedBefore("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun `different permissions are tracked independently`() = runTest {
        val history = DefaultPermissionRequestHistory(newDataStore("c.preferences_pb"))

        history.markRequested("android.permission.RECORD_AUDIO")

        assertFalse(history.hasRequestedBefore("android.permission.ACCESS_FINE_LOCATION"))
    }

    @Test
    fun `a read failure is treated as not previously requested, not a crash`() = runTest {
        val history = DefaultPermissionRequestHistory(FailingDataStore(IOException("disk unavailable")))

        val result = history.hasRequestedBefore("android.permission.RECORD_AUDIO")

        assertFalse(result)
    }

    @Test
    fun `a write failure does not throw`() = runTest {
        val history = DefaultPermissionRequestHistory(FailingDataStore(IOException("disk unavailable")))

        history.markRequested("android.permission.RECORD_AUDIO") // must not throw
    }
}
