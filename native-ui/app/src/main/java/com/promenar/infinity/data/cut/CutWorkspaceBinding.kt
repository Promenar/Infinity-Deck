package com.promenar.infinity.data.cut

import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** 同一次版本测量或导出共享命名空间身份，目录切换后停止访问。 */
data class CutWorkspaceBinding(
    val context: CoroutineContext = EmptyCoroutineContext,
    val verify: suspend () -> Unit = {},
)

internal class CutWorkspaceOperation(val binding: CutWorkspaceBinding) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CutWorkspaceOperation>
}

internal class GuardedCutBackend(private val backend: ExternalFolderBackend, private val verify: suspend () -> Unit) : ExternalFolderBackend by backend {
    override suspend fun stat(path: String) = checked { backend.stat(path) }
    override suspend fun list(path: String) = checked { backend.list(path) }
    override suspend fun read(path: String, maxBytes: Long) = checked { backend.read(path, maxBytes) }
    override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (InputStream) -> T): T = checked { backend.readStream(path, maxBytes, consume) }
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) = checked { backend.write(path, content, expectedSha256, createOnly) }
    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (OutputStream) -> Unit) = checked {
        backend.writeStream(path, expectedSize, createOnly) { output -> verify(); produce(output); verify() }
    }
    override suspend fun createDirectory(path: String) = checked { backend.createDirectory(path) }
    override suspend fun delete(path: String, expectedSha256: String?) = checked { backend.delete(path, expectedSha256) }
    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) = checked { backend.deleteForTransfer(path, expected, expectedSha256) }
    private suspend fun <T> checked(block: suspend () -> T): T { verify(); return block() }
}
