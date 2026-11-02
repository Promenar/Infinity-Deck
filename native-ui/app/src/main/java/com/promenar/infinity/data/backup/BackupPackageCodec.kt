package com.promenar.infinity.data.backup

import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

interface BackupPackageCodec {
    fun encode(snapshot: BackupSnapshot, options: BackupOptions): ByteArray
    fun decode(bytes: ByteArray, password: CharArray?): ValidatedBackup
}

class DefaultBackupPackageCodec private constructor(
    private val crypto: BackupCrypto,
    private val limits: BackupLimits,
    private val temporaryBytesObserver: ((String, ByteArray) -> Unit)?,
) : BackupPackageCodec {
    constructor() : this(BackupCrypto(), BackupLimits(), null)

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val activeWipeSession = ThreadLocal<WipeSession?>()

    override fun encode(snapshot: BackupSnapshot, options: BackupOptions): ByteArray =
        withWipeSession(resultWiper = { it.fill(0) }) {
            encodeInternal(snapshot, options)
        }

    private fun encodeInternal(snapshot: BackupSnapshot, options: BackupOptions): ByteArray {
        val includeSecrets = options.includeSecrets
        val encryptPackage = options.encryptPackage || includeSecrets
        if (includeSecrets && BackupContent.SECRETS !in options.content) {
            throw BackupValidationException("包含密钥时必须选择密钥内容")
        }
        if (encryptPackage && (options.password == null || options.password.isEmpty())) {
            throw BackupValidationException("加密备份必须设置非空密码")
        }

        val ownedPassword = options.password?.copyOf()
        var entries: List<Pair<String, ByteArray>> = emptyList()
        try {
            entries = buildContentEntries(snapshot, options)
            validateEncodeEntries(entries, snapshot.skills?.metadata?.directories?.size ?: 0)
            val createZipPackage: (BackupKdfMetadata?) -> ByteArray = { kdf ->
                val manifest = BackupManifest(
                    formatVersion = FORMAT_VERSION,
                    databaseSchemaVersion = snapshot.databaseSchemaVersion,
                    appVersion = snapshot.appVersion,
                    createdAt = snapshot.createdAt,
                    entries = entries.map { (path, content) ->
                        BackupManifestEntry(path, content.size.toLong(), sha256(content))
                    },
                    encrypted = kdf != null,
                    containsSecrets = entries.any { it.first == SECRETS_PATH },
                    kdf = kdf,
                    skills = snapshot.skills?.metadata,
                )
                val manifestBytes = json.encodeToString(manifest).toByteArray()
                try {
                    if (manifestBytes.size.toLong() > limits.maxManifestBytes) {
                        throw BackupValidationException("manifest 超过允许大小", reason = BackupFailureReason.CAPACITY_EXCEEDED)
                    }
                    createZip(manifestBytes, entries)
                } finally {
                    wipe("encode-manifest", manifestBytes)
                }
            }
            if (!encryptPackage) return createZipPackage(null)
            return crypto.encryptPackage(ownedPassword!!) { kdf -> createZipPackage(kdf) }
        } finally {
            wipeAll(entries.map { (path, content) -> "encode:$path" to content })
            ownedPassword?.fill('\u0000')
        }
    }

    override fun decode(bytes: ByteArray, password: CharArray?): ValidatedBackup {
        if (bytes.size.toLong() > maxInputBytes()) throw BackupValidationException("备份包超过允许大小")
        return withWipeSession(resultWiper = ::wipeValidatedResult) {
            val inputSnapshot = bytes.copyOf()
            try {
                decodeInternal(inputSnapshot, password)
            } finally {
                wipe("decode-input-snapshot", inputSnapshot)
            }
        }
    }

    private fun decodeInternal(bytes: ByteArray, password: CharArray?): ValidatedBackup {
        val encrypted = crypto.isEncrypted(bytes)
        if (encrypted && (password == null || password.isEmpty())) {
            throw BackupValidationException("加密备份包需要密码")
        }

        val ownedPassword = password?.copyOf()
        var zipBytes: ByteArray? = null
        var envelopeMetadata: AuthenticatedEnvelopeKdf? = null
        return try {
            if (encrypted) {
                zipBytes = try {
                    crypto.decrypt(bytes, ownedPassword!!)
                } catch (error: GeneralSecurityException) {
                    throw BackupValidationException("备份密码错误或备份包认证失败")
                }
                notifyObserver("after-authentication", ByteArray(0))
                envelopeMetadata = parseAuthenticatedEnvelopeKdf(bytes)
            } else {
                zipBytes = bytes
                envelopeMetadata = null
            }
            readValidatedZip(zipBytes!!, envelopeMetadata)
        } finally {
            ownedPassword?.fill('\u0000')
            envelopeMetadata?.salt?.fill(0)
            if (encrypted) zipBytes?.let { wipe("decoded-zip", it) }
        }
    }

    private fun buildContentEntries(
        snapshot: BackupSnapshot,
        options: BackupOptions,
    ): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        try {
            if (BackupContent.DATABASE in options.content) result += DATABASE_PATH to snapshot.database.copyOf()
            if (BackupContent.PREFERENCES in options.content) result += PREFERENCES_PATH to snapshot.preferences.copyOf()
            if (BackupContent.FILES in options.content) {
                snapshot.files.toSortedMap().forEach { (id, content) ->
                    requireSafeFileId(id)
                    result += "$FILES_PREFIX$id" to content.copyOf()
                }
                AvatarBackupFiles.validateSizes(snapshot.avatars.mapValues { it.value.size.toLong() })
                snapshot.avatars.toSortedMap().forEach { (id, bytes) ->
                    result += "${AvatarBackupFiles.PREFIX}$id" to bytes.copyOf()
                }
            }
            snapshot.skills?.let { skills ->
                SkillBackupPaths.validate(skills.metadata, skills.files.keys, skills.files.values.sumOf { it.size.toLong() })
                skills.files.toSortedMap().forEach { (path, bytes) ->
                    result += "${SkillBackupPaths.PREFIX}$path" to bytes.copyOf()
                }
                if (result.size + skills.metadata.directories.size > limits.maxEntries) {
                    throw BackupValidationException("技能分区与备份内容项总数超过上限", reason = BackupFailureReason.CAPACITY_EXCEEDED)
                }
            }
            if (options.includeSecrets && BackupContent.SECRETS in options.content) {
                val secrets = snapshot.secrets
                    .filterKeys { it != SecretCatalog.automaticBackupPassword }
                    .toSortedMap(compareBy(SecretId::value))
                    .map { (id, value) -> SecretBackupEntry(id.value, Base64.getEncoder().encodeToString(value)) }
                result += SECRETS_PATH to json.encodeToString(secrets).toByteArray()
            }
            return result
        } catch (error: RuntimeException) {
            wipeAll(result.map { (path, content) -> "invalid-encode:$path" to content })
            throw error
        }
    }

    private fun validateEncodeEntries(entries: List<Pair<String, ByteArray>>, directoryCount: Int) {
        if (entries.size + directoryCount > limits.maxEntries) throw BackupValidationException("备份内容项过多", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        var contentTotal = 0L
        entries.forEach { (path, content) ->
            validateArchivePath(path)
            validateContentPath(path)
            if (content.size.toLong() > limits.maxEntryBytes) {
                throw BackupValidationException("备份内容项超过允许大小", reason = BackupFailureReason.CAPACITY_EXCEEDED)
            }
            contentTotal = checkedTotal(contentTotal, content.size.toLong())
        }
    }

    private fun createZip(manifest: ByteArray, entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                writeZipEntry(zip, MANIFEST_PATH, manifest)
                entries.forEach { (path, content) -> writeZipEntry(zip, path, content) }
            }
        }.toByteArray()

    private fun writeZipEntry(zip: ZipOutputStream, path: String, content: ByteArray) {
        val entry = ZipEntry(path).apply { time = FIXED_ZIP_TIME }
        zip.putNextEntry(entry)
        zip.write(content)
        zip.closeEntry()
    }

    private fun readValidatedZip(
        zipBytes: ByteArray,
        envelopeMetadata: AuthenticatedEnvelopeKdf?,
    ): ValidatedBackup {
        val contents = linkedMapOf<String, ByteArray>()
        try {
            val centralPaths = BackupZipEntryTypes.rejectLinks(zipBytes)
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                val first = zip.nextEntry ?: throw BackupValidationException("空备份包")
                BackupZipEntryTypes.rejectExtraLinks(first.extra)
                if (first.isDirectory || first.name != MANIFEST_PATH) {
                    throw BackupValidationException("manifest.json 必须是第一项")
                }
                val manifestBytes = readLimited(zip, limits.maxManifestBytes, MANIFEST_PATH)
                val manifest = try {
                    parseAndValidateManifest(manifestBytes, envelopeMetadata)
                } finally {
                    wipe(MANIFEST_PATH, manifestBytes)
                }
                zip.closeEntry()
                val declared = manifest.entries.associateBy { it.path }
                val seen = mutableSetOf(MANIFEST_PATH)
                var contentTotal = 0L

                while (true) {
                    val entry = zip.nextEntry ?: break
                    BackupZipEntryTypes.rejectExtraLinks(entry.extra)
                    if (entry.isDirectory) throw BackupValidationException("备份包不允许目录项")
                    validateArchivePath(entry.name)
                    if (!seen.add(entry.name)) throw BackupValidationException("备份包包含重复路径")
                    validateContentPath(entry.name)
                    val metadata = declared[entry.name]
                        ?: throw BackupValidationException("备份包包含 manifest 未声明的 entry")
                    if (seen.size - 1 > limits.maxEntries) throw BackupValidationException("备份包内容项过多")

                    val remaining = limits.maxTotalBytes - contentTotal
                    if (remaining < 0 || metadata.sizeBytes > remaining) {
                        throw BackupValidationException("备份包内容超过允许总大小")
                    }
                    val content = readLimited(zip, minOf(metadata.sizeBytes, limits.maxEntryBytes), entry.name)
                    if (content.size.toLong() != metadata.sizeBytes || sha256(content) != metadata.sha256) {
                        wipe("invalid:${entry.name}", content)
                        throw BackupValidationException("备份内容完整性校验失败")
                    }
                    contents[entry.name] = content
                    contentTotal += content.size
                    zip.closeEntry()
                }
                if ((seen - MANIFEST_PATH) != declared.keys) {
                    throw BackupValidationException("备份包缺少 manifest 声明的 entry")
                }
                if (seen != centralPaths) throw BackupValidationException("ZIP 本地条目与中央目录不一一对应")

                val secrets = parseSecrets(contents[SECRETS_PATH])
                return ValidatedBackup(
                    manifest = manifest,
                    database = contents[DATABASE_PATH]?.copyOf() ?: ByteArray(0),
                    preferences = contents[PREFERENCES_PATH]?.copyOf() ?: ByteArray(0),
                    files = contents.filterKeys { it.startsWith(FILES_PREFIX) }
                        .mapKeys { (path, _) -> path.removePrefix(FILES_PREFIX) }
                        .mapValues { (_, content) -> content.copyOf() },
                    secrets = secrets,
                    avatars = contents.filterKeys { it.startsWith(AvatarBackupFiles.PREFIX) }
                        .mapKeys { (path, _) -> path.removePrefix(AvatarBackupFiles.PREFIX) }
                        .mapValues { (_, bytes) -> bytes.copyOf() },
                    skills = manifest.skills?.let { metadata ->
                        SkillBackupPartition(
                            contents.filterKeys { it.startsWith(SkillBackupPaths.PREFIX) }
                                .mapKeys { (path, _) -> path.removePrefix(SkillBackupPaths.PREFIX) }
                                .mapValues { (_, bytes) -> bytes.copyOf() },
                            metadata,
                        )
                    },
                )
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: ZipException) {
            throw BackupValidationException("无效的 ZIP 备份包")
        } catch (error: IOException) {
            throw BackupValidationException("无法读取 ZIP 备份包")
        } finally {
            wipeAll(contents.map { (path, content) -> "decode:$path" to content })
        }
    }

    private fun parseAndValidateManifest(
        bytes: ByteArray,
        envelopeMetadata: AuthenticatedEnvelopeKdf?,
    ): BackupManifest {
        val manifest = try {
            json.decodeFromString<BackupManifest>(bytes.toString(Charsets.UTF_8))
        } catch (error: SerializationException) {
            throw BackupValidationException("备份 manifest 无效")
        } catch (error: IllegalArgumentException) {
            throw BackupValidationException("备份 manifest 无效")
        }
        if (manifest.formatVersion !in 1..FORMAT_VERSION) throw BackupValidationException("不支持的备份格式版本")
        if (manifest.formatVersion == 1 && manifest.skills != null) {
            throw BackupValidationException("旧格式不支持技能分区")
        }
        if (manifest.encrypted != (envelopeMetadata != null)) throw BackupValidationException("备份加密元数据不一致")
        validateKdf(manifest.kdf, envelopeMetadata)
        if (manifest.entries.size > limits.maxEntries) throw BackupValidationException("manifest 内容项过多")

        val paths = mutableSetOf<String>()
        var contentTotal = 0L
        manifest.entries.forEach { entry ->
            validateArchivePath(entry.path)
            validateContentPath(entry.path)
            if (!paths.add(entry.path) || entry.path == MANIFEST_PATH) {
                throw BackupValidationException("manifest 包含重复或保留路径")
            }
            if (entry.sizeBytes < 0 || entry.sizeBytes > limits.maxEntryBytes) {
                throw BackupValidationException("manifest 声明大小无效")
            }
            contentTotal = checkedTotal(contentTotal, entry.sizeBytes)
            if (!SHA256_PATTERN.matches(entry.sha256)) throw BackupValidationException("manifest 摘要无效")
        }
        val declaresSecrets = SECRETS_PATH in paths
        val avatarEntries = manifest.entries.filter { it.path.startsWith(AvatarBackupFiles.PREFIX) }
        if (manifest.formatVersion < 3 && avatarEntries.isNotEmpty()) {
            throw BackupValidationException("旧格式不支持头像分区")
        }
        AvatarBackupFiles.validateSizes(avatarEntries.associate { it.path.removePrefix(AvatarBackupFiles.PREFIX) to it.sizeBytes })
        if (manifest.containsSecrets != declaresSecrets || declaresSecrets && !manifest.encrypted) {
            throw BackupValidationException("备份密钥元数据不一致")
        }
        val skillEntries = manifest.entries.filter { it.path.startsWith(SkillBackupPaths.PREFIX) }
        val skills = manifest.skills
        if (skills == null && skillEntries.isNotEmpty()) throw BackupValidationException("技能分区缺少清单元数据")
        if (skills != null) {
            if (manifest.entries.size + skills.directories.size > limits.maxEntries) {
                throw BackupValidationException("技能分区与备份内容项总数超过上限", reason = BackupFailureReason.CAPACITY_EXCEEDED)
            }
            SkillBackupPaths.validate(
                skills,
                skillEntries.map { it.path.removePrefix(SkillBackupPaths.PREFIX) },
                skillEntries.sumOf { it.sizeBytes },
            )
        }
        return manifest
    }

    private fun validateKdf(
        kdf: BackupKdfMetadata?,
        envelope: AuthenticatedEnvelopeKdf?,
    ) {
        if (envelope == null) {
            if (kdf != null) throw BackupValidationException("未加密备份不应包含 KDF 元数据")
            return
        }
        val manifestKdf = kdf ?: throw BackupValidationException("加密备份缺少 KDF 元数据")
        val manifestSalt = try {
            Base64.getDecoder().decode(manifestKdf.saltBase64)
        } catch (error: IllegalArgumentException) {
            throw BackupValidationException("无效的 KDF salt")
        }
        try {
            if (manifestKdf.algorithm != envelope.algorithm ||
                manifestKdf.iterations != envelope.iterations ||
                manifestKdf.keySizeBits != envelope.keySizeBits ||
                !manifestSalt.contentEquals(envelope.salt)
            ) throw BackupValidationException("manifest KDF 与加密 envelope 不一致")
        } finally {
            manifestSalt.fill(0)
        }
    }

    private fun parseAuthenticatedEnvelopeKdf(envelope: ByteArray): AuthenticatedEnvelopeKdf {
        try {
            val buffer = ByteBuffer.wrap(envelope)
            buffer.position(4)
            val version = buffer.get().toInt() and 0xff
            val algorithmSize = buffer.short.toInt() and 0xffff
            val algorithm = ByteArray(algorithmSize).also(buffer::get).toString(StandardCharsets.UTF_8)
            val iterations = buffer.int
            val keySizeBits = buffer.int
            val salt = ByteArray(buffer.get().toInt() and 0xff).also(buffer::get)
            val ivSize = buffer.get().toInt() and 0xff
            buffer.position(buffer.position() + ivSize)
            if (version != ENVELOPE_VERSION || algorithm != BackupCrypto.KDF_ALGORITHM ||
                iterations != BackupCrypto.PBKDF2_ITERATIONS || keySizeBits != BackupCrypto.KEY_SIZE_BITS ||
                salt.size != BackupCrypto.SALT_SIZE_BYTES || ivSize != BackupCrypto.IV_SIZE_BYTES
            ) throw BackupValidationException("已认证 envelope KDF 无效")
            return AuthenticatedEnvelopeKdf(algorithm, iterations, keySizeBits, salt)
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: RuntimeException) {
            throw BackupValidationException("已认证 envelope KDF 无效")
        }
    }

    private fun parseSecrets(bytes: ByteArray?): Map<SecretId, ByteArray> {
        if (bytes == null) return emptyMap()
        val entries = try {
            json.decodeFromString<List<SecretBackupEntry>>(bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw BackupValidationException("备份密钥内容无效")
        }
        val result = linkedMapOf<SecretId, ByteArray>()
        try {
            entries.forEach { entry ->
                val id = SecretId(entry.id)
                if (id == SecretCatalog.automaticBackupPassword || result.containsKey(id)) {
                    throw BackupValidationException("备份密钥目录无效")
                }
                result[id] = try {
                    Base64.getDecoder().decode(entry.valueBase64)
                } catch (error: IllegalArgumentException) {
                    throw BackupValidationException("备份密钥内容无效")
                }
            }
            return result
        } catch (error: RuntimeException) {
            wipeAll(result.map { (id, value) -> "invalid-secret:${id.value}" to value })
            throw error
        }
    }

    private fun readLimited(input: ZipInputStream, limit: Long, label: String): ByteArray {
        val output = WipingByteArrayOutputStream(label)
        val buffer = ByteArray(8192)
        return try {
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw BackupValidationException("备份内容项解压后超过允许大小")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } finally {
            buffer.fill(0)
            output.wipe()
        }
    }

    private fun checkedTotal(current: Long, addition: Long): Long {
        if (addition > limits.maxTotalBytes - current) {
            throw BackupValidationException("备份内容超过允许总大小", reason = BackupFailureReason.CAPACITY_EXCEEDED)
        }
        return current + addition
    }

    private fun validateArchivePath(path: String) {
        if (path.isBlank() || path.startsWith('/') || '\\' in path || ':' in path ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) throw BackupValidationException("备份包包含不安全路径")
    }

    private fun validateContentPath(path: String) {
        if (path == DATABASE_PATH || path == PREFERENCES_PATH || path == SECRETS_PATH) return
        if (path.startsWith(FILES_PREFIX)) {
            requireSafeFileId(path.removePrefix(FILES_PREFIX))
            return
        }
        if (path.startsWith(SkillBackupPaths.PREFIX)) {
            SkillBackupPaths.parts(path.removePrefix(SkillBackupPaths.PREFIX))
            return
        }
        if (path.startsWith(AvatarBackupFiles.PREFIX)) {
            AvatarBackupFiles.requireId(path.removePrefix(AvatarBackupFiles.PREFIX))
            return
        }
        throw BackupValidationException("备份包包含未知内容路径")
    }

    private fun requireSafeFileId(id: String) {
        if (id.isBlank() || '/' in id || '\\' in id || id == "." || id == ".." || ':' in id) {
            throw BackupValidationException("文件标识不是安全的相对路径")
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun maxInputBytes(): Long {
        // ZIP/envelope 本身也会在解码期间常驻；只给固定协议头与压缩元数据留少量余量，
        // 避免“压缩输入 + 解密副本 + 解压内容”绕过统一 Android 物化预算。
        val overhead = limits.maxManifestBytes.coerceAtMost(Long.MAX_VALUE - 64L * 1024) + 64L * 1024
        return limits.maxTotalBytes.coerceAtMost(Long.MAX_VALUE - overhead) + overhead
    }

    private fun wipe(label: String, bytes: ByteArray) {
        bytes.fill(0)
        notifyObserver(label, bytes)
    }

    private fun wipeAll(values: Collection<Pair<String, ByteArray>>) {
        values.forEach { (_, bytes) -> bytes.fill(0) }
        values.forEach { (label, bytes) -> notifyObserver(label, bytes) }
    }

    private inner class WipingByteArrayOutputStream(private val label: String) : ByteArrayOutputStream() {
        fun wipe() {
            buf.fill(0)
            notifyObserver("partial:$label", buf)
            reset()
        }
    }

    private fun notifyObserver(label: String, bytes: ByteArray) {
        activeWipeSession.get()?.notify(label, bytes)
            ?: error("清零观察只能在 codec 操作作用域内执行")
    }

    private inline fun <T> withWipeSession(
        crossinline resultWiper: (T) -> Unit,
        block: () -> T,
    ): T {
        check(activeWipeSession.get() == null) { "不支持同线程嵌套 codec 操作" }
        val session = WipeSession(temporaryBytesObserver)
        activeWipeSession.set(session)
        try {
            val result = block()
            val deferred = session.deferredFailure
            if (deferred != null) {
                resultWiper(result)
                throw deferred
            }
            return result
        } catch (error: Throwable) {
            session.deferredFailure?.takeIf { it !== error }?.let(error::addSuppressed)
            throw error
        } finally {
            activeWipeSession.remove()
        }
    }

    private fun wipeValidatedResult(result: ValidatedBackup) {
        result.close()
    }

    private data class BackupLimits(
        val maxTotalBytes: Long = BackupPackageLimits.MAX_IN_MEMORY_BYTES,
        val maxEntryBytes: Long = BackupPackageLimits.MAX_IN_MEMORY_BYTES,
        val maxEntries: Int = BackupPackageLimits.MAX_ENTRIES,
        val maxManifestBytes: Long = BackupPackageLimits.MAX_MANIFEST_BYTES,
    ) {
        init {
            require(maxTotalBytes >= 0 && maxEntryBytes >= 0 && maxEntries >= 0 && maxManifestBytes > 0)
            require(maxManifestBytes <= Int.MAX_VALUE)
        }
    }

    private data class AuthenticatedEnvelopeKdf(
        val algorithm: String,
        val iterations: Int,
        val keySizeBits: Int,
        val salt: ByteArray,
    )

    private class WipeSession(
        private val observer: ((String, ByteArray) -> Unit)?,
    ) {
        var deferredFailure: Throwable? = null
            private set

        fun notify(label: String, bytes: ByteArray) {
            try {
                observer?.invoke(label, bytes)
            } catch (error: CancellationException) {
                record(error)
            } catch (error: Error) {
                record(error)
            } catch (_: Exception) {
                // 普通观察异常不进入安全控制流。
            }
        }

        private fun record(error: Throwable) {
            val current = deferredFailure
            if (current == null) deferredFailure = error else if (current !== error) current.addSuppressed(error)
        }
    }

    companion object {
        const val FORMAT_VERSION = 3
        private const val MANIFEST_PATH = "manifest.json"
        private const val DATABASE_PATH = "database.json"
        private const val PREFERENCES_PATH = "preferences.json"
        private const val SECRETS_PATH = "secrets.json"
        private const val FILES_PREFIX = "files/"
        private const val FIXED_ZIP_TIME = 0L
        private const val ENVELOPE_VERSION = 1
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
