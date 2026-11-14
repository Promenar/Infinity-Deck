package com.promenar.infinity.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import org.junit.Test

class RecordingPolicyTest {
    @Test fun cloudRequiresConsentForTheCurrentRoute() {
        assertEquals(RecordingRoute.Consent, chooseRecordingRoute(RecordingMode.Auto, "provider:a", null, true))
        assertEquals(RecordingRoute.Consent, chooseRecordingRoute(RecordingMode.Auto, "provider:b", "provider:a", true))
        assertEquals(RecordingRoute.Cloud, chooseRecordingRoute(RecordingMode.Auto, "provider:a", "provider:a", true))
    }

    @Test fun localFallbackAndManualLocalNeverUseCloud() {
        assertEquals(RecordingRoute.Local, chooseRecordingRoute(RecordingMode.Auto, null, null, true))
        assertEquals(RecordingRoute.Local, chooseRecordingRoute(RecordingMode.Local, "provider:a", "provider:a", true))
        assertEquals(RecordingRoute.Unavailable, chooseRecordingRoute(RecordingMode.Local, "provider:a", "provider:a", false))
        assertEquals(RecordingRoute.Raw, chooseRecordingRoute(RecordingMode.Raw, "provider:a", "provider:a", true))
    }

    @Test fun longRecordingsUseBoundedBatchesWithContextAndNoGaps() {
        val segments = List(5) { RecordingSegment(it * 1000L, (it + 1) * 1000L, "abc") }
        assertEquals(0..1, nextRecordingBatch(segments, 0, 6))
        assertEquals(2..3, nextRecordingBatch(segments, 2, 6))
        assertEquals(4..4, nextRecordingBatch(segments, 4, 6))
        assertNull(nextRecordingBatch(segments, 5, 6))
        assertEquals("abc", recordingContext(segments, 2, 4))
        assertEquals("abc\na", recordingFollowing(segments, 2, 5))
    }

    @Test fun firstLocalDraftStartsAtTheFirstConfirmedSegmentWithoutWaitingForSilence() {
        val segments = List(4) { RecordingSegment(it * 1000L, (it + 1) * 1000L, "beta") }
        val recording = RecordingSession(id = "test", createdAt = 0, title = "对测", segments = segments)
        assertNull(nextLiveRecordingDraft(recording.copy(segments = emptyList()), RecordingRoute.Local, quiet = false, allowLocalOverlap = true))
        assertNull(nextLiveRecordingDraft(recording.copy(liveOrganization = false), RecordingRoute.Local, quiet = true, allowLocalOverlap = true))
        assertNull(nextLiveRecordingDraft(recording, RecordingRoute.Consent, quiet = true, allowLocalOverlap = true))
        assertEquals(0..0, nextLiveRecordingDraft(recording, RecordingRoute.Local, quiet = false, allowLocalOverlap = true))
        assertNull(nextLiveRecordingDraft(recording, RecordingRoute.Local, quiet = false, allowLocalOverlap = false))
        assertEquals(0..0, nextLiveRecordingDraft(recording, RecordingRoute.Local, quiet = true, allowLocalOverlap = false))
        assertEquals(0..3, nextLiveRecordingDraft(recording, RecordingRoute.Cloud, quiet = false, allowLocalOverlap = false))

        val next = recording.copy(draftBatches = listOf(RecordingDraftBatch(0, 1, "beta")))
        assertNull(nextLiveRecordingDraft(next, RecordingRoute.Local, quiet = false, allowLocalOverlap = true))
        assertEquals(1..3, nextLiveRecordingDraft(next, RecordingRoute.Local, quiet = true, allowLocalOverlap = false))
    }

    @Test fun relatedContextFindsTechnicalTermsBeyondAdjacentSegments() {
        val segments = listOf("Infinity Deck 的入口", "天气", "会议时间", "还有一个事项", "Deck 设置")
            .mapIndexed { index, text -> RecordingSegment(index * 1000L, (index + 1) * 1000L, text) }
        assertEquals("Infinity Deck 的入口", recordingRelatedContext(segments, 4..4, "Infinity Deck", 100))
    }

    @Test fun interruptionKeepsProvisionalTextOnlyWhereAudioExists() {
        val session = RecordingSession(id = "id", createdAt = 0, title = "课程",
            segments = listOf(RecordingSegment(0, 1000, "第一段")),
            checkpointText = RecordingSegment(1500, 3000, "第二段"),
        )
        val recovered = recoverRecordingTranscript(session, 2500 * 32L)
        assertEquals(RecordingPhase.Interrupted, recovered.phase)
        assertEquals(RecordingSegment(1500, 2500, "第二段"), recovered.segments.last())
        assertNull(recovered.checkpointText)
        assertEquals(session.segments, recoverRecordingTranscript(session, 1300 * 32L).segments)
    }

    @Test fun deletedDocumentsRemoveOnlyTheirFinishedRecordings() {
        val finished = RecordingSession(id = "finished", createdAt = 0, title = "a", phase = RecordingPhase.Finished)
        val interrupted = RecordingSession(id = "interrupted", createdAt = 0, title = "b", phase = RecordingPhase.Interrupted)
        val active = RecordingSession(id = "active", createdAt = 0, title = "c", phase = RecordingPhase.Interrupted)
        val paused = RecordingSession(id = "paused", createdAt = 0, title = "d", phase = RecordingPhase.Paused)
        val other = RecordingSession(id = "other", createdAt = 0, title = "e", phase = RecordingPhase.Finished)
        val sessions = listOf(finished, interrupted, active, paused, other)
        assertEquals(
            listOf("finished", "interrupted"),
            recordingsRemovedWithDocuments(sessions, listOf("finished", "interrupted", "active", "paused", "note"), "active"),
        )
        assertEquals(emptyList<String>(), recordingsRemovedWithDocuments(sessions, emptyList(), null))
    }

    @Test fun playbackPositionMapsToTheSegmentThatStartedLast() {
        val segments = listOf(
            RecordingSegment(1_000, 5_000, "a"),
            RecordingSegment(5_000, 9_000, "b"),
            RecordingSegment(12_000, 15_000, "c"),
        )
        assertEquals(-1, recordingSegmentAt(emptyList(), 3_000))
        assertEquals(-1, recordingSegmentAt(segments, 999))
        assertEquals(0, recordingSegmentAt(segments, 1_000))
        assertEquals(0, recordingSegmentAt(segments, 4_999))
        assertEquals(1, recordingSegmentAt(segments, 5_000))
        // 两段之间的停顿仍算前一段。
        assertEquals(1, recordingSegmentAt(segments, 10_500))
        assertEquals(2, recordingSegmentAt(segments, 60_000))
    }

    @Test fun recordingDocumentsAreNotEligibleForAutomaticRemoteIndexing() {
        assertFalse(LOCAL_RECORDING_MIME in DocumentReferenceExtractor.SUPPORTED_MIME_TYPES)
    }

    @Test fun exportKeepsSourceAndOrganizedTextSeparate() {
        val markdown = recordingMarkdown(RecordingSession(
            id = "id", createdAt = 0, title = "课程", organized = "Infinity Deck",
            segments = listOf(RecordingSegment(61_000, 63_000, "Infinity Dick")),
        ))
        assertEquals("# 课程\n\n## 整理稿\n\nInfinity Deck\n\n## 原始转写\n\n- 01:01 Infinity Dick", markdown)
    }
}
