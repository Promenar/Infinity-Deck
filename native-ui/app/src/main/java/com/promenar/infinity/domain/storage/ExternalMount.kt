package com.promenar.infinity.domain.storage

import kotlinx.coroutines.flow.Flow

/** 文件工具使用会话内的虚拟路径；命令访问能力由来源类型决定。 */
data class ExternalMount(
    val id: String,
    val alias: String,
    val sourceType: String,
    val sourceId: String,
    val sessionId: String,
    val createdAt: Long,
    val unavailableReason: String? = null,
    val readOnly: Boolean = false,
) {
    val commandAccessible: Boolean get() = sourceType == "real_path" && !readOnly
}

data class ExternalMountCommandAccess(
    val realPath: String?,
    val unavailableReason: String?,
) {
    val accessible: Boolean get() = realPath != null
}

/** 文件面板与工具共用挂载管理，所有涉及文件与数据库的方法自行切换到 IO。 */
interface IExternalFolderManager {
    fun observeMounts(sessionId: String): Flow<List<ExternalMount>>
    suspend fun addSaf(alias: String, sessionId: String, treeUri: String, grantFlags: Int): ExternalMount
    suspend fun addRealPath(alias: String, sessionId: String, source: String): ExternalMount
    suspend fun addKnowledgeBase(alias: String, sessionId: String, rootUuid: String): ExternalMount {
        throw ExternalFolderException("UNSUPPORTED", "该挂载管理器不支持知识库挂载")
    }
    suspend fun remove(mountId: String)
    suspend fun reauthorize(mountId: String, treeUri: String, grantFlags: Int)
    suspend fun refresh(sessionId: String)
    suspend fun list(sessionId: String, virtualPath: String): List<ExternalFolderEntry>
    suspend fun read(sessionId: String, virtualPath: String, maxBytes: Long = 1024L * 1024): ByteArray
    suspend fun importSnapshot(sessionId: String, workspaceRootUuid: String, virtualPath: String): String
    suspend fun snapshotChanged(sessionId: String, virtualPath: String): Boolean
}
