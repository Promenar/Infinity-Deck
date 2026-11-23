package com.promenar.infinity.ui.canvas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityInspectorTopBar
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing

/**
 * 独立产物整页承载（如外部入口或测试调用）：使用统一 56dp 轻页头与吸顶分类页签。
 */
@Composable
internal fun CanvasListPage(onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val session = LocalCanvasSession.current
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(UiTags.CANVAS_PAGE),
    ) {
        InfinityInspectorTopBar(
            title = stringResource(R.string.canvas_title),
            onBack = onBack,
        )
        when {
            session == null -> Unit
            session.listFailed -> Text(
                text = stringResource(R.string.canvas_load_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(InfinitySpacing.Large),
            )
            else -> ArtifactsContent(
                artifacts = session.artifacts,
                candidates = session.candidates,
                onOpen = session.openArtifact,
                onClaim = session.claimCandidate,
                onIgnore = session.ignoreCandidate,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
