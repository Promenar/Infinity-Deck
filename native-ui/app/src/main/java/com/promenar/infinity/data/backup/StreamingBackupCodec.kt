package com.promenar.infinity.data.backup

import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** v3 内容逐项处理；清单最后写入，解包完成前不把暂存树交给提交器。 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal class StreamingBackupCodec(private val crypto: BackupCrypto) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun encode(snapshot: BackupSnapshot, options: BackupOptions, output: OutputStream): BackupResultSummary {
        val layers = snapshot.layers ?: throw BackupValidationException("缺少层清单")
        if (BackupLayer.CORE !in layers || (BackupLayer.SECRETS in layers) != options.includeSecrets) {
            throw BackupValidationException("备份层选择与密钥选项不一致")
        }
        val encrypted = options.encryptPackage || options.includeSecrets
        if (encrypted && (options.password == null || options.password.isEmpty())) throw BackupValidationException("加密备份需要密码")
        var summary = BackupResultSummary()
        val write: (OutputStream, BackupKdfMetadata?) -> Unit = { target, kdf ->
            val entries = mutableListOf<BackupManifestEntry>()
            val paths = mutableSetOf<String>()
            var total = 0L
            val zip = ZipOutputStream(object : java.io.FilterOutputStream(target) { override fun close() { flush() } })
            zip.use {
                snapshot.streamEntries.forEach { source ->
                    validatePath(source.path)
                    if (!paths.add(source.path) || source.layer !in layers ||
                        paths.size + (snapshot.skills?.metadata?.directories?.size ?: 0) > BackupPackageLimits.MAX_ENTRIES
                    ) throw BackupValidationException("备份内容路径或层归属无效")
                    val size = source.blob.size
                    if (size < 0 || size > BackupPackageLimits.MAX_ENTRY_BYTES || size > BackupPackageLimits.MAX_TOTAL_BYTES - total) capacity()
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.putNextEntry(ZipEntry(source.path).apply { time = 0 })
                    val copied = source.blob.open().use { input -> BackupStreams.copy(input, zip, size, digest) }
                    if (copied != size) throw BackupValidationException("暂存内容大小发生变化")
                    zip.closeEntry()
                    total += size
                    entries += BackupManifestEntry(source.path, size, BackupStreams.hex(digest.digest()), source.layer)
                }
                val stats = layers.associateWith { layer ->
                    val subset = entries.filter { it.layer == layer }
                    BackupLayerStats(subset.size, subset.sumOf { it.sizeBytes })
                }
                val manifest = BackupManifest(3, snapshot.databaseSchemaVersion, snapshot.appVersion, snapshot.createdAt,
                    entries, encrypted, options.includeSecrets, kdf, snapshot.skills?.metadata, stats, snapshot.skipped, snapshot.embedding)
                val bytes = json.encodeToString(manifest).toByteArray()
                try {
                    if (bytes.size > BackupPackageLimits.MAX_MANIFEST_BYTES) capacity()
                    zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0 }); zip.write(bytes); zip.closeEntry()
                } finally { bytes.fill(0) }
                summary = BackupResultSummary(stats, snapshot.skipped, skills = snapshot.skills?.metadata?.summary)
            }
        }
        if (encrypted) crypto.encryptStream(output, options.password!!) { target, kdf -> write(target, kdf) }
        else write(output, null)
        return summary
    }

    fun decode(input: InputStream, password: CharArray?, directory: Path, legacy: (ByteArray, CharArray?) -> ValidatedBackup): ValidatedBackup {
        val root = Files.createTempDirectory(directory, "decode-")
        try {
            val wire = root.resolve("input.iwb")
            Files.newOutputStream(wire).use { BackupStreams.copy(input, it, BackupStreams.MAX_WIRE_BYTES) }
            if (Files.size(wire) == 0L) throw BackupValidationException("备份包为空")
            val prefix = ByteArray(4)
            Files.newInputStream(wire).use { java.io.DataInputStream(it).readFully(prefix) }
            if (crypto.isEncrypted(prefix)) return legacyFile(wire, password, legacy).also { BackupStreams.deleteTree(root) }
            var kdf: BackupKdfMetadata? = null
            val zipPath = if (crypto.isStreamEncrypted(prefix)) {
                if ((password == null || password.isEmpty())) throw BackupValidationException("加密备份需要密码")
                val plain = root.resolve("content.zip")
                BackupStreams.requireSpace(root, Files.size(wire))
                try {
                    Files.newInputStream(wire).use { encrypted -> Files.newOutputStream(plain).use { out ->
                        kdf = crypto.decryptStream(encrypted, out, password!!)
                    } }
                } catch (error: GeneralSecurityException) { throw BackupValidationException("密码错误或备份认证失败", error) }
                plain
            } else wire
            val central = BackupZipEntryTypes.rejectLinks(zipPath)
            ZipFile(zipPath.toFile()).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json") ?: throw BackupValidationException("备份缺少清单")
                if (manifestEntry.size !in 1..BackupPackageLimits.MAX_MANIFEST_BYTES) throw BackupValidationException("清单大小无效")
                val manifestBytes = readSmall(zip.getInputStream(manifestEntry), BackupPackageLimits.MAX_MANIFEST_BYTES)
                val manifest = try { json.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8)) }
                    finally { manifestBytes.fill(0) }
                if (manifest.layers == null) {
                    if (kdf != null) throw BackupValidationException("分块信封缺少层清单")
                    return legacyFile(wire, password, legacy).also { BackupStreams.deleteTree(root) }
                }
                validateManifest(manifest, kdf, central)
                BackupStreams.requireSpace(root, manifest.entries.sumOf { it.sizeBytes } + 1024 * 1024)
                val blobs = linkedMapOf<String, BackupBlob>()
                manifest.entries.forEachIndexed { index, metadata ->
                    val entry = zip.getEntry(metadata.path) ?: throw BackupValidationException("清单声明的内容缺失")
                    BackupZipEntryTypes.rejectExtraLinks(entry.extra)
                    if (entry.isDirectory || entry.size != metadata.sizeBytes) throw BackupValidationException("备份内容大小不一致")
                    val path = root.resolve("entry-$index")
                    val digest = MessageDigest.getInstance("SHA-256")
                    val size = Files.newOutputStream(path).use { out -> zip.getInputStream(entry).use {
                        BackupStreams.copy(it, out, metadata.sizeBytes, digest)
                    } }
                    if (size != metadata.sizeBytes || BackupStreams.hex(digest.digest()) != metadata.sha256) throw BackupValidationException("备份内容摘要不一致")
                    blobs[metadata.path] = BackupBlob.File(path)
                }
                var secrets = emptyMap<SecretId, ByteArray>()
                var preferences = ByteArray(0)
                try {
                    if ("preferences.json" !in blobs) throw BackupValidationException("核心层缺少偏好")
                    blobs["secrets.json"]?.let { source ->
                        val result = linkedMapOf<SecretId, ByteArray>()
                        secrets = result
                        source.open().use { input ->
                            json.decodeFromStream<List<SecretBackupEntry>>(input).forEach { value ->
                                val id = SecretId(value.id)
                                if (id == SecretCatalog.automaticBackupPassword || id in result) throw BackupValidationException("密钥目录无效")
                                result[id] = Base64.getDecoder().decode(value.valueBase64)
                            }
                        }
                    }
                    Files.delete(wire)
                    if (zipPath != wire) Files.delete(zipPath)
                    return ValidatedBackup(manifest, preferences = preferences, secrets = secrets,
                        skills = manifest.skills?.let { SkillBackupPartition(emptyMap(), it, blobs.filterKeys { path -> path.startsWith("skills/") }.mapKeys { entry -> entry.key.removePrefix("skills/") }) }, blobs = blobs, ownedDirectory = root)
                } catch (error: Throwable) {
                    preferences.fill(0); secrets.values.forEach { it.fill(0) }; throw error
                }
            }
        } catch (error: Throwable) {
            BackupStreams.deleteTree(root)
            if (error is java.util.concurrent.CancellationException || error is BackupValidationException || error is Error) throw error
            throw BackupValidationException("备份格式无效或内容读取失败", error)
        }
    }

    private fun legacyFile(path: Path, password: CharArray?, legacy: (ByteArray, CharArray?) -> ValidatedBackup): ValidatedBackup {
        if (Files.size(path) > BackupPackageLimits.MAX_IN_MEMORY_BYTES) capacity()
        val bytes = Files.readAllBytes(path)
        try { return legacy(bytes, password) } finally { bytes.fill(0) }
    }
    private fun validateManifest(manifest: BackupManifest, kdf: BackupKdfMetadata?, central: Set<String>) {
        val layers = manifest.layers ?: throw BackupValidationException("缺少层清单")
        if (manifest.formatVersion != 3 || BackupLayer.CORE !in layers || manifest.encrypted != (kdf != null) || manifest.kdf != kdf ||
            manifest.containsSecrets != (BackupLayer.SECRETS in layers) || manifest.containsSecrets && kdf == null ||
            manifest.entries.size + (manifest.skills?.directories?.size ?: 0) > BackupPackageLimits.MAX_ENTRIES
        ) throw BackupValidationException("备份清单参数无效")
        var total = 0L
        val seen = mutableSetOf("manifest.json")
        manifest.entries.forEach { entry ->
            validatePath(entry.path)
            if (!seen.add(entry.path) || entry.layer !in layers || entry.sizeBytes < 0 || entry.sizeBytes > BackupPackageLimits.MAX_ENTRY_BYTES ||
                entry.sizeBytes > BackupPackageLimits.MAX_TOTAL_BYTES - total || !Regex("[0-9a-f]{64}").matches(entry.sha256)
            ) throw BackupValidationException("备份条目清单无效")
            total += entry.sizeBytes
            val expected = when {
                entry.path == "secrets.json" -> BackupLayer.SECRETS
                entry.path.startsWith("files/") -> entry.layer.takeIf { it == BackupLayer.KNOWLEDGE || it == BackupLayer.WORKSPACE_FILES }
                entry.path.startsWith("database/") -> BackupLayerPolicy.tableLayer(entry.path.split('/')[1], entry.layer)
                else -> BackupLayer.CORE
            }
            if (expected == null || expected != entry.layer) throw BackupValidationException("条目层归属无效")
        }
        if (seen != central || ("secrets.json" in seen) != manifest.containsSecrets) throw BackupValidationException("备份清单与ZIP不一致")
        layers.forEach { (layer, stats) ->
            val entries = manifest.entries.filter { it.layer == layer }
            if (stats != BackupLayerStats(entries.size, entries.sumOf { it.sizeBytes })) throw BackupValidationException("层统计不一致")
        }
        val skillEntries = manifest.entries.filter { it.path.startsWith("skills/") }
        if (skillEntries.isNotEmpty() && manifest.skills == null) throw BackupValidationException("技能元数据缺失")
        manifest.skills?.let { SkillBackupPaths.validate(it, skillEntries.map { entry -> entry.path.removePrefix("skills/") }, skillEntries.sumOf { entry -> entry.sizeBytes }) }
        AvatarBackupFiles.validateSizes(manifest.entries.filter { it.path.startsWith("avatars/") }.associate { it.path.removePrefix("avatars/") to it.sizeBytes })
    }
    private fun validatePath(path: String) {
        when {
            path == "preferences.json" || path == "secrets.json" -> Unit
            path.startsWith("skills/") -> SkillBackupPaths.parts(path.removePrefix("skills/"))
            path.startsWith("avatars/") -> AvatarBackupFiles.requireId(path.removePrefix("avatars/"))
            path.startsWith("files/") -> if (!Regex("files/[A-Za-z0-9._-]{1,128}").matches(path)) throw BackupValidationException("文件标识无效")
            Regex("database/[a-z_]+/[0-9]{6}\\.jsonl").matches(path) -> Unit
            else -> throw BackupValidationException("备份包含未知路径")
        }
    }
    private fun readSmall(input: InputStream, limit: Long): ByteArray = input.use { source ->
        val output = WipeableByteArrayOutputStream()
        try { BackupStreams.copy(source, output, limit); output.toByteArray() } finally { output.wipe() }
    }
    private fun capacity(): Nothing = throw BackupValidationException("备份超过容量上限", reason = BackupFailureReason.CAPACITY_EXCEEDED)
}
