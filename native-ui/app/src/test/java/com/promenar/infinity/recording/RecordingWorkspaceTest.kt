package com.promenar.infinity.recording

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.RandomAccessFile
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class RecordingWorkspaceTest {
    @Test fun discardJournalRestoresOnlyTheAppendAfterProcessDeath() = runBlocking {
        val root = Files.createTempDirectory("recording-append-").toFile()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wrapped = mockk<Context>()
        every { wrapped.filesDir } returns root
        every { wrapped.getSharedPreferences(any(), any()) } returns context.getSharedPreferences(root.name, 0)
        val store = RecordingStore(wrapped)
        val original = store.newSession("原录音").copy(
            phase = RecordingPhase.Finished, audioBytes = 64,
            segments = listOf(RecordingSegment(0, 2, "原逐字稿")), organized = "手工稿", manualEdit = true,
            documentId = "原文档", documentHash = "原摘要",
        )
        try {
            store.write(original)
            store.beginCapture(original, original.copy(phase = RecordingPhase.Recording))
            RandomAccessFile(store.audio(original.id), "rw").use { file ->
                RecordingStore.updateWavHeader(file, 128)
                file.seek(44)
                file.write(ByteArray(64) { 7 })
                file.write(ByteArray(64) { 9 })
            }
            store.write(original.copy(phase = RecordingPhase.Paused, audioBytes = 128,
                segments = original.segments + RecordingSegment(2, 4, "追加")))
            store.markDiscard(original.id)
            assertEquals(original, RecordingStore(wrapped).restore().single())
            val bytes = store.audio(original.id).readBytes()
            assertEquals(108, bytes.size)
            assertTrue(bytes.drop(44).all { it == 7.toByte() })
            assertEquals(64, bytes[40].toInt())
            assertEquals(original, RecordingStore(wrapped).restore().single())
        } finally { root.deleteRecursively() }
    }

    @Test fun explicitOutputLanguageDoesNotChangeSourceSpeakerOrTimestamp() {
        val segments = listOf(RecordingSegment(0, 3000, "中英 mixed", 8), RecordingSegment(3000, 6000, "待确认", null))
        val item = RecordingSession("id", 0, "会议", segments = segments, distinguishSpeakers = true,
            speakers = listOf(RecordingSpeaker(8, "林薇")), outputLanguage = RecordingLanguage.English)
        val result = renderRecordingSummary(item, "The decision is pending.", listOf(
            RecordingSummaryPoint(8, "Asked for screenshots.", listOf(0)),
            RecordingSummaryPoint(null, "A date is still open.", listOf(3000)),
        ))
        assertTrue(result.startsWith("### Summary\n\nThe decision is pending."))
        assertTrue(result.contains("**林薇**"))
        assertTrue(result.contains("### Discussion points"))
        assertTrue(result.contains("[00:03](recording://seek/3000)"))
        assertEquals("中英 mixed", item.segments.first().text)
    }
    @Test fun scriptCheckAllowsJapaneseKanjiAndRejectsEnglishWithChineseName() {
        assertFalse(recordingLanguageMatches(RecordingLanguage.Chinese, "The release is Friday. 王"))
        assertTrue(recordingLanguageMatches(RecordingLanguage.Japanese, "進捗確認、予算承認。"))
        assertTrue(recordingLanguageMatches(RecordingLanguage.English, "The release is Friday. 王"))
    }
}
