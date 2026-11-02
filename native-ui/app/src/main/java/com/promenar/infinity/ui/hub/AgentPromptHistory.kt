package com.promenar.infinity.ui.hub

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.model.AgentPromptRevision
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityFullscreenDialog
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.diff.FileDiffView
import com.promenar.infinity.ui.theme.forReading

/** 当前内容对应的版本：最新一个与当前提示词相同的版本；没有相同内容时为空。 */
internal fun currentRevisionId(revisions: List<AgentPromptRevision>, currentPrompt: String): String? =
    revisions.firstOrNull { it.content == currentPrompt }?.id

/**
 * 提示词历史版本页：最近 20 版，最新在前；每版显示时间、来源（我 / 智能体 / 恢复）与字数，当前版本用主色描边。
 * 点开任意一版，底部面板给出它与当前内容的差异，可“恢复此版本”。
 */
@Composable
internal fun AgentPromptHistoryDialog(
    show: Boolean,
    revisions: List<AgentPromptRevision>,
    currentPrompt: String,
    onRestore: (AgentPromptRevision) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    InfinityFullscreenDialog(onDismissRequest = onDismiss, systemBarColor = MaterialTheme.colorScheme.background) {
        AgentPromptHistoryContent(
            revisions = revisions,
            currentPrompt = currentPrompt,
            onBack = onDismiss,
            onSelect = { selectedId = it.id },
            modifier = Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding(),
        )
    }
    AgentPromptRevisionSheet(
        revision = revisions.firstOrNull { it.id == selectedId },
        currentPrompt = currentPrompt,
        isCurrent = selectedId != null && selectedId == currentRevisionId(revisions, currentPrompt),
        onRestore = { revision ->
            selectedId = null
            onRestore(revision)
        },
        onDismiss = { selectedId = null },
    )
}

@Composable
internal fun AgentPromptHistoryContent(
    revisions: List<AgentPromptRevision>,
    currentPrompt: String,
    onBack: () -> Unit,
    onSelect: (AgentPromptRevision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentId = remember(revisions, currentPrompt) { currentRevisionId(revisions, currentPrompt) }
    Column(modifier.fillMaxSize().testTag("agent_prompt_history")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfinityBackButton(onClick = onBack)
            Text(
                text = stringResource(R.string.agent_prompt_history_title),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.agent_prompt_history_caption),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (revisions.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.agent_prompt_history_empty),
                        style = MaterialTheme.typography.bodyMedium.forReading(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp).testTag("agent_prompt_history_empty"),
                    )
                }
            }
            items(revisions, key = { it.id }) { revision ->
                AgentPromptRevisionRow(revision, isCurrent = revision.id == currentId, onClick = { onSelect(revision) })
            }
        }
    }
}

@Composable
private fun AgentPromptRevisionRow(revision: AgentPromptRevision, isCurrent: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerLow,
        border = BorderStroke(if (isCurrent) 1.5.dp else 1.dp, if (isCurrent) scheme.primary else scheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().testTag("agent_prompt_revision_${revision.id}"),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfinityMetaLine(
                segments = revisionMeta(revision, isCurrent),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = revision.content.lineSequence().lastOrNull { it.isNotBlank() }
                    ?: stringResource(R.string.agent_prompt_history_empty_content),
                style = MaterialTheme.typography.bodyMedium.forReading(),
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 元数据分段：时间、来源、字数、当前。 */
@Composable
private fun revisionMeta(revision: AgentPromptRevision, isCurrent: Boolean): List<String> =
    buildList {
        add(revisionTime(revision.createdAt))
        add(revisionSource(revision.source))
        add(pluralStringResource(R.plurals.agent_prompt_history_chars, revision.content.length, revision.content.length))
        if (isCurrent) add(stringResource(R.string.agent_prompt_history_current))
    }

@Composable
internal fun revisionSource(source: AgentPromptRevision.Source): String = stringResource(
    when (source) {
        AgentPromptRevision.Source.USER -> R.string.agent_prompt_history_source_user
        AgentPromptRevision.Source.AGENT -> R.string.agent_prompt_history_source_agent
        AgentPromptRevision.Source.ROLLBACK -> R.string.agent_prompt_history_source_rollback
    },
)

@Composable
private fun revisionTime(millis: Long): String {
    val context = LocalContext.current
    return remember(millis) {
        DateUtils.formatDateTime(
            context,
            millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
        )
    }
}

/** 版本详情：与当前内容的差异（删去的是当前有、此版本没有的行），以及恢复入口。 */
@Composable
internal fun AgentPromptRevisionSheet(
    revision: AgentPromptRevision?,
    currentPrompt: String,
    isCurrent: Boolean,
    onRestore: (AgentPromptRevision) -> Unit,
    onDismiss: () -> Unit,
) {
    InfinityBottomSheet(
        show = revision != null,
        onDismiss = onDismiss,
        title = revision?.let { stringResource(R.string.agent_prompt_history_sheet_title, revisionTime(it.createdAt), revisionSource(it.source)) },
    ) {
        val shown = revision ?: return@InfinityBottomSheet
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).testTag("agent_prompt_revision_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(if (isCurrent) R.string.agent_prompt_history_is_current else R.string.agent_prompt_history_diff_caption),
                style = MaterialTheme.typography.bodySmall.forReading(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FileDiffView(
                oldText = if (isCurrent) shown.content else currentPrompt,
                newText = shown.content,
                maxViewportHeight = 320.dp,
            )
            InfinityPillRow(Modifier.fillMaxWidth(), stackedSpacing = 8.dp) {
                InfinityPillButton(
                    stringResource(if (isCurrent) R.string.agent_prompt_history_close else R.string.common_btn_cancel),
                    onDismiss,
                    Modifier.testTag("agent_prompt_revision_close"),
                )
                if (!isCurrent) {
                    InfinityPillButton(
                        stringResource(R.string.agent_prompt_history_restore),
                        { onRestore(shown) },
                        Modifier.testTag("agent_prompt_revision_restore"),
                        tone = InfinityPillTone.PRIMARY,
                    )
                }
            }
            if (!isCurrent) {
                Text(
                    text = stringResource(R.string.agent_prompt_history_restore_caption),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
