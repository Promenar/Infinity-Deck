package com.promenar.infinity.domain.storage

import com.promenar.infinity.data.local.db.entity.FileEntry
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 一次工具调用固定可见挂载身份，防止同别名换源穿过多次仓库访问。 */
class ExternalMountOperationBindings(val mountIds: Map<String, String>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ExternalMountOperationBindings>
}

/** 工具只依赖虚拟命名空间和后端契约，不判断存储来源。 */
interface ExternalWorkspaceAccess : IExternalFolderManager {
    suspend fun visible(sessionId: String): List<ExternalMount>
    suspend fun resolveUuid(sessionId: String, uuid: String): String?
    suspend fun entry(sessionId: String, path: String): FileEntry?
    suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?, createOnly: Boolean): FileEntry
    suspend fun createDirectory(sessionId: String, path: String): FileEntry
    suspend fun delete(sessionId: String, path: String, expectedSha256: String?)
    suspend fun deleteSessionMounts(sessionId: String)
    fun backend(sessionId: String, mountIds: Map<String, String> = emptyMap()): ExternalFolderBackend

    /** 流式读取虚拟路径，语义同 [ExternalFolderBackend.readStream]；默认实现退回 [read]。 */
    suspend fun <T> readStream(sessionId: String, virtualPath: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T =
        consume(read(sessionId, virtualPath, maxBytes).inputStream())
    suspend fun version(sessionId: String, path: String, hash: String): ByteArray?

    /**
     * 会话当前为自选目录工作区时返回以 `/` 为根的工作区挂载（别名为空串），`/mnt/<别名>` 挂载照旧；
     * 私有工作区返回 null。工作区不可用时仍返回挂载并带 [ExternalMount.unavailableReason]，访问时拒绝而不回退。
     */
    suspend fun workspaceMount(sessionId: String): ExternalMount? = null
}
