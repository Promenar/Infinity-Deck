package com.promenar.infinity.domain.storage

import com.promenar.infinity.data.ssh.SshPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val SFTP_REMOTE_SOURCE_TYPE = "sftp_remote"

data class SftpRemoteMountSpec(
    val hostAlias: String,
    val rootPath: String,
    val libraryMountId: String? = null,
) {
    fun normalized(): SftpRemoteMountSpec {
        val alias = runCatching { SshPolicy.alias(hostAlias) }.getOrElse {
            throw ExternalFolderException("INVALID_HOST_ALIAS", "SSH 主机别名无效")
        }
        if (libraryMountId != null && !mountId.matches(libraryMountId)) invalid()
        return copy(hostAlias = alias, rootPath = canonicalPath(rootPath))
    }

    companion object {
        private val mountId = Regex("[A-Za-z0-9._:-]{1,128}")
        private object MountSpecDecoder : DeserializationStrategy<SftpRemoteMountSpec> {
            override val descriptor = buildClassSerialDescriptor("SftpRemoteMountSpec") {
                element<JsonElement>("v")
                element<JsonElement>("host_alias")
                element<JsonElement>("root_path")
                element<JsonElement>("library_mount_id", isOptional = true)
            }

            override fun deserialize(decoder: Decoder): SftpRemoteMountSpec = decoder.decodeStructure(descriptor) {
                val fields = mutableMapOf<Int, JsonElement>()
                while (true) {
                    val index = decodeElementIndex(descriptor)
                    if (index == CompositeDecoder.DECODE_DONE) break
                    if (index !in 0..3 || index in fields) throw SerializationException("挂载字段无效或重复")
                    fields[index] = decodeSerializableElement(descriptor, index, JsonElement.serializer())
                }
                fun string(index: Int): String =
                    (fields[index] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
                if (fields[0] != JsonPrimitive(1)) invalid()
                SftpRemoteMountSpec(string(1), string(2), if (3 in fields) string(3) else null).normalized()
            }
        }

        fun canonicalPath(path: String): String {
            if (!path.startsWith('/') || path.any { it == '\\' || it.isISOControl() } ||
                path.codePoints().anyMatch { it in 0xd800..0xdfff }
            ) invalid()
            if (path == "/") return path
            val canonical = path.trimEnd('/')
            if (canonical.isEmpty() || canonical.drop(1).split('/').any { it.isEmpty() || it == "." || it == ".." }) invalid()
            return canonical
        }

        fun encode(spec: SftpRemoteMountSpec): String = spec.normalized().let {
            buildJsonObject {
                put("v", 1)
                put("host_alias", it.hostAlias)
                put("root_path", it.rootPath)
                it.libraryMountId?.let { id -> put("library_mount_id", id) }
            }.toString()
        }

        fun decode(raw: String): SftpRemoteMountSpec? {
            if (raw.length > 32_768) return null
            return runCatching { Json.decodeFromString(MountSpecDecoder, raw) }.getOrNull()
        }

        private fun invalid(): Nothing = throw ExternalFolderException("INVALID_PATH", "SFTP 绝对路径或挂载引用无效")
    }
}

data class SftpRemoteProbeResult(
    val reachable: Boolean,
    val latencyMs: Long,
    val writable: Boolean,
    val supportsHardlink: Boolean,
    val checksumTool: String?,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

data class SftpRemoteDirEntry(val name: String, val fullPath: String, val isDirectory: Boolean)
