package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderBackend
import java.security.MessageDigest

internal fun externalSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

object ExternalTreeTransfer {
    suspend fun move(source: ExternalFolderBackend, sourcePath: String, target: ExternalFolderBackend, targetPath: String, expectedSha256: String? = null) {
        ExternalStreamingMove.move(source, sourcePath, target, targetPath, expectedSha256)
    }
}
