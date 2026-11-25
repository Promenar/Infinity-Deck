package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.jobs.BackgroundJobManager
import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** 从已启用技能包按包内相对目录树原样导入提交器及配套文件的解析结果。 */
data class SubmitterImportedBundle(
    val entryFileName: String,
    val sourceText: String,
    val supportFiles: Map<String, ByteArray> = emptyMap(),
)

/**
 * ComfyUI 独立兼容层向智能体暴露的全部工具：
 * - 只读查询/预检/链路状态为 `SAFE_READ`；
 * - 新增/更新/删除提交器脚本与工作流（含传统 `comfy_define_template`）固定为 `AGENT_CONFIG`（每次均需用户审批且禁止限时免批）；
 * - `comfy_run_workflow` 默认为 `FREE`；当 `fixed_overrides` 偏离工作流固定基线时，由审批底座绑定一次性 Diff 审批并二次核验。
 *
 * 主脚本在内部受控 Python 3.14 运行；此处绝不向智能体暴露第 3 个通用 Python 编程工具。
 */
fun comfyTools(
    repository: ComfyRepository,
    chainStore: StatefulChainStore? = null,
    executionEngine: ComfyWorkflowExecutionEngine? = null,
    submitterRunner: ComfySubmitterRunner? = null,
    jobs: BackgroundJobManager? = null,
    readWorkspaceFile: (suspend (sessionId: String, path: String) -> ByteArray?)? = null,
    importSkillSubmitter: (suspend (sessionId: String, skillName: String, entryPath: String, includePaths: List<String>) -> SubmitterImportedBundle)? = null,
    productionPanelNudge: ProductionPanelNudgeTracker? = null,
): List<SkillDefinition> = listOf(
    ComfyProbeSkill(repository),
    ComfyListTemplatesSkill(repository),
    ComfyDefineTemplateSkill(repository),
    ComfyListHostsSkill(repository, submitterRunner),
    ComfyListSubmittersSkill(repository, submitterRunner),
    ComfyListWorkflowsSkill(repository),
    ComfyPreflightWorkflowSkill(repository, submitterRunner),
    ComfyGetChainStateSkill(chainStore),
    ComfySaveSubmitterSkill(repository, submitterRunner, readWorkspaceFile, importSkillSubmitter),
    ComfyDeleteSubmitterSkill(repository),
    ComfySaveWorkflowSkill(repository),
    ComfyDeleteWorkflowSkill(repository),
    ComfyRunWorkflowSkill(repository, executionEngine, jobs, productionPanelNudge),
    ComfySetChainTipSkill(chainStore),
)

class ComfyProbeSkill(repository: ComfyRepository) : ComfySkill(
    repository,
    "comfy_probe",
    "探测已配置的 ComfyUI 主机，返回节点与模型候选摘要。必须传入 node_type 或 node_types（1～30 个节点类型，二者选其一）按节点分片探测；不支持无过滤拉取全量 /object_info。",
    """{"type":"object","properties":{"host_id":{"type":"string"},"node_type":{"type":"string","description":"单个精确节点类名，与 node_types 二选一"},"node_types":{"type":"array","items":{"type":"string"},"description":"1～30 个精确节点类名，4 并发分片读取，与 node_type 二选一"}},"required":["host_id"],"additionalProperties":false}""",
)

class ComfyListTemplatesSkill(repository: ComfyRepository) : ComfySkill(
    repository,
    "comfy_templates",
    "列出已配置主机和 ComfyUI 工作流（兼容旧模板结构）、通道 id、锁定状态、预检结果。recheck=true 重新预检指定工作流。",
    """{"type":"object","properties":{"template_id":{"type":"string"},"recheck":{"type":"boolean"}},"additionalProperties":false}""",
)

class ComfyDefineTemplateSkill(repository: ComfyRepository) : ComfySkill(
    repository,
    "comfy_define_template",
    """创建或按同一 id 更新 API Format 工作流（兼容旧模板 schema）。每次变更须经用户审批，保存后保持锁定并重新预检。模型文件由 models 显式声明。
        支持可选 fps（视频输出帧率，如 24），声明后制作规划按镜头秒数自动换算并对齐 frameBase+k*frameStep 帧数；ComfyUI V3 动态组合子选项（如 SaveVideo 的 format.codec）在 API Format 中须使用父键.子键扁平键。
        生成工作流使用 role=GENERATE，图像、视频、语音或音乐按 group 与 capabilities 声明；配乐使用 role=GENERATE、group=MUSIC、MUSIC 模式、DURATION 秒数槽位与 AUDIO 产出。
        派生处理使用 role=DERIVE、operation.label 与一个 INPUT_VIDEO 来源槽位，可不声明 PROMPT。媒体连线必须给出 uploadTargets，节点类型和输入名先用 comfy_probe 核验。
        任意工作流可用 params 声明最多 6 个 ENUM/NUMBER/BOOLEAN 参数，每项以唯一 key 对应一个 PARAM 槽位的 param。default 必须满足选项、范围与整数约束，提交请求时可按 key 覆盖 params。
        派生处理最小字段示例（workflow 使用主机探测所得的 API Format 节点字典）：{"id":"upscale","hostId":"host","group":"VIDEO","role":"DERIVE","operation":{"label":"超分"},"slots":[{"nodeId":"1","input":"video","kind":"INPUT_VIDEO"},{"nodeId":"2","input":"videos","kind":"OUTPUT","outputKind":"VIDEO"}],"capabilities":{"modes":["REFERENCE"]}}。
        配乐最小字段示例（workflow 使用主机探测所得的 API Format 节点字典）：{"id":"music","hostId":"host","group":"MUSIC","role":"GENERATE","slots":[{"nodeId":"1","input":"prompt","kind":"PROMPT"},{"nodeId":"1","input":"seconds","kind":"DURATION"},{"nodeId":"2","input":"audio","kind":"OUTPUT","outputKind":"AUDIO"}],"capabilities":{"modes":["MUSIC"]}}。""".trimIndent(),
    COMFY_TEMPLATE_SCHEMA,
)

class ComfyListHostsSkill(
    repository: ComfyRepository,
    submitterRunner: ComfySubmitterRunner? = null,
) : ComfySkill(
    repository,
    "comfy_list_hosts",
    "列出所有已在设置中配置的 ComfyUI 主机、网络地址、鉴权方式与凭据配置状态（不回显任何密钥），以及关联提交器脚本与工作流数量。",
    """{"type":"object","properties":{},"additionalProperties":false}""",
    submitterRunner = submitterRunner,
)

class ComfyListSubmittersSkill(
    repository: ComfyRepository,
    submitterRunner: ComfySubmitterRunner? = null,
) : ComfySkill(
    repository,
    "comfy_list_submitters",
    "列出 ComfyUI 提交器脚本清单、调用协议（PAYLOAD_JSON / CLI_ARGS）、配套模块文件与最近一次内置 Python 3.14 干跑自检状态；可按 host_id 过滤。",
    """{"type":"object","properties":{"host_id":{"type":"string","description":"可选，仅查看该主机下的脚本"}},"additionalProperties":false}""",
    submitterRunner = submitterRunner,
)

class ComfyListWorkflowsSkill(repository: ComfyRepository) : ComfySkill(
    repository,
    "comfy_list_workflows",
    "列出 ComfyUI 工作流。默认列表中仅返回轻量摘要；传入 workflow_id 或 detail=true 时返回全部 CONTENT/FIXED 完整声明、chain_spec 与预检详情。传入 workflow_id 与 recheck=true 可触发重新预检。",
    """{"type":"object","properties":{"host_id":{"type":"string"},"workflow_id":{"type":"string"},"media_category":{"type":"string","enum":["IMAGE","VIDEO","SPEECH","MUSIC","AUDIO","POST_PROCESS","MODEL_3D","FILE"]},"detail":{"type":"boolean","description":"为 true 或指定 workflow_id 时返回完整字段定义，列表默认返回轻量摘要"},"recheck":{"type":"boolean"}},"additionalProperties":false}""",
)

class ComfyPreflightWorkflowSkill(
    repository: ComfyRepository,
    submitterRunner: ComfySubmitterRunner? = null,
) : ComfySkill(
    repository,
    "comfy_preflight_workflow",
    "对指定 ComfyUI 工作流执行即时预检（连通性、节点/模型或内置通用 JSON 检查），可选同步在内置 Python 3.14 中干跑自检绑定的提交器脚本（run_dry_run=true）。",
    """{"type":"object","properties":{"workflow_id":{"type":"string"},"run_dry_run":{"type":"boolean","description":"默认 true，对绑定的 .py 提交器执行内置 Python 干跑校验"}},"required":["workflow_id"],"additionalProperties":false}""",
    submitterRunner = submitterRunner,
)

class ComfyGetChainStateSkill(
    chainStore: StatefulChainStore? = null,
) : ComfySkill(
    null,
    "comfy_get_chain_state",
    "读取当前会话下有状态链式工作流（stateful_chain，如 H3 Motion Context 多段接续）的完整 DAG 版本树、active_tip_node_id、各段产物路径与潜空间有效状态（ACTIVE / OVERWRITTEN_BY_SIBLING / EXPIRED），用于自主决定接续或从任意中段重跑/分叉。",
    """{"type":"object","properties":{"chain_id":{"type":"string"},"workflow_id":{"type":"string"},"shot_ref":{"type":"string"}},"additionalProperties":false}""",
    chainStore = chainStore,
)

