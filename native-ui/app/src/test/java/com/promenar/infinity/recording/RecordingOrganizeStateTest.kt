package com.promenar.infinity.recording

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RecordingOrganizeStateTest {
    private val cloud = RecordingOrganizeEngine(true, "提供商", "模型")
    private val local = RecordingOrganizeEngine(false, model = "本地模型")
    private val source = RecordingOrganizeSource(true, "旧提供商", "旧模型", 100)
    private val segments = listOf(RecordingSegment(0, 1000, "第一段"), RecordingSegment(2000, 5000, "第二段"))
    private val session = RecordingSession("id", 1, "标题", phase = RecordingPhase.Finished, segments = segments)
    private val screen = RecordingScreenState(cloudLabel = "提供商 · 模型", localLabel = "本地模型")
    private val failure = RecordingOrganizeFailure(RecordingOrganizeErrorKind.Timeout, cloud, 1000, 500, 10_001)
    private val run = RecordingOrganizeRun("id", cloud, RecordingOrganizeStage.Points, 2, 4, 1000)

    private fun state(
        item: RecordingSession = session,
        screen: RecordingScreenState = this.screen,
        route: RecordingRoute = RecordingRoute.Cloud,
        speaker: RecordingAsrModelState = RecordingAsrModelState.Installed,
        now: Long = 5000,
    ) = recordingOrganizeState(item, screen, route, speaker, now)

    @Test fun parsesCloudAndLocalLabelsWithoutInventingAProvider() {
        assertEquals(cloud, parseRecordingOrganizeEngine(true, " 提供商 · 模型 "))
        assertEquals(RecordingOrganizeEngine(true, model = "模型"), parseRecordingOrganizeEngine(true, " 模型 "))
        assertEquals(RecordingOrganizeEngine(false, model = "提供商 · 模型"), parseRecordingOrganizeEngine(false, " 提供商 · 模型 "))
        for (label in listOf(" · 模型", "提供商 · ", " · ")) {
            assertEquals(RecordingOrganizeEngine(true, model = label.trim()), parseRecordingOrganizeEngine(true, label))
        }
        assertNull(parseRecordingOrganizeEngine(true, null))
        assertNull(parseRecordingOrganizeEngine(false, "  "))
    }

    @Test fun countsBoundedBatchesAndSpeakerBoundaries() {
        val input = List(7) { RecordingSegment(it * 1000L, (it + 1) * 1000L, "字".repeat(400), it % 2) }
        assertEquals(1, countRecordingPointBatches(input, true, false))
        assertEquals(7, countRecordingPointBatches(input, false, false))
        assertEquals(2, countRecordingPointBatches(input, true, true))
        assertEquals(4, countRecordingPointBatches(input + input, true, true))
        assertEquals(2, countRecordingPointBatches(input + input.first(), true, false))
        assertEquals(0, countRecordingPointBatches(emptyList(), false, true))
        assertEquals(1, countRecordingPointBatches(listOf(input.first().copy(text = "字".repeat(5000))), false, false))

        val points = List(4) { RecordingSummaryPoint(null, "点".repeat(10_000), listOf(it * 1000L)) }
        assertEquals(2 to 0, countRecordingOverviewBatches(points, overviewCursor = 0, cloud = true))
        assertEquals(2 to 1, countRecordingOverviewBatches(points, overviewCursor = 2, cloud = true))
        assertEquals(1 to 0, countRecordingOverviewBatches(listOf(points.first()), overviewCursor = 0, cloud = false))
        assertEquals(2 to 1, countRecordingOverviewBatches(listOf(points[0], points[1]), overviewCursor = 1, cloud = false))
    }

    @Test fun activeRecordingRoutesTakePriorityOverFailuresAndRuns() {
        val active = screen.copy(activeId = "id", organizeRun = run, durationMs = 9000)
        val item = session.copy(lastOrganizeFailure = failure)
        assertEquals(RecordingOrganizeState.RawOnly(true), state(item, active, RecordingRoute.Raw))
        assertEquals(RecordingOrganizeState.AwaitingConsent(cloud, true, true), state(item, active, RecordingRoute.Consent))
        assertEquals(RecordingOrganizeState.Unavailable(true), state(item, active, RecordingRoute.Unavailable))
        assertEquals(RecordingOrganizeState.LiveAfterRecording(cloud), state(item.copy(liveOrganization = false), active))
        assertEquals(RecordingOrganizeState.LiveAfterRecording(local), state(item.copy(liveOrganization = false), active, RecordingRoute.Local))
    }

    @Test fun liveDraftUsesRunCoverageOrLastSavedDraftAndClampsLag() {
        val active = screen.copy(activeId = "id", durationMs = 9000)
        val draft = run.copy(stage = RecordingOrganizeStage.LiveDraft, engine = local, coveredAudioMs = 5000)
        assertEquals(RecordingOrganizeState.LiveDraft(local, 5000, 4, true), state(screen = active.copy(organizeRun = draft)))
        val saved = session.copy(draftBatches = listOf(RecordingDraftBatch(0, 1, "草稿")))
        assertEquals(RecordingOrganizeState.LiveDraft(cloud, 1000, 8, false), state(saved, active))
        assertEquals(RecordingOrganizeState.LiveDraft(cloud, 1000, 8, true), state(saved, active.copy(organizeRun = run)))
        assertEquals(RecordingOrganizeState.LiveDraft(cloud, 0, 9, false), state(screen = active))
        assertEquals(RecordingOrganizeState.LiveDraft(cloud, 0, 9, false), state(saved.copy(draftBatches = listOf(RecordingDraftBatch(0, 99, "坏游标"))), active))
        assertEquals(RecordingOrganizeState.LiveDraft(local, 5000, 0, true), state(screen = active.copy(durationMs = 1000, organizeRun = draft)))
        assertEquals(RecordingOrganizeState.LiveDraft(cloud, 0, 9, false), state(screen = active.copy(organizeRun = draft.copy(sessionId = "other"))))
    }

    @Test fun finalRunsExposeElapsedProgressAndPreviousProvenance() {
        val running = screen.copy(organizeRun = run)
        assertEquals(RecordingOrganizeState.RunningFirstTime(run, 4, .5f), state(screen = running))
        val old = session.copy(organized = "旧稿", organizedSource = source, lastOrganizeFailure = failure)
        assertEquals(RecordingOrganizeState.RunningRerun(run, 4, .5f, source), state(old, running, RecordingRoute.Raw))
        for ((input, expected) in listOf(run.copy(step = -1) to 0f, run.copy(step = 9, totalSteps = 0) to 1f)) {
            assertEquals(RecordingOrganizeState.RunningFirstTime(input, 0, expected), state(screen = running.copy(organizeRun = input), now = 500))
        }
        val undated = run.copy(startedAtEpochMs = 0)
        assertEquals(RecordingOrganizeState.RunningFirstTime(undated, 0, .5f), state(screen = running.copy(organizeRun = undated)))
    }

    @Test fun legacyOrganizingIdBuildsFallbackRunForBothFirstRunAndRerun() {
        val legacy = screen.copy(organizingId = "id")
        val fallback = RecordingOrganizeRun("id", cloud, RecordingOrganizeStage.Points)
        assertEquals(RecordingOrganizeState.RunningFirstTime(fallback, 0, 1f), state(screen = legacy))
        assertEquals(RecordingOrganizeState.RunningRerun(fallback, 0, 1f, null), state(session.copy(organized = "旧稿"), legacy))
        val unknown = fallback.copy(engine = RecordingOrganizeEngine(true, model = ""))
        assertEquals(RecordingOrganizeState.RunningFirstTime(unknown, 0, 1f), state(screen = legacy.copy(cloudLabel = null)))
    }

    @Test fun rawAndConsentPreserveCompletedOldWorkBeforeShowingFailures() {
        val old = session.copy(organized = "旧稿", organizedThrough = 2, organizedSource = source, lastOrganizeFailure = failure)
        assertEquals(RecordingOrganizeState.Completed(source, false), state(old, route = RecordingRoute.Raw))
        assertEquals(RecordingOrganizeState.Completed(source, false), state(old, route = RecordingRoute.Consent))
        assertEquals(RecordingOrganizeState.RawOnly(false), state(old.copy(summaryPending = true), route = RecordingRoute.Raw))
        assertEquals(RecordingOrganizeState.AwaitingConsent(cloud, false, true), state(old.copy(summaryPending = true), route = RecordingRoute.Consent))
        assertEquals(RecordingOrganizeState.AwaitingConsent(cloud, false, true), state(route = RecordingRoute.Consent))
        assertEquals(RecordingOrganizeState.AwaitingConsent(cloud, false, false), state(screen = screen.copy(localLabel = null), route = RecordingRoute.Consent))
        assertEquals(RecordingOrganizeState.AwaitingConsent(cloud, false, true), state(session.copy(segments = emptyList(), summaryPending = true), route = RecordingRoute.Consent))
        assertEquals(RecordingOrganizeState.IdleEmpty, state(session.copy(segments = emptyList()), route = RecordingRoute.Consent))
    }

    @Test fun failuresKeepOldSourceAndUseActualFailedEngineAndRoundedRetryTime() {
        val failed = session.copy(lastOrganizeFailure = failure)
        assertEquals(RecordingOrganizeState.FailedEmpty(failure, cloud, 6, true), state(failed))
        assertEquals(RecordingOrganizeState.FailedKeptOld(failure, cloud, 6, source), state(failed.copy(organized = "旧稿", organizedSource = source)))
        val unknown = failure.copy(engine = null, retryAtEpochMs = null)
        assertEquals(RecordingOrganizeState.FailedEmpty(unknown, local, null, true), state(failed.copy(lastOrganizeFailure = unknown), route = RecordingRoute.Local))
        assertEquals(RecordingOrganizeState.FailedEmpty(failure, cloud, 0, false), state(failed, screen.copy(localLabel = null), now = 20_000))
        assertEquals(RecordingOrganizeState.FailedEmpty(failure, cloud, 1, true), state(failed, now = 10_000))
        assertEquals(RecordingOrganizeState.FailedEmpty(failure, cloud, 0, true), state(failed, now = 10_001))
    }

    @Test fun unavailableOnlyBlocksWorkStillNeeded() {
        assertEquals(RecordingOrganizeState.Unavailable(false), state(route = RecordingRoute.Unavailable))
        assertEquals(RecordingOrganizeState.Unavailable(false), state(session.copy(segments = emptyList(), summaryPending = true), route = RecordingRoute.Unavailable))
        assertEquals(RecordingOrganizeState.Completed(null, false), state(session.copy(organized = "旧稿", organizedThrough = 2), route = RecordingRoute.Unavailable))
        assertEquals(RecordingOrganizeState.Completed(null, false), state(session.copy(organized = "手改", manualEdit = true, organizedThrough = 2), route = RecordingRoute.Unavailable))
        assertEquals(RecordingOrganizeState.StaleNewSegments(null, 2, 5000, false), state(session.copy(organized = "手改", manualEdit = true), route = RecordingRoute.Unavailable))
        assertEquals(RecordingOrganizeState.IdleEmpty, state(session.copy(segments = emptyList()), route = RecordingRoute.Unavailable))
    }

    @Test fun cancellationAndNewSegmentsReportCountAndDuration() {
        val old = session.copy(organized = "旧稿", organizedThrough = 1)
        assertEquals(RecordingOrganizeState.StaleNewSegments(cloud, 1, 3000, false), state(old))
        assertEquals(RecordingOrganizeState.StaleNewSegments(cloud, 1, 3000, true), state(old, screen.copy(organizeCanceledIds = setOf("id"))))
        assertEquals(RecordingOrganizeState.StaleNewSegments(cloud, 2, 5000, true), state(screen = screen.copy(organizeCanceledIds = setOf("id"))))
        assertEquals(RecordingOrganizeState.StaleNewSegments(cloud, 0, 0, true), state(session.copy(summaryPending = true, organizedThrough = 99), screen.copy(organizeCanceledIds = setOf("id"))))
        assertEquals(RecordingOrganizeState.StaleNewSegments(cloud, 1, 3000, false), state(old.copy(manualEdit = true)))
    }

    @Test fun speakerWaitPrecedesStalenessUnlessCanceledAndMissingModelDoesNotBlock() {
        val old = session.copy(organized = "旧稿", organizedThrough = 1, distinguishSpeakers = true, audioBytes = 32000)
        assertEquals(RecordingOrganizeState.WaitingSpeakers(cloud, 0f), state(old))
        assertEquals(RecordingOrganizeState.WaitingSpeakers(cloud, .4f), state(old, screen.copy(speakerAnalysisId = "id", speakerProgress = .4f)))
        assertEquals(RecordingOrganizeState.StaleNewSegments(cloud, 1, 3000, false), state(old, speaker = RecordingAsrModelState.Missing))
        assertEquals(RecordingOrganizeState.StaleNewSegments(cloud, 1, 3000, false), state(old.copy(speakerAnalyzedBytes = 32000)))
        assertEquals(RecordingOrganizeState.StaleNewSegments(cloud, 1, 3000, true), state(old, screen.copy(organizeCanceledIds = setOf("id"))))
    }

    @Test fun localQueueCloudQueueCompletedAndIdleAreDistinct() {
        assertEquals(RecordingOrganizeState.WaitingLocal(local), state(route = RecordingRoute.Local))
        assertEquals(RecordingOrganizeState.WaitingCloud(cloud), state())
        assertEquals(RecordingOrganizeState.IdleEmpty, state(session.copy(segments = emptyList())))
        val old = session.copy(organized = "旧稿", organizedThrough = 2, organizedSource = source)
        assertEquals(RecordingOrganizeState.Completed(source, true), state(old, screen.copy(organizeUpdatedId = "id")))
        assertEquals(RecordingOrganizeState.WaitingCloud(cloud), state(old.copy(organizedSource = null, summaryPending = true)))
        assertEquals(RecordingOrganizeState.WaitingLocal(local), state(old.copy(summaryPending = true), route = RecordingRoute.Local))
        assertEquals(RecordingOrganizeState.IdleEmpty, state(session.copy(manualEdit = true)))
    }

    @Test fun cloudWaitReportsPreprocessingAndOtherSessionsQueueForFirstRunAndRerun() {
        val old = session.copy(organized = "旧稿", organizedThrough = segments.size, organizedSource = source, summaryPending = true)
        for (item in listOf(session, old, old.copy(manualEdit = true))) {
            assertEquals(RecordingOrganizeState.WaitingCloud(cloud), state(item))
            assertEquals(RecordingOrganizeState.WaitingCloud(cloud, waitingPreprocess = true), state(item, screen.copy(refiningId = "id")))
            assertEquals(RecordingOrganizeState.WaitingCloud(cloud, waitingPreprocess = true), state(item, screen.copy(recognizingId = "id")))
            assertEquals(RecordingOrganizeState.WaitingCloud(cloud), state(item, screen.copy(refiningId = "other", recognizingId = "other")))
            assertEquals(RecordingOrganizeState.WaitingCloud(cloud, queuedBehindOther = true), state(item, screen.copy(organizingId = "other")))
            assertEquals(RecordingOrganizeState.WaitingCloud(cloud, queuedBehindOther = true), state(item, screen.copy(organizeRun = run.copy(sessionId = "other"))))
            assertEquals(RecordingOrganizeState.WaitingCloud(cloud, waitingPreprocess = true, queuedBehindOther = true),
                state(item, screen.copy(refiningId = "id", organizeRun = run.copy(sessionId = "other"))))
        }
        assertEquals(RecordingOrganizeState.WaitingCloud(null), state(screen = screen.copy(cloudLabel = null)))
    }

    @Test fun recognitionRewriteClearsOldFailureAndPreservingTheDraftKeepsIt() {
        val failed = session.copy(lastOrganizeFailure = failure)
        assertNull(recordingRecognitionResult(failed, segments, rewriteOrganized = true).lastOrganizeFailure)
        assertEquals(failure, recordingRecognitionResult(failed, segments, rewriteOrganized = false).lastOrganizeFailure)
    }

    @Test fun historyBadgesPrioritizeCurrentWorkAndHideRawFailures() {
        val failed = session.copy(lastOrganizeFailure = failure)
        assertEquals(RecordingOrganizeHistoryBadge.Organizing, recordingOrganizeHistoryStatus(failed, screen.copy(organizeRun = run)))
        assertEquals(RecordingOrganizeHistoryBadge.Organizing, recordingOrganizeHistoryStatus(failed, screen.copy(organizingId = "id")))
        assertEquals(RecordingOrganizeHistoryBadge.Failed, recordingOrganizeHistoryStatus(failed, screen))
        assertNull(recordingOrganizeHistoryStatus(failed.copy(mode = RecordingMode.Raw), screen))
        assertNull(recordingOrganizeHistoryStatus(session, screen.copy(organizingId = "other", organizeRun = run.copy(sessionId = "other"))))
    }

    @Test fun legacyAndNewTranscriptsRoundTripWithSafeDefaults() {
        val legacy = """{"id":"id","createdAt":1,"title":"标题"}"""
        assertEquals(RecordingSession("id", 1, "标题"), Json.decodeFromString<RecordingSession>(legacy))
        for (savedSource in listOf(null, source)) for (savedFailure in listOf(null, failure)) {
            val item = session.copy(organizedSource = savedSource, lastOrganizeFailure = savedFailure)
            assertEquals(item, Json.decodeFromString<RecordingSession>(Json.encodeToString(item)))
        }
        val defaults = """{"id":"id","createdAt":1,"title":"标题","organizedSource":{"cloud":false,"model":"本地","completedAt":1},"lastOrganizeFailure":{"kind":"Timeout","failedAt":2}}"""
        val decoded = Json.decodeFromString<RecordingSession>(defaults)
        assertEquals(RecordingOrganizeSource(false, model = "本地", completedAt = 1), decoded.organizedSource)
        assertEquals(RecordingOrganizeFailure(RecordingOrganizeErrorKind.Timeout, failedAt = 2), decoded.lastOrganizeFailure)
        assertEquals(RecordingOrganizeEngine(false, model = "本地"), decoded.organizedSource?.engine)
        assertFalse(Json.encodeToString(decoded).contains("\"engine\""))
    }
}
