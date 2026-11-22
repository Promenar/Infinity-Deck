package com.promenar.infinity.ui.chat.diff

import com.promenar.infinity.data.mediagen.comfy.ComfyFixedOverrideApprovalPlanner
import com.promenar.infinity.data.mediagen.comfy.ComfyRepository
import com.promenar.infinity.domain.mediagen.FieldMutability
import com.promenar.infinity.domain.mediagen.WorkflowExecutionMode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal class ComfyWorkflowApprovalPreviewLoader(private val repository: ComfyRepository) {
    suspend fun loadManagement(
        toolName: String,
        args: JsonObject,
        fallbackReason: String,
    ): ApprovalDiffPreview.ComfyManagement? = try {
        val reason = (args["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: fallbackReason
        val hosts = repository.hosts().associateBy { it.id }
        when (toolName) {
            "comfy_save_submitter" -> {
                val hostId = str(args, "host_id") ?: return null
                val scriptId = str(args, "script_id") ?: return null
                val fileName = str(args, "file_name") ?: scriptId
                val existing = repository.submitter(hostId, scriptId)
                val protocol = str(args, "protocol") ?: "PAYLOAD_JSON"
                val supportCount = (args["support_files"] as? JsonObject)?.size
                    ?: (args["support_paths"] as? JsonArray)?.size ?: 0
                val title = if (existing == null) "新增提交器脚本" else "更新提交器脚本"
                val rows = buildList {
                    add("脚本文件" to "$fileName ($scriptId)")
                    add("所属主机" to (hosts[hostId]?.name ?: hostId))
                    add("调用协议" to protocol)
                    add("配套模块" to if (supportCount == 0) "无" else "$supportCount 个文件")
                    add("凭据边界" to "由宿主本机回环代理注入，脚本零接触凭据")
                }
                ApprovalDiffPreview.ComfyManagement(
                    title = title,
                    hostName = hosts[hostId]?.name ?: hostId,
                    reason = reason,
                    rows = rows,
                    isDeletion = false,
                )
            }
            "comfy_delete_submitter" -> {
                val hostId = str(args, "host_id") ?: return null
                val scriptId = str(args, "script_id") ?: return null
                val existing = repository.submitter(hostId, scriptId)
                ApprovalDiffPreview.ComfyManagement(
                    title = "删除提交器脚本",
                    hostName = hosts[hostId]?.name ?: hostId,
                    reason = reason,
                    rows = listOf(
                        "目标脚本" to (existing?.fileName ?: scriptId),
                        "所属主机" to (hosts[hostId]?.name ?: hostId),
                    ),
                    isDeletion = true,
                )
            }
            "comfy_save_workflow" -> {
                val wfObj = (args["workflow"] as? JsonObject) ?: args
                val workflowId = str(wfObj, "workflowId") ?: str(wfObj, "workflow_id") ?: return null
                val hostId = str(wfObj, "hostId") ?: str(wfObj, "host_id") ?: return null
                val name = str(wfObj, "name") ?: workflowId
                val existing = repository.workflowStateOrNull(workflowId)
                val submitterId = str(wfObj, "submitterScriptId") ?: str(wfObj, "submitter_script_id")
                val mode = str(wfObj, "executionMode") ?: str(wfObj, "execution_mode") ?: WorkflowExecutionMode.SINGLE.name
                val fields = (wfObj["fields"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val contentKeys = fields.filter {
                    (str(it, "mutability") ?: FieldMutability.CONTENT.name) == FieldMutability.CONTENT.name
                }.mapNotNull { str(it, "key") }
                val fixedKeys = fields.filter {
                    str(it, "mutability") == FieldMutability.FIXED.name
                }.mapNotNull { str(it, "key") }
                val submitterFile = submitterId?.let { repository.submitter(hostId, it)?.fileName ?: it } ?: "内置通用 API JSON"
                ApprovalDiffPreview.ComfyManagement(
                    title = if (existing == null) "注册工作流" else "更新工作流",
                    hostName = hosts[hostId]?.name ?: hostId,
                    reason = reason,
                    rows = listOf(
                        "工作流名称" to "$name ($workflowId)",
                        "所属主机" to (hosts[hostId]?.name ?: hostId),
                        "绑定提交器" to submitterFile,
                        "执行模式" to if (mode == WorkflowExecutionMode.STATEFUL_CHAIN.name) {
                            "有状态链式（first / next · 支持任意中段重试）"
                        } else {
                            "单次执行（single）"
                        },
                        "内容字段" to if (contentKeys.isEmpty()) "0 项" else "${contentKeys.joinToString(", ")} (${contentKeys.size} 项)",
                        "固定字段" to if (fixedKeys.isEmpty()) "0 项" else "${fixedKeys.joinToString(", ")} (${fixedKeys.size} 项)",
                    ),
                    isDeletion = false,
                )
            }
            "comfy_delete_workflow" -> {
                val workflowId = str(args, "workflow_id") ?: return null
                val existing = repository.workflowStateOrNull(workflowId)?.workflow
                val hostName = existing?.hostId?.let { hosts[it]?.name ?: it } ?: "ComfyUI"
                ApprovalDiffPreview.ComfyManagement(
                    title = "删除工作流",
                    hostName = hostName,
                    reason = reason,
                    rows = listOf(
                        "工作流" to (existing?.name ?: workflowId),
                        "所属主机" to hostName,
                    ),
                    isDeletion = true,
                )
            }
            else -> null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    suspend fun loadFixedOverride(
        args: JsonObject,
        fallbackReason: String,
    ): ApprovalDiffPreview.ComfyFixedOverride? = try {
        val preflight = ComfyFixedOverrideApprovalPlanner.analyze(repository, args) ?: return null
        val contentInputs = (args["content_inputs"] as? JsonObject).orEmpty()
        val contentSummary = buildString {
            if (contentInputs.isEmpty()) append("沿用默认内容字段")
            else {
                val keys = contentInputs.keys.take(4).joinToString(" + ")
                append("内容字段已就绪：").append(keys)
                if (contentInputs.size > 4) append(" 等 ${contentInputs.size} 项")
            }
        }
        val stage = str(args, "stage")
        val parent = str(args, "parent_node_id")
        val retry = str(args, "retry_node_id")
        val chainId = str(args, "chain_id")
        val chainSummary = when {
            retry != null -> "${chainId.orEmpty()} / 重跑节点 $retry".trimStart(' ', '/')
            parent != null -> "${chainId.orEmpty()} / 从 $parent 发起新段".trimStart(' ', '/')
            stage == "next" -> "${chainId.orEmpty()} / 从当前末端接续".trimStart(' ', '/')
            stage == "first" -> "新建有状态链首段"
            else -> null
        }?.trim()
        val reason = preflight.reason.ifBlank { fallbackReason }
        ApprovalDiffPreview.ComfyFixedOverride(
            preflight = preflight.copy(reason = reason),
            contentSummary = contentSummary,
            chainContextSummary = chainSummary,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
}
