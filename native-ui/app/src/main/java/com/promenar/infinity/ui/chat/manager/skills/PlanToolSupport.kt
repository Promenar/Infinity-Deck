package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.TaskState
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.repository.TaskRepository
import com.promenar.infinity.domain.repository.ITaskRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 计划工具的结构化结果：内容一律经 JSON 序列化，标题与目标中的引号、换行不会破坏结果。 */
internal fun planResult(prefix: String, body: JsonObjectBuilder.() -> Unit): ToolResult = ToolResult(
    id = "${prefix}_${System.currentTimeMillis()}",
    content = buildJsonObject(body).toString(),
)

/** 可由模型纠正的计划错误：带原因回传，状态为 error。 */
internal fun planError(prefix: String, code: String, message: String?, suggestion: String? = null): ToolResult =
    ToolResult(
        id = "${prefix}_${System.currentTimeMillis()}",
        content = buildJsonObject {
            put("error", code)
            put("message", message.orEmpty().take(MAX_PLAN_ERROR_CHARS))
            suggestion?.let { put("suggestion", it) }
        }.toString(),
        status = "error",
    )

internal fun JsonObjectBuilder.putPlanSummary(repo: ITaskRepository, state: TaskState) {
    val (done, total) = repo.countLeafProgress(state.steps)
    put("planId", state.id)
    put("goal", state.title)
    put("status", state.status)
    put("leafProgress", buildJsonObject {
        put("done", done)
        put("total", total)
    })
    put("currentFocusStepId", state.currentFocusStepId.orEmpty())
    put("steps", Json.encodeToJsonElement(state.steps))
}

internal fun flattenSteps(steps: List<TaskStep>): List<TaskStep> =
    steps.flatMap { listOf(it) + flattenSteps(it.children) }

internal val PLAN_STATUS_HINT = "可用状态：" + TaskRepository.STEP_STATUSES.joinToString("、") + "。"

private const val MAX_PLAN_ERROR_CHARS = 600