class ComfySaveSubmitterSkill(
    repository: ComfyRepository,
    submitterRunner: ComfySubmitterRunner? = null,
    readWorkspaceFile: (suspend (sessionId: String, path: String) -> ByteArray?)? = null,
    importSkillSubmitter: (suspend (sessionId: String, skillName: String, entryPath: String, includePaths: List<String>) -> SubmitterImportedBundle)? = null,
) : ComfySkill(
    repository,
    "comfy_save_submitter",
    "新增或更新 ComfyUI 主机下的 Python 3.14 提交器脚本（如 submit_qwen21.py / submit_h3_motion.py）及配套 JSON/Python 模块文件。支持 via skill_import 直接从已启用技能包按原始目录层级零改写导入，无需人工抄录。须经用户一次性审批；保存后默认执行干跑自检。编写自定义 PAYLOAD_JSON 脚本前请用 read_skill_file(name=\"infinity-deck\", path=\"references/comfyui-submitters.md\") 读取契约。",
    COMFY_SAVE_SUBMITTER_SCHEMA,
    submitterRunner = submitterRunner,
    readWorkspaceFile = readWorkspaceFile,
    importSkillSubmitter = importSkillSubmitter,
)

class ComfyDeleteSubmitterSkill(repository: ComfyRepository) : ComfySkill(
    repository,
    "comfy_delete_submitter",
    "删除指定 ComfyUI 主机下的提交器脚本（仍被工作流引用时拒绝删除）。每次均需用户一次性审批。",
    """{"type":"object","properties":{"host_id":{"type":"string"},"script_id":{"type":"string"},"reason":{"type":"string","description":"删除原因，最多 300 字"}},"required":["host_id","script_id"],"additionalProperties":false}""",
)

class ComfySaveWorkflowSkill(repository: ComfyRepository) : ComfySkill(
    repository,
    "comfy_save_workflow",
    "创建或更新万能 ComfyUI 工作流定义（不限字段数量），声明所有 CONTENT（生成时按任务填写）与 FIXED（工作流固化参数，实验改动需经 Diff 审批）字段、12 种字段类型、绑定提交器脚本或内置 API Format JSON、以及可选的有状态链（stateful_chain）配置。每次变更须经用户一次性审批。",
    COMFY_SAVE_WORKFLOW_SCHEMA,
)

class ComfyDeleteWorkflowSkill(repository: ComfyRepository) : ComfySkill(
    repository,
    "comfy_delete_workflow",
    "删除指定 ComfyUI 工作流定义。每次变更须经用户一次性审批。",
    """{"type":"object","properties":{"workflow_id":{"type":"string"},"reason":{"type":"string","description":"删除原因，最多 300 字"}},"required":["workflow_id"],"additionalProperties":false}""",
)

class ComfyRunWorkflowSkill(
    repository: ComfyRepository,
    executionEngine: ComfyWorkflowExecutionEngine? = null,
    jobs: BackgroundJobManager? = null,
    productionPanelNudge: ProductionPanelNudgeTracker? = null,
) : ComfySkill(
    repository,
    "comfy_run_workflow",
    "执行已注册的 ComfyUI 工作流。日常生成仅传入 content_inputs，不触发工作流固定参数审批；仅当传入 fixed_overrides 且值偏离已保存基线时，系统自动弹出精确 Diff 卡片请求一次性实验批准（批准仅当次生效，绝不修改原工作流）。支持单次执行（stage=single）和有状态链式执行（stage=first / next、指定 chain_id / parent_node_id 从任意有效中段接续分叉、或 retry_node_id 重跑指定段落），并支持后台任务参数。",
    COMFY_RUN_WORKFLOW_SCHEMA,
    executionEngine = executionEngine,
    jobs = jobs,
    productionPanelNudge = productionPanelNudge,
)

class ComfySetChainTipSkill(
    chainStore: StatefulChainStore? = null,
) : ComfySkill(
    null,
    "comfy_set_chain_tip",
    "切换有状态链（stateful_chain）的当前主链末端节点 active_tip_node_id 到任意状态仍为 ACTIVE 的历史段落节点，便于后续 stage=next 默认从选定分支继续。",
    """{"type":"object","properties":{"chain_id":{"type":"string"},"node_id":{"type":"string"}},"required":["chain_id","node_id"],"additionalProperties":false}""",
    chainStore = chainStore,
)

