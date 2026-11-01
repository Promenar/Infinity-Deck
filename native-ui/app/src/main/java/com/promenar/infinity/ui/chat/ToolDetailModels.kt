package com.promenar.infinity.ui.chat

import com.promenar.infinity.R
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 工具调用详情卡的纯数据层：计划清单、后台任务查看与通用字段化展示。
 * 只做解析与判定，不依赖 Compose；任何解析失败都返回 null，由界面退回原文展示。
 */

/** 待本地化的文案：资源 ID 加格式参数；参数本身也可以是 [ToolText]，由界面递归取字符串。 */
internal data class ToolText(val resId: Int, val args: List<Any> = emptyList())

private fun parseObject(text: String?): JsonObject? =
    text?.takeIf(String::isNotBlank)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

// ─────────────────────────────────────────────────────────────────
//  计划卡
// ─────────────────────────────────────────────────────────────────

/** 计划工具：初始化、增量更新与读取；drop_plan 只用来清空回放状态，不出卡片。 */
internal val PLAN_CARD_TOOLS = setOf("initialize_plan", "update_plan", "get_plan")
private val PLAN_REPLAY_TOOLS = PLAN_CARD_TOOLS + "drop_plan"

/** 回放中的计划步骤；[status] 只对叶子有意义，父步骤状态在展示时由子步骤派生。 */
internal data class PlanNode(
    val id: String,
    val title: String,
    val status: String = "todo",
    val sortOrder: Int = 0,
    val children: List<PlanNode> = emptyList(),
) {
    val isLeaf: Boolean get() = children.isEmpty()
}

/** 一次计划工具调用的回放输入；[key] 为界面中调用步骤的 id。 */
internal data class PlanCallRecord(
    val key: String,
    val name: String,
    val arguments: String?,
    val result: String?,
    val failed: Boolean = false,
)

/** 调用前后的计划树；[before] 为 null 表示调用前没有可知的计划（初始化或回放起点）。 */
internal data class PlanCallSnapshot(
    val name: String,
    val goal: String?,
    val before: List<PlanNode>?,
    val after: List<PlanNode>,
)

/**
 * 按会话顺序回放计划工具调用，得到每次调用前后的计划树。
 * 计划工具的结果只带进度与当前步骤，不带清单，因此 update_plan 要在前一次已知计划上重放操作；
 * 前面没有已知计划（历史未加载、计划由界面创建）时该次调用没有快照，界面退回字段展示。
 * 规则与仓库一致：doing 同时只有一个；放弃与移除的步骤（含子步骤）不再出现在清单里。
 */
internal fun replayPlanCalls(calls: List<PlanCallRecord>): Map<String, PlanCallSnapshot> {
    var goal: String? = null
    var tree: List<PlanNode>? = null
    val snapshots = LinkedHashMap<String, PlanCallSnapshot>()
    for (call in calls) {
        if (call.failed) continue
        val args = parseObject(call.arguments)
        val result = parseObject(call.result)
        when (call.name) {
            "initialize_plan" -> {
                if ((result?.get("conflict") as? JsonPrimitive)?.booleanOrNull == true) {
                    // 已有未完成计划时不会新建，卡片显示原计划。
                    tree?.let { snapshots[call.key] = PlanCallSnapshot(call.name, goal, it, it) }
                    continue
                }
                val parsed = (args?.get("tree") as? JsonArray)?.let { parsePlanNodes(it, withStatus = false) }
                    ?.takeIf { it.isNotEmpty() } ?: continue
                val focus = result?.text("currentFocusStepId")?.takeIf(String::isNotBlank) ?: firstLeaf(parsed)?.id
                val created = mapLeaves(parsed) { if (it.id == focus) it.copy(status = "doing") else it.copy(status = "todo") }
                goal = args?.text("goal") ?: result?.text("goal")
                tree = created
                snapshots[call.key] = PlanCallSnapshot(call.name, goal, null, created)
            }
            "get_plan" -> {
                result ?: continue
                if ((result["active"] as? JsonPrimitive)?.booleanOrNull == false) {
                    tree = null; goal = null; continue
                }
                val parsed = (result["tree"] as? JsonArray)?.let { parsePlanNodes(it, withStatus = true) } ?: continue
                val before = tree
                goal = result.text("goal") ?: goal
                tree = parsed
                snapshots[call.key] = PlanCallSnapshot(call.name, goal, before, parsed)
            }
            "update_plan" -> {
                val base = tree ?: continue
                val operations = args?.get("operations") as? JsonArray ?: continue
                var next = applyPlanOperations(base, operations)
                // 结果中的当前步骤是权威值，纠正界面直接改计划等回放看不到的变化。
                (result?.get("currentPlan") as? JsonObject)?.let { plan ->
                    next = withFocus(next, plan.text("currentFocusStepId").orEmpty())
                    plan.text("goal")?.let { goal = it }
                }
                tree = next
                snapshots[call.key] = PlanCallSnapshot(call.name, goal, base, next)
            }
            "drop_plan" -> { tree = null; goal = null }
        }
    }
    return snapshots
}

