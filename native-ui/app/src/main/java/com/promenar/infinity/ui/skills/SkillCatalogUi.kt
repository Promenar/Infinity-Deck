package com.promenar.infinity.ui.skills

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.theme.forReading

/** 技能行与详情面板的操作回调；会话面板与设置页共用。 */
class SkillCatalogActions(
    val onToggle: (DiscoveredSkill, Boolean) -> Unit = { _, _ -> },
    val onTrust: (DiscoveredSkill) -> Unit = {},
    val onAcknowledge: (DiscoveredSkill) -> Unit = {},
    val onDelete: (DiscoveredSkill) -> Unit = {},
    /** 非 null 时，用户级技能的详情面板提供“编辑”，由调用方打开应用内编辑器。 */
    val onEdit: ((DiscoveredSkill) -> Unit)? = null,
    val loadDocument: suspend (DiscoveredSkill) -> String? = { null },
)

/**
 * 一个来源级别的技能分组：每行显示名称、描述与需要用户处理的状态。
 * 点按打开详情；行尾为启用开关，待信任技能为“信任”按钮，损坏技能为错误图标。
 *
 * [header] 非 null 时代替纯文字的 [title] 作为组标题（例如带计数与批量操作的标题行），间距与普通分组标题一致；
 * [builtinTrailingLabel] 非 null 时内置技能行尾显示这段单行文字（例如“始终启用”），否则内置行尾留空。
 */
@Composable
fun SkillScopeSection(
    title: String,
    footer: String?,
    skills: List<DiscoveredSkill>,
    emptyMessage: String,
    actions: SkillCatalogActions,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    builtinTrailingLabel: String? = null,
    trailingRows: @Composable () -> Unit = {},
) {
    var opened by remember { mutableStateOf<DiscoveredSkill?>(null) }
    var pendingTrust by remember { mutableStateOf<DiscoveredSkill?>(null) }
    val sectionFooter = if (skills.isEmpty()) emptyMessage else footer
    val rows: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        skills.forEach { skill ->
            SkillRow(
                skill = skill,
                onOpen = { opened = skill },
                onToggle = { actions.onToggle(skill, it) },
                onTrust = { pendingTrust = skill },
                builtinTrailingLabel = builtinTrailingLabel,
            )
        }
        trailingRows()
    }
    if (header == null) {
        InfinitySettingsSection(title = title, footer = sectionFooter, modifier = modifier, content = rows)
    } else {
        // 与 InfinitySettingsSection 相同的标题内边距与分组面，只把标题换成调用方提供的内容。
        Column(modifier = modifier.fillMaxWidth()) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = InfinitySpacing.RowHorizontal,
                        end = InfinitySpacing.RowHorizontal,
                        bottom = InfinitySpacing.SectionHeaderBottom,
                    ),
            ) { header() }
            InfinitySettingsGroup(footer = sectionFooter, content = rows)
        }
    }

    opened?.let { skill ->
        // 详情随最新扫描结果刷新：同一 id 的技能状态变化后详情面板显示新状态。
        val current = skills.firstOrNull { it.id == skill.id }
        if (current == null) {
            opened = null
        } else {
            SkillDetailSheet(
                skill = current,
                actions = actions,
                onRequestTrust = { pendingTrust = current },
                onDismiss = { opened = null },
            )
        }
    }
    pendingTrust?.let { skill ->
        SkillTrustDialog(
            skill = skill,
            onConfirm = {
                actions.onTrust(skill)
                pendingTrust = null
            },
            onDismiss = { pendingTrust = null },
        )
    }
}