abstract class ComfySkill(
    private val repository: ComfyRepository?,
    override val name: String,
    override val description: String,
    override val parametersSchema: String,
    private val chainStore: StatefulChainStore? = null,
    private val executionEngine: ComfyWorkflowExecutionEngine? = null,
    private val submitterRunner: ComfySubmitterRunner? = null,
    private val jobs: BackgroundJobManager? = null,
    private val readWorkspaceFile: (suspend (sessionId: String, path: String) -> ByteArray?)? = null,
    private val importSkillSubmitter: (suspend (sessionId: String, skillName: String, entryPath: String, includePaths: List<String>) -> SubmitterImportedBundle)? = null,
    private val productionPanelNudge: ProductionPanelNudgeTracker? = null,
) : SkillDefinition {
    override val id get() = name
    override val mcpServerId: String? = null
    override val risk: ToolRisk
        get() = when (name) {
            "comfy_define_template",
            "comfy_save_submitter",
            "comfy_delete_submitter",
            "comfy_save_workflow",
            "comfy_delete_workflow",
            -> ToolRisk.AGENT_CONFIG
            "comfy_run_workflow" -> ToolRisk.EXTERNAL_WRITE
            "comfy_set_chain_tip" -> ToolRisk.SESSION_STATE
            else -> ToolRisk.SAFE_READ
        }

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        return try {
            when (name) {
                "comfy_run_workflow" -> executeRunWorkflow(args, context)
                else -> {
                    val content = dispatchNonRunning(args, context)
                    ToolResult(id = "comfy_${UUID.randomUUID()}", content = content.toString())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ComfyFailure) {
            errorResult(e.message ?: "ComfyUI 操作失败")
        } catch (_: IllegalStateException) {
            errorResult("配置已变化，请重新读取后操作")
        } catch (_: SerializationException) {
            errorResult("ComfyUI 配置字段无法解析，请检查已保存的配置")
        } catch (e: IllegalArgumentException) {
            errorResult(safeParameterError(e))
        } catch (_: Exception) {
            errorResult("ComfyUI 操作失败，请检查参数、主机连接和配置")
        }
    }

    private suspend fun dispatchNonRunning(args: JsonObject, context: SkillExecutionContext): JsonObject {
        val repo = repository
        return when (name) {
            "comfy_probe" -> executeProbe(requireNotNull(repo), args)
            "comfy_define_template" -> executeDefineTemplate(requireNotNull(repo), args)
            "comfy_templates" -> executeListTemplates(requireNotNull(repo), args)
            "comfy_list_hosts" -> executeListHosts(requireNotNull(repo), args, context)
            "comfy_list_submitters" -> executeListSubmitters(requireNotNull(repo), args)
            "comfy_list_workflows" -> executeListWorkflows(requireNotNull(repo), args)
            "comfy_preflight_workflow" -> executePreflightWorkflow(requireNotNull(repo), args, context)
            "comfy_get_chain_state" -> executeGetChainState(args, context)
            "comfy_save_submitter" -> executeSaveSubmitter(requireNotNull(repo), args, context)
            "comfy_delete_submitter" -> executeDeleteSubmitter(requireNotNull(repo), args)
            "comfy_save_workflow" -> executeSaveWorkflow(requireNotNull(repo), args)
            "comfy_delete_workflow" -> executeDeleteWorkflow(requireNotNull(repo), args)
            "comfy_set_chain_tip" -> executeSetChainTip(args, context)
            else -> throw ComfyFailure("未知 ComfyUI 工具")
        }
    }

    private suspend fun executeProbe(repo: ComfyRepository, args: JsonObject): JsonObject {
        keys(args, setOf("host_id"), setOf("host_id", "node_type", "node_types"))
        val hostId = text(args, "host_id")
        val nodeType = optional(args, "node_type")
        val nodeTypes = if ("node_types" !in args || args["node_types"] == JsonNull) null else {
            val array = args["node_types"] as? JsonArray ?: throw ComfyFailure("node_types 必须是字符串数组")
            array.map {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotBlank() }
                    ?: throw ComfyFailure("node_types 的每项必须是非空字符串")
            }
        }
        if ((nodeType == null) == (nodeTypes == null)) {
            throw ComfyFailure("comfy_probe 必须提供 node_type 或 node_types（1～30 个节点名，二者选其一）；为避免慢速中转挂起，不支持无过滤全量探测")
        }
        return if (nodeType != null) repo.probe(hostId, nodeType) else repo.probeNodes(hostId, requireNotNull(nodeTypes))
    }

    private suspend fun executeDefineTemplate(repo: ComfyRepository, args: JsonObject): JsonObject {
        keys(args, setOf("template"), setOf("template"))
        val raw = args["template"] as? JsonObject ?: throw ComfyFailure("template 必须是对象")
        require(raw.toString().toByteArray().size <= 1024 * 1024) { "工作流参数超过 1 MiB" }
        val validation = ToolSchemaValidator().validate(parametersSchema, args)
        if (validation is ToolSchemaValidation.Invalid) {
            throw ComfyFailure("${validation.error.path.removePrefix("$.template.")}: ${validation.error.message}")
        }
        val template = try {
            ComfyConfigurationCodec.decodeTemplate(raw)
        } catch (_: SerializationException) {
            throw ComfyFailure("template 字段、枚举或类型无效，请按参数说明填写")
        }
        return renderTemplateState(repo.defineTemplateByAgent(template))
    }

    private suspend fun executeListTemplates(repo: ComfyRepository, args: JsonObject): JsonObject {
        keys(args, emptySet(), setOf("template_id", "recheck"))
        val templateId = optional(args, "template_id")
        val recheck = optionalBoolean(args, "recheck", defaultValue = false)
        require(!recheck || templateId != null) { "重新预检需要 template_id" }
        if (recheck) repo.preflight(requireNotNull(templateId))
        val all = repo.templates().filter { templateId == null || it.template.id == templateId }
        require(templateId == null || all.isNotEmpty()) { "工作流不存在" }
        return buildJsonObject {
            put("count", all.size)
            put("hosts", JsonArray(repo.hosts().map { host -> buildJsonObject { put("id", host.id); put("name", host.name) } }))
            val rendered = boundedTemplateStates(all)
            put("templates", JsonArray(rendered))
            put("truncated", all.size > rendered.size)
        }
    }

    private suspend fun executeListHosts(
        repo: ComfyRepository,
        args: JsonObject,
        context: SkillExecutionContext,
    ): JsonObject {
        keys(args, emptySet(), emptySet())
        val hosts = repo.hosts()
        val submitters = repo.submitters()
        val workflows = repo.effectiveWorkflowStates()
        val rendered = hosts.map { host ->
            val credConfigured = host.authKind == ComfyAuthKind.NONE || repo.hasHostCredential(host.id)
            buildJsonObject {
                put("id", host.id)
                put("name", host.name)
                put("base_url", host.baseUrl)
                put("auth_kind", host.authKind.name)
                put("credential_configured", credConfigured)
                put("timeout_seconds", host.timeoutSeconds)
                put("audio_upload_path", host.audioUploadPath)
                put("audio_upload_field", host.audioUploadField)
                put("submitter_count", submitters.count { it.hostId == host.id })
                put("workflow_count", workflows.count { it.workflow.hostId == host.id })
            }
        }
        val activeRuntime = submitterRunner?.resolveRuntime(context.sessionId)
        val availRuntimes = submitterRunner?.availableRuntimes().orEmpty()
        val probedTools = if (submitterRunner != null && activeRuntime != null) {
            submitterRunner.checkTools(activeRuntime, listOf("ffmpeg", "ffprobe"))
        } else emptyMap()
        return buildJsonObject {
            put("count", hosts.size)
            put("python_available", submitterRunner?.isPythonAvailable == true)
            activeRuntime?.let { put("execution_runtime", it.name) }
            put("available_runtimes", JsonArray(availRuntimes.map { JsonPrimitive(it.name) }))
            if (probedTools.isNotEmpty()) {
                put("available_tools", JsonObject(probedTools.mapValues { JsonPrimitive(it.value) }))
            }
            put("hosts", JsonArray(rendered))
        }
    }

    private suspend fun executeListSubmitters(repo: ComfyRepository, args: JsonObject): JsonObject {
        keys(args, emptySet(), setOf("host_id"))
        val hostId = optional(args, "host_id")
        val list = repo.submitters(hostId)
        val rendered = list.take(200).map(::renderSubmitter)
        return buildJsonObject {
            put("count", list.size)
            put("python_available", submitterRunner?.isPythonAvailable == true)
            put("submitters", JsonArray(rendered))
            put("truncated", list.size > rendered.size)
        }
    }

    private suspend fun executeListWorkflows(repo: ComfyRepository, args: JsonObject): JsonObject {
        keys(args, emptySet(), setOf("host_id", "workflow_id", "media_category", "detail", "recheck"))
        val hostId = optional(args, "host_id")
        val workflowId = optional(args, "workflow_id")
        val categoryRaw = optional(args, "media_category")
        val categoryFilterSet = categoryRaw?.let { raw ->
            if (raw.equals("AUDIO", ignoreCase = true)) {
                setOf(ComfyMediaCategory.SPEECH, ComfyMediaCategory.MUSIC)
            } else {
                val matched = ComfyMediaCategory.entries.firstOrNull { c -> c.name == raw }
                    ?: throw IllegalArgumentException("media_category 无效：$raw")
                setOf(matched)
            }
        }
        val detail = optionalBoolean(args, "detail", defaultValue = false)
        val recheck = optionalBoolean(args, "recheck", defaultValue = false)
        require(!recheck || workflowId != null) { "recheck=true 需要指定 workflow_id" }
        if (recheck && workflowId != null) {
            repo.preflightWorkflow(workflowId)
        }
        val submittersByKey = repo.submitters().associateBy { it.hostId to it.scriptId }
        val filtered = repo.effectiveWorkflowStates(hostId).filter { state ->
            (workflowId == null || state.workflow.workflowId == workflowId) &&
                (categoryFilterSet == null || state.workflow.mediaCategory in categoryFilterSet)
        }
        require(workflowId == null || filtered.isNotEmpty()) { "工作流不存在：$workflowId" }
        val fullDetail = detail || workflowId != null
        val rendered = boundedWorkflowStates(filtered, submittersByKey, fullDetail = fullDetail)
        return buildJsonObject {
            put("count", filtered.size)
            put("detail", fullDetail)
            put("hosts", JsonArray(repo.hosts().map { h -> buildJsonObject { put("id", h.id); put("name", h.name) } }))
            put("workflows", JsonArray(rendered))
            put("truncated", filtered.size > rendered.size)
        }
    }

    private suspend fun executePreflightWorkflow(
        repo: ComfyRepository,
        args: JsonObject,
        context: SkillExecutionContext,
    ): JsonObject {
        keys(args, setOf("workflow_id"), setOf("workflow_id", "run_dry_run"))
        val workflowId = text(args, "workflow_id")
        val runDryRun = optionalBoolean(args, "run_dry_run", defaultValue = true)
        var state = repo.preflightWorkflow(workflowId)
        var dryRunObj: JsonObject? = null
        val subId = state.workflow.submitterScriptId
        if (runDryRun && subId != null) {
            val runner = submitterRunner ?: throw ComfyFailure("提交器运行环境尚未初始化")
            val sub = repo.submitter(state.workflow.hostId, subId)
                ?: throw ComfyFailure("提交器脚本不存在：$subId")
            val dryResult = runner.dryRunScript(sub, state.workflow, sessionId = context.sessionId)
            val updatedSub = repo.updateSubmitterDryRun(
                hostId = sub.hostId,
                scriptId = sub.scriptId,
                status = if (dryResult.success) SubmitterDryRunStatus.PASSED else SubmitterDryRunStatus.FAILED,
                message = dryResult.summary.ifBlank { dryResult.stderrTail },
                latencyMs = dryResult.latencyMs,
            )
            dryRunObj = buildJsonObject {
                put("status", updatedSub.lastDryRunStatus.name)
                put("execution_runtime", dryResult.runtime.name)
                put("message", updatedSub.lastDryRunMessage)
                put("latency_ms", dryResult.latencyMs)
            }
            state = repo.preflightWorkflow(workflowId)
        }
        val submittersByKey = repo.submitters(state.workflow.hostId).associateBy { it.hostId to it.scriptId }
        return buildJsonObject {
            put("workflow", renderWorkflowState(state, submittersByKey))
            dryRunObj?.let { put("dry_run", it) }
        }
    }

    private suspend fun executeGetChainState(args: JsonObject, context: SkillExecutionContext): JsonObject {
        keys(args, emptySet(), setOf("chain_id", "workflow_id", "shot_ref"))
        val store = chainStore ?: throw ComfyFailure("有状态链存储不可用")
        val chainId = optional(args, "chain_id")
        val workflowId = optional(args, "workflow_id")
        val shotRef = optional(args, "shot_ref")
        val chains = if (chainId != null) {
            val single = store.get(context.sessionId, chainId) ?: throw ComfyFailure("有状态链不存在：$chainId")
            if ((workflowId == null || single.workflowId == workflowId) && (shotRef == null || single.shotRef == shotRef)) {
                listOf(single)
            } else emptyList()
        } else {
            store.listSession(context.sessionId, workflowId = workflowId, shotRef = shotRef)
        }
        return buildJsonObject {
            put("session_id", context.sessionId)
            put("count", chains.size)
            put("chains", JsonArray(chains.map(::renderChainRecord)))
        }
    }

    private suspend fun executeSaveSubmitter(
        repo: ComfyRepository,
        args: JsonObject,
        context: SkillExecutionContext,
    ): JsonObject {
        keys(
            args,
            setOf("host_id", "script_id"),
            setOf(
                "host_id", "script_id", "file_name", "display_name", "description",
                "source_kind", "protocol", "source_text", "source_path", "skill_import",
                "support_files", "support_paths", "run_dry_run", "reason",
            ),
        )
        val validation = ToolSchemaValidator().validate(parametersSchema, args)
        if (validation is ToolSchemaValidation.Invalid) {
            throw ComfyFailure("${validation.error.path}: ${validation.error.message}")
        }
        val hostId = text(args, "host_id")
        val scriptId = text(args, "script_id")
        val skillImportObj = args["skill_import"] as? JsonObject
        val directSource = optionalRawString(args, "source_text")
        val sourcePath = optional(args, "source_path")

        val suppliedSources = listOfNotNull(
            directSource?.let { "source_text" },
            sourcePath?.let { "source_path" },
            skillImportObj?.let { "skill_import" },
        )
        if (suppliedSources.size != 1) {
            throw IllegalArgumentException("请在 source_text、source_path 与 skill_import 三者中恰选其一")
        }

        val importedBundle = skillImportObj?.let { imp ->
            val importer = importSkillSubmitter
                ?: throw ComfyFailure("当前运行环境未接入技能包直接导入；请改用 source_text 或 source_path")
            val skillName = text(imp, "skill_name")
            val entryPath = text(imp, "entry_path")
            val includePaths = (imp["include_paths"] as? JsonArray).orEmpty().map { elem ->
                (elem as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("skill_import.include_paths 条目必须是非空字符串")
            }
            importer(context.sessionId, skillName, entryPath, includePaths)
        }

        val fileName = optional(args, "file_name") ?: importedBundle?.entryFileName
            ?: throw ComfyFailure("file_name 必须是非空字符串（使用 skill_import 时可省略以沿用 entry_path）")
        val displayName = optional(args, "display_name") ?: fileName
        val description = optional(args, "description") ?: ""
        val sourceKind = optional(args, "source_kind")?.let { raw ->
            SubmitterSourceKind.entries.firstOrNull { it.name == raw }
                ?: throw IllegalArgumentException("source_kind 无效：$raw")
        } ?: if (importedBundle != null) SubmitterSourceKind.SKILL_SYNC else SubmitterSourceKind.AGENT_AUTHORED
        val protocol = optional(args, "protocol")?.let { raw ->
            SubmitterInvocationProtocol.entries.firstOrNull { it.name == raw }
                ?: throw IllegalArgumentException("protocol 无效：$raw")
        } ?: SubmitterInvocationProtocol.PAYLOAD_JSON

        val sourceText = importedBundle?.sourceText ?: directSource ?: run {
            val reader = readWorkspaceFile
                ?: throw ComfyFailure("当前无法直接按 source_path 读取脚本；请先用 read_file 读取脚本内容后通过 source_text 提交")
            val bytes = reader(context.sessionId, requireNotNull(sourcePath))
                ?: throw ComfyFailure("未找到脚本文件：$sourcePath")
            bytes.toString(Charsets.UTF_8)
        }
        require(sourceText.isNotBlank()) { "提交器脚本内容不能为空" }
        require(sourceText.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "提交器主脚本不能超过 512 KiB" }

        val supportBytes = linkedMapOf<String, ByteArray>()
        importedBundle?.supportFiles?.forEach { (rel, bytes) ->
            if (rel != fileName) supportBytes[rel] = bytes
        }
        (args["support_files"] as? JsonObject)?.let { obj ->
            require(supportBytes.size + obj.size <= 128) { "配套文件数量不能超过 128 个" }
            obj.forEach { (relPath, v) ->
                val txt = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("support_files[$relPath] 必须是字符串")
                supportBytes[relPath] = txt.toByteArray(Charsets.UTF_8)
            }
        }
        (args["support_paths"] as? JsonArray)?.let { arr ->
            val reader = readWorkspaceFile
                ?: throw ComfyFailure("当前无法直接按 support_paths 读取文件；请通过 support_files 传入文件名与文件内容")
            require(supportBytes.size + arr.size <= 128) { "配套文件数量不能超过 128 个" }
            val sourceNormalized = sourcePath?.trim()?.replace('\\', '/')?.trimEnd('/')
            val sourceBasePrefix = sourceNormalized?.let { norm ->
                if (norm.endsWith("/$fileName")) norm.removeSuffix("/$fileName")
                else norm.substringBeforeLast('/', "")
            }?.takeIf { it.isNotEmpty() }

            fun deriveRelPath(path: String): String {
                val pNorm = path.trim().replace('\\', '/')
                if (sourceBasePrefix != null && pNorm.startsWith("$sourceBasePrefix/")) {
                    val candidate = pNorm.removePrefix("$sourceBasePrefix/").trimStart('/')
                    if (candidate.isNotEmpty() && candidate != fileName) return candidate
                }
                return pNorm.substringAfterLast('/')
            }

            arr.forEach { item ->
                when (item) {
                    is JsonPrimitive -> {
                        val path = item.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                            ?: throw IllegalArgumentException("support_paths 条目不能为空")
                        val rel = deriveRelPath(path)
                        supportBytes[rel] = reader(context.sessionId, path)
                            ?: throw ComfyFailure("未找到配套文件：$path")
                    }
                    is JsonObject -> {
                        val path = text(item, "path")
                        val rel = optional(item, "relative_name") ?: deriveRelPath(path)
                        supportBytes[rel] = reader(context.sessionId, path)
                            ?: throw ComfyFailure("未找到配套文件：$path")
                    }
                    else -> throw IllegalArgumentException("support_paths 元素必须为路径字符串或 {path, relative_name}")
                }
            }
        }
        val totalSupportBytes = supportBytes.values.sumOf { it.size }
        require(totalSupportBytes <= 8 * 1024 * 1024) { "配套模块总大小不能超过 8 MiB" }

        var saved = repo.saveSubmitter(
            hostId = hostId,
            scriptId = scriptId,
            fileName = fileName,
            displayName = displayName,
            description = description,
            sourceKind = sourceKind,
            protocol = protocol,
            sourceText = sourceText,
            supportFiles = supportBytes,
        )
        val runDryRun = optionalBoolean(args, "run_dry_run", defaultValue = true)
        var dryRunSummary: JsonObject? = null
        if (runDryRun && submitterRunner != null && submitterRunner.isPythonAvailable) {
            val dry = submitterRunner.dryRunScript(saved, null, sessionId = context.sessionId)
            saved = repo.updateSubmitterDryRun(
                hostId = hostId,
                scriptId = scriptId,
                status = if (dry.success) SubmitterDryRunStatus.PASSED else SubmitterDryRunStatus.FAILED,
                message = dry.summary.ifBlank { dry.stderrTail },
                latencyMs = dry.latencyMs,
            )
            dryRunSummary = buildJsonObject {
                put("success", dry.success)
                put("status", saved.lastDryRunStatus.name)
                put("execution_runtime", dry.runtime.name)
                put("message", saved.lastDryRunMessage)
                put("latency_ms", dry.latencyMs)
            }
        }
        return buildJsonObject {
            put("submitter", renderSubmitter(saved))
            dryRunSummary?.let { put("dry_run", it) }
        }
    }

    private suspend fun executeDeleteSubmitter(repo: ComfyRepository, args: JsonObject): JsonObject {
        keys(args, setOf("host_id", "script_id"), setOf("host_id", "script_id", "reason"))
        val hostId = text(args, "host_id")
        val scriptId = text(args, "script_id")
        repo.deleteSubmitter(hostId, scriptId)
        return buildJsonObject {
            put("deleted", true)
            put("host_id", hostId)
            put("script_id", scriptId)
        }
    }

    private suspend fun executeSaveWorkflow(repo: ComfyRepository, args: JsonObject): JsonObject {
        keys(args, setOf("workflow"), setOf("workflow", "run_preflight", "reason"))
        val raw = args["workflow"] as? JsonObject ?: throw ComfyFailure("workflow 必须是对象")
        require(raw.toString().toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "workflow 定义不能超过 2 MiB" }
        val validation = ToolSchemaValidator().validate(parametersSchema, args)
        if (validation is ToolSchemaValidation.Invalid) {
            throw ComfyFailure("${validation.error.path.removePrefix("$.workflow.")}: ${validation.error.message}")
        }
        val runPreflight = optionalBoolean(args, "run_preflight", defaultValue = true)
        val workflow = try {
            workflowJsonCodec.decodeFromJsonElement(ComfyWorkflow.serializer(), raw)
        } catch (e: SerializationException) {
            val fieldHint = Regex("path: \\$(\\.[A-Za-z0-9_\\[\\].]+)").find(e.message.orEmpty())
                ?.groupValues?.get(1)?.removePrefix(".") ?: "workflow"
            throw IllegalArgumentException("$fieldHint: 字段缺失、包含未知键或类型无效")
        }
        val state = repo.saveWorkflow(workflow, runPreflight = runPreflight)
        val submittersByKey = repo.submitters(workflow.hostId).associateBy { it.hostId to it.scriptId }
        return buildJsonObject {
            put("workflow", renderWorkflowState(state, submittersByKey))
        }
    }

    private suspend fun executeDeleteWorkflow(repo: ComfyRepository, args: JsonObject): JsonObject {
        keys(args, setOf("workflow_id"), setOf("workflow_id", "reason"))
        val workflowId = text(args, "workflow_id")
        repo.deleteWorkflow(workflowId)
        return buildJsonObject {
            put("deleted", true)
            put("workflow_id", workflowId)
        }
    }

    private suspend fun executeSetChainTip(args: JsonObject, context: SkillExecutionContext): JsonObject {
        keys(args, setOf("chain_id", "node_id"), setOf("chain_id", "node_id"))
        val store = chainStore ?: throw ComfyFailure("有状态链存储不可用")
        val chainId = text(args, "chain_id")
        val nodeId = text(args, "node_id")
        val record = store.get(context.sessionId, chainId) ?: throw ComfyFailure("有状态链不存在：$chainId")
        val updated = StatefulChainDag.setActiveTip(record, nodeId, System.currentTimeMillis())
        store.save(updated)
        val tipNode = updated.node(nodeId)!!
        return buildJsonObject {
            put("chain_id", updated.chainId)
            put("active_tip_node_id", updated.activeTipNodeId)
            put("segment_index", tipNode.segmentIndex)
            put("attempt_label", tipNode.attemptLabel)
            put("output_media_path", tipNode.outputMediaPath)
        }
    }

    private suspend fun executeRunWorkflow(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val repo = requireNotNull(repository)
        val engine = executionEngine ?: throw ComfyFailure("ComfyUI 工作流执行引擎不可用")
        keys(
            args,
            setOf("workflow_id"),
            setOf(
                "workflow_id", "content_inputs", "fixed_overrides", "reason",
                "run_mode", "stage", "chain_id", "parent_node_id", "retry_node_id",
                "branch_policy", "output_dir", "shot_ref",
                "run_in_background", "yield_after_seconds", "max_runtime_minutes", "on_exit",
            ),
        )
        val workflowId = text(args, "workflow_id")
        val contentInputs = (args["content_inputs"] as? JsonObject)?.toMap() ?: run {
            if ("content_inputs" in args && args["content_inputs"] != JsonNull) {
                throw IllegalArgumentException("content_inputs 必须是 JSON 对象")
            }
            emptyMap()
        }
        val fixedOverrides = (args["fixed_overrides"] as? JsonObject)?.toMap() ?: run {
            if ("fixed_overrides" in args && args["fixed_overrides"] != JsonNull) {
                throw IllegalArgumentException("fixed_overrides 必须是 JSON 对象")
            }
            emptyMap()
        }

        // 防绕过门禁：若检测到偏离基线的 FIXED 字段覆写，必须核验一次性 Diff 审批绑定目标完全一致。
        val preflight = ComfyFixedOverrideApprovalPlanner.analyze(repo, args)
        if (preflight != null) {
            if (!context.isApprovalBoundExecution || context.approvedExecutionTarget != preflight.authorizationTarget) {
                throw ComfyFailure(
                    "检测到对固定字段（${preflight.diffs.joinToString("、") { it.key }}）的实验性覆写，" +
                        "须经用户一次性 Diff 审批（${preflight.authorizationTarget}）后方可执行。",
                )
            }
        }

        val runModeRaw = optional(args, "run_mode") ?: "execute"
        val runMode = when (runModeRaw.lowercase()) {
            "execute" -> ComfyWorkflowRunMode.EXECUTE
            "emit_workflow" -> ComfyWorkflowRunMode.EMIT_WORKFLOW
            "preflight_only" -> ComfyWorkflowRunMode.PREFLIGHT_ONLY
            else -> throw IllegalArgumentException("run_mode 必须为 execute、emit_workflow 或 preflight_only")
        }
        val stage = optional(args, "stage") ?: "single"
        require(stage in setOf("single", "first", "next")) { "stage 必须为 single、first 或 next" }
        val chainId = optional(args, "chain_id")
        val parentNodeId = optional(args, "parent_node_id")
        val retryNodeId = optional(args, "retry_node_id")
        val branchPolicy = optional(args, "branch_policy")?.let { raw ->
            StatefulBranchPolicy.entries.firstOrNull { it.name == raw }
                ?: throw IllegalArgumentException("branch_policy 必须为 ISOLATED_DIR 或 IN_PLACE_OVERWRITE")
        }
        val outputDir = optional(args, "output_dir")
        val shotRef = optional(args, "shot_ref")

        suspend fun runOnce(sink: ToolLiveOutputSink): ToolResult {
            val result = engine.execute(
                ComfyWorkflowExecutionRequest(
                    sessionId = context.sessionId,
                    workflowId = workflowId,
                    contentInputs = contentInputs,
                    fixedOverrides = fixedOverrides,
                    runMode = runMode,
                    stage = stage,
                    chainId = chainId,
                    parentNodeId = parentNodeId,
                    retryNodeId = retryNodeId,
                    branchPolicy = branchPolicy,
                    outputDir = outputDir,
                    shotRef = shotRef,
                    onProgress = { pct, msg ->
                        val label = pct?.let { "${(it * 100).toInt().coerceIn(0, 100)}% $msg" } ?: msg
                        sink.emit(ToolOutputStream.STDOUT, "$label\n")
                    },
                ),
            )
            val payload = buildJsonObject {
                put("workflow_id", result.workflow.workflowId)
                put("workflow_name", result.workflow.name)
                put("host_id", result.host.id)
                put("run_mode", result.runMode.name.lowercase())
                result.runtime?.let { put("execution_runtime", it.name) }
                result.promptId?.let { put("prompt_id", it) }
                put("summary", result.summary)
                put(
                    "media",
                    JsonArray(
                        result.savedMedia.map { ref ->
                            buildJsonObject {
                                put("path", ref.path)
                                put("kind", ref.kind.name)
                                put("mime_type", ref.mimeType)
                                put("size_bytes", ref.sizeBytes)
                            }
                        },
                    ),
                )
                if (result.appliedSeeds.isNotEmpty()) {
                    put("applied_seeds", JsonObject(result.appliedSeeds.mapValues { JsonPrimitive(it.value) }))
                }
                result.emittedWorkflowPath?.let { put("emitted_workflow_path", it) }
                result.emittedWorkflowJson?.let { wfJson ->
                    val serialized = wfJson.toString()
                    if (serialized.toByteArray(Charsets.UTF_8).size <= 16 * 1024) {
                        put("emitted_workflow", wfJson)
                    } else {
                        put("emitted_workflow_node_count", wfJson.size)
                    }
                }
                result.preflightReport?.let { put("preflight_report", it) }
                if (result.warnings.isNotEmpty()) {
                    put("warnings", JsonArray(result.warnings.map(::JsonPrimitive)))
                }
                if (result.appliedFixedDiffs.isNotEmpty()) {
                    put(
                        "applied_fixed_overrides",
                        JsonArray(
                            result.appliedFixedDiffs.map { d ->
                                buildJsonObject {
                                    put("key", d.key)
                                    put("baseline", d.oldDisplayValue)
                                    put("applied", d.newDisplayValue)
                                }
                            },
                        ),
                    )
                }
                result.chainRecord?.let { chain ->
                    put("chain_id", chain.chainId)
                    put("active_tip_node_id", chain.activeTipNodeId)
                }
                result.appendedNode?.let { node ->
                    put("node_id", node.nodeId)
                    put("segment_index", node.segmentIndex)
                    put("attempt_label", node.attemptLabel)
                    put("branch_id", node.branchId)
                    put("branch_policy", node.branchPolicy.name)
                    node.parentNodeId?.let { put("parent_node_id", it) }
                    node.assignedReadLatentDir?.let { put("assigned_read_latent_dir", it) }
                    node.assignedWriteLatentDir?.let { put("assigned_write_latent_dir", it) }
                    reportedLatentDirOf(node)?.let { put("reported_latent_dir", it) }
                    reportedClipIndexOf(node)?.let { put("clip_index", it) }
                    node.serverLatentSlotKey?.let { put("server_latent_slot_key", it) }
                    put("has_workflow_snapshot", "workflow_snapshot" in node.continuationStateJson)
                    node.isolationWarning?.let { put("isolation_warning", it) }
                    val summaryObj = sanitizedContinuationSummary(node.continuationStateJson)
                    if (summaryObj.isNotEmpty()) put("continuation_summary", summaryObj)
                }
                if (result.invalidatedNodeIds.isNotEmpty()) {
                    put("invalidated_node_ids", JsonArray(result.invalidatedNodeIds.map(::JsonPrimitive)))
                }
                val isVideoWorkflow = result.workflow.mediaCategory in setOf(ComfyMediaCategory.VIDEO, ComfyMediaCategory.POST_PROCESS)
                productionPanelNudge?.hintIfNeeded(context.sessionId, isVideoOrPostProcess = isVideoWorkflow)?.let { hint ->
                    put("production_panel_hint", hint)
                }
            }
            return ToolResult(id = "comfy_${UUID.randomUUID()}", content = payload.toString())
        }

        return if (jobs != null) {
            runMediaJob(
                id = "comfy_run_workflow",
                args = args,
                context = context,
                jobs = jobs,
                defaultYield = 20,
                defaultAction = "wake",
                backgroundKey = "run_in_background",
                summary = "ComfyUI 工作流：$workflowId",
                block = { sink -> runOnce(sink) },
            )
        } else {
            runOnce(ToolLiveOutputSink.None)
        }
    }

    private fun errorResult(message: String) = ToolResult(
        id = "comfy_${UUID.randomUUID()}",
        status = "error",
        content = buildJsonObject { put("error", message) }.toString(),
    )

    private fun keys(args: JsonObject, required: Set<String>, allowed: Set<String>) {
        if (!args.keys.containsAll(required) || args.keys.any { it !in allowed }) {
            throw ComfyFailure("参数缺失或包含未知字段，请检查工具参数")
        }
    }

    private fun text(args: JsonObject, key: String): String =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: throw ComfyFailure("$key 必须是非空字符串")

    private fun optional(args: JsonObject, key: String): String? =
        if (key !in args || args[key] == JsonNull) null else text(args, key)

    private fun optionalRawString(args: JsonObject, key: String): String? {
        if (key !in args || args[key] == JsonNull) return null
        val prim = args[key] as? JsonPrimitive ?: throw ComfyFailure("$key 必须是字符串")
        if (!prim.isString) throw ComfyFailure("$key 必须是字符串")
        return prim.content
    }

    private fun optionalBoolean(args: JsonObject, key: String, defaultValue: Boolean): Boolean {
        if (key !in args || args[key] == JsonNull) return defaultValue
        val prim = args[key] as? JsonPrimitive ?: throw IllegalArgumentException("$key 必须是布尔值")
        if (prim.isString) throw IllegalArgumentException("$key 必须是布尔值")
        return prim.booleanOrNull ?: throw IllegalArgumentException("$key 必须是布尔值")
    }

    private fun safeParameterError(e: IllegalArgumentException): String =
        e.message?.take(600) ?: "参数值无效"
}

private val workflowJsonCodec = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

private fun boundedTemplateStates(states: List<ComfyTemplateState>): List<JsonElement> {
    val result = mutableListOf<JsonElement>()
    var bytes = 0
    for (state in states.take(100)) {
        val rendered = renderTemplateState(state)
        bytes += rendered.toString().toByteArray().size
        if (bytes > 24 * 1024) break
        result += rendered
    }
    return result
}

private fun renderTemplateState(state: ComfyTemplateState): JsonObject = buildJsonObject {
    put("id", state.template.id)
    put("name", state.template.name)
    put("host_id", state.template.hostId)
    put("channel_id", state.template.channelId)
    put("locked", state.template.locked)
    put(
        "preflight",
        buildJsonObject {
            put("status", state.preflight.status.name)
            put("missing", JsonArray(state.preflight.missing.take(12).map { JsonPrimitive(it.take(160)) }))
            put("uncertain", JsonArray(state.preflight.uncertain.take(12).map { JsonPrimitive(it.take(160)) }))
            put("truncated", state.preflight.missing.size > 12 || state.preflight.uncertain.size > 12)
        },
    )
    val channel = state.template.channel()
    put("group", channel.group.name)
    put("max_images", channel.capabilities.maxImages)
    put("max_audio", channel.capabilities.maxAudio)
    put("max_videos", channel.capabilities.maxVideos)
    put("role", state.template.role.name)
    state.template.operation?.let { put("operation", buildJsonObject { put("label", it.label) }) }
    put("params", JsonArray(state.template.params.map { workflowJsonCodec.encodeToJsonElement(TemplateParam.serializer(), it) }))
    state.template.fps?.let { put("fps", it) }
    put("modes", JsonArray(channel.capabilities.modes.map { JsonPrimitive(it.name) }))
}

private fun boundedWorkflowStates(
    states: List<ComfyWorkflowState>,
    submittersByKey: Map<Pair<String, String>, ComfySubmitterScript>,
    fullDetail: Boolean = true,
): List<JsonElement> {
    val result = mutableListOf<JsonElement>()
    var bytes = 0
    for (state in states.take(100)) {
        val rendered = if (fullDetail) {
            renderWorkflowState(state, submittersByKey)
        } else {
            renderWorkflowSummary(state, submittersByKey)
        }
        bytes += rendered.toString().toByteArray().size
        if (bytes > 32 * 1024) break
        result += rendered
    }
    return result
}

private fun renderWorkflowSummary(
    state: ComfyWorkflowState,
    submittersByKey: Map<Pair<String, String>, ComfySubmitterScript>,
): JsonObject {
    val wf = state.workflow
    val submitter = wf.submitterScriptId?.let { submittersByKey[wf.hostId to it] }
    return buildJsonObject {
        put("workflow_id", wf.workflowId)
        put("channel_id", wf.channelId)
        put("host_id", wf.hostId)
        put("name", wf.name)
        if (wf.description.isNotBlank()) put("description", wf.description.take(160))
        put("media_category", wf.mediaCategory.name)
        put("execution_mode", wf.executionMode.name)
        wf.submitterScriptId?.let { put("submitter_script_id", it) }
        submitter?.let {
            put("submitter_file", it.fileName)
            put("submitter_protocol", it.protocol.name)
            put("submitter_dry_run_status", it.lastDryRunStatus.name)
        }
        put("has_raw_workflow_json", wf.rawWorkflowJson != null)
        put("preflight_status", state.preflight.status.name)
        put("content_field_keys", JsonArray(wf.contentFields().map { JsonPrimitive(it.key) }))
        put("fixed_field_keys", JsonArray(wf.fixedFields().map { JsonPrimitive(it.key) }))
        put("modes", JsonArray(wf.capabilities.modes.map { JsonPrimitive(it.name) }))
    }
}

private fun renderWorkflowState(
    state: ComfyWorkflowState,
    submittersByKey: Map<Pair<String, String>, ComfySubmitterScript>,
): JsonObject {
    val wf = state.workflow
    val submitter = wf.submitterScriptId?.let { submittersByKey[wf.hostId to it] }
    return buildJsonObject {
        put("workflow_id", wf.workflowId)
        put("channel_id", wf.channelId)
        put("host_id", wf.hostId)
        put("name", wf.name)
        if (wf.description.isNotBlank()) put("description", wf.description)
        put("media_category", wf.mediaCategory.name)
        put("execution_mode", wf.executionMode.name)
        wf.submitterScriptId?.let { put("submitter_script_id", it) }
        submitter?.let {
            put("submitter_file", it.fileName)
            put("submitter_protocol", it.protocol.name)
            put("submitter_dry_run_status", it.lastDryRunStatus.name)
        }
        put("has_raw_workflow_json", wf.rawWorkflowJson != null)
        put(
            "preflight",
            buildJsonObject {
                put("status", state.preflight.status.name)
                put("missing", JsonArray(state.preflight.missing.take(12).map { JsonPrimitive(it.take(160)) }))
                put("uncertain", JsonArray(state.preflight.uncertain.take(12).map { JsonPrimitive(it.take(160)) }))
                put("truncated", state.preflight.missing.size > 12 || state.preflight.uncertain.size > 12)
            },
        )
        put("content_fields", JsonArray(wf.contentFields().map { workflowJsonCodec.encodeToJsonElement(WorkflowFieldSpec.serializer(), it) }))
        put("fixed_fields", JsonArray(wf.fixedFields().map { workflowJsonCodec.encodeToJsonElement(WorkflowFieldSpec.serializer(), it) }))
        wf.chainSpec?.let { put("chain_spec", workflowJsonCodec.encodeToJsonElement(StatefulChainSpec.serializer(), it)) }
        if (wf.cliBaseArgs.isNotEmpty()) put("cli_base_args", JsonArray(wf.cliBaseArgs.map(::JsonPrimitive)))
        wf.emitWorkflowFlag?.let {
            put("emit_workflow_flag", it)
            put("emit_workflow_takes_path", wf.emitWorkflowTakesPath)
        }
        wf.preflightOnlyFlag?.let { put("preflight_only_flag", it) }
        wf.timeoutSeconds?.let { put("timeout_seconds", it) }
        if (wf.requiredTools.isNotEmpty()) put("required_tools", JsonArray(wf.requiredTools.map(::JsonPrimitive)))
        if (wf.requiredPlatforms.isNotEmpty()) put("required_platforms", JsonArray(wf.requiredPlatforms.map(::JsonPrimitive)))
        if (wf.allowEmptyOutputs) put("allow_empty_outputs", true)
        if (!wf.backfillOutputsFromHistory) put("backfill_outputs_from_history", false)
        if (wf.historyOutputFields.isNotEmpty()) put("history_output_fields", JsonArray(wf.historyOutputFields.map(::JsonPrimitive)))
        wf.fps?.let { put("fps", it) }
        put("frame_base", wf.frameBase)
        put("frame_step", wf.frameStep)
        wf.maxFrames?.let { put("max_frames", it) }
        put("modes", JsonArray(wf.capabilities.modes.map { JsonPrimitive(it.name) }))
    }
}

private fun renderSubmitter(s: ComfySubmitterScript): JsonObject = buildJsonObject {
    put("host_id", s.hostId)
    put("script_id", s.scriptId)
    put("file_name", s.fileName)
    put("display_name", s.displayName)
    if (s.description.isNotBlank()) put("description", s.description)
    put("source_kind", s.sourceKind.name)
    put("protocol", s.protocol.name)
    put("sha256", s.sha256)
    put("support_files", JsonArray(s.supportFiles.map(::JsonPrimitive)))
    put("last_dry_run_status", s.lastDryRunStatus.name)
    if (s.lastDryRunMessage.isNotBlank()) put("last_dry_run_message", s.lastDryRunMessage)
    s.lastDryRunLatencyMs?.let { put("last_dry_run_latency_ms", it) }
    put("updated_at", s.updatedAt)
}

private fun reportedLatentDirOf(node: StatefulChainNode): String? =
    (node.continuationStateJson["latent_dir"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun reportedClipIndexOf(node: StatefulChainNode): Int? =
    (node.continuationStateJson["clip_index"] as? JsonPrimitive)?.intOrNull

private fun sanitizedContinuationSummary(state: JsonObject): JsonObject {
    if (state.isEmpty()) return JsonObject(emptyMap())
    val filtered = LinkedHashMap<String, JsonElement>()
    var bytes = 0
    for ((k, v) in state) {
        if (k == "workflow_snapshot" || k.startsWith("__")) continue
        val encodedSize = v.toString().toByteArray(Charsets.UTF_8).size
        if (encodedSize > 2048 || bytes + encodedSize > 4096) continue
        filtered[k] = v
        bytes += encodedSize
    }
    return JsonObject(filtered)
}

private fun renderChainRecord(record: StatefulChainRecord): JsonObject = buildJsonObject {
    put("chain_id", record.chainId)
    put("session_id", record.sessionId)
    put("workflow_id", record.workflowId)
    put("host_id", record.hostId)
    record.shotRef?.let { put("shot_ref", it) }
    put("root_node_id", record.rootNodeId)
    put("active_tip_node_id", record.activeTipNodeId)
    put("updated_at", record.updatedAt)
    put(
        "nodes",
        JsonArray(
            record.nodes.map { node ->
                buildJsonObject {
                    put("node_id", node.nodeId)
                    node.parentNodeId?.let { put("parent_node_id", it) }
                    put("stage", node.stage)
                    put("segment_index", node.segmentIndex)
                    put("attempt_label", node.attemptLabel)
                    put("branch_id", node.branchId)
                    put("branch_policy", node.branchPolicy.name)
                    put("state_validity", node.stateValidity.name)
                    put("output_media_path", node.outputMediaPath)
                    node.startSeconds?.let { put("start_seconds", it) }
                    node.endSeconds?.let { put("end_seconds", it) }
                    node.frames?.let { put("frames", it) }
                    if (node.promptSummary.isNotBlank()) put("prompt_summary", node.promptSummary)
                    node.assignedReadLatentDir?.let { put("assigned_read_latent_dir", it) }
                    node.assignedWriteLatentDir?.let { put("assigned_write_latent_dir", it) }
                    reportedLatentDirOf(node)?.let { put("reported_latent_dir", it) }
                    reportedClipIndexOf(node)?.let { put("clip_index", it) }
                    node.serverLatentSlotKey?.let { put("server_latent_slot_key", it) }
                    put("has_workflow_snapshot", "workflow_snapshot" in node.continuationStateJson)
                    node.isolationWarning?.let { put("isolation_warning", it) }
                    val summaryObj = sanitizedContinuationSummary(node.continuationStateJson)
                    if (summaryObj.isNotEmpty()) put("continuation_summary", summaryObj)
                    put("created_at", node.createdAt)
                }
            },
        ),
    )
}

private const val COMFY_SAVE_SUBMITTER_SCHEMA = """{
 "type":"object",
 "required":["host_id","script_id"],
 "additionalProperties":false,
 "properties":{
  "host_id":{"type":"string","description":"目标 ComfyUI 主机 id"},
  "script_id":{"type":"string","description":"脚本唯一 id，允许字母、数字、下划线、连字符"},
  "file_name":{"type":"string","description":"相对 .py 入口路径，例如 submit_qwen21.py 或 scripts/submit_h3.py；使用 skill_import 时可省略，默认沿用 entry_path"},
  "display_name":{"type":"string"},
  "description":{"type":"string"},
  "source_kind":{"type":"string","enum":["DESKTOP_IMPORT","SKILL_SYNC","BUILTIN_GENERIC","AGENT_AUTHORED"]},
  "protocol":{"type":"string","enum":["PAYLOAD_JSON","CLI_ARGS"],"description":"PAYLOAD_JSON 以 --payload <json> 传参（dry_run/stage/branch_id/branch_policy/segment_index/parent_node_id/read_latent_dir/write_latent_dir/latent_dir/output_dir/result_path/host_proxy_url/inputs/inherited_state）并读取 result_path（prompt_id/outputs[{path,kind}]/continuation_state）；CLI_ARGS 按工作流参数与 --help 干跑；完整契约见 infinity-deck/references/comfyui-submitters.md"},
  "skill_import":{"type":"object","additionalProperties":false,"required":["skill_name","entry_path"],"description":"从已启用的用户/项目技能包按包内相对路径原样零改写导入（自动保留 scripts/ 与 assets/ 等相对目录并跳过 __pycache__/.pyc），无需手抄。与 source_text/source_path 三选一","properties":{
   "skill_name":{"type":"string","description":"已启用技能名，如 comfyui-generation"},
   "entry_path":{"type":"string","description":"技能包内主脚本相对路径，如 scripts/submit_h3.py"},
   "include_paths":{"type":"array","items":{"type":"string"},"description":"可选，附带文件或目录前缀列表；省略时自动导入主脚本同目录模块及 assets/"}
  }},
  "source_text":{"type":"string","description":"提交器 Python 源码正文；与 source_path、skill_import 三选一"},
  "source_path":{"type":"string","description":"当前工作区路径或 /skills/<name>/...、/mnt/skills/<name>/... 用户技能路径；与 source_text、skill_import 三选一"},
  "support_files":{"type":"object","description":"同脚本一起隔离存放的配套 JSON/Python 模块，键为相对路径、值为 UTF-8 文本"},
  "support_paths":{"type":"array","items":{"type":["string","object"]},"description":"可选，从工作区或已启用技能路径读取的配套文件列表；同根子目录自动保留相对路径"},
  "run_dry_run":{"type":"boolean","description":"保存后是否在内置 Python 3.14 中干跑校验，默认 true"},
  "reason":{"type":"string","description":"变更原因，最多 300 字"}
 }
}"""

private const val COMFY_SAVE_WORKFLOW_SCHEMA = """{
 "type":"object",
 "required":["workflow"],
 "additionalProperties":false,
 "properties":{
  "reason":{"type":"string","description":"变更说明，最多 300 字"},
  "run_preflight":{"type":"boolean","description":"保存后是否立即预检，默认 true"},
  "workflow":{
   "type":"object",
   "additionalProperties":false,
   "required":["workflowId","hostId","name","fields","capabilities"],
   "properties":{
    "workflowId":{"type":"string","description":"工作流唯一 id"},
    "hostId":{"type":"string","description":"所属 ComfyUI 主机 id"},
    "name":{"type":"string"},
    "description":{"type":"string"},
    "mediaCategory":{"type":"string","enum":["IMAGE","VIDEO","SPEECH","MUSIC","AUDIO","POST_PROCESS","MODEL_3D","FILE"]},
    "submitterScriptId":{"type":["string","null"],"description":"绑定的提交器 script_id；与 rawWorkflowJson 二选一或配合使用"},
    "rawWorkflowJson":{"type":["object","null"],"description":"内置通用 API Format 节点字典"},
    "outputNodeIds":{"type":"array","items":{"type":"string"}},
    "executionMode":{"type":"string","enum":["SINGLE","STATEFUL_CHAIN"]},
    "chainSpec":{
     "type":["object","null"],
     "additionalProperties":false,
     "properties":{
      "stages":{"type":"array","items":{"type":"string"}},
      "lockedInheritedKeys":{"type":"array","items":{"type":"string"}},
      "defaultBranchPolicy":{"type":"string","enum":["ISOLATED_DIR","IN_PLACE_OVERWRITE"]},
      "supportsIsolatedBranchDir":{"type":"boolean"},
      "hostFlag":{"type":"string"},
      "outputDirFlag":{"type":"string"},
      "stageFlag":{"type":"string"},
      "previousWorkflowFlag":{"type":"string"},
      "latentDirFlag":{"type":"string","description":"单目录 CLI 标志（默认 --latent-dir）；首段与续段均由宿主自动注入"},
      "branchIdFlag":{"type":"string","description":"可选，脚本原生支持时传入分支 ID 的 CLI 标志"},
      "segmentIndexFlag":{"type":"string","description":"可选，段号 CLI 标志（如 --clip-index），首段与续段均由宿主自动注入"},
      "readLatentDirFlag":{"type":"string","description":"可选，分离只读父段潜空间目录标志；须与 writeLatentDirFlag 成对声明方可启用 CLI 真物理分支隔离"},
      "writeLatentDirFlag":{"type":"string","description":"可选，分离写入本段潜空间目录标志；须与 readLatentDirFlag 成对声明方可启用 CLI 真物理分支隔离"},
      "dryRunFlag":{"type":"string"},
      "sidecarSuffixes":{"type":"array","items":{"type":"string"},"description":"可选，随前段工作流图持久保存并在续段自动还原的配套文件后缀（如 [\".segment.json\"]）"}
     }
    },
    "fields":{
     "type":"array",
     "description":"不限数量自定义字段；严格区分 CONTENT（每次生成传）与 FIXED（基线固化，实验覆写需审批）",
     "items":{
      "type":"object",
      "additionalProperties":false,
      "required":["key","label","mutability","valueType"],
      "properties":{
       "key":{"type":"string"},
       "label":{"type":"string"},
       "description":{"type":"string"},
       "mutability":{"type":"string","enum":["CONTENT","FIXED"]},
       "valueType":{"type":"string","enum":["STRING","TEXT","INT","FLOAT","BOOL","ENUM","JSON","IMAGE_FILE","VIDEO_FILE","AUDIO_FILE","ANY_FILE","DIRECTORY","FILE_LIST","TIMED_FILES"]},
       "required":{"type":"boolean"},
       "defaultValue":{"type":["string","number","boolean","object","array","null"]},
       "options":{"type":"array","items":{"type":"string"}},
       "min":{"type":["number","null"]},
       "max":{"type":["number","null"]},
       "semanticRole":{"type":"string","enum":["NONE","PROMPT","NEGATIVE_PROMPT","REFERENCE_IMAGES","FIRST_FRAME","LAST_FRAME","GUIDES","FRAMES","DURATION_SECONDS","WIDTH","HEIGHT","SEED","AUDIO_REF","INPUT_VIDEO"]},
       "cliFlag":{"type":["string","null"]},
       "cliFalseFlag":{"type":["string","null"],"description":"可选，BOOL_FLAG 为 false 时输出的反向标志（如 --no-free-after）"},
       "cliMode":{"type":"string","enum":["VALUE","REPEAT","BOOL_FLAG","TEXT_FILE","JSON","TEMPLATE"]},
       "cliValueTemplate":{"type":["string","null"],"description":"可选参数模板（支持 {file}、{value}、{json} 及对象属性 {key}，如 \"{node}={file}\"、\"{frame_idx}:{file}\"、\"{node}.{input}={json}\"）"},
       "stageParentDirectory":{"type":"boolean","description":"ANY_FILE 暂存时是否连同同级目录一起暂存（适用于引用相对路径切片文件的计划 JSON）"},
       "seedPolicy":{"type":"string","enum":["CALLER","RANDOM_EACH_RUN","FIXED"],"description":"种子策略：CALLER 由调用方传入，RANDOM_EACH_RUN 缺省时每次自动随机生成正整数种子，FIXED 固定默认值"},
       "nodeId":{"type":["string","null"]},
       "inputKey":{"type":["string","null"]},
       "uploadNodeId":{"type":["string","null"]},
       "uploadInputKey":{"type":["string","null"]},
       "uploadTargets":{"type":"array","description":"内置 JSON 多静态上传目标列表（如 [{nodeId,input}]）；FILE_LIST 按序对应各上传节点，未用可选节点自动裁剪","items":{"type":"object","additionalProperties":false,"required":["nodeId","input"],"properties":{"nodeId":{"type":"string"},"input":{"type":"string"}}}},
       "dynamicInputPrefix":{"type":["string","null"],"description":"内置 JSON 动态多槽位输入前缀（如 ref_images.ref_image_），配合 nodeId 按传参数量自动建 LoadImage/LoadAudio/LoadVideo 并连线"},
       "dynamicStartIndex":{"type":"integer","description":"动态多槽位起始后缀序号，默认 0"},
       "uploadNodeClassType":{"type":["string","null"],"description":"可选，动态新建上传节点的 class_type（默认按媒体类型选 LoadImage/LoadAudio/LoadVideo）"}
      }
     }
    },
    "capabilities":{
     "type":"object",
     "additionalProperties":false,
     "required":["modes"],
     "properties":{
      "modes":{"type":"array","items":{"type":"string","enum":["TEXT","REFERENCE","FIRST_FRAME","LAST_FRAME","FIRST_LAST_FRAME","SPEECH","MUSIC"]}},
      "nativeAudio":{"type":"boolean"},
      "maxImages":{"type":"integer"},
      "maxAudio":{"type":"integer"},
      "maxVideos":{"type":"integer"},
      "resolutions":{"type":"array","items":{"type":"string"}},
      "minDurationSeconds":{"type":["integer","null"]},
      "maxDurationSeconds":{"type":["integer","null"]},
      "promptFormat":{"type":"string","enum":["GENERIC","H3","SEEDANCE"]},
      "characterReferenceMode":{"type":"string","enum":["COMPOSITE","SEPARATE"]},
      "referenceOrder":{"type":"array","items":{"type":"string"}}
     }
    },
    "cliBaseArgs":{"type":"array","items":{"type":"string"},"description":"附加固定 CLI 参数列表，支持 {host.comfyui_version} 等主机元数据插值"},
    "hostFlag":{"type":"string"},
    "outputDirFlag":{"type":"string"},
    "dryRunFlag":{"type":"string"},
    "receiptFlag":{"type":["string","null"],"description":"CLI 回执文件路径标志（默认 --receipt）；若脚本本身不支持 --receipt，请显式传入 null 或空字符串，宿主会自动解析 stdout 末行/多行 JSON 并扫描 --output-dir 目录回收全部产物"},
    "emitWorkflowFlag":{"type":["string","null"],"description":"可选，仅导出结构校验后 API 图的 CLI 标志（如 --emit-workflow 或 --dry-run），配合 run_mode=emit_workflow 使用"},
    "emitWorkflowTakesPath":{"type":"boolean","description":"emitWorkflowFlag 是否接受文件路径参数（默认 true，如 --emit-workflow <path>；若为 --dry-run 等无路径布尔开关则设为 false）"},
    "preflightOnlyFlag":{"type":["string","null"],"description":"可选，仅做远端预检不提交 GPU 的 CLI 标志（如 --preflight-only），配合 run_mode=preflight_only 使用"},
    "promptIdPattern":{"type":["string","null"],"description":"可选正则（捕获组 1），用于从纯文本输出提取 prompt_id"},
    "timeoutSeconds":{"type":["integer","null"],"description":"可选单工作流超时秒数（10～7200），覆盖主机默认超时"},
    "allowEmptyOutputs":{"type":"boolean","description":"是否允许无本地/历史媒体输出（如仅保存服务端参考包的提取图），默认 false"},
    "backfillOutputsFromHistory":{"type":"boolean","description":"脚本提交后若存在未下载的服务端产物（如 SaveGLB 或 --submit-only），是否自动按 prompt_id 轮询 /history 并补拉，默认 true"},
    "historyOutputFields":{"type":"array","items":{"type":"string"},"description":"可选，从 /history/{prompt_id} 补拉产物时读取的输出键名列表（如 [\"3d\", \"glb\", \"images\"]）"},
    "requiredTools":{"type":"array","items":{"type":"string"},"description":"可选，执行前必须存在的外部命令清单（如 [\"ffmpeg\", \"ffprobe\"]）；缺失时提前拒绝并提示安装或切换环境"},
    "requiredPlatforms":{"type":"array","items":{"type":"string"},"description":"可选，限制允许执行的平台标识（如 [\"linux\", \"android\"] 或 [\"macos\"]）；不匹配时提前拒绝"},
    "fps":{"type":["number","null"]},
    "frameBase":{"type":"integer"},
    "frameStep":{"type":"integer"},
    "maxFrames":{"type":["integer","null"]},
    "updatedAt":{"type":"integer"}
   }
  }
 }
}"""

private const val COMFY_RUN_WORKFLOW_SCHEMA = """{
 "type":"object",
 "required":["workflow_id"],
 "additionalProperties":false,
 "properties":{
  "workflow_id":{"type":"string","description":"目标 ComfyUI 工作流 id"},
  "content_inputs":{"type":"object","description":"日常内容字段键值字典，例如 prompt、ref_images、seed"},
  "fixed_overrides":{"type":"object","description":"可选，单次实验性覆写工作流 FIXED 固定字段；若偏离基线将弹出一次性 Diff 审批且绝不改写原基线"},
  "reason":{"type":"string","description":"当传入 fixed_overrides 时向用户说明实验理由，最多 300 字"},
  "run_mode":{"type":"string","enum":["execute","emit_workflow","preflight_only"],"description":"运行模式：execute 正式提交生成（默认）；emit_workflow 仅组装导出图供审阅；preflight_only 仅连主机预检不提交 GPU"},
  "stage":{"type":"string","enum":["single","first","next"],"description":"单次工作流填 single；有状态链式工作流填 first 或 next"},
  "chain_id":{"type":"string","description":"有状态链 id；单活跃链续接时可省略由宿主自动解析"},
  "parent_node_id":{"type":"string","description":"有状态链中作为新段父节点的 ACTIVE 节点 id；省略则接续当前 active_tip_node_id"},
  "retry_node_id":{"type":"string","description":"重跑已有链段落节点 id；可作用于任意分段并继承同一前驱上下文"},
  "branch_policy":{"type":"string","enum":["ISOLATED_DIR","IN_PLACE_OVERWRITE"]},
  "output_dir":{"type":"string","description":"可选，工作区输出相对子目录"},
  "shot_ref":{"type":"string","description":"可选，关联制作镜头编码"},
  "run_in_background":{"type":"boolean"},
  "yield_after_seconds":{"type":"integer"},
  "max_runtime_minutes":{"type":"integer"},
  "on_exit":{"type":"string","enum":["none","notify","wake"]}
 }
}"""

private const val COMFY_TEMPLATE_SCHEMA = """{
 "type":"object","required":["template"],"additionalProperties":false,"properties":{"template":{
  "type":"object","additionalProperties":false,"required":["id","hostId","group","workflow","slots","capabilities"],"properties":{
   "id":{"type":"string","description":"稳定 id，字母数字下划线或连字符"},"name":{"type":"string"},"hostId":{"type":"string"},
   "locked":{"type":"boolean","description":"审批保存后保持锁定"},
   "group":{"type":"string","enum":["IMAGE","VIDEO","SPEECH","MUSIC","AUDIO"]},"workflow":{"type":"object","description":"ComfyUI API Format 节点字典"},
   "role":{"type":"string","enum":["GENERATE","DERIVE"]},
   "operation":{"type":["object","null"],"additionalProperties":false,"required":["label"],"properties":{"label":{"type":"string","description":"DERIVE 操作显示名称，1～20 字符"}}},
   "params":{"type":"array","description":"最多 6 项；提交时可按 key 覆盖，每项需要一个 PARAM 槽位","items":{"type":"object","additionalProperties":false,"required":["key","label","type","default"],"properties":{
    "key":{"type":"string","description":"[a-z][a-z0-9_]{0,31}，工作流内唯一"},"label":{"type":"string","description":"1～40 字符"},
    "type":{"type":"string","enum":["ENUM","NUMBER","BOOLEAN"]},"default":{"type":["string","number","boolean"],"description":"ENUM 为选项字符串，NUMBER 为范围内数值，BOOLEAN 为布尔值"},
    "options":{"type":"array","items":{"type":"string"},"description":"ENUM 专用，2～20 个不重复选项"},
    "min":{"type":["number","null"]},"max":{"type":["number","null"]},"integer":{"type":"boolean","description":"NUMBER 专用，是否要求整数"}
   }}},
   "frameBase":{"type":"integer","description":"正整数"},"frameStep":{"type":"integer","description":"正整数"},"maxFrames":{"type":["integer","null"],"description":"正整数"},
   "fps":{"type":["number","null"],"description":"视频工作流输出帧率（如 24），声明后制作规划按镜头秒数自动换算并对齐 frameBase+k*frameStep 帧数"},
   "slots":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["nodeId","input","kind"],"properties":{
    "nodeId":{"type":"string"},"input":{"type":"string","description":"精确输入键；动态键使用组名.成员数字后缀；OUTPUT 填历史输出字段 images/gifs/videos/audio"},
    "kind":{"type":"string","enum":["PROMPT","REFERENCE_IMAGES","FIRST_FRAME","LAST_FRAME","REFERENCE_AUDIO","INPUT_VIDEO","PARAM","DURATION","WIDTH","HEIGHT","FRAMES","SEED","OUTPUT"]},
    "param":{"type":["string","null"],"description":"PARAM 槽位对应的 params.key；其它种类不填写"},
    "capacity":{"type":["integer","null"],"description":"1～100"},"outputKind":{"type":["string","null"],"enum":["IMAGE","VIDEO","AUDIO",null]},
    "uploadTargets":{"type":"array","description":"媒体槽位是节点连线时必填，按容量顺序填写上传文件输入，不修改图片连线","items":{"type":"object","additionalProperties":false,"required":["nodeId","input"],"properties":{"nodeId":{"type":"string"},"input":{"type":"string"}}}}
   }}},
   "models":{"type":"array","description":"显式声明需检查文件可选值的输入","items":{"type":"object","additionalProperties":false,"required":["nodeId","input"],"properties":{"nodeId":{"type":"string"},"input":{"type":"string"}}}},
   "capabilities":{"type":"object","additionalProperties":false,"required":["modes"],"properties":{
    "modes":{"type":"array","items":{"type":"string","enum":["TEXT","REFERENCE","FIRST_FRAME","LAST_FRAME","FIRST_LAST_FRAME","SPEECH","MUSIC"]}},
    "nativeAudio":{"type":"boolean"},"resolutions":{"type":"array","items":{"type":"string"}},
    "minDurationSeconds":{"type":["integer","null"]},"maxDurationSeconds":{"type":["integer","null"]},"promptFormat":{"type":"string","enum":["GENERIC","H3","SEEDANCE"]},
    "referenceOrder":{"type":"array","items":{"type":"string"},"description":"角色分张参考视角语义顺序（如 [\"front\",\"side\",\"back\"] 或 [\"front_full_body\",\"side_full_body\",\"back_full_body\"]），勿填 ComfyUI 输入键名；留空按默认 front→side→back。参考容量由槽位计算"}
   }}
  }
 }}}
"""
