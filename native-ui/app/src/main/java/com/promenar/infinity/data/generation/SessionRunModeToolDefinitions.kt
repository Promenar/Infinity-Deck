package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.repository.ToolDefinitionDigestResolution
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.chat.manager.SystemPromptSections
import kotlinx.serialization.json.*

/** 广告工具和执行摘要共用的会话定义；底层定义指纹仍参与校验。 */
object SessionRunModeToolDefinitions {
    private val files = setOf("read_file", "write_file", "patch_file", "diff_file", "list_files", "search_files", "create_file", "create_directory", "move_file", "delete_file")
    private const val PATHS = "文件工具使用 Linux 路径：/workspace 为主工作区，/home/session 为会话专用空间，/mnt/<别名> 为真实路径挂载，/mnt/skills 为用户技能；其它绝对路径位于 rootfs。相对路径相对 /workspace，禁止 .. 和符号链接。delete_file 永久删除，需要用户批准。"

    fun forSession(tool: ProtocolTool, mode: SessionRunMode?): ProtocolTool {
        if (mode != SessionRunMode.LINUX || tool.sourceId != "builtin") return tool
        val name = tool.function.name
        if (name !in setOf("shell_exec", "python_exec") && name !in files) return tool
        val base = tool.baseDefinitionDigest ?: (ToolInvocationIdentityFactory.definitionDigest(tool) as? ToolDefinitionDigestResolution.Valid)?.digest ?: return tool
        val description = when (name) {
            "shell_exec" -> SystemPromptSections.LINUX_SHELL_GUIDANCE
            "python_exec" -> "在本会话 Linux 环境的 python3 中执行 code，可使用本会话保密库的环境变量引用与 capture_secret；捕获强制前台。路径、pip 与 apk 安装、后台任务规则与 shell_exec 工具说明相同。"
            else -> "$name。$PATHS"
        }
        val schema = if (name !in files) tool.function.parameters else runCatching {
            val definition = Json.parseToJsonElement(tool.function.parameters).jsonObject
            val properties = definition["properties"]?.jsonObject.orEmpty().mapValues { (key, value) ->
                if (key !in setOf("path", "parentPath", "newParentPath")) value
                else JsonObject(value.jsonObject + ("description" to JsonPrimitive("Linux 路径，例如 /workspace/notes；相对路径相对 /workspace")))
            }
            JsonObject(definition + ("properties" to JsonObject(properties))).toString()
        }.getOrDefault(tool.function.parameters)
        return tool.copy(function = tool.function.copy(description = description, parameters = schema), baseDefinitionDigest = base)
    }
}
