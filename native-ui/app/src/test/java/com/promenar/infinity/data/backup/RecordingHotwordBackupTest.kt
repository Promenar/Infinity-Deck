package com.promenar.infinity.data.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.promenar.infinity.recording.RecordingHotword
import com.promenar.infinity.recording.RecordingHotwordStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class RecordingHotwordBackupTest {
    @Test fun libraryRoundTripsThroughPreferenceTransactionAndRollbackRestoresOriginal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, RecordingHotwordStore.FILE_NAME)
        val ledger = File(context.noBackupFilesDir, "recording-backup-${System.nanoTime()}.json")
        val store = RecordingHotwordStore(file)
        val original = listOf(RecordingHotword("one", "llama.cpp", "Lama CPP", false), RecordingHotword("two", "张永"))
        try {
            store.write(original)
            val adapter = AndroidTransactionalBackupPreferenceStore(context, ledger)
            val snapshot = adapter.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
            assertTrue(snapshot.entries.any { it.namespace == "recording_hotwords" && it.key == "library" })
            assertFalse(snapshot.entries.any { it.namespace.contains("asr-models") })
            val codec = DefaultBackupPackageCodec()
            val packed = codec.encode(BackupSnapshot(
                databaseSchemaVersion = DATABASE_BACKUP_SCHEMA_VERSION,
                appVersion = "test", preferences = Json.encodeToString(snapshot).toByteArray(),
            ), BackupOptions(setOf(BackupContent.PREFERENCES)))
            val roundTrip = codec.decode(packed, null).use { restored ->
                assertTrue(restored.manifest.entries.none { it.path.contains("asr-models") })
                Json.decodeFromString<BackupPreferenceSnapshot>(restored.preferences.decodeToString())
            }
            store.write(emptyList())
            val before = adapter.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
            adapter.prepare("hotwords-round-trip", before, roundTrip)
            adapter.commitPrepared("hotwords-round-trip")
            assertEquals(original, store.read())
            // 新适配器依据持久化事务反向恢复。
            AndroidTransactionalBackupPreferenceStore(context, ledger).rollbackPrepared("hotwords-round-trip")
            assertTrue(store.read().isEmpty())
            assertFalse(File(context.filesDir, "${RecordingHotwordStore.FILE_NAME}.new").exists())
        } finally { file.delete(); ledger.delete() }
    }

    @Test fun invalidHotwordBackupIsRejectedBeforeWriting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledger = File(context.noBackupFilesDir, "recording-backup-invalid-${System.nanoTime()}.json")
        try {
            val adapter = AndroidTransactionalBackupPreferenceStore(context, ledger)
            val before = adapter.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
            val invalid = before.copy(entries = before.entries.filterNot { it.namespace == "recording_hotwords" } +
                BackupPreferenceEntry("recording_hotwords", "library", """[{"id":"one","text":""}]""", PreferenceValueType.STRING))
            try { adapter.prepare("invalid-hotwords", before, invalid); fail("应拒绝空写法") } catch (_: BackupValidationException) { }
        } finally { ledger.delete() }
    }
}
