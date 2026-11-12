package com.promenar.infinity.ui.chat.diff

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.model.ApprovalCallIdentity
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.repository.WorkspaceLinePatchResult
import com.promenar.infinity.data.repository.applyWorkspaceLinePatch
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.isUserSkills
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.ui.chat.ApprovalPreview
import com.promenar.infinity.ui.chat.manager.skills.FilePatchSkill
import com.promenar.infinity.ui.chat.manager.skills.MountedWorkspaceRepository
import com.promenar.infinity.ui.chat.manager.skills.normalizeWorkspaceToolPath
import com.promenar.infinity.ui.common.diff.FileChangeKind
import com.promenar.infinity.ui.common.diff.FileDiffContent
import com.promenar.infinity.ui.common.diff.FileDiffLimits
import com.promenar.infinity.ui.common.diff.FileDiffModel
import com.promenar.infinity.ui.common.diff.splitDiffText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 审批卡的差异预览；为空时界面回退到参数文本片段。 */
@Immutable
sealed interface ApprovalDiffPreview {
    /** 写入、新建或补丁应用后的逐行差异。 */
    data class Diff(val model: FileDiffModel) : ApprovalDiffPreview

    /** 删除文件：只给出将删除的行数。 */
    data class Deletion(val lines: Int) : ApprovalDiffPreview

    /**
     * `agents_write`：操作与对象、有变化的字段（旧值 → 新值）、提示词差异与影响说明。
     * [targetName] 为 Agent 名称或会话标题；新建会话时 [ownerAgentName] 为归属 Agent。
     * [promptDiff] 为空表示提示词未改；[sessionPrompt] 区分会话提示词与 Agent 提示词。
     */
    data class AgentConfig(
        val op: com.promenar.infinity.domain.agentconfig.AgentConfigOp,
        val targetName: String,
        val ownerAgentName: String? = null,
        val isCurrentTarget: Boolean = false,
        val fields: List<FieldChange> = emptyList(),
        val promptDiff: FileDiffModel? = null,
        val sessionPrompt: Boolean = false,
        val impact: AgentConfigImpact,
        val reason: String = "",
    ) : ApprovalDiffPreview

    /** 模板审批展示节点类型、模型输入和有效通道能力的变化。 */
    data class ComfyTemplate(
        val templateName: String,
        val hostName: String,
        val isNew: Boolean,
        val reason: String,
        val addedNodeTypes: List<String>,
        val removedNodeTypes: List<String>,
        val modelChanges: List<FieldChange>,
        val capabilityChanges: List<FieldChange>,
    ) : ApprovalDiffPreview
}

/** 审批卡字段表的一行；[key] 为工具字段键，[oldValue]/[newValue] 已格式化为显示文本，旧值为空表示新建。 */
@Immutable
data class FieldChange(val key: String, val oldValue: String?, val newValue: String?)

/** 审批卡的影响说明。 */
@Immutable
sealed interface AgentConfigImpact {
    /** 修改 Agent 提示词：影响全部 [sessionCount] 个会话，修改前的内容可在编辑页恢复。 */
    data class AgentPrompt(val agentName: String, val sessionCount: Int) : AgentConfigImpact
    /** 修改 Agent 其它字段：影响之后的轮次与新建会话的默认值。 */
    data class AgentFields(val agentName: String, val sessionCount: Int) : AgentConfigImpact
    data object NewAgent : AgentConfigImpact
    data object NewSession : AgentConfigImpact
    data object CurrentSession : AgentConfigImpact
    data object OtherSession : AgentConfigImpact
}

/**
 * 在组合之外为待审批的文件工具计算差异预览：
 * - `write_file`：当前内容 → 参数中的新内容；
 * - `create_file`：全部为新增，不读取文件；
 * - `patch_file`：当前内容 → 按补丁干运行（与执行时同一应用逻辑）后的内容；
 * - `delete_file`：只统计将删除的行数。
 *
 * 当前内容在 [Dispatchers.IO] 读取，私有工作区与 `/mnt` 挂载、自选目录工作区（rooted）都支持；
 * 差异在 [Dispatchers.Default] 计算。读取失败、二进制、目录、超过 [FileDiffLimits] 阈值或补丁无法应用时返回空。
 */
