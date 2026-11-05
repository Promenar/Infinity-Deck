package com.promenar.infinity.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.rag.ConversationReachStats
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.ScopePickerSheet
import com.promenar.infinity.ui.common.ScopeResource
import com.promenar.infinity.ui.common.scopeLabelRes
import com.promenar.infinity.ui.common.withDocumentScope
import com.promenar.infinity.ui.common.withGraphScope
import com.promenar.infinity.ui.common.withMemoryScope

/**
 * 会话的对话记忆与资料检索范围。档位在智能体上限内选择；会话保存的档位超出上限时（智能体上限后来调低），
 * 行内显示实际生效的档位并注明受上限限制。对应功能关闭时整行停用。
 */
@Composable
internal fun SessionScopeSection(
    ragOptions: RagOptions,
    policy: AgentMemoryPolicy,
    onRagOptionsChange: ((RagOptions) -> RagOptions) -> Unit,
    reachStats: Map<ConversationScope, ConversationReachStats> = emptyMap(),
    /** 页脚“智能体设置”的去向（S7）；为 null 时页脚是纯文字。 */
    onOpenAgentSettings: (() -> Unit)? = null,
) {
    var picking by remember { mutableStateOf<ScopeResource?>(null) }
    InfinitySettingsSection(
        title = stringResource(R.string.memory_scope_section),
        linkedFooter = scopeFooter(onOpenAgentSettings),
    ) {
        // 图谱不再按范围档位选择：每个会话绑定一张图谱，见 SessionGraphBindingSection。
        ScopeResource.entries.filter { it != ScopeResource.Graph }.forEach { resource ->
            val enabled = when (resource) {
                ScopeResource.Memory -> ragOptions.enableMemory
                ScopeResource.Graph -> ragOptions.enableKnowledgeGraph == true
                ScopeResource.Documents -> ragOptions.enableDocs
            }
            val (saved, cap) = scopeOrdinals(resource, ragOptions, policy)
            val effective = minOf(saved, cap)
            val effectiveLabel = stringResource(scopeLabelRes(resource, effective))
            // 对话记忆与图谱对话证据按会话计范围，说明当前档位实际覆盖多少会话。
            val reach = if (resource == ScopeResource.Documents) null else reachStats[ConversationScope.entries[effective]]
            val reachText = reach?.let { stats ->
                listOfNotNull(
                    pluralStringResource(R.plurals.memory_scope_reach_sessions, stats.sessionCount, stats.sessionCount),
                    if (effective == ConversationScope.ALL_AGENTS.ordinal) {
                        pluralStringResource(R.plurals.rag_trace_reach_agents, stats.agentCount, stats.agentCount)
                    } else null,
                ).joinToString(stringResource(R.string.chat_step_summary_separator))
            }
            InfinityValueRow(
                title = stringResource(resource.titleRes),
                value = if (enabled) effectiveLabel else stringResource(R.string.memory_scope_feature_off),
                subtitle = when {
                    !enabled -> null
                    saved > cap -> stringResource(R.string.memory_scope_capped, effectiveLabel)
                    else -> reachText
                },
                enabled = enabled,
                onClick = { picking = resource },
                modifier = Modifier.testTag("session_scope_${resource.name.lowercase()}"),
            )
        }
    }
    picking?.let { resource ->
        val (saved, cap) = scopeOrdinals(resource, ragOptions, policy)
        ScopePickerSheet(
            show = true,
            title = stringResource(resource.titleRes),
            resource = resource,
            selectedOrdinal = minOf(saved, cap),
            maxOrdinal = cap,
            onSelect = { ordinal ->
                onRagOptionsChange { current ->
                    when (resource) {
                        ScopeResource.Memory -> current.withMemoryScope(ConversationScope.entries[ordinal])
                        ScopeResource.Graph -> current.withGraphScope(ConversationScope.entries[ordinal])
                        ScopeResource.Documents -> current.withDocumentScope(DocumentScope.entries[ordinal])
                    }
                }
            },
            onDismiss = { picking = null },
            testTagPrefix = "session_scope_option_${resource.name.lowercase()}",
        )
    }
}

/** 范围页脚：“智能体设置”可点时带下划线链接色。 */
@Composable
private fun scopeFooter(onOpenAgentSettings: (() -> Unit)?): AnnotatedString {
    val linkText = stringResource(R.string.settings_ia_scope_footer_link)
    val full = stringResource(R.string.settings_ia_scope_footer, linkText)
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(full, linkText, onOpenAgentSettings, linkColor) {
        buildAnnotatedString {
            append(full)
            val start = full.indexOf(linkText)
            if (onOpenAgentSettings != null && start >= 0) {
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "agent_settings",
                        styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onOpenAgentSettings() },
                    ),
                    start,
                    start + linkText.length,
                )
            }
        }
    }
}

private fun scopeOrdinals(resource: ScopeResource, options: RagOptions, policy: AgentMemoryPolicy): Pair<Int, Int> = when (resource) {
    ScopeResource.Memory -> options.effectiveMemoryScope.ordinal to policy.memoryCap.ordinal
    ScopeResource.Graph -> options.effectiveGraphScope.ordinal to policy.graphCap.ordinal
    ScopeResource.Documents -> options.effectiveDocumentScope.ordinal to policy.documentCap.ordinal
}
