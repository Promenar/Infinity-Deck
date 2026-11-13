package com.promenar.infinity.recording

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class RecordingStoreMigrationTest {
    @Test fun migratedJsonIsRewrittenAndWriteFailureDoesNotHideReadableSession() = runBlocking {
        val root = Files.createTempDirectory("recording-migration-").toFile()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wrapped = mockk<Context>()
        every { wrapped.filesDir } returns root
        every { wrapped.getSharedPreferences(any(), any()) } returns context.getSharedPreferences(root.name, 0)
        val id = UUID.randomUUID().toString()
        val file = File(root, "recording-sessions/$id/transcript.json")
        file.parentFile!!.mkdirs()
        val old = """{"id":"$id","createdAt":1,"title":"标题","phase":"Finished","glossary":"张永,Zipformer"}"""
        try {
            file.writeText(old)
            val readOnly = RecordingStore(wrapped) { throw IOException("模拟存储不足") }
            assertEquals(listOf("张永", "Zipformer"), readOnly.restore().single().hotwords)
            assertEquals(old, file.readText())
            val store = RecordingStore(wrapped)
            assertEquals(listOf("张永", "Zipformer"), store.restore().single().hotwords)
            assertFalse(file.readText().contains("glossary"))
            // 当前格式不触发迁移写入。
            var writes = 0
            RecordingStore(wrapped) { writes++ }.restore()
            assertEquals(0, writes)
        } finally { root.deleteRecursively() }
    }
}