private fun parsePlanNodes(array: JsonArray, withStatus: Boolean): List<PlanNode>? = array.mapIndexed { index, element ->
    val item = element as? JsonObject ?: return null
    val id = item.text("id")?.takeIf(String::isNotBlank) ?: return null
    val children = (item["children"] as? JsonArray)?.let { parsePlanNodes(it, withStatus) ?: return null }.orEmpty()
    val sortOrder = (item["sortOrder"] as? JsonPrimitive)?.intOrNull?.takeIf { withStatus || it > 0 } ?: index
    PlanNode(
        id = id,
        title = item.text("title").orEmpty(),
        status = if (withStatus) normalizedPlanStatus(item.text("status")) ?: "todo" else "todo",
        sortOrder = sortOrder,
        children = children,
    )
}.filterNot { it.status == "dropped" }

/** 与仓库的状态别名一致；无法识别时为 null。 */
internal fun normalizedPlanStatus(raw: String?): String? = when (raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
    "todo", "pending", "not_started", "open" -> "todo"
    "doing", "in_progress", "active", "started", "working" -> "doing"
    "done", "completed", "complete", "finished", "success", "succeeded" -> "done"
    "dropped", "skipped", "cancelled", "canceled", "abandoned" -> "dropped"
    else -> null
}

private fun sortedNodes(nodes: List<PlanNode>): List<PlanNode> = nodes.sortedBy(PlanNode::sortOrder)

private fun firstLeaf(nodes: List<PlanNode>): PlanNode? {
    for (node in sortedNodes(nodes)) {
        if (node.isLeaf) return node
        firstLeaf(node.children)?.let { return it }
    }
    return null
}

private fun mapLeaves(nodes: List<PlanNode>, transform: (PlanNode) -> PlanNode): List<PlanNode> = nodes.map { node ->
    if (node.isLeaf) transform(node) else node.copy(children = mapLeaves(node.children, transform))
}

private fun mapNode(nodes: List<PlanNode>, id: String, transform: (PlanNode) -> PlanNode): List<PlanNode> = nodes.map { node ->
    if (node.id == id) transform(node) else node.copy(children = mapNode(node.children, id, transform))
}

private fun removeNode(nodes: List<PlanNode>, id: String): List<PlanNode> =
    nodes.filterNot { it.id == id }.map { it.copy(children = removeNode(it.children, id)) }

private fun findNode(nodes: List<PlanNode>, id: String): PlanNode? {
    for (node in nodes) {
        if (node.id == id) return node
        findNode(node.children, id)?.let { return it }
    }
    return null
}

/** 把 [id] 设为唯一进行中的叶子；[id] 为空时清除进行中，找不到该叶子时保持原样。 */
private fun withFocus(nodes: List<PlanNode>, id: String): List<PlanNode> {
    if (id.isNotEmpty() && findNode(nodes, id)?.isLeaf != true) return nodes
    return mapLeaves(nodes) { leaf ->
        when {
            leaf.id == id -> leaf.copy(status = "doing")
            leaf.status == "doing" -> leaf.copy(status = "todo")
            else -> leaf
        }
    }
}

