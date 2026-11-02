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
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.ui.skills.SkillCatalogActions
import com.promenar.infinity.ui.skills.SkillScopeSection
import com.promenar.infinity.data.skills.SkillMarkdownParser
import com.promenar.infinity.ui.theme.forInput

/** 设置页“技能包”标签：内置与用户级技能列表，末尾是导入与新建；无状态，便于截图与预览。 */
@Composable
fun AgentSkillsSection(
    skills: List<DiscoveredSkill>,
    actions: SkillCatalogActions,
    onImport: () -> Unit,
    onCreate: () -> Unit,
) {
    SkillScopeSection(
        title = stringResource(R.string.settings_ia_skills_title),
        footer = stringResource(R.string.skills_runtime_user_footer),
        skills = skills,
        emptyMessage = stringResource(R.string.agent_skills_empty),
        actions = actions,
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
