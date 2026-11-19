package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.knowledge.KNOWLEDGE_READ_WRITE
import com.promenar.infinity.data.knowledge.KnowledgeAccessPreview
import com.promenar.infinity.data.knowledge.KnowledgeAccessTarget
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether
import kotlinx.serialization.json.Json

/** 知识库访问授权审批卡的内容：申请原因、要挂载的知识库、授权档位，以及预览给出的不可挂载原因。 */
@Immutable
data class KnowledgeAccessApproval(
    val reason: String?,
    val targets: List<KnowledgeAccessTarget>,
    val allowed: Boolean,
    val deniedReason: String? = null,
    val readWrite: Boolean = false,
)

internal const val REQUEST_KNOWLEDGE_ACCESS_TOOL = "request_knowledge_access"

private val knowledgeAccessJson = Json { ignoreUnknownKeys = true }

/**
 * 授权目标即完整的 [KnowledgeAccessPreview] JSON（批准与执行时都按它重验），界面直接解码，不另调接口。
 * 不是访问授权工具、或目标无法解码时返回 null，审批卡按通用工具显示。
 */
internal fun knowledgeAccessApproval(toolName: String, executionTarget: String?, reason: String?): KnowledgeAccessApproval? {
    if (toolName != REQUEST_KNOWLEDGE_ACCESS_TOOL) return null
    val preview = executionTarget?.takeIf(String::isNotBlank)
        ?.let { runCatching { knowledgeAccessJson.decodeFromString<KnowledgeAccessPreview>(it) }.getOrNull() }
        ?: return null
    return KnowledgeAccessApproval(
        reason = reason?.trim()?.takeIf(String::isNotEmpty),
        targets = preview.knowledgeBases,
        allowed = preview.allowed,
        deniedReason = preview.reason?.takeIf { !preview.allowed && it.isNotBlank() },
        readWrite = preview.accessMode == KNOWLEDGE_READ_WRITE || preview.knowledgeBases.any { !it.readOnly },
    )
}

/** 标题右侧只读/读写胶囊徽标。 */
@Composable
internal fun KnowledgeAccessModeBadge(readWrite: Boolean, modifier: Modifier = Modifier) {
    val accent = if (readWrite) {
        MaterialTheme.infinityDomainColors.warning
    } else {
        MaterialTheme.infinityDomainColors.info
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.14f),
        modifier = modifier.testTag(if (readWrite) KNOWLEDGE_ACCESS_BADGE_RW_TAG else KNOWLEDGE_ACCESS_BADGE_RO_TAG),
    ) {
        Text(
            text = stringResource(
                if (readWrite) R.string.knowledge_access_badge_read_write else R.string.knowledge_access_badge_read_only,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 正文依次为申请原因（引用样式）、知识库名称与文档数、挂载路径、权限能力与边界说明。 */
@Composable
internal fun KnowledgeAccessApprovalBody(access: KnowledgeAccessApproval) {
    Column(Modifier.fillMaxWidth().testTag(KNOWLEDGE_ACCESS_BODY_TAG), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        access.reason?.let { reason ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(
                    Modifier.width(3.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                ) {}
                Text(
                    text = reason.keepCjkTailTogether(),
                    style = MaterialTheme.typography.bodyMedium.forReading(),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp).testTag(KNOWLEDGE_ACCESS_REASON_TAG),
                )
            }
        }
        access.targets.forEach { target ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KnowledgeAccessField(stringResource(R.string.knowledge_access_label_kb)) {
                    Text(
                        text = pluralStringResource(R.plurals.knowledge_access_documents, target.documentCount, target.title, target.documentCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                KnowledgeAccessField(stringResource(R.string.knowledge_access_label_mount)) {
                    InfinityIdentifierText(
                        text = if (target.alreadyMounted) stringResource(R.string.knowledge_access_already_mounted, target.path) else target.path,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (access.allowed) {
            val accent = if (access.readWrite) {
                MaterialTheme.infinityDomainColors.warning
            } else {
                MaterialTheme.infinityDomainColors.info
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (access.readWrite) Icons.Rounded.EditNote else Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(top = 1.dp).size(16.dp),
                )
                Text(
                    text = stringResource(
                        if (access.readWrite) R.string.knowledge_access_read_write else R.string.knowledge_access_read_only,
                    ).keepCjkTailTogether(),
                    style = MaterialTheme.typography.bodySmall.forReading(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(
                        if (access.readWrite) KNOWLEDGE_ACCESS_READ_WRITE_TAG else KNOWLEDGE_ACCESS_READ_ONLY_TAG,
                    ),
                )
            }
        } else {
            access.deniedReason?.let { ApprovalRiskLine(it) }
        }
    }
}

@Composable
private fun KnowledgeAccessField(label: String, value: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        value()
    }
}

internal const val KNOWLEDGE_ACCESS_BODY_TAG = "CHAT_APPROVAL_KNOWLEDGE_ACCESS"
internal const val KNOWLEDGE_ACCESS_REASON_TAG = "CHAT_APPROVAL_KNOWLEDGE_REASON"
internal const val KNOWLEDGE_ACCESS_READ_ONLY_TAG = "CHAT_APPROVAL_KNOWLEDGE_READ_ONLY"
internal const val KNOWLEDGE_ACCESS_READ_WRITE_TAG = "CHAT_APPROVAL_KNOWLEDGE_READ_WRITE"
internal const val KNOWLEDGE_ACCESS_BADGE_RO_TAG = "CHAT_APPROVAL_KNOWLEDGE_BADGE_RO"
internal const val KNOWLEDGE_ACCESS_BADGE_RW_TAG = "CHAT_APPROVAL_KNOWLEDGE_BADGE_RW"
