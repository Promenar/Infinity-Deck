package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.mediagen.WorkflowFixedOverrideDiff
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

data class ComfyFixedFieldDiff(
    val key: String,
    val label: String,
    val oldValue: String,
    val newValue: String,
)

data class ComfyFixedOverridePreflight(
    val workflowId: String,
    val workflowName: String,
    val hostName: String,
    val submitterLabel: String,
    val reason: String,
    val diffs: List<ComfyFixedFieldDiff>,
    val authorizationTarget: String,
)

/**
 * 为 `comfy_run_workflow` 计算“工作流固定字段 (`FIXED`) 单次实验覆写”预检：
 * - 仅填 `content_inputs` 时返回 null（常规免工作流审批）；
 * - `fixed_overrides` 中的值与工作流当前基线完全一致时同样返回 null；
 * - 只要存在任一偏离基线的 `FIXED` 字段，即生成确定性 `comfy-fixed-override:` 授权目标与精确 Diff；
 * - 审批通过后仅当次运行生效，绝不改写原工作流基线。
 */
object ComfyFixedOverrideApprovalPlanner {
    const val TARGET_PREFIX = "comfy-fixed-override:"
    const val REASON_MAX_LENGTH = 300

    fun isOverrideTarget(target: String?): Boolean = target?.startsWith(TARGET_PREFIX) == true

    fun extractReason(args: JsonObject): String {
        val raw = args["reason"] ?: return ""
        if (raw == JsonNull) return ""
        val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: return ""
        return text.take(REASON_MAX_LENGTH)
    }

    suspend fun analyze(repository: ComfyRepository, args: JsonObject): ComfyFixedOverridePreflight? {
        val workflowId = (args["workflow_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val fixedObj = args["fixed_overrides"] as? JsonObject ?: return null
        if (fixedObj.isEmpty()) return null

        val wfState = repository.workflowStateOrNull(workflowId) ?: return null
        val workflow = wfState.workflow
        val diffs = runCatching { workflow.detectFixedOverrides(fixedObj) }.getOrNull() ?: return null
        if (diffs.isEmpty()) return null

        val hostName = repository.hosts().firstOrNull { it.id == workflow.hostId }?.name ?: workflow.hostId
        val submitterLabel = workflow.submitterScriptId?.let { id ->
            repository.submitter(workflow.hostId, id)?.fileName ?: id
        } ?: "内置通用 API JSON"

        val digest = computeDigest(workflowId, diffs)
        val fieldDiffs = diffs.map { d ->
            ComfyFixedFieldDiff(
                key = d.key,
                label = d.label,
                oldValue = d.oldDisplayValue,
                newValue = d.newDisplayValue,
            )
        }
        return ComfyFixedOverridePreflight(
            workflowId = workflow.workflowId,
            workflowName = workflow.name,
            hostName = hostName,
            submitterLabel = submitterLabel,
            reason = extractReason(args),
            diffs = fieldDiffs,
            authorizationTarget = "$TARGET_PREFIX${workflow.workflowId}:$digest",
        )
    }

    private fun computeDigest(workflowId: String, diffs: List<WorkflowFixedOverrideDiff>): String {
        val canonical = buildString {
            append(workflowId)
            diffs.sortedBy { it.key }.forEach { d ->
                append('|').append(d.key).append('=').append(canonicalJson(d.newValue))
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
            .take(24)
    }

    private fun canonicalJson(elem: JsonElement): String = when (elem) {
        is JsonObject -> elem.entries.sortedBy { it.key }
            .joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonicalJson(it.value)}" }
        is JsonArray -> elem.joinToString(",", "[", "]") { canonicalJson(it) }
        else -> elem.toString()
    }
}
