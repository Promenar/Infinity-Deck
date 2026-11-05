package com.promenar.infinity.ui.hub

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.ScopeChoiceList
import com.promenar.infinity.ui.common.ScopeResource
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.scopeLabelRes

/** 私密智能体：不向其他智能体共享对话记忆，也不读写用户级长期记忆。放在“知识与记忆”分组。 */
@Composable
internal fun AgentPrivateToggle(
    policy: AgentMemoryPolicy,
    onPolicyChange: ((AgentMemoryPolicy) -> AgentMemoryPolicy) -> Unit,
) {
    SettingsToggle(
        title = stringResource(R.string.agent_memory_private),
        description = stringResource(R.string.agent_memory_private_desc),
        checked = !policy.shareWithOtherAgents,
        icon = Icons.Rounded.Lock,
        onCheckedChange = { private -> onPolicyChange { it.copy(shareWithOtherAgents = !private) } },
        modifier = Modifier.testTag("agent_memory_private"),
    )
}

/**
 * 智能体检索范围：三类资源的会话上限与新会话默认档位，单独成组。
 * 每类资源一行，值显示上限、副标题显示默认；点开在同一面板里先选上限、再选默认。
 */
@Composable
internal fun AgentScopeSection(
    policy: AgentMemoryPolicy,
    onPolicyChange: ((AgentMemoryPolicy) -> AgentMemoryPolicy) -> Unit,
    onOpenKnowledgeSettings: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf<ScopeResource?>(null) }
    InfinitySettingsSection(
        title = stringResource(R.string.agent_memory_section),
        linkedFooter = agentScopeFooter(onOpenKnowledgeSettings),
    ) {
        // 图谱不再设范围档位：每个会话绑定一张图谱，在会话设置中改绑。
        ScopeResource.entries.filter { it != ScopeResource.Graph }.forEach { resource ->
            val (default, cap) = policy.ordinals(resource)
            InfinityValueRow(
                title = stringResource(resource.titleRes),
                value = stringResource(scopeLabelRes(resource, cap)),
                subtitle = stringResource(R.string.agent_memory_default_value, stringResource(scopeLabelRes(resource, default))),
                onClick = { editing = resource },
                modifier = Modifier.testTag("agent_memory_${resource.name.lowercase()}"),
            )
        }
    }
    editing?.let { resource ->
        val (default, cap) = policy.ordinals(resource)
        InfinityBottomSheet(show = true, onDismiss = { editing = null }, title = stringResource(resource.titleRes), scrollContent = true) {
            ScopeHeading(stringResource(R.string.agent_memory_cap_heading))
            ScopeChoiceList(
                resource = resource,
                selectedOrdinal = cap,
                onSelect = { ordinal -> onPolicyChange { it.withOrdinals(resource, cap = ordinal) } },
                testTagPrefix = "agent_memory_cap_${resource.name.lowercase()}",
            )
            ScopeHeading(stringResource(R.string.agent_memory_default_heading))
            ScopeChoiceList(
                resource = resource,
                selectedOrdinal = default,
                maxOrdinal = cap,
                onSelect = { ordinal -> onPolicyChange { it.withOrdinals(resource, default = ordinal) } },
                testTagPrefix = "agent_memory_default_${resource.name.lowercase()}",
            )
        }
    }
}

/** 页脚中的“知识库设置”可点；没有跳转时只显示文字。 */
@Composable
private fun agentScopeFooter(onOpenKnowledgeSettings: (() -> Unit)?): AnnotatedString {
    val linkText = stringResource(R.string.agent_scope_footer_link)
    val full = stringResource(R.string.agent_scope_footer, linkText)
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(full, linkText, onOpenKnowledgeSettings, linkColor) {
        buildAnnotatedString {
            append(full)
            val start = full.indexOf(linkText)
            if (onOpenKnowledgeSettings != null && start >= 0) {
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "knowledge_settings",
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onOpenKnowledgeSettings() },
                    ),
                    start,
                    start + linkText.length,
                )
            }
        }
    }
}

@Composable
private fun ScopeHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

private fun AgentMemoryPolicy.ordinals(resource: ScopeResource): Pair<Int, Int> = when (resource) {
    ScopeResource.Memory -> memoryDefault.ordinal to memoryCap.ordinal
    ScopeResource.Graph -> graphDefault.ordinal to graphCap.ordinal
    ScopeResource.Documents -> documentDefault.ordinal to documentCap.ordinal
}

/** 只改给出的一项；上限调低后由 [AgentMemoryPolicy.normalized] 收紧默认值。 */
internal fun AgentMemoryPolicy.withOrdinals(resource: ScopeResource, default: Int? = null, cap: Int? = null): AgentMemoryPolicy {
    val (currentDefault, currentCap) = ordinals(resource)
    val d = default ?: currentDefault
    val c = cap ?: currentCap
    return when (resource) {
        ScopeResource.Memory -> copy(memoryDefault = ConversationScope.entries[d], memoryCap = ConversationScope.entries[c])
        ScopeResource.Graph -> copy(graphDefault = ConversationScope.entries[d], graphCap = ConversationScope.entries[c])
        ScopeResource.Documents -> copy(documentDefault = DocumentScope.entries[d], documentCap = DocumentScope.entries[c])
    }.normalized()
}
