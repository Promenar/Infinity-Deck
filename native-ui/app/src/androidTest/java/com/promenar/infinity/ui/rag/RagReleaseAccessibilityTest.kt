package com.promenar.infinity.ui.rag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.domain.model.Folder
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.MemoryVectorRecord
import com.promenar.infinity.ui.common.FileIndexStatus
import com.promenar.infinity.ui.common.IndexStatusBadge
import com.promenar.infinity.ui.common.KgStatus
import com.promenar.infinity.ui.common.KgStatusIcon
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.status.UiStatusNotice
import com.promenar.infinity.ui.rag.components.IndexingProgressBar
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class RagReleaseAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun indexingProgressBarPublishesOneProgressStateAndLiveRegionWithoutAccessibleDisplayText() {
        val status = "Indexing release documents"
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                IndexingProgressBar(
                    progress = 0.42f,
                    statusText = status,
                    modifier = Modifier.testTag("indexing-progress-contract"),
                )
            }
        }

        assertSingleIndexingSemantics(
            scopeTag = "indexing-progress-contract",
            status = status,
            percentage = "42%",
            liveRegionMode = LiveRegionMode.Polite,
        )
    }

    @Test
    fun indexAndKnowledgeGraphStatusesExposeLocalizedTextAndStateDescriptions() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val fileStatuses = listOf(
            FileIndexStatus.INDEXED to R.string.rag_status_ready,
            FileIndexStatus.INDEXING to R.string.rag_status_indexing,
            FileIndexStatus.NOT_INDEXED to R.string.rag_status_pending,
            FileIndexStatus.FAILED to R.string.rag_status_error,
        )

        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                Column {
                    fileStatuses.forEach { (status, _) ->
                        IndexStatusBadge(
                            status = status,
                            modifier = Modifier.testTag("file-status-${status.name}"),
                        )
                    }
                    KgStatusIcon(
                        status = KgStatus.NOT_STARTED,
                        modifier = Modifier.testTag("kg-status-not-started"),
                    )
                }
            }
        }

        fileStatuses.forEach { (status, labelResource) ->
            val label = resources.getString(labelResource)
            rule.onNodeWithTag("file-status-${status.name}")
                .assertTextEquals(label)
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        label,
                    ),
                )
        }

        val graphDescription = resources.getString(
            R.string.kg_title,
        ) + ": " + resources.getString(R.string.rag_status_pending)
        rule.onNodeWithTag("kg-status-not-started")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    graphDescription,
                ),
            )
    }

    private fun assertSingleIndexingSemantics(
        scopeTag: String,
        status: String,
        percentage: String,
        liveRegionMode: LiveRegionMode,
    ) {
        val scope = hasTestTag(scopeTag) or hasAnyAncestor(hasTestTag(scopeTag))
        rule.onAllNodes(
            scope and SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        rule.onAllNodes(
            scope and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        rule.onAllNodes(
            scope and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, liveRegionMode),
            useUnmergedTree = true,
        ).assertCountEquals(1)
        rule.onAllNodes(scope and hasText(status), useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodes(scope and hasText(percentage), useUnmergedTree = true).assertCountEquals(0)
    }
}