/** 逐条重放 update_plan 的操作；无法应用的操作跳过（真实调用中这类操作会让整次调用失败）。 */
private fun applyPlanOperations(base: List<PlanNode>, operations: JsonArray): List<PlanNode> {
    var nodes = base
    operations.forEach { element ->
        val op = element as? JsonObject ?: return@forEach
        val stepId = op.text("stepId")?.takeIf(String::isNotBlank)
        val payload = op["payload"] as? JsonObject
        when (op.text("action")) {
            "set_status" -> {
                val id = stepId ?: return@forEach
                val status = normalizedPlanStatus(payload?.text("status")) ?: return@forEach
                if (findNode(nodes, id)?.isLeaf != true) return@forEach
                nodes = when (status) {
                    "dropped" -> removeNode(nodes, id)
                    "doing" -> withFocus(nodes, id)
                    else -> mapNode(nodes, id) { it.copy(status = status) }
                }
            }
            "add_step" -> {
                val title = payload?.text("title")?.takeIf(String::isNotBlank) ?: return@forEach
                val id = stepId ?: "added-${nodes.hashCode()}-${operations.indexOf(element)}"
                if (findNode(nodes, id) != null) return@forEach
                val parentId = op.text("parentId")?.takeIf(String::isNotBlank)
                val siblings = if (parentId == null) nodes else findNode(nodes, parentId)?.children ?: return@forEach
                val sortOrder = payload?.text("sortOrder")?.toIntOrNull() ?: ((siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                val added = PlanNode(id, title, "todo", sortOrder)
                nodes = if (parentId == null) nodes + added else mapNode(nodes, parentId) { it.copy(children = it.children + added) }
            }
            "remove_step" -> nodes = removeNode(nodes, stepId ?: return@forEach)
            "move_step" -> {
                val id = stepId ?: return@forEach
                val node = findNode(nodes, id) ?: return@forEach
                val newParent = (payload?.text("newParentId") ?: op.text("parentId"))?.takeIf(String::isNotBlank)
                if (newParent != null && (newParent == id || findNode(node.children, newParent) != null)) return@forEach
                if (newParent != null && findNode(nodes, newParent) == null) return@forEach
                val moved = node.copy(sortOrder = payload?.text("newSortOrder")?.toIntOrNull() ?: node.sortOrder)
                val without = removeNode(nodes, id)
                nodes = if (newParent == null) without + moved else mapNode(without, newParent) { it.copy(children = it.children + moved) }
            }
            "update_title" -> {
                val title = payload?.text("title")?.takeIf(String::isNotBlank) ?: return@forEach
                nodes = mapNode(nodes, stepId ?: return@forEach) { it.copy(title = title) }
            }
        }
    }
    return nodes
}

/** 从会话消息中取出全部计划工具调用，键与界面展示副本中的调用步骤 id（`消息id::步骤id`）一致。 */
internal fun planCallRecords(messages: List<Message>): List<PlanCallRecord> = messages.flatMap { message ->
    if (message.role != MessageRole.ASSISTANT) return@flatMap emptyList()
    val steps = message.executionSteps.orEmpty()
    if (steps.isEmpty()) return@flatMap emptyList()
    val callNames = message.toolCalls.orEmpty().associate { it.id to it.name }
    // 只给可能是计划工具的步骤补工具名，避免为每条步骤解析 JSON。
    val named = steps.map { step ->
        val name = step.toolName?.takeIf(String::isNotBlank) ?: step.toolCallId?.let(callNames::get)
            ?: if (listOfNotNull(step.data, step.toolArgs, step.content).any { "_plan" in it }) inferredToolName(step) else null
        if (name == step.toolName) step else step.copy(toolName = name)
    }
    if (named.none { it.toolName in PLAN_REPLAY_TOOLS }) return@flatMap emptyList()
    toolInvocations(named).mapNotNull { invocation ->
        val name = invocation.name?.takeIf { it in PLAN_REPLAY_TOOLS } ?: return@mapNotNull null
        val call = invocation.call ?: return@mapNotNull null
        PlanCallRecord(
            key = "${message.id}::${call.id}",
            name = name,
            arguments = call.toolArgs,
            result = invocation.results.firstOrNull { it.type == "tool_result" }?.content,
            failed = invocation.failed,
        )
    }
}

/** 会话级回放结果；由消息列表一次性计算。 */
internal fun planCallSnapshots(messages: List<Message>): Map<String, PlanCallSnapshot> = replayPlanCalls(planCallRecords(messages))

/** 单次调用自身就能确定清单时（初始化、读取）的快照；没有会话级回放结果时使用。 */
internal fun standalonePlanSnapshot(key: String, name: String?, arguments: String?, result: String?, failed: Boolean): PlanCallSnapshot? {
    if (name !in PLAN_CARD_TOOLS) return null
    return replayPlanCalls(listOf(PlanCallRecord(key, name!!, arguments, result, failed)))[key]
}

internal enum class PlanRowState { DONE, CURRENT, TODO }

/** 清单中的一行；父步骤 [isGroup] 只作分组标题。 */
internal data class PlanCardRow(
    val id: String,
    val title: String,
    val state: PlanRowState,
    val depth: Int,
    val isGroup: Boolean,
    val justCompleted: Boolean = false,
)

/** [position] 为当前步骤在叶子中的序号（从 1 起）；全部完成时 [allDone] 为 true。 */
internal data class PlanCardModel(
    val toolName: String,
    val goal: String?,
    val rows: List<PlanCardRow>,
    val position: Int,
    val total: Int,
    val allDone: Boolean,
    val completedTitles: List<String>,
    val startedTitle: String?,
)

internal fun planCardModel(snapshot: PlanCallSnapshot): PlanCardModel? {
    val beforeStatus = snapshot.before?.let { before ->
        buildMap { fun walk(nodes: List<PlanNode>) { nodes.forEach { put(it.id, it.status); walk(it.children) } }; walk(before) }
    }
    val rows = mutableListOf<PlanCardRow>()
    fun walk(nodes: List<PlanNode>, depth: Int) {
        sortedNodes(nodes).forEach { node ->
            if (node.isLeaf) {
                val state = when (node.status) {
                    "done" -> PlanRowState.DONE
                    "doing" -> PlanRowState.CURRENT
                    else -> PlanRowState.TODO
                }
                val justCompleted = state == PlanRowState.DONE && beforeStatus != null && beforeStatus[node.id] != "done"
                rows += PlanCardRow(node.id, node.title, state, depth, isGroup = false, justCompleted = justCompleted)
            } else {
                rows += PlanCardRow(node.id, node.title, PlanRowState.TODO, depth, isGroup = true)
                walk(node.children, depth + 1)
            }
        }
    }
    walk(snapshot.after, 0)
    val leaves = rows.filterNot { it.isGroup }
    if (leaves.isEmpty()) return null
    val currentIndex = leaves.indexOfFirst { it.state == PlanRowState.CURRENT }
        .takeIf { it >= 0 } ?: leaves.indexOfFirst { it.state != PlanRowState.DONE }
    val allDone = currentIndex < 0
    val started = leaves.firstOrNull { it.state == PlanRowState.CURRENT }
        ?.takeIf { beforeStatus != null && beforeStatus[it.id] != "doing" }
    return PlanCardModel(
        toolName = snapshot.name,
        goal = snapshot.goal?.takeIf(String::isNotBlank),
        rows = rows,
        position = if (allDone) leaves.size else currentIndex + 1,
        total = leaves.size,
        allDone = allDone,
        completedTitles = leaves.filter { it.justCompleted }.map { it.title },
        startedTitle = started?.title,
    )
}

/** 折叠行摘要：创建、读取给出规模与进度；更新说明完成与开始了哪些步骤；看不出状态变化时返回 null 沿用通用摘要。 */
internal fun planSummaryText(model: PlanCardModel): ToolText? = when (model.toolName) {
    "initialize_plan" -> if (model.goal != null) ToolText(R.string.tool_card_plan_summary_created, listOf(model.goal, model.total))
        else ToolText(R.string.tool_card_plan_summary_created_untitled, listOf(model.total))
    "get_plan" -> if (model.allDone) ToolText(R.string.tool_card_plan_summary_viewed_done, listOf(model.total))
        else ToolText(R.string.tool_card_plan_summary_viewed, listOf(model.position, model.total))
    "update_plan" -> {
        val completed = model.completedTitles
        val started = model.startedTitle
        when {
            completed.size == 1 && started != null -> ToolText(R.string.tool_card_plan_summary_done_started, listOf(completed.single(), started))
            completed.size > 1 && started != null -> ToolText(R.string.tool_card_plan_summary_done_many_started, listOf(completed.size, started))
            completed.size == 1 -> ToolText(R.string.tool_card_plan_summary_done, listOf(completed.single()))
            completed.size > 1 -> ToolText(R.string.tool_card_plan_summary_done_many, listOf(completed.size))
            started != null -> ToolText(R.string.tool_card_plan_summary_started, listOf(started))
            else -> null
        }
    }
    else -> null
}

// ─────────────────────────────────────────────────────────────────
//  后台任务卡
// ─────────────────────────────────────────────────────────────────

/** 日志末尾最多显示的行数与单行字符数；完整内容由“打开完整日志”查看。 */
internal const val JOB_LOG_TAIL_LINES = 8
private const val JOB_LOG_LINE_CHARS = 240
private val ANSI_ESCAPE = Regex("""\u001B\[[0-9;?]*[ -/]*[@-~]""")

internal data class JobCardModel(
    val jobId: String?,
    /** 小写状态：running、exited、stopped、timed_out、interrupted、failed。 */
    val state: String,
    val exitCode: Int?,
    val elapsedSeconds: Long?,
    val startedAtMillis: Long?,
    /** 命令摘要；任务没有短标题时作为任务名。 */
    val command: String?,
    val logLines: List<String>,
    val logTruncated: Boolean,
    val logReference: String?,
) {
    val running: Boolean get() = state == "running"
}

/** job_status / job_wait 结果；不是任务状态对象时返回 null。 */
internal fun parseJobCard(result: String?, maxLines: Int = JOB_LOG_TAIL_LINES): JobCardModel? {
    val root = parseObject(result) ?: return null
    val state = root.text("state")?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    val tail = root.text("log_tail").orEmpty()
    val allLines = tail.replace("\r\n", "\n").split('\n')
        .map { line -> ANSI_ESCAPE.replace(line.substringAfterLast('\r'), "").trimEnd() }
        .dropLastWhile(String::isEmpty)
    val lines = allLines.takeLast(maxLines)
        .map { if (it.length > JOB_LOG_LINE_CHARS) it.take(JOB_LOG_LINE_CHARS - 1) + "…" else it }
    return JobCardModel(
        jobId = root.text("job_id")?.takeIf(String::isNotBlank),
        state = state,
        exitCode = (root["exit_code"] as? JsonPrimitive)?.intOrNull,
        elapsedSeconds = (root["elapsed_seconds"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 },
        startedAtMillis = root.text("started_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
        command = root.text("summary")?.lineSequence()?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty),
        logLines = lines,
        logTruncated = (root["log_truncated"] as? JsonPrimitive)?.booleanOrNull == true || allLines.size > lines.size,
        logReference = root.text("log_reference")?.takeIf(String::isNotBlank),
    )
}

/** 已运行时长：不足一小时为 m:ss，否则为 h:mm:ss。 */
internal fun jobElapsedClock(seconds: Long): String = if (seconds < 3_600) String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    else String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)

/** 状态文字：退出码为 0 的退出视为完成；未知状态原样显示。 */
internal fun jobStateText(state: String, exitCode: Int?): ToolText = when (state) {
    "running" -> ToolText(R.string.tool_card_job_state_running)
    "exited" -> if (exitCode == null || exitCode == 0) ToolText(R.string.tool_card_job_state_completed) else ToolText(R.string.tool_card_job_state_exited)
    "stopped" -> ToolText(R.string.tool_card_job_state_stopped)
    "timed_out" -> ToolText(R.string.tool_card_job_state_timed_out)
    "interrupted" -> ToolText(R.string.tool_card_job_state_interrupted)
    "failed" -> ToolText(R.string.tool_card_job_state_failed)
    else -> ToolText(R.string.tool_card_job_state_other, listOf(state))
}

/** 状态是否表示失败，用于着色。 */
internal fun jobStateIsFailure(state: String, exitCode: Int?): Boolean =
    state == "failed" || state == "timed_out" || (state == "exited" && exitCode != null && exitCode != 0)

/**
 * 折叠行摘要，例如“查看后台任务 · 复制 Personal-Archive · 运行中 12:03”。
 * 任务名优先取会话任务列表里的短标题，其次是结果中的命令摘要，最后是短任务号。
 */
internal fun jobSummaryText(kind: CommandStepKind, label: String?, model: JobCardModel): ToolText {
    val action = ToolText(if (kind == CommandStepKind.WAIT) R.string.chat_step_job_wait_name else R.string.chat_step_job_status_name)
    val name: Any = label?.takeIf(String::isNotBlank) ?: model.command?.let { if (it.length > 40) it.take(39) + "…" else it }
        ?: model.jobId?.let { ToolText(R.string.chat_step_job_unknown, listOf(shortJobId(it))) }
        ?: ToolText(R.string.tool_card_job_unnamed)
    val state = jobStateText(model.state, model.exitCode)
    return model.elapsedSeconds?.let { ToolText(R.string.tool_card_job_summary_elapsed, listOf(action, name, state, jobElapsedClock(it))) }
        ?: ToolText(R.string.tool_card_job_summary, listOf(action, name, state))
}

/** 适用任务卡的调用：查看任务，或已拿到结果的等待任务。 */
internal fun usesJobCard(toolName: String?): Boolean = toolName == "job_status" || toolName == "job_wait"

// ─────────────────────────────────────────────────────────────────
//  通用字段化展示
// ─────────────────────────────────────────────────────────────────

/** 一行字段；[depth] 为嵌套层级，[isHeader] 的行只是嵌套对象或数组的标题，值在其下方各行。 */
internal data class ToolFieldRow(
    val key: String,
    val value: String,
    val depth: Int = 0,
    val isHeader: Boolean = false,
    /** 值过长或含换行，界面默认折叠。 */
    val isLong: Boolean = false,
)

/** 嵌套值紧凑成一行的长度上限；超过时展开为缩进行。 */
private const val INLINE_NESTED_CHARS = 60
private const val MAX_FIELD_DEPTH = 2
private const val MAX_FIELD_ROWS = 120
internal const val LONG_FIELD_CHARS = 160

/**
 * 有专用展示的工具（命令、文件编辑与传输、浏览器、知识检索、记忆写入、子代理、保密输入输出、计划）不做字段化，保持原有展示。
 */
internal fun usesFieldFallback(toolName: String?): Boolean = when (toolSummaryAction(toolName)) {
    ToolSummaryAction.COMMAND, ToolSummaryAction.FILE_EDIT, ToolSummaryAction.FILE_TRANSFER, ToolSummaryAction.BROWSER,
    ToolSummaryAction.KNOWLEDGE_SEARCH, ToolSummaryAction.MEMORY_WRITE, ToolSummaryAction.SUBAGENT, ToolSummaryAction.SECRET -> false
    else -> true
}

/**
 * JSON 对象按“字段名 → 值”逐行展开：字符串去掉引号，标量原样；嵌套对象与数组较短时紧凑成一行，较长时缩进展开（最多两层）。
 * 不是 JSON 对象、对象为空或行数过多时返回 null，由界面显示原文。
 */
internal fun toolFieldRows(text: String?): List<ToolFieldRow>? {
    val root = parseObject(text)?.takeIf { it.isNotEmpty() } ?: return null
    val rows = mutableListOf<ToolFieldRow>()
    fun leaf(key: String, value: String, depth: Int) {
        rows += ToolFieldRow(key, value, depth, isLong = value.length > LONG_FIELD_CHARS || '\n' in value)
    }
    fun add(key: String, element: JsonElement, depth: Int) {
        when (element) {
            is JsonPrimitive -> leaf(key, if (element is JsonNull) "null" else element.content, depth)
            is JsonArray -> when {
                element.isEmpty() -> leaf(key, "[]", depth)
                element.all { it is JsonPrimitive && it !is JsonNull } -> {
                    val joined = element.joinToString(", ") { (it as JsonPrimitive).content }
                    if (joined.length <= INLINE_NESTED_CHARS || depth >= MAX_FIELD_DEPTH) leaf(key, joined, depth)
                    else { rows += ToolFieldRow(key, "", depth, isHeader = true); element.forEachIndexed { i, item -> add("[${i + 1}]", item, depth + 1) } }
                }
                else -> {
                    val compact = element.toString()
                    if (compact.length <= INLINE_NESTED_CHARS || depth >= MAX_FIELD_DEPTH) leaf(key, compact, depth)
                    else { rows += ToolFieldRow(key, "", depth, isHeader = true); element.forEachIndexed { i, item -> add("[${i + 1}]", item, depth + 1) } }
                }
            }
            is JsonObject -> {
                val compact = element.toString()
                if (element.isEmpty()) leaf(key, "{}", depth)
                else if (compact.length <= INLINE_NESTED_CHARS || depth >= MAX_FIELD_DEPTH) leaf(key, compact, depth)
                else { rows += ToolFieldRow(key, "", depth, isHeader = true); element.forEach { (k, v) -> add(k, v, depth + 1) } }
            }
        }
    }
    root.forEach { (key, value) -> add(key, value, 0) }
    return rows.takeIf { it.size <= MAX_FIELD_ROWS }
}

/** 取一次调用的首个成功结果文本，供卡片解析。 */
internal fun firstSuccessContent(results: List<ExecutionStep>): String? =
    results.firstOrNull { it.type == "tool_result" }?.content
