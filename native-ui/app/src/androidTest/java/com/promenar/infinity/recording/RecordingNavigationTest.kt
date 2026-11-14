package com.promenar.infinity.recording

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Lifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class RecordingNavigationTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test
    fun toolbarAndSystemBackReturnToRecordingHistory() {
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val store = RecordingStore(app)
        val session = store.newSession("Recording navigation fixture").copy(
            phase = RecordingPhase.Finished,
            audioBytes = 32_000,
            segments = listOf(RecordingSegment(0, 1_000, "Recording fixture transcript")),
            mode = RecordingMode.Raw,
            documentHash = "fixture",
        )
        runBlocking { store.write(session) }
        try {
            ActivityScenario.launch<RecordingActivity>(recordingIntent(app)).use { scenario ->
                rule.onNodeWithText(session.title, substring = true).performClick()
                rule.onNodeWithText("Recording fixture transcript").assertIsDisplayed()
                scenario.recreate()
                rule.onNodeWithText("Recording fixture transcript").assertIsDisplayed()
                rule.onNodeWithContentDescription(app.getString(R.string.common_cd_back)).performClick()
                rule.onNodeWithText(session.title).assertIsDisplayed()
                rule.onNodeWithText("Recording fixture transcript").assertDoesNotExist()
                rule.onNodeWithText(session.title, substring = true).performClick()
                rule.onNodeWithText("Recording fixture transcript").assertIsDisplayed()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                rule.onNodeWithText(session.title).assertIsDisplayed()
                rule.onNodeWithText("Recording fixture transcript").assertDoesNotExist()
            }
            val directIntent = recordingIntent(app).putExtra("viewSession", session.id)
            ActivityScenario.launch<RecordingActivity>(directIntent).use { scenario ->
                rule.onNodeWithText("Recording fixture transcript").assertIsDisplayed()
                rule.onNodeWithContentDescription(app.getString(R.string.common_cd_back)).performClick()
                rule.waitUntil { scenario.state == Lifecycle.State.DESTROYED }
            }
            val missingIntent = recordingIntent(app).putExtra("viewSession", "missing-session")
            ActivityScenario.launch<RecordingActivity>(missingIntent).use { scenario ->
                rule.onNodeWithText(session.title, substring = true).performClick()
                rule.onNodeWithText("Recording fixture transcript").assertIsDisplayed()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                rule.onNodeWithText(session.title).assertIsDisplayed()
                rule.onNodeWithText("Recording fixture transcript").assertDoesNotExist()
            }
        } finally {
            runBlocking { store.delete(session.id) }
        }
    }
}
