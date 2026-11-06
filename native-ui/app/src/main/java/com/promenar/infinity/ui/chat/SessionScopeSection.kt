package com.promenar.infinity.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
import com.promenar.infinity.data.agent.GlobalMemoryPolicy
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.rag.ConversationReachStats
import com.promenar.infinity.data.rag.KgCollectionSummary
import com.promenar.infinity.ui.common.CollectionCheckList
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.PermissionChoice
import com.promenar.infinity.ui.common.PermissionChoiceList
import com.promenar.infinity.ui.common.PermissionHeading
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.activeMemoryChoices
import com.promenar.infinity.ui.common.activeMemoryLabel
import com.promenar.infinity.ui.common.reachChoices
import com.promenar.infinity.ui.common.reachLabel

/**
 * 会话的检索范围：对话记忆（默认只读本会话，开启全局记忆后可读其它会话）、资料（工作区加勾选的资料合集）
 * 与主动记忆模式。选择受智能体限制：超出时按智能体执行并注明原因，保存的选择不被改写。
 */
@Composable
internal fun SessionScopeSection(
    ragOptions: RagOptions,
    policy: AgentMemoryPolicy,
    onRagOptionsChange: ((RagOptions) -> RagOptions) -> Unit,
    reachStats: Map<ConversationScope, ConversationReachStats> = emptyMap(),
    /** 页脚“智能体设置”的去向（S7）；为 null 时页脚是纯文字。 */
    onOpenAgentSettings: (() -> Unit)? = null,
    collections: List<KgCollectionSummary> = emptyList(),
) {
    var editing by remember { mutableStateOf<SessionPermission?>(null) }
    val forbidden = policy.globalMemory == GlobalMemoryPolicy.FORBID
    val globalOn = ragOptions.globalMemoryEnabled && !forbidden
    val effectiveReach = policy.clamp(ragOptions).memoryScope ?: ConversationScope.SESSION
    val allowed = policy.allowedCollectionIds?.toSet()
    val available = collections.filter { allowed == null || it.id in allowed }
    val selected = ragOptions.selectedCollectionIds
    InfinitySettingsSection(
        title = stringResource(R.string.memory_scope_section),
        linkedFooter = scopeFooter(onOpenAgentSettings),
    ) {
        val memoryEnabled = ragOptions.enableMemory
        val reachText = reachStats[effectiveReach]?.let { stats ->
            listOfNotNull(
                pluralStringResource(R.plurals.memory_scope_reach_sessions, stats.sessionCount, stats.sessionCount),
                if (effectiveReach == ConversationScope.ALL_AGENTS) {
                    pluralStringResource(R.plurals.rag_trace_reach_agents, stats.agentCount, stats.agentCount)
                } else null,
            ).joinToString(stringResource(R.string.chat_step_summary_separator))
        }
        InfinityValueRow(
            title = stringResource(R.string.memory_scope_row_memory),
            value = when {
                !memoryEnabled -> stringResource(R.string.memory_scope_feature_off)
                globalOn -> stringResource(R.string.perm_global_value, stringResource(reachLabel(effectiveReach)))
                else -> stringResource(R.string.perm_session_only)
            },
            subtitle = when {
                !memoryEnabled -> null
                forbidden && ragOptions.globalMemoryEnabled -> stringResource(R.string.perm_global_forbidden)
                globalOn && ragOptions.globalMemoryScope > effectiveReach ->
                    stringResource(R.string.memory_scope_capped, stringResource(reachLabel(effectiveReach)))
                else -> reachText
            },
            enabled = memoryEnabled,
            onClick = { editing = SessionPermission.Memory },
            modifier = Modifier.testTag("session_scope_memory"),
        )
        val restricted = selected.orEmpty().any { allowed != null && it !in allowed }
        val docsValue = when {
            !ragOptions.enableDocs -> stringResource(R.string.memory_scope_feature_off)
            selected == null && allowed == null -> stringResource(R.string.perm_docs_value_all)
            else -> {
                val count = (selected?.filter { id -> available.any { it.id == id } } ?: available.map { it.id }).size
                if (count == 0) stringResource(R.string.perm_docs_value_none)
                else pluralStringResource(R.plurals.perm_docs_value_count, count, count)
            }
        }
        InfinityValueRow(
            title = stringResource(R.string.memory_scope_row_documents),
            value = docsValue,
            subtitle = if (ragOptions.enableDocs && restricted) stringResource(R.string.ks_graph_lock_agent) else null,
            enabled = ragOptions.enableDocs,
            onClick = { editing = SessionPermission.Documents },
            modifier = Modifier.testTag("session_scope_documents"),
        )
        InfinityValueRow(
            title = stringResource(R.string.perm_active_memory),
            value = stringResource(activeMemoryLabel(ragOptions.activeMemoryMode ?: policy.activeMemoryDefault)),
            onClick = { editing = SessionPermission.ActiveMemory },
            modifier = Modifier.testTag("session_scope_active_memory"),
        )
    }
    when (editing) {
        SessionPermission.Memory -> InfinityBottomSheet(
            show = true, onDismiss = { editing = null }, title = stringResource(R.string.memory_scope_row_memory), scrollContent = true,
        ) {
            SettingsToggle(
                title = stringResource(R.string.perm_global_memory),
                description = stringResource(if (forbidden) R.string.perm_global_forbidden else R.string.perm_global_toggle_desc),
                checked = globalOn,
                enabled = !forbidden,
                onCheckedChange = { on ->
                    onRagOptionsChange { current ->
                        current.copy(
                            globalMemoryEnabled = on,
                            memoryScope = if (on) current.globalMemoryScope.coerceAtLeast(ConversationScope.AGENT) else ConversationScope.SESSION,
                        )
                    }
                },
                modifier = Modifier.testTag("session_scope_global_memory"),
            )
            PermissionHeading(stringResource(R.string.perm_reach_heading))
            PermissionChoiceList(
                choices = reachChoices(policy.memoryCap),
                selected = ragOptions.globalMemoryScope.coerceAtLeast(ConversationScope.AGENT),
                onSelect = { scope ->
                    onRagOptionsChange { current ->
                        current.copy(globalMemoryScope = scope, memoryScope = if (current.globalMemoryEnabled) scope else ConversationScope.SESSION)
                    }
                },
                testTagPrefix = "session_scope_reach",
                enabled = globalOn,
            )
        }
        SessionPermission.Documents -> InfinityBottomSheet(
            show = true, onDismiss = { editing = null }, title = stringResource(R.string.memory_scope_row_documents), scrollContent = true,
        ) {
            PermissionChoiceList(
                choices = listOf(PermissionChoice(true, R.string.perm_default_all, R.string.perm_default_all_desc)),
                selected = selected == null,
                onSelect = { onRagOptionsChange { it.copy(selectedCollectionIds = null) } },
                testTagPrefix = "session_scope_docs_all",
                enabled = ragOptions.enableDocs,
            )
            PermissionHeading(stringResource(R.string.perm_collections_pick_heading))
            CollectionCheckList(
                collections = collections,
                checked = (selected ?: available.map { it.id }).toSet(),
                locked = collections.filter { allowed != null && it.id !in allowed }.mapTo(mutableSetOf()) { it.id },
                onToggle = { id, on ->
                    onRagOptionsChange { current ->
                        val base = current.selectedCollectionIds ?: available.map { it.id }
                        current.copy(selectedCollectionIds = if (on) (base + id).distinct() else base - id)
                    }
                },
                testTagPrefix = "session_scope_collection",
                enabled = ragOptions.enableDocs,
            )
            SessionPermissionNote(stringResource(R.string.perm_docs_footer))
        }
        SessionPermission.ActiveMemory -> InfinityBottomSheet(
            show = true, onDismiss = { editing = null }, title = stringResource(R.string.perm_active_memory), scrollContent = true,
        ) {
            PermissionChoiceList(
                choices = activeMemoryChoices,
                selected = ragOptions.activeMemoryMode ?: policy.activeMemoryDefault,
                onSelect = { mode -> onRagOptionsChange { it.copy(activeMemoryMode = mode) } },
                testTagPrefix = "session_scope_active",
            )
            SessionPermissionNote(stringResource(R.string.perm_active_switch_note))
        }
        null -> Unit
    }
}

private enum class SessionPermission { Memory, Documents, ActiveMemory }

@Composable
private fun SessionPermissionNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
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
