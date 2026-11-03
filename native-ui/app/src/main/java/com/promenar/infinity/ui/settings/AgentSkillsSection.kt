package com.promenar.infinity.ui.settings

import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.InfinityModalSheet
import androidx.compose.foundation.clickable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalTextStyle
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.skills.SkillCatalogActions
import com.promenar.infinity.ui.skills.SkillScopeSection
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillSourceGroup
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPillButton
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.data.skills.SkillMarkdownParser
import com.promenar.infinity.ui.theme.forInput

/**
 * 设置页“技能包”标签：按来源分组。内置组只读、行尾写明始终启用；用户组标题行带启用计数与整组开关，
 * 末尾是导入与新建。通用设置页不绑定项目，项目组不显示。无状态，便于截图与预览。
 *
 * 用户组即使没有技能也显示，因为导入与新建入口在组内；此时不显示计数与整组按钮。
 */
@Composable
fun AgentSkillsSection(
    groups: List<SkillSourceGroup>,
    actions: SkillCatalogActions,
    onSetGroupEnabled: (SkillScope, Boolean) -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
) {
    val builtin = groups.firstOrNull { it.scope == SkillScope.BUILTIN }
    val user = groups.firstOrNull { it.scope == SkillScope.USER }
    Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large)) {
        if (builtin != null && builtin.skills.isNotEmpty()) {
            SkillScopeSection(
                title = stringResource(R.string.skills_group_builtin),
                footer = stringResource(R.string.runtime_guide_builtin_footer),
                skills = builtin.skills,
                emptyMessage = "",
                actions = actions,
                builtinTrailingLabel = stringResource(R.string.skills_group_always_on),
            )
        }
        val userSkills = user?.skills.orEmpty()
        val userTitle = stringResource(R.string.skills_group_user)
        SkillScopeSection(
            title = userTitle,
            footer = stringResource(R.string.skills_runtime_user_footer),
            skills = userSkills,
            emptyMessage = stringResource(R.string.agent_skills_empty),
            actions = actions,
            header = if (user != null && user.canToggle && userSkills.isNotEmpty()) {
                {
                    SkillGroupToggleHeader(
                        title = userTitle,
                        enabledCount = user.enabledCount,
                        total = userSkills.size,
                        onEnableAll = { onSetGroupEnabled(SkillScope.USER, true) },
                        onDisableAll = { onSetGroupEnabled(SkillScope.USER, false) },
                    )
                }
            } else null,
            trailingRows = {
                // 导入与新建作为分组末尾的操作行，和 MCP 的“添加服务器”保持同一形态。
                InfinitySettingsItem(
                    icon = Icons.Rounded.FileOpen,
                    title = stringResource(R.string.tools_skill_import_row),
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = onImport,
                )
                InfinitySettingsItem(
                    icon = Icons.Rounded.Add,
                    title = stringResource(R.string.agent_skills_create),
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = onCreate,
                )
            },
        )
    }
}

/**
 * 可整组开关的分组标题：左侧“组名 · 已启用/总数”，右侧“全部开启”“全部关闭”两个紧凑胶囊按钮。
 * 一行放不下时两个按钮作为整体换到标题下一行，按钮文字始终单行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillGroupToggleHeader(
    title: String,
    enabledCount: Int,
    total: Int,
    onEnableAll: () -> Unit,
    onDisableAll: () -> Unit,
) {
    val titleStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        // 组名与计数作为两段元数据排版，分隔符只出现在同一行相邻两段之间；整体作为一个标题朗读。
        InfinityMetaLine(
            segments = listOf(title, stringResource(R.string.skills_group_enabled_count, enabledCount, total)),
            style = titleStyle,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(end = InfinitySpacing.Small)
                .semantics(mergeDescendants = true) { heading() },
        )
        Row(
            modifier = Modifier.align(Alignment.CenterVertically),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InfinityPillButton(
                text = stringResource(R.string.skills_group_enable_all),
                onClick = onEnableAll,
                compact = true,
            )
            InfinityPillButton(
                text = stringResource(R.string.skills_group_disable_all),
                onClick = onDisableAll,
                compact = true,
            )
        }
    }
}

/** 新建或编辑技能；名称校验与 SKILL.md 规则一致。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSkillEditorSheet(
    initialName: String?,
    initialDescription: String,
    initialBody: String,
    allowedTools: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, body: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    var description by remember { mutableStateOf(initialDescription) }
    var body by remember { mutableStateOf(initialBody) }
    val nameError = remember(name) {
        name.takeIf(String::isNotEmpty)?.let { runCatching { SkillMarkdownParser.validateName(it) }.exceptionOrNull() }
    }
    val canSave = name.isNotBlank() && nameError == null && description.isNotBlank() &&
        description.length <= SkillMarkdownParser.MAX_DESCRIPTION_LENGTH

    InfinityModalSheet(onDismissRequest = onDismiss, height = InfinitySheetHeight.Full) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (initialName == null) R.string.agent_skills_create else R.string.agent_skills_edit),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                textStyle = LocalTextStyle.current.forInput(),
                value = name,
                onValueChange = { name = it.trim().lowercase() },
                label = { Text(stringResource(R.string.agent_skills_name_hint)) },
                isError = nameError != null,
                supportingText = nameError?.let { { Text(stringResource(R.string.agent_skills_name_rule)) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                textStyle = LocalTextStyle.current.forInput(),
                value = description,
                onValueChange = { description = it.take(SkillMarkdownParser.MAX_DESCRIPTION_LENGTH) },
                label = { Text(stringResource(R.string.agent_skills_description_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.agent_skills_body_hint)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace).forInput(),
                modifier = Modifier.fillMaxWidth().height(240.dp),
            )
            if (allowedTools.isNotEmpty()) {
                Text(
                    stringResource(R.string.agent_skills_allowed_tools, allowedTools.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { onSave(name, description, body) }, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.shared_btn_save))
            }
        }
    }
}
