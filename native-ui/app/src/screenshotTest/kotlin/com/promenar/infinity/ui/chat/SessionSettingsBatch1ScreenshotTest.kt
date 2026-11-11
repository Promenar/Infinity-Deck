package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillSnapshot
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.data.skills.SkillWorkspace
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.ui.skills.SkillCatalogUiState
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.*

@PreviewTest
@Preview(name = "会话模型 深中411", widthDp = 411, heightDp = 780, locale = "zh-rCN")
@Preview(name = "会话模型 深中360", widthDp = 360, heightDp = 780, locale = "zh-rCN")
@Composable
fun sessionModelBatch1Preview() = SessionFrame(0) {
    ModelPanel("preview-model", {}, listOf(ModelInfo(id = "preview-model", name = "示例对话模型", description = "", type = "chat", providerId = "preview", enabled = true)))
}

@PreviewTest
@Preview(name = "会话参数 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Preview(name = "会话参数 深中360", widthDp = 360, heightDp = 1000, locale = "zh-rCN")
@Composable
fun sessionParamsBatch1Preview() = SessionFrame(1) { SessionParamsContent(InferenceParams()) }

@PreviewTest
@Preview(name = "会话参数 方案已修改 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Preview(name = "会话参数 方案已修改 深英360 字号130", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun sessionParamsSchemesPreview() = SessionFrame(1) {
    val params = InferenceParams(temperature = 0.6, topP = 0.9, parameterSchemeId = "coding")
    val schemes = listOf(
        com.promenar.infinity.data.model.params.ParameterScheme("coding", "qwen::qwen3.8-max", "写代码",
            com.promenar.infinity.data.model.params.SamplingValues(temperature = 0.2, topP = 0.9), 1, 1),
        com.promenar.infinity.data.model.params.ParameterScheme("writing", "qwen::qwen3.8-max", "长文创作",
            com.promenar.infinity.data.model.params.SamplingValues(temperature = 1.1), 2, 2),
    )
    val profile = com.promenar.infinity.data.model.params.resolveModelParameterProfile(
        com.promenar.infinity.data.remote.protocol.ProtocolType.Qwen_DashScope, "qwen3.8-max", "https://dashscope.aliyuncs.com/compatible-mode/v1",
    )
    val layout = com.promenar.infinity.ui.common.params.samplingParamLayout(profile, null)
    SessionParamsContent(params, sessionSamplingEditorState(params, InferenceParams(topK = 20), layout, schemes), modelName = "Qwen3.8 Max",
        initialExpanded = com.promenar.infinity.data.model.params.SamplingParam.TEMPERATURE)
}

@PreviewTest
@Preview(name = "会话参数 第三方接口 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Preview(name = "会话参数 第三方接口 深英360", widthDp = 360, heightDp = 1000, locale = "en")
@Composable
fun sessionParamsThirdPartyPreview() = SessionFrame(1) {
    val params = InferenceParams(maxTokens = 8192, topK = 40)
    val profile = com.promenar.infinity.data.model.params.resolveModelParameterProfile(
        com.promenar.infinity.data.remote.protocol.ProtocolType.Generic_OpenAI_Compat, "deepseek/deepseek-v4-pro", "https://openrouter.ai/api/v1",
    )
    val layout = com.promenar.infinity.ui.common.params.samplingParamLayout(profile, null)
    SessionParamsContent(params, sessionSamplingEditorState(params, null, layout, emptyList()), modelName = "DeepSeek V4 Pro",
        initialExpanded = com.promenar.infinity.data.model.params.SamplingParam.MAX_TOKENS)
}

@PreviewTest
@Preview(name = "会话参数 沿用助手 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Composable
fun sessionParamsInheritedPreview() = SessionFrame(1) {
    val params = InferenceParams(maxTokens = 8192)
    SessionParamsContent(params, sessionSamplingEditorState(params, InferenceParams(temperature = 0.3, topK = 20),
        com.promenar.infinity.ui.common.params.SamplingParamLayout(com.promenar.infinity.ui.common.params.SAMPLING_PARAM_ORDER), emptyList()), modelName = "DeepSeek V4 Pro")
}

@PreviewTest
@Preview(name = "会话工具 深中411", widthDp = 411, heightDp = 780, locale = "zh-rCN")
@Preview(name = "会话工具 深中360", widthDp = 360, heightDp = 780, locale = "zh-rCN")
@Composable
fun sessionToolsBatch1Preview() = SessionFrame(2) { SessionToolsContent() }

@PreviewTest
@Preview(name = "会话工具 技能 深中411", widthDp = 411, heightDp = 1200, locale = "zh-rCN")
@Preview(name = "会话工具 技能 深中360 字号130", widthDp = 360, heightDp = 1500, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionToolsSkillsPreview() = SessionFrame(2) { SessionToolsSkillsSample() }

@PreviewTest
@Preview(name = "会话工具 技能 浅中411", widthDp = 411, heightDp = 1200, locale = "zh-rCN")
@Composable
fun sessionToolsSkillsLightPreview() = SessionFrame(2, light = true) { SessionToolsSkillsSample() }

@PreviewTest
@Preview(name = "会话工具 已关闭 仅联网搜索 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Preview(name = "会话工具 已关闭 仅联网搜索 深中360", widthDp = 360, heightDp = 1100, locale = "zh-rCN")
@Composable
fun sessionToolsOffPreview() = SessionFrame(2) {
    SessionToolsContent(
        options = SessionOptions(toolsEnabled = false, keepWebSearchWhenToolsOff = true),
        skillsSummary = SessionSkillsSummary(enabledUserSkills = 2, projectSkills = null, workspaceName = null),
        onOpenSkills = {},
    )
}

/** 技能分组只读摘要：用户技能已启用数与当前工作区的项目技能数，点按跳全局技能包。 */
@Composable
private fun SessionToolsSkillsSample() {
    val state = SkillCatalogUiState(
        loading = false,
        projectAvailable = true,
        snapshot = SkillSnapshot(
            workspace = SkillWorkspace(key = "preview", root = java.io.File("/workspaces/infinity-app")),
            skills = listOf(
                previewSkill("pdf-report", "把分析结果整理为带目录与图表的 PDF 报告。"),
                previewSkill("git-release", "按仓库约定生成版本说明并打标签。", modified = true),
                previewSkill("meeting-notes", "从会议录音转写中提炼决议与待办。", status = SkillStatus.DISABLED, implicit = false),
                previewSkill("android-build", "构建并校验两个发行渠道的 APK。", scope = SkillScope.PROJECT),
                previewSkill("db-migration", "为 Room 迁移编写测试并核对 schema。", scope = SkillScope.PROJECT, status = SkillStatus.PENDING_TRUST),
                previewSkill("broken-skill", "", scope = SkillScope.PROJECT, status = SkillStatus.BROKEN, reason = "name 与目录名不一致"),
            ),
        ),
    )
    SessionToolsContent(skillsSummary = sessionSkillsSummary(state), onOpenSkills = {})
}

private fun previewSkill(
    name: String,
    description: String,
    scope: SkillScope = SkillScope.USER,
    status: SkillStatus = SkillStatus.ENABLED,
    modified: Boolean = false,
    implicit: Boolean = true,
    reason: String? = null,
) = DiscoveredSkill(
    id = "${scope.name.lowercase()}:$name",
    scope = scope,
    name = name,
    description = description,
    displayName = null,
    shortDescription = null,
    virtualDir = if (scope == SkillScope.USER) "/skills/$name" else "/.agents/skills/$name",
    files = emptyList(),
    status = status,
    modifiedSinceConfirmed = modified,
    allowImplicitInvocation = implicit,
    brokenReason = reason,
    metadata = null,
)

@PreviewTest
@Preview(name = "会话上下文 深中411", widthDp = 411, heightDp = 1120, locale = "zh-rCN")
@Preview(name = "会话上下文 深中360", widthDp = 360, heightDp = 1120, locale = "zh-rCN")
@Preview(name = "会话上下文 深中130", widthDp = 411, heightDp = 1400, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionContextBatch1Preview() = SessionFrame(3) { SessionContextPanelContent() }

/** 会话档位超出助手上限（对话记忆存为“全部助手”，助手上限为“本助手”），以及文档检索关闭。 */
@PreviewTest
@Preview(name = "会话检索范围 受限 浅中411", widthDp = 411, heightDp = 1180, locale = "zh-rCN")
@Preview(name = "会话检索范围 受限 浅中360 字号130", widthDp = 360, heightDp = 1500, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionRetrievalScopeCappedPreview() = SessionFrame(3, light = true) {
    SessionContextPanelContent(
        ragOptions = com.promenar.infinity.data.model.RagOptions(
            enableDocs = false,
            enableKnowledgeGraph = true,
            memoryScope = com.promenar.infinity.data.model.ConversationScope.ALL_AGENTS,
            graphScope = com.promenar.infinity.data.model.ConversationScope.AGENT,
        ),
        memoryPolicy = com.promenar.infinity.data.agent.AgentMemoryPolicy(
            memoryCap = com.promenar.infinity.data.model.ConversationScope.AGENT,
        ),
    )
}

/** 档位选择面板内容：超出助手上限的档位不可选并注明原因。 */
@PreviewTest
@Preview(name = "检索范围选择 深中411", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Preview(name = "检索范围选择 深中360 字号130", widthDp = 360, heightDp = 560, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun retrievalScopeChoicesPreview() = SessionFrame(3) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        ScopeChoiceList(ScopeResource.Memory, selectedOrdinal = 1, onSelect = {}, maxOrdinal = 1)
    }
}

/** 助手编辑页“记忆与知识”分组：私密助手，对话记忆上限为“本助手”。 */
@PreviewTest
@Preview(name = "助手记忆与知识 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Preview(name = "助手记忆与知识 深中360 字号130", widthDp = 360, heightDp = 860, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun agentMemoryPolicyPreview() = SessionFrame(3) {
    Column(Modifier.padding(16.dp)) {
        com.promenar.infinity.ui.hub.AgentScopeSection(
            policy = com.promenar.infinity.data.agent.AgentMemoryPolicy(
                memoryCap = com.promenar.infinity.data.model.ConversationScope.AGENT,
                shareWithOtherAgents = false,
            ),
            onPolicyChange = {},
            onOpenKnowledgeSettings = {},
        )
    }
}

@Composable
private fun SessionFrame(index: Int, light: Boolean = false, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (light) InfinityThemeMode.LIGHT else InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxSize()) {
                Text(stringResource(R.string.sheet_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                InfinityPanelTabs(
                    tabs = listOf(R.string.sheet_tab_model, R.string.sheet_tab_thinking, R.string.sheet_tab_tools, R.string.sheet_tab_settings)
                        .mapIndexed { i, res -> InfinityPanelTab(i, stringResource(res)) },
                    selected = index,
                    onSelect = {},
                )
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
