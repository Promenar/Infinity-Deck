package com.promenar.infinity.ui.workspace

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

/** 只有“会被清掉”的情况用提醒色，其余一律中性。 */
enum class WorkspaceTagTone { NEUTRAL, CAUTION }

data class WorkspaceTag(@StringRes val label: Int, val tone: WorkspaceTagTone)

/** 每类工作区固定两条标签，依次回答“进不进备份”和“什么时候会消失”。 */
fun workspaceKindTags(kind: String?): List<WorkspaceTag> = when (kind) {
    WorkspaceEntity.KIND_PROJECT -> listOf(
        WorkspaceTag(R.string.workspace_tag_not_backed_up, WorkspaceTagTone.NEUTRAL),
        WorkspaceTag(R.string.workspace_tag_gone_on_uninstall, WorkspaceTagTone.CAUTION),
    )
    WorkspaceEntity.KIND_SHARED -> listOf(
        WorkspaceTag(R.string.workspace_tag_not_backed_up, WorkspaceTagTone.NEUTRAL),
        WorkspaceTag(R.string.workspace_tag_kept_on_uninstall, WorkspaceTagTone.NEUTRAL),
    )
    WorkspaceEntity.KIND_LINUX_HOME -> listOf(
        WorkspaceTag(R.string.workspace_tag_not_backed_up, WorkspaceTagTone.NEUTRAL),
        WorkspaceTag(R.string.workspace_tag_cleared_on_reset, WorkspaceTagTone.CAUTION),
    )
    else -> listOf(
        WorkspaceTag(R.string.workspace_tag_backed_up, WorkspaceTagTone.NEUTRAL),
        WorkspaceTag(R.string.workspace_tag_kept_on_delete, WorkspaceTagTone.NEUTRAL),
    )
}

@Composable
fun WorkspaceTagChip(label: String, tone: WorkspaceTagTone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val (container, content) = when (tone) {
        WorkspaceTagTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        WorkspaceTagTone.CAUTION -> domain.warningContainer to domain.onWarningContainer
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = InfinitySpacing.Small, vertical = 2.dp),
        )
    }
}

/** 一类工作区的两条标签，窄屏或大字号时自动换行。 */
@Composable
fun WorkspaceKindTagRow(kind: String?, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        workspaceKindTags(kind).forEach { tag -> WorkspaceTagChip(stringResource(tag.label), tag.tone) }
    }
}

/** 选择器的分组标题：按存放位置分组（应用内存储、Linux 环境、手机存储）。 */
@Composable
fun WorkspaceStorageHeader(kind: String?, modifier: Modifier = Modifier) {
    Text(
        workspaceStorageLabel(kind),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.Large + InfinitySpacing.XSmall)
            .padding(top = InfinitySpacing.Small)
            .semantics { heading() },
    )
}

/** 知识库挂载的只读标签：信息色带锁，与授权方式、工具范围等中性标签同排。 */
@Composable
fun KnowledgeReadOnlyTag(modifier: Modifier = Modifier) {
    val domain = MaterialTheme.infinityDomainColors
    KnowledgeMountTag(
        icon = Icons.Rounded.Lock,
        label = stringResource(R.string.knowledge_mount_read_only_tag),
        container = domain.infoContainer,
        content = domain.onInfoContainer,
        modifier = modifier.testTag(KNOWLEDGE_READ_ONLY_TAG),
    )
}

/** 知识库挂载的读写授权标签：提醒色带编辑图标，明确区分于只读挂载。 */
@Composable
fun KnowledgeReadWriteTag(modifier: Modifier = Modifier) {
    val domain = MaterialTheme.infinityDomainColors
    KnowledgeMountTag(
        icon = Icons.Rounded.EditNote,
        label = stringResource(R.string.knowledge_mount_read_write_tag),
        container = domain.warningContainer,
        content = domain.onWarningContainer,
        modifier = modifier.testTag(KNOWLEDGE_READ_WRITE_TAG),
    )
}

@Composable
private fun KnowledgeMountTag(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = InfinitySpacing.Small, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}

const val KNOWLEDGE_READ_ONLY_TAG = "knowledge_read_only_tag"
const val KNOWLEDGE_READ_WRITE_TAG = "knowledge_read_write_tag"
