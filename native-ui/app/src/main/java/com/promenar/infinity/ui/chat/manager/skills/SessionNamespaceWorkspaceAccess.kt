package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.storage.ExternalFolderManager
import com.promenar.infinity.domain.repository.DiffResult
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import kotlinx.serialization.json.JsonObject

/**
 * 统一会话文件命名空间接口：Linux 模式与内置真实路径模式共用同一套 [MountedWorkspaceSkill] 执行、差异与传输管道。
 */
internal interface SessionNamespaceWorkspaceAccess : ExternalWorkspaceAccess {
    val knowledgeManager: ExternalFolderManager?
    suspend fun toolRootUuid(sessionId: String): String
    suspend fun workingDirectory(sessionId: String): String
    suspend fun statEntry(sessionId: String, path: String): FileEntry?
    suspend fun isPathWritable(sessionId: String, path: String): Boolean
    suspend fun validatePath(sessionId: String, path: String): String
    suspend fun executionTarget(sessionId: String, tool: ProtocolTool, args: JsonObject): String?
    suspend fun invocationArguments(sessionId: String, tool: String, args: JsonObject): JsonObject
    suspend fun diffPrivate(sessionId: String, uuid: String, basisHash: String?): DiffResult?
    suspend fun movePrivate(sessionId: String, source: String, destination: String, expectedHash: String?): ToolResult?
    suspend fun sameMountedBackend(sessionId: String, source: String, destination: String): Boolean
    suspend fun moveMounted(sessionId: String, source: String, destination: String): Unit?
    suspend fun textMime(sessionId: String, path: String): String?
    suspend fun searchRoots(sessionId: String): List<String>
    suspend fun aliasOf(sessionId: String, path: String): String
    fun protectedVirtualRoots(): Set<String>
}
