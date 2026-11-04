package com.promenar.infinity.domain.workspace

import com.promenar.infinity.domain.storage.ExternalFolderEntry

/**
 * 用户在资源管理器中对会话当前目录工作区（内置文件系统或设备文件系统）发起的文件操作。
 * 路径与目录浏览一致，以工作区根为 `/`；不经 Agent 审批，但与 Agent 写入共用同一套路径约束。
 */
interface DirectoryWorkspaceFiles {
    suspend fun createDirectory(sessionId: String, parent: String, name: String): ExternalFolderEntry

    /** 同目录改名；目标已存在时以 [DirectoryFileOpCode.NAME_EXISTS] 失败。 */
    suspend fun rename(sessionId: String, path: String, newName: String): ExternalFolderEntry

    /** 逐项移动到 [targetDirectory]；同名时该项失败，不覆盖。 */
    suspend fun move(sessionId: String, paths: List<String>, targetDirectory: String): DirectoryBatchResult

    /** 逐项复制（目录递归）；同名时自动改为“名称 (2)”等不冲突的名称。 */
    suspend fun copy(sessionId: String, paths: List<String>, targetDirectory: String): DirectoryBatchResult

    /** 逐项永久删除，保留根目录和保留路径保护。 */
    suspend fun delete(sessionId: String, paths: List<String>): DirectoryBatchResult
}

data class DirectoryBatchResult(
    val succeeded: List<String>,
    val failed: Map<String, DirectoryFileOpCode>,
)

enum class DirectoryFileOpCode {
    NAME_INVALID,
    NAME_EXISTS,
    NOT_FOUND,
    TARGET_INSIDE_SOURCE,
    READ_ONLY,
    WORKSPACE_UNAVAILABLE,
    SESSION_BUSY,
    TOO_LARGE,
    IO,
}

class DirectoryFileOpException(val code: DirectoryFileOpCode, cause: Throwable? = null) :
    Exception(code.name, cause)

/** 会话专用空间的现有文件规模；删除会话前据此决定是否询问保留文件。 */
data class PrivateWorkspaceSummary(val fileCount: Int, val totalBytes: Long)
