package com.promenar.infinity.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.agent.GlobalMemoryPolicy
import com.promenar.infinity.data.model.ActiveMemoryMode
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.rag.KgCollectionSummary
import com.promenar.infinity.ui.rag.kgCollectionTitle

/** 单选项：标签、说明与是否可选；不可选时以 [disabledReason] 取代说明。 */
internal data class PermissionChoice<T>(
    val value: T,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val enabled: Boolean = true,
    @StringRes val disabledReason: Int? = null,
)

/** 面板中的单选列表，与范围选择列表同一样式。 */
@Composable
internal fun <T> PermissionChoiceList(
    choices: List<PermissionChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    testTagPrefix: String,
    enabled: Boolean = true,
) {
    choices.forEachIndexed { index, choice ->
        val isSelected = choice.value == selected
        val allowed = enabled && choice.enabled
        InfinitySettingsItem(
            horizontalPadding = 0.dp,
            title = stringResource(choice.labelRes),
            subtitle = stringResource(if (choice.enabled) choice.descriptionRes else choice.disabledReason ?: choice.descriptionRes),
            subtitleMaxLines = 3,
            role = Role.RadioButton,
            selected = isSelected,
            enabled = allowed,
            modifier = Modifier.testTag("${testTagPrefix}_$index"),
            onClick = { onSelect(choice.value) },
            trailingContent = {
                if (isSelected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            },
        )
    }
}

/** 面板内的小标题。 */
@Composable
internal fun PermissionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** 资料合集勾选列表；[locked] 中的合集保留在列表里、停用并写明受智能体限制。 */
@Composable
internal fun CollectionCheckList(
    collections: List<KgCollectionSummary>,
    checked: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    testTagPrefix: String,
    locked: Set<String> = emptySet(),
    enabled: Boolean = true,
) {
    val sharedTitle = stringResource(R.string.kg_spaces_shared_collection_title)
    if (collections.isEmpty()) {
        Text(
            stringResource(R.string.perm_collections_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    collections.forEach { collection ->
        val isLocked = collection.id in locked
        val isChecked = collection.id in checked && !isLocked
        InfinitySettingsItem(
            horizontalPadding = 0.dp,
            title = kgCollectionTitle(collection, sharedTitle),
            subtitle = if (isLocked) stringResource(R.string.ks_graph_lock_agent)
                else stringResource(R.string.ks_graph_page_option_meta, collection.documentCount, collection.nodeCount),
            role = Role.Checkbox,
            selected = isChecked,
            enabled = enabled && !isLocked,
            modifier = Modifier.testTag("${testTagPrefix}_${collection.id}"),
            onClick = { onToggle(collection.id, !isChecked) },
            trailingContent = { Checkbox(checked = isChecked, onCheckedChange = null, enabled = enabled && !isLocked) },
        )
    }
}

internal val globalMemoryChoices = listOf(
    PermissionChoice(GlobalMemoryPolicy.FORBID, R.string.perm_global_forbid, R.string.perm_global_forbid_desc),
    PermissionChoice(GlobalMemoryPolicy.ALLOW_DEFAULT_OFF, R.string.perm_global_allow_off, R.string.perm_global_allow_off_desc),
    PermissionChoice(GlobalMemoryPolicy.ALLOW_DEFAULT_ON, R.string.perm_global_allow_on, R.string.perm_global_allow_on_desc),
)

/** 全局记忆的两种范围；[cap] 为智能体上限，超出部分停用。 */
internal fun reachChoices(cap: ConversationScope = ConversationScope.ALL_AGENTS) = listOf(
    PermissionChoice(ConversationScope.AGENT, R.string.perm_reach_agent, R.string.perm_reach_agent_desc),
    PermissionChoice(
        ConversationScope.ALL_AGENTS, R.string.perm_reach_all, R.string.perm_reach_all_desc,
        enabled = cap == ConversationScope.ALL_AGENTS, disabledReason = R.string.memory_scope_above_cap,
    ),
)

internal val activeMemoryChoices = listOf(
    PermissionChoice(ActiveMemoryMode.SHARED, R.string.perm_active_shared, R.string.perm_active_shared_desc),
    PermissionChoice(ActiveMemoryMode.ISOLATED, R.string.perm_active_isolated, R.string.perm_active_isolated_desc),
)

@StringRes
internal fun globalMemoryLabel(policy: GlobalMemoryPolicy): Int = globalMemoryChoices.first { it.value == policy }.labelRes

@StringRes
internal fun reachLabel(scope: ConversationScope): Int =
    if (scope == ConversationScope.ALL_AGENTS) R.string.perm_reach_all else R.string.perm_reach_agent

@StringRes
internal fun activeMemoryLabel(mode: ActiveMemoryMode): Int =
    if (mode == ActiveMemoryMode.ISOLATED) R.string.perm_active_isolated else R.string.perm_active_shared
