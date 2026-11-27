package com.promenar.infinity.data.ssh

import java.io.InputStream

internal class SftpRuntimeMountTransport(
    private val runtime: SshRuntime,
    private val owner: String,
    private val host: String,
    private val snapshot: SshHost? = null,
) : SftpMountTransport {
    private suspend fun <T> bound(action: suspend SshRuntime.() -> T): T =
        runtime.withHostSnapshot(snapshot) { runtime.action() }

    override suspend fun stat(path: String) = bound { sftpMountStat(owner, host, path) }
    override suspend fun list(path: String, limit: Int) = bound { sftpMountList(owner, host, path, limit) }
    override suspend fun mkdir(path: String) = bound { sftpMountMkdir(owner, host, path) }
    override suspend fun renameNoReplace(source: String, target: String) = bound { sftpMountRenameNoReplace(owner, host, source, target) }
    override suspend fun removeFile(path: String) = bound { sftpMountRemoveFile(owner, host, path) }
    override suspend fun removeEmptyDir(path: String) = bound { sftpMountRemoveEmptyDir(owner, host, path) }
    override suspend fun <T> read(path: String, maxBytes: Long, offset: Long, consume: suspend (InputStream) -> T): T =
        bound { sftpMountReadStream(owner, host, path, maxBytes, offset, consume) }
    override suspend fun createTemporary(path: String) = bound { sftpMountCreateTemporary(owner, host, path) }
    override suspend fun append(path: String, offset: Long, content: ByteArray) = bound { sftpMountAppend(owner, host, path, offset, content) }
    override suspend fun sha256(path: String) = bound { sftpRemoteSha256(owner, host, path) }
    override suspend fun publish(temporary: String, target: String, expectedSha256: String) =
        bound { sftpMountPublish(owner, host, temporary, target, expectedSha256) }

}
