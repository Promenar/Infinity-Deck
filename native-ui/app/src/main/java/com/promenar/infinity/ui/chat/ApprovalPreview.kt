package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.model.ApprovalCallIdentity
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.domain.tool.ToolArgumentsValidation
import com.promenar.infinity.domain.tool.ToolArgumentsValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

/**
 * 审批卡片的写入预览：从助手消息中取出与审批身份摘要完全一致的原始参数，
 * 生成目标与完整内容。摘要不一致或无法解析时返回 null，界面回退到参数摘要。
 * 预览正文不做任何截断：用户批准的必须是完整参数，折叠与限高只由界面负责。
 */
object ApprovalPreview {
    internal const val IRREVERSIBLE_WARNING = "将永久删除，无法恢复。"
    internal const val NETWORK_ALLOWED = "可联网"

    enum class Action { OVERWRITE, CREATE_FILE, EDIT, MOVE, DELETE, CREATE_DIRECTORY, SCRIPT }

    /** 与语言无关的预览数据；动作标题由界面按资源本地化。 */
    data class Preview(
        val action: Action,
        val target: String,
        val detail: String?,
        val scriptAllowsNetwork: Boolean = false,
        /** 内置执行后端：命令直接改动工作区且可访问应用私有数据与网络。 */
        val commandRunsInWorkspace: Boolean = false,
    )

    fun forCall(call: ApprovalCallIdentity, messages: List<Message>, assistantMessageId: String?): Preview? =
        arguments(call, messages, assistantMessageId)?.let { format(call.toolName, it) }

    /** 与审批身份摘要完全一致的原始参数；摘要不一致或无法解析时为 null。差异预览与文本预览共用。 */
    fun arguments(call: ApprovalCallIdentity, messages: List<Message>, assistantMessageId: String?): JsonObject? {
        val arguments = messages
            .firstOrNull { it.id == assistantMessageId }
            ?.toolCalls
            ?.firstOrNull { it.id == call.toolCallId }
            ?.arguments
            ?: return null
        val validated = ToolArgumentsValidator().validate(arguments) as? ToolArgumentsValidation.Valid ?: return null
        if (validated.sha256 != call.argumentsDigest) return null
        return validated.arguments
    }

    fun format(toolName: String, args: JsonObject): Preview? {
        val target = args.string("path") ?: args.string("uuid").orEmpty()
        val newChild = (args.string("parentPath") ?: args.string("parentUuid") ?: "").trimEnd('/') +
            "/" + args.string("name").orEmpty()
        return when (toolName) {
            "copy_knowledge_to_linux" -> Preview(Action.CREATE_FILE,
                args.string("target") ?: com.promenar.infinity.ui.chat.manager.skills.CopyKnowledgeToLinuxSkill.DEFAULT_TARGET,
                args.string("source")?.let { "来源：$it" })
            "write_file" -> Preview(Action.OVERWRITE, target, args.string("content"))
            "create_file" -> Preview(Action.CREATE_FILE, newChild, args.string("content"))
            "create_directory" -> Preview(Action.CREATE_DIRECTORY, newChild, null)
            "delete_file" -> Preview(Action.DELETE, target, IRREVERSIBLE_WARNING)
            "exec_js", "run_script", "python_sandbox" -> Preview(Action.SCRIPT, if (toolName == "run_script") target else "", buildString {
                if ((args["allow_network"] as? JsonPrimitive)?.booleanOrNull == true) appendLine(NETWORK_ALLOWED)
                args.string("code")?.let { append(it) }
            }.trimEnd().ifEmpty { null },
                scriptAllowsNetwork = (args["allow_network"] as? JsonPrimitive)?.booleanOrNull == true)
            "browser_eval" -> Preview(Action.SCRIPT, "", args.string("code"))
            "shell_exec", "python_exec" -> Preview(
                Action.SCRIPT,
                "",
                args.string("command") ?: args.string("code"),
                commandRunsInWorkspace = true,
            )
            // Termux 在其自身 home 中运行，不改动 Infinity Workbench 工作区。
            "job_input" -> Preview(Action.SCRIPT, args.string("job_id").orEmpty(), args.string("text"))
            "termux_exec" -> Preview(Action.SCRIPT, "", args.string("command"))
            "move_file" -> Preview(
                Action.MOVE,
                target,
                buildString {
                    (args.string("newParentPath") ?: args.string("newParentUuid"))?.let { append("→ $it/") }
                    args.string("newName")?.let { if (isNotEmpty()) append(' '); append("→ $it") }
                }.ifEmpty { null },
            )
            "patch_file" -> Preview(
                Action.EDIT,
                target,
                buildString {
                    (args["operations"] as? JsonArray).orEmpty().forEach { element ->
                        val op = element as? JsonObject ?: return@forEach
                        appendLine(
                            when (op.string("action")) {
                                "insert_after" -> "+ @L${op.int("afterLine")}"
                                "delete_lines" -> "- L${op.int("startLine")}–${op.int("endLine")}"
                                else -> "~ L${op.int("startLine")}–${op.int("endLine")}"
                            },
                        )
                        op.string("newContent")?.let { appendLine(prefixLines(it, prefix = "  + ")) }
                    }
                }.trimEnd().ifEmpty { null },
            )
            else -> null
        }
    }

    private fun prefixLines(text: String, prefix: String): String =
        text.lines().joinToString("\n") { prefix + it }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)

    private fun JsonObject.int(key: String): String =
        (this[key] as? JsonPrimitive)?.intOrNull?.toString() ?: "?"
}
