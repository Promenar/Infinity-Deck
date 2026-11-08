package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.ui.chat.diff.WorkspaceChangeList
import com.promenar.infinity.ui.chat.diff.parseWorkspaceChanges
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 仅从明确的工具信封取名，参数里的普通 name 字段不能当成工具名。 */
internal fun inferredToolName(step: ExecutionStep): String? {
    step.toolName?.takeIf(String::isNotBlank)?.let { return it }
    for (payload in listOfNotNull(step.data, step.toolArgs, step.content)) {
        val envelope = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: continue
        fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        envelope.text("tool_name")?.let { return it }
        envelope.text("tool")?.let { return it }
        (envelope["function"] as? JsonObject)?.text("name")?.let { return it }
        if ("arguments" in envelope || "parameters" in envelope) envelope.text("name")?.let { return it }
    }
    return null
}

internal data class ToolInvocationPresentation(val steps: List<ExecutionStep>) {
    val name: String? get() = steps.firstNotNullOfOrNull(::inferredToolName)
    val call: ExecutionStep? get() = steps.firstOrNull { it.type == "tool_call" }
    val results: List<ExecutionStep> get() = steps.filter { it.type == "tool_result" || it.type == "error" }
    val failed: Boolean get() = results.any { it.type == "error" }
    val complete: Boolean get() = results.isNotEmpty()

    /** 命令类工具的结果可在完整输出页查看。 */
    val isCommand: Boolean get() = com.promenar.infinity.ui.linux.output.CommandOutputParser.isCommandTool(name)

    /** 内置 shell 的结果标明运行在 Linux 环境；旧结果没有运行环境字段，按系统 shell 处理。 */
    val ranInLinux: Boolean get() = name == "shell_exec" && results.any { result ->
        result.content?.let {
            com.promenar.infinity.ui.linux.output.CommandOutputParser.parse(name, call?.toolArgs, it, null).runtime ==
                com.promenar.infinity.ui.linux.output.CommandRuntime.LINUX
        } == true
    }
    val durationMs: Long? get() {
        val start = call?.timestamp?.takeIf { it > 0 } ?: return null
        val end = results.maxOfOrNull { it.timestamp } ?: return null
        return (end - start).takeIf { it >= 0 }
    }

    /** 成功结果携带的文件改动摘要；历史消息没有该字段时为空，界面保持原样。 */
    val fileChanges: List<FileChangeSummary>
        get() = results.filter { it.type == "tool_result" }.flatMap { it.fileChanges.orEmpty() }

    /** 内置命令在会话私有工作区的改动清单；自选目录工作区与其它工具为空。 */
    val workspaceChanges: WorkspaceChangeList?
        get() = if (name !in WORKSPACE_TRACKED_COMMANDS) null
        else results.firstNotNullOfOrNull { parseWorkspaceChanges(it.content) }

    /** 内置命令结束后报告的运行时关键数据变化；后台运行的命令由状态查询结果带回。 */
    val runtimeChanges: List<String>
        get() = if (name !in RUNTIME_MONITORED_TOOLS) emptyList()
        else results.flatMap { parseRuntimeChanges(it.content) }.distinct()

    private companion object {
        val WORKSPACE_TRACKED_COMMANDS = setOf("shell_exec", "python_exec")
        val RUNTIME_MONITORED_TOOLS = setOf("shell_exec", "python_exec", "job_status", "job_wait", "job_input")
    }
}