class ApprovalDiffPreviewLoader(
    private val workspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess?,
    private val limits: FileDiffLimits = FileDiffLimits.Default,
    private val privateReader: PrivateWorkspaceReader = PrivateWorkspaceReader.Default,
) {
    suspend fun load(
        toolName: String,
        args: JsonObject,
        sessionId: String,
        privateWorkspaceRootUuid: String?,
    ): ApprovalDiffPreview? = try {
        when (toolName) {
            "create_file" -> {
                val content = args.string("content").orEmpty()
                diff("", content, FileChangeKind.CREATED)
            }
            "write_file", "patch_file", "delete_file" -> loadExisting(toolName, args, sessionId, privateWorkspaceRootUuid)
            else -> null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun loadExisting(
        toolName: String,
        args: JsonObject,
        sessionId: String,
        privateWorkspaceRootUuid: String?,
    ): ApprovalDiffPreview? {
        val reader = reader(sessionId, privateWorkspaceRootUuid) ?: return null
        val uuid = args.string("uuid")?.trim()
        val path = if (uuid != null) reader.pathOf(uuid) ?: return null
            else args.string("path")?.let(::normalizeWorkspaceToolPath) ?: return null
        val current = (reader.read(path).snapshot as? FileSnapshot.Text)?.text ?: return null
        return when (toolName) {
            "write_file" -> diff(current, args.string("content") ?: return null, FileChangeKind.MODIFIED)
            "patch_file" -> {
                val operations = (args["operations"] as? JsonArray)
                    ?.let(FilePatchSkill::parsePatchOperations)
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null
                val patched = withContext(Dispatchers.Default) {
                    applyWorkspaceLinePatch(current, operations, fileUuid = "")
                } as? WorkspaceLinePatchResult.Applied ?: return null
                diff(current, patched.content, FileChangeKind.MODIFIED)
            }
            "delete_file" -> ApprovalDiffPreview.Deletion(withContext(Dispatchers.Default) { splitDiffText(current).lines.size })
            else -> null
        }
    }

    private suspend fun reader(sessionId: String, privateWorkspaceRootUuid: String?): WorkspaceSnapshotReader? {
        val workspaceMount = mounts?.workspaceMount(sessionId)
        val rooted = workspaceMount != null
        val rootUuid = workspaceMount?.let { ExternalMountPaths.uuid(it, "/") } ?: privateWorkspaceRootUuid
            ?: mounts?.visible(sessionId)?.firstOrNull { it.isUserSkills }?.let { ExternalMountPaths.uuid(it, "/") }
            ?: return null
        val namespace = mounts?.let { MountedWorkspaceRepository(workspace, it, sessionId, rooted) } ?: workspace
        return WorkspaceSnapshotReader(namespace, workspace, mounts, sessionId, rootUuid, rooted, privateReader = privateReader)
    }

    /** 只有逐行差异可用时才给出预览；二进制与超阈值的摘要降级回退到文本片段。 */
    private suspend fun diff(oldText: String, newText: String, kind: FileChangeKind): ApprovalDiffPreview? =
        withContext(Dispatchers.Default) {
            val model = FileDiffModel.fromTexts(oldText, newText, changeKind = kind, limits = limits)
            if (model.content is FileDiffContent.Lines) ApprovalDiffPreview.Diff(model) else null
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
}

/**
 * 审批卡的差异预览状态：首次组合为空（显示文本片段），后台计算完成后切换为差异预览。
 * 只在应用为 [InfinityApplication] 时读取工作区（预览与截图环境回退到文本片段）。
 */
@Composable
internal fun rememberApprovalDiffPreview(
    call: ApprovalCallIdentity,
    messages: List<Message>,
    assistantMessageId: String?,
    sessionId: String?,
    privateWorkspaceRootUuid: String?,
    approvalReason: String = "",
): ApprovalDiffPreview? {
    // 实验室沙箱里不读工作区，审批卡回退为参数片段。
    val sandboxed = com.promenar.infinity.ui.common.LocalLabSandbox.current
    val application = (LocalContext.current.applicationContext as? InfinityApplication)?.takeUnless { sandboxed }
    val loader = remember(application) {
        application?.let { ApprovalDiffPreviewLoader(it.workspaceRepository, it.externalFolderManager) }
    }
    val arguments = remember(call, messages, assistantMessageId) {
        ApprovalPreview.arguments(call, messages, assistantMessageId)
    }
    val agentConfigLabels = rememberAgentConfigValueLabels()
    val preview by produceState<ApprovalDiffPreview?>(null, loader, call.toolName, arguments, sessionId, privateWorkspaceRootUuid, approvalReason) {
        value = when {
            loader == null || application == null || arguments == null || sessionId == null -> null
            call.toolName == com.promenar.infinity.domain.agentconfig.AgentConfigTools.WRITE -> try {
                com.promenar.infinity.domain.agentconfig.AgentsWriteRequest.parse(arguments)?.let { request ->
                    AgentConfigPreviewBuilder.build(request, sessionId, AppAgentConfigPreviewSources(application), agentConfigLabels)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            call.toolName == "comfy_define_template" ->
                ComfyTemplatePreviewLoader(AppComfyTemplatePreviewSources(application.comfyMedia.repository))
                    .load(arguments, approvalReason)
            else -> loader.load(call.toolName, arguments, sessionId, privateWorkspaceRootUuid)
        }
    }
    return preview
}

@Composable
private fun rememberAgentConfigValueLabels(): AgentConfigValueLabels {
    val on = androidx.compose.ui.res.stringResource(com.promenar.infinity.R.string.agent_config_value_on)
    val off = androidx.compose.ui.res.stringResource(com.promenar.infinity.R.string.agent_config_value_off)
    val workspace = androidx.compose.ui.res.stringResource(com.promenar.infinity.R.string.common_mode_workspace)
    val untitled = androidx.compose.ui.res.stringResource(com.promenar.infinity.R.string.agent_config_untitled_session)
    val efforts = com.promenar.infinity.data.model.params.ReasoningEffort.entries.associate {
        it.wireName to com.promenar.infinity.ui.chat.reasoningEffortLabel(it)
    }
    return remember(on, off, workspace, untitled, efforts) {
        AgentConfigValueLabels(on, off, workspace, untitled) { wire -> efforts[wire] ?: wire }
    }
}
