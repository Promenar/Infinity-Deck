package com.promenar.infinity.data.mediagen.comfy

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.promenar.infinity.data.backup.AndroidTransactionalBackupPreferenceStore
import com.promenar.infinity.data.backup.PreferenceValueType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ComfyPersistenceTest {
    @Test fun reopensConfigurationAndIncludesItInRealBackupPreferenceSnapshot() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val f = ComfyFixture(); f.prepare()
        val storage = PreferenceComfyConfigurationStorage(preferences)
        storage.write(f.storage.read())
        assertEquals(f.storage.read(), PreferenceComfyConfigurationStorage(preferences).read())
        assertEquals(f.storage.read(), storage.observe().first())
        val snapshot = AndroidTransactionalBackupPreferenceStore(context).snapshot(16L * 1024 * 1024)
        val entry = snapshot.entries.single { it.key == COMFY_CONFIGURATION_KEY }
        assertEquals("settings", entry.namespace)
        assertEquals(PreferenceValueType.STRING, entry.type)
        assertEquals(f.storage.read(), ComfyConfigurationCodec.decode(entry.value))
    }
}