/** 结果不是 JSON 对象或没有该字段时为空。 */
internal fun parseRuntimeChanges(content: String?): List<String> {
    if (content.isNullOrBlank()) return emptyList()
    val root = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return emptyList()
    return (root["runtime_changes"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
}

/** 一组工具调用中报告的全部关键数据变化，去重后按出现顺序排列。 */
internal fun toolGroupRuntimeChanges(invocations: List<ToolInvocationPresentation>): List<String> =
    invocations.flatMap { it.runtimeChanges }.distinct()

/** 一组工具调用中全部成功文件工具的改动摘要，按调用顺序排列。 */
internal fun toolGroupFileChanges(invocations: List<ToolInvocationPresentation>): List<FileChangeSummary> =
    invocations.flatMap { it.fileChanges }

/** 调用 ID 优先；兼容 res_步骤ID 与按名称顺序返回的历史记录，不把结果再计作一次调用。 */
internal fun toolInvocations(steps: List<ExecutionStep>): List<ToolInvocationPresentation> {
    val groups = mutableListOf<MutableList<ExecutionStep>>()
    steps.filter { it.type == "tool_call" || it.type == "tool_result" || it.type == "error" }
        .distinctBy { it.id }.forEach { step ->
        val callId = step.toolCallId?.takeIf(String::isNotBlank)
        val name = inferredToolName(step)
        val existing = groups.firstOrNull { group ->
            callId != null && group.any { it.toolCallId == callId }
        } ?: if (step.type != "tool_call") {
            groups.firstOrNull { group ->
                group.any { it.type == "tool_call" && step.id == "res_${it.id}" }
            } ?: groups.firstOrNull { group ->
                callId == null && group.any { it.type == "tool_call" } &&
                    group.none { it.type == "tool_result" || it.type == "error" } &&
                    (name == null || group.firstNotNullOfOrNull(::inferredToolName) == name)
            }
        } else null
        if (existing != null) existing.add(step) else groups.add(mutableListOf(step))
    }
    return groups.map { ToolInvocationPresentation(it.toList()) }
}

/** 多工具行显示实际调用区间；缺少起止事件时不猜测耗时。 */
internal fun toolGroupDurationMs(invocations: List<ToolInvocationPresentation>, nowMs: Long? = null): Long? {
    if (invocations.isEmpty()) return null
    if (invocations.any { !it.complete }) {
        if (nowMs == null || invocations.any { it.call == null || it.call!!.timestamp <= 0 }) return null
        return (nowMs - invocations.minOf { it.call!!.timestamp }).takeIf { it >= 0 }
    }
    if (invocations.any { it.durationMs == null }) return null
    val start = invocations.minOf { requireNotNull(it.call).timestamp }
    val end = invocations.flatMap { it.results }.maxOf { it.timestamp }
    return (end - start).takeIf { it >= 0 }
}

/** 工具耗时文案：不足 1 秒显示毫秒，否则显示保留一位小数的秒数。 */
internal fun toolDurationText(durationMs: Long): ToolDurationText =
    if (durationMs < 1_000) {
        ToolDurationText(com.promenar.infinity.R.string.chat_tool_duration, listOf(durationMs))
    } else {
        ToolDurationText(
            com.promenar.infinity.R.string.chat_tool_duration_seconds,
            listOf(String.format(java.util.Locale.ROOT, "%.1f", durationMs / 1_000.0)),
        )
    }

/** 待本地化的文案：资源 ID 加格式参数。 */
internal data class ToolDurationText(val resId: Int, val args: List<Any>)

internal enum class ToolSummaryCategory {
    COMMAND, WEB_SEARCH, WEB_READ, FILE_READ, FILE_EDIT, FILE_TRANSFER, RETRIEVAL, MEDIA, PLAN, UTILITY, SKILL, SUBAGENT, OTHER,
}

internal enum class ToolSummaryAction(val category: ToolSummaryCategory) {
    COMMAND(ToolSummaryCategory.COMMAND),
    WEB_SEARCH(ToolSummaryCategory.WEB_SEARCH),
    WEB_READ(ToolSummaryCategory.WEB_READ),
    BROWSER(ToolSummaryCategory.WEB_READ),
    FILE_READ(ToolSummaryCategory.FILE_READ),
    FILE_EDIT(ToolSummaryCategory.FILE_EDIT),
    FILE_TRANSFER(ToolSummaryCategory.FILE_TRANSFER),
    KNOWLEDGE_SEARCH(ToolSummaryCategory.RETRIEVAL),
    MEMORY_SEARCH(ToolSummaryCategory.RETRIEVAL),
    MEMORY_WRITE(ToolSummaryCategory.UTILITY),
    IMAGE(ToolSummaryCategory.MEDIA),
    MEDIA_VIEW(ToolSummaryCategory.MEDIA),
    MEDIA_FETCH(ToolSummaryCategory.MEDIA),
    PLAN(ToolSummaryCategory.PLAN),
    TIME(ToolSummaryCategory.UTILITY),
    CALCULATE(ToolSummaryCategory.UTILITY),
    SCHEDULE(ToolSummaryCategory.UTILITY),
    SKILL(ToolSummaryCategory.SKILL),
    SUBAGENT(ToolSummaryCategory.SUBAGENT),
    SECRET(ToolSummaryCategory.UTILITY),
    OTHER(ToolSummaryCategory.OTHER),
}

/**
 * [PENDING]：本轮仍在进行（生成中或等待审批），调用还会有结果；
 * [INTERRUPTED]：本轮已结束（停止、暂停、出错或应用被杀），未拿到结果的调用不会再有结果。
 */
internal enum class ToolSummaryStatus { EMPTY, RUNNING, PENDING, INTERRUPTED, COMPLETED, FAILED }

internal data class ToolSummaryPart(val action: ToolSummaryAction, val count: Int)

internal data class ToolGroupSummary(
    val parts: List<ToolSummaryPart>,
    val count: Int,
    val status: ToolSummaryStatus,
    val failedCount: Int,
    val durationMs: Long?,
    val runningAction: ToolSummaryAction? = null,
    val pendingCount: Int = 0,
    /** 超时转入后台、产出尚未回来的调用数。 */
    val backgroundedCount: Int = 0,
) {
    val categories: List<ToolSummaryCategory> get() = parts.map { it.action.category }.distinct()
    val isFileEditOnly: Boolean get() = categories == listOf(ToolSummaryCategory.FILE_EDIT)
    val useGenericSummary: Boolean get() = categories.size >= 3
}

/** 内置工具仅按完整 ID 匹配，MCP 命名空间与未知工具统一归入其它。 */
internal fun toolSummaryAction(name: String?): ToolSummaryAction = when (name) {
    "shell_exec", "python_exec", "termux_exec", "ssh_exec", "ssh_session_open", "ssh_session_send", "ssh_session_read",
    "ssh_session_close", "ssh_host_install_key", "run_script", "exec_js", "python_sandbox",
    "job_status", "job_wait", "job_stop", "job_list", "job_input" -> ToolSummaryAction.COMMAND
    "web_search", "search_tavily", "search_searxng" -> ToolSummaryAction.WEB_SEARCH
    "web_fetch" -> ToolSummaryAction.WEB_READ
    "browser_open", "browser_snapshot", "browser_click", "browser_type" -> ToolSummaryAction.BROWSER
    "read_file", "list_files", "search_files", "read_tool_output", "read_skill_file" -> ToolSummaryAction.FILE_READ
    "write_file", "create_file", "patch_file", "diff_file", "move_file", "delete_file",
    "create_directory" -> ToolSummaryAction.FILE_EDIT
    "sftp_upload", "sftp_download" -> ToolSummaryAction.FILE_TRANSFER
    "knowledge_search" -> ToolSummaryAction.KNOWLEDGE_SEARCH
    "memory_search", "memory_read" -> ToolSummaryAction.MEMORY_SEARCH
    "memory_save", "memory_forget" -> ToolSummaryAction.MEMORY_WRITE
    "generate_image", "image_generation" -> ToolSummaryAction.IMAGE
    "view_media" -> ToolSummaryAction.MEDIA_VIEW
    "media_fetch" -> ToolSummaryAction.MEDIA_FETCH
    "initialize_plan", "update_plan", "get_plan", "drop_plan" -> ToolSummaryAction.PLAN
    "current_time" -> ToolSummaryAction.TIME
    "calculator" -> ToolSummaryAction.CALCULATE
    "schedule_create", "schedule_list", "schedule_pause", "schedule_resume", "schedule_cancel" -> ToolSummaryAction.SCHEDULE
    "activate_skill" -> ToolSummaryAction.SKILL
    com.promenar.infinity.data.subagent.SubagentLimits.TOOL_NAME -> ToolSummaryAction.SUBAGENT
    com.promenar.infinity.domain.secret.SecretIoLimits.REQUEST_TOOL_NAME,
    com.promenar.infinity.domain.secret.SecretIoLimits.SHOW_TOOL_NAME -> ToolSummaryAction.SECRET
    else -> ToolSummaryAction.OTHER
}

/**
 * 摘要中一次调用计入的数量：生图按成功结果中的图片张数，查看与下载按媒体个数；
 * 没有结构化媒体的旧结果按一次调用计，失败的调用没有产出，计 0，只计入失败数。
 */
internal fun toolSummaryWeight(action: ToolSummaryAction, invocation: ToolInvocationPresentation): Int {
    if (action == ToolSummaryAction.SUBAGENT) return subagentTaskCount(invocation.call?.toolArgs)
    if (action != ToolSummaryAction.IMAGE && action != ToolSummaryAction.MEDIA_VIEW && action != ToolSummaryAction.MEDIA_FETCH) return 1
    if (invocation.failed || backgroundedJobId(invocation) != null) return 0
    val media = invocation.results.filter { it.type == "tool_result" }.flatMap { it.media.orEmpty() }
    val counted = if (action == ToolSummaryAction.IMAGE) media.count { it.kind == com.promenar.infinity.domain.media.MediaKind.IMAGE } else media.size
    return counted.takeIf { it > 0 } ?: 1
}

/** 超时转入后台的生图与媒体下载：成功结果是任务开始 JSON、没有媒体时返回任务号，产出由任务结束后的结果给出。 */
internal fun backgroundedJobId(invocation: ToolInvocationPresentation): String? {
    val action = toolSummaryAction(invocation.name)
    if (action != ToolSummaryAction.IMAGE && action != ToolSummaryAction.MEDIA_FETCH) return null
    if (!invocation.complete || invocation.failed) return null
    val success = invocation.results.filter { it.type == "tool_result" }
    if (success.any { !it.media.isNullOrEmpty() }) return null
    return success.firstNotNullOfOrNull { result ->
        val root = runCatching { Json.parseToJsonElement(result.content.orEmpty()) as? JsonObject }.getOrNull()
        root?.takeIf { "state" in it }?.let { (it["job_id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    }
}

/** 摘要保留类别首次出现顺序；同一类别中的检索、实用与媒体动作分别计数。 */
internal fun toolGroupSummary(invocations: List<ToolInvocationPresentation>): ToolGroupSummary {
    val actions = invocations.map { toolSummaryAction(it.name) }
    val categories = actions.map { it.category }.distinct()
    val parts = categories.flatMap { category ->
        invocations.zip(actions).filter { (_, action) -> action.category == category }
            .groupBy({ it.second }, { (invocation, action) -> toolSummaryWeight(action, invocation) })
            .map { (action, weights) -> ToolSummaryPart(action, weights.sum()) }
    }
    val unfinished = invocations.lastOrNull { !it.complete }
    val failedCount = invocations.count { it.failed }
    return ToolGroupSummary(
        parts = parts,
        count = invocations.size,
        status = when {
            invocations.isEmpty() -> ToolSummaryStatus.EMPTY
            unfinished != null -> ToolSummaryStatus.RUNNING
            failedCount > 0 -> ToolSummaryStatus.FAILED
            else -> ToolSummaryStatus.COMPLETED
        },
        failedCount = failedCount,
        durationMs = toolGroupDurationMs(invocations),
        runningAction = unfinished?.let { toolSummaryAction(it.name) },
        pendingCount = invocations.count { !it.complete },
        backgroundedCount = invocations.count { backgroundedJobId(it) != null },
    )
}

/** 未完成且不在执行的调用：本轮仍在进行时是“等待处理”，本轮已结束时是“已中断”。 */
internal fun toolGroupDisplaySummary(
    invocations: List<ToolInvocationPresentation>,
    isExecuting: Boolean,
    turnActive: Boolean = true,
): ToolGroupSummary {
    val summary = toolGroupSummary(invocations)
    return if (!isExecuting && summary.status == ToolSummaryStatus.RUNNING) {
        summary.copy(
            status = if (turnActive) ToolSummaryStatus.PENDING else ToolSummaryStatus.INTERRUPTED,
            runningAction = null,
        )
    } else summary
}

/** 一次 `agent_task` 调用派生的子代理数；参数无法解析时按一个计。 */
internal fun subagentTaskCount(arguments: String?): Int = runCatching {
    (kotlinx.serialization.json.Json.parseToJsonElement(arguments.orEmpty()) as? kotlinx.serialization.json.JsonObject)
        ?.get("tasks")?.let { it as? kotlinx.serialization.json.JsonArray }?.size
}.getOrNull()?.takeIf { it > 0 } ?: 1