@Composable
private fun SkillRow(
    skill: DiscoveredSkill,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onTrust: () -> Unit,
    builtinTrailingLabel: String? = null,
) {
    val title = skill.displayName ?: skill.name
    val description = (skill.shortDescription ?: skill.description).replace('\n', ' ').trim()
    val note = skillStatusNote(skill)
    val subtitle = listOfNotNull(note, description.takeIf(String::isNotEmpty)).joinToString(" · ").ifEmpty { null }  // wrap-check: allow 含可能很长的技能说明，按两行省略
    // 开关先在本地翻转，避免目录重扫完成前的回弹。
    var checked by remember(skill.id, skill.status) { mutableStateOf(skill.status == SkillStatus.ENABLED) }
    InfinitySettingsItem(
        icon = if (skill.scope != SkillScope.PROJECT) Icons.Rounded.AutoAwesome else Icons.Rounded.FolderSpecial,
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = 2,
        onClick = onOpen,
        modifier = Modifier.testTag("skill_row:${skill.id}"),
        trailingContent = {
            // 内置技能单独成组、不可停用，行尾不放开关；调用方给出说明文字时以单行文字表达常开。
            if (skill.scope == SkillScope.BUILTIN) {
                if (builtinTrailingLabel != null) Text(
                    builtinTrailingLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            } else when (skill.status) {
                SkillStatus.ENABLED, SkillStatus.DISABLED -> {
                    val label = stringResource(R.string.skills_runtime_toggle_cd, title)
                    InfinitySwitch(
                        checked = checked,
                        onCheckedChange = { checked = it; onToggle(it) },
                        modifier = Modifier.semantics { contentDescription = label },
                    )
                }
                SkillStatus.PENDING_TRUST -> InfinityPillButton(
                    text = stringResource(R.string.skills_runtime_trust),
                    onClick = onTrust,
                    compact = true,
                )
                SkillStatus.BROKEN -> Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = stringResource(R.string.skills_runtime_status_broken_short),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}

/** 需要用户注意的状态说明；普通的启用、停用状态由开关表达，不重复写出。 */
@Composable
private fun skillStatusNote(skill: DiscoveredSkill): String? = when {
    // 内置技能所在分组的说明已写明只读，行内不再重复。
    skill.scope == SkillScope.BUILTIN -> null
    skill.status == SkillStatus.BROKEN ->
        stringResource(R.string.skills_runtime_status_broken, skill.brokenReason.orEmpty())
    skill.status == SkillStatus.PENDING_TRUST -> stringResource(R.string.skills_runtime_status_pending)
    skill.modifiedSinceConfirmed -> stringResource(R.string.skills_runtime_status_modified)
    !skill.allowImplicitInvocation -> stringResource(R.string.skills_runtime_status_explicit_only, skill.name)
    else -> null
}

@Composable
private fun SkillTrustDialog(skill: DiscoveredSkill, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    InfinityConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.skills_runtime_trust_title, skill.displayName ?: skill.name),
        message = stringResource(R.string.skills_runtime_trust_message, skill.virtualDir),
        confirmText = stringResource(R.string.skills_runtime_trust),
        cancelText = stringResource(R.string.common_btn_cancel),
        onConfirm = onConfirm,
        onCancel = onDismiss,
    )
}

/** 技能详情：路径、状态、操作与只读的 SKILL.md 原文。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillDetailSheet(
    skill: DiscoveredSkill,
    actions: SkillCatalogActions,
    onRequestTrust: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var document by remember(skill.id) { mutableStateOf<String?>(null) }
    var loading by remember(skill.id) { mutableStateOf(true) }
    LaunchedEffect(skill.id, skill.status, skill.modifiedSinceConfirmed) {
        loading = true
        document = actions.loadDocument(skill)
        loading = false
    }
    InfinityBottomSheet(
        show = true,
        onDismiss = onDismiss,
        title = skill.displayName ?: skill.name,
        height = InfinitySheetHeight.Full,
        scrollContent = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 24.dp)) {
            if (skill.description.isNotBlank()) {
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodyMedium.forReading(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            SkillDetailLine(stringResource(R.string.skills_runtime_detail_path), skill.virtualDir, monospace = true)
            SkillDetailLine(
                stringResource(R.string.skills_runtime_detail_status),
                skillStatusNote(skill) ?: stringResource(
                    if (skill.status == SkillStatus.ENABLED) R.string.skills_runtime_status_enabled else R.string.skills_runtime_status_disabled,
                ),
            )
            if (skill.files.isNotEmpty()) {
                SkillDetailLine(
                    stringResource(R.string.skills_runtime_detail_files),
                    pluralStringResource(R.plurals.agent_skills_files_count, skill.files.size, skill.files.size),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (skill.status == SkillStatus.PENDING_TRUST) {
                    OutlinedButton(onClick = onRequestTrust) { Text(stringResource(R.string.skills_runtime_trust)) }
                }
                if (skill.modifiedSinceConfirmed) {
                    OutlinedButton(onClick = { actions.onAcknowledge(skill) }) {
                        Text(stringResource(R.string.skills_runtime_acknowledge))
                    }
                }
                val onEdit = actions.onEdit
                if (onEdit != null && skill.scope == SkillScope.USER && skill.status != SkillStatus.BROKEN) {
                    OutlinedButton(onClick = { onDismiss(); onEdit(skill) }) { Text(stringResource(R.string.skills_runtime_edit)) }
                }
                if (skill.scope != SkillScope.BUILTIN) OutlinedButton(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.shared_btn_delete)) }
            }
            Text(
                "SKILL.md",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            // 读取中、读取失败与正文之间交叉淡入。
            val phase = when {
                loading -> 0
                document == null -> 1
                else -> 2
            }
            androidx.compose.animation.AnimatedContent(
                targetState = phase,
                transitionSpec = { com.promenar.infinity.ui.theme.InfinityMotion.fadeThrough() },
                label = "skill_document_state",
            ) { shown ->
                when (shown) {
                    0 -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    1 -> Text(
                        stringResource(R.string.skills_runtime_document_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> SelectionContainer {
                        Text(
                            document.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        InfinityConfirmDialog(
            onDismissRequest = { confirmDelete = false },
            title = stringResource(R.string.skills_runtime_delete_title, skill.displayName ?: skill.name),
            message = stringResource(R.string.permanent_delete_skill_message, skill.virtualDir),
            confirmText = stringResource(R.string.shared_btn_delete),
            cancelText = stringResource(R.string.common_btn_cancel),
            onConfirm = {
                confirmDelete = false
                actions.onDelete(skill)
                onDismiss()
            },
            onCancel = { confirmDelete = false },
            isDestructive = true,
        )
    }
}

@Composable
private fun SkillDetailLine(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace)
            else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 会话面板“工具”标签中的技能视图：内置、用户级各一组，目录型工作区另有项目级一组。 */
@Composable
fun SessionSkillsSections(state: SkillCatalogUiState, actions: SkillCatalogActions) {
    val snapshot = state.snapshot
    if (state.loading && snapshot == null) return
    Column(verticalArrangement = Arrangement.spacedBy(com.promenar.infinity.ui.theme.InfinitySpacing.Large)) {
        BuiltinSkillsSection(snapshot?.builtin.orEmpty(), actions)
        SkillScopeSection(
            title = stringResource(R.string.skills_runtime_section_user),
            footer = stringResource(R.string.skills_runtime_user_footer),
            skills = snapshot?.user.orEmpty(),
            emptyMessage = stringResource(R.string.skills_runtime_user_empty),
            actions = actions,
        )
        if (state.projectAvailable) {
            SkillScopeSection(
                title = stringResource(R.string.skills_runtime_section_project),
                footer = stringResource(R.string.skills_runtime_project_footer),
                skills = snapshot?.project.orEmpty(),
                emptyMessage = stringResource(R.string.skills_runtime_project_empty),
                actions = actions,
            )
        }
    }
}

/** 随应用提供的只读技能；没有内置技能时不显示该组。 */
@Composable
fun BuiltinSkillsSection(skills: List<DiscoveredSkill>, actions: SkillCatalogActions, modifier: Modifier = Modifier) {
    if (skills.isEmpty()) return
    SkillScopeSection(
        title = stringResource(R.string.runtime_guide_section_builtin),
        footer = stringResource(R.string.runtime_guide_builtin_footer),
        skills = skills,
        emptyMessage = "",
        actions = actions,
        modifier = modifier,
    )
}
