package com.promenar.infinity.data.skills

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 已安装技能：元数据、附带文件（相对路径，不含 SKILL.md）与启用状态。 */
data class InstalledAgentSkill(
    val metadata: AgentSkillMetadata,
    val files: List<String>,
    val enabled: Boolean,
)

class AgentSkillConflictException(val name: String) : IllegalStateException("技能 $name 已存在")

/**
 * 技能包的文件系统存储，位于应用私有目录。
 *
 * 安装先写入同级暂存目录并完整校验，再以目录重命名切换，失败不留下半个技能。
 * 读取接口同步且基于内存索引，供工具解析在生成协程中直接调用。
 */
class AgentSkillStore(
    private val rootDir: File,
    private val settings: SharedPreferences,
) {
    private val lock = Any()
    private val _skills = MutableStateFlow<List<InstalledAgentSkill>>(emptyList())
    @Volatile private var loaded = false

    val skills: StateFlow<List<InstalledAgentSkill>>
        get() {
            ensureLoaded()
            return _skills.asStateFlow()
        }

    fun list(): List<InstalledAgentSkill> {
        ensureLoaded()
        return _skills.value
    }

    /** 统一目录操作或 Agent 安装后同步旧设置页使用的索引。 */
    fun refresh() = synchronized(lock) { reload() }

    fun enabledSkills(): List<AgentSkillMetadata> = list().filter { it.enabled }.map { it.metadata }

    /** 按名称查找前先从磁盘重载：技能目录也会被 Agent 与技能目录视图直接修改。 */
    fun find(name: String): InstalledAgentSkill? =
        synchronized(lock) { reload(); _skills.value }.firstOrNull { it.metadata.name == name }

    fun setEnabled(name: String, enabled: Boolean) = synchronized(lock) {
        val current = enabledNames().toMutableSet()
        if (enabled) current += name else current -= name
        settings.edit { putStringSet(ENABLED_KEY, current) }
        reload()
    }

    /** 读取技能正文；技能不存在或 SKILL.md 已损坏时返回 null。 */
    fun readDocument(name: String): ParsedSkillDocument? {
        if (find(name) == null) return null
        val file = File(skillDir(name), SKILL_FILE)
        return runCatching { SkillMarkdownParser.parse(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** 读取技能附带的文本文件；路径越界、过大或非文本时抛出 [AgentSkillFormatException]。 */
    fun readFile(name: String, path: String): String {
        val skill = find(name) ?: throw AgentSkillFormatException("技能不存在：$name")
        val relative = normalizeRelativePath(path)
        if (relative != SKILL_FILE && relative !in skill.files) {
            throw AgentSkillFormatException("技能 $name 中没有文件：$relative")
        }
        val file = File(skillDir(name), relative)
        if (file.length() > MAX_READ_BYTES) {
            throw AgentSkillFormatException("文件超过 ${MAX_READ_BYTES / 1024} KiB 读取上限：$relative")
        }
        return decodeText(file.readBytes()) ?: throw AgentSkillFormatException("不是 UTF-8 文本文件：$relative")
    }

    fun installMarkdown(text: String, overwrite: Boolean = false): AgentSkillMetadata {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw AgentSkillFormatException("SKILL.md 超过 ${MAX_FILE_BYTES / 1024 / 1024} MiB")
        return install(overwrite, previousName = null) { staging -> File(staging, SKILL_FILE).writeBytes(bytes) }
    }

    /** 边解压边写入暂存目录并累计校验上限，不把整个压缩包读入内存。 */
    fun installZip(input: InputStream, overwrite: Boolean = false): AgentSkillMetadata =
        install(overwrite, previousName = null) { staging ->
            var count = 0
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val rawName = entry.name.replace('\\', '/')
                    if (rawName.startsWith("__MACOSX/") || rawName.substringAfterLast('/') == ".DS_Store") continue
                    val path = normalizeRelativePath(rawName)
                    if (count >= MAX_FILES) throw AgentSkillFormatException("技能包文件数超过 $MAX_FILES")
                    val out = File(staging, path)
                    if (out.exists()) throw AgentSkillFormatException("技能包包含重复路径：$path")
                    out.parentFile?.mkdirs()
                    val written = copyBounded(zip, out, MAX_FILE_BYTES.toLong())
                        ?: throw AgentSkillFormatException("文件超过 ${MAX_FILE_BYTES / 1024 / 1024} MiB：$path")
                    count++
                    total += written
                    if (total > MAX_TOTAL_BYTES) throw AgentSkillFormatException("技能包解压后超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MiB")
                }
            }
            hoistSingleTopLevelDirectory(staging)
        }

    /** 应用内新建或编辑；改名时保留原技能的附带文件。 */
    fun save(previousName: String?, metadata: AgentSkillMetadata, body: String): AgentSkillMetadata {
        val text = SkillMarkdownParser.render(metadata, body)
        val existing = previousName?.let { find(it) ?: throw AgentSkillFormatException("技能不存在：$it") }
        return install(overwrite = previousName == metadata.name, previousName = previousName) { staging ->
            // 附带文件逐个流式复制，改名或编辑大技能时不整体读入内存。
            existing?.files?.forEach { relative ->
                val out = File(staging, normalizeRelativePath(relative))
                out.parentFile?.mkdirs()
                File(skillDir(previousName!!), relative).inputStream().use { source -> out.outputStream().use(source::copyTo) }
            }
            File(staging, SKILL_FILE).writeBytes(text.toByteArray(Charsets.UTF_8))
        }
    }

    fun delete(name: String) = synchronized(lock) {
        val dir = skillDir(name)
        if (dir.exists()) {
            val trash = File(rootDir, ".trash-${UUID.randomUUID()}")
            if (!dir.renameTo(trash)) throw IllegalStateException("无法删除技能 $name")
            trash.deleteRecursively()
        }
        settings.edit { putStringSet(ENABLED_KEY, enabledNames() - name) }
        reload()
    }

    /** [populate] 把技能全部文件写入暂存目录；随后校验 SKILL.md 并以目录重命名切换。 */
    private fun install(
        overwrite: Boolean,
        previousName: String?,
        populate: (staging: File) -> Unit,
    ): AgentSkillMetadata = synchronized(lock) {
        rootDir.mkdirs()
        val staging = File(rootDir, ".staging-${UUID.randomUUID()}")
        val metadata: AgentSkillMetadata
        val renaming: Boolean
        try {
            staging.mkdirs()
            populate(staging)
            val skillFile = File(staging, SKILL_FILE)
            if (!skillFile.isFile) throw AgentSkillFormatException("技能包缺少 SKILL.md")
            if (skillFile.length() > MAX_FILE_BYTES) throw AgentSkillFormatException("SKILL.md 超过 ${MAX_FILE_BYTES / 1024 / 1024} MiB")
            val text = decodeText(skillFile.readBytes()) ?: throw AgentSkillFormatException("SKILL.md 不是 UTF-8 文本")
            metadata = SkillMarkdownParser.parse(text).metadata
            val target = skillDir(metadata.name)
            renaming = previousName != null && previousName != metadata.name
            if (target.exists() && !overwrite) throw AgentSkillConflictException(metadata.name)
            var replaced: File? = null
            if (target.exists()) {
                replaced = File(rootDir, ".trash-${UUID.randomUUID()}")
                if (!target.renameTo(replaced)) throw IllegalStateException("无法替换技能 ${metadata.name}")
            }
            if (!staging.renameTo(target)) {
                replaced?.renameTo(target)
                throw IllegalStateException("无法安装技能 ${metadata.name}")
            }
            replaced?.deleteRecursively()
        } finally {
            if (staging.exists()) staging.deleteRecursively()
            File(rootDir, "${staging.name}-lift").takeIf { it.exists() }?.deleteRecursively()
        }

        val enabled = enabledNames().toMutableSet()
        val wasEnabled = previousName?.let { it in enabled } ?: true
        if (renaming) {
            skillDir(previousName!!).deleteRecursively()
            enabled -= previousName
        }
        if (wasEnabled) enabled += metadata.name
        settings.edit { putStringSet(ENABLED_KEY, enabled) }
        reload()
        metadata
    }

    private fun ensureLoaded() {
        if (!loaded) synchronized(lock) { if (!loaded) reload() }
    }

    private fun reload() {
        val enabled = enabledNames()
        val skills = rootDir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull { dir ->
                val document = runCatching {
                    SkillMarkdownParser.parse(File(dir, SKILL_FILE).readText(Charsets.UTF_8))
                }.getOrNull() ?: return@mapNotNull null
                // 目录名与 frontmatter 名称不一致的包视为损坏，不提供给模型。
                if (document.metadata.name != dir.name) return@mapNotNull null
                InstalledAgentSkill(
                    metadata = document.metadata,
                    files = dir.walkTopDown()
                        .filter { it.isFile }
                        .map { it.relativeTo(dir).invariantSeparatorsPath }
                        .filter { it != SKILL_FILE }
                        .sorted()
                        .toList(),
                    enabled = document.metadata.name in enabled,
                )
            }
            .sortedBy { it.metadata.name }
        _skills.value = skills
        loaded = true
    }

    private fun enabledNames(): Set<String> = settings.getStringSet(ENABLED_KEY, null)?.toSet().orEmpty()

    private fun skillDir(name: String): File {
        SkillMarkdownParser.validateName(name)
        return File(rootDir, name)
    }

    companion object {
        const val ENABLED_KEY = "agent_skills_enabled"
        const val SKILL_FILE = "SKILL.md"
        const val MAX_FILES = 1000
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
        const val MAX_READ_BYTES = 256L * 1024

        /** 归一化包内相对路径并拒绝越界、绝对路径与保留名。 */
        fun normalizeRelativePath(raw: String): String {
            val path = raw.replace('\\', '/').trim()
            if (path.isEmpty() || path.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(path) || '\u0000' in path) {
                throw AgentSkillFormatException("非法路径：$raw")
            }
            val segments = path.split('/').filter(String::isNotEmpty)
            if (segments.isEmpty() || segments.any { it == "." || it == ".." || com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.hasInternalPrefix(it) }) {
                throw AgentSkillFormatException("非法路径：$raw")
            }
            return segments.joinToString("/")
        }

        /** 压缩包根部没有 SKILL.md 时，把唯一顶层目录中的内容提到暂存根部。 */
        private fun hoistSingleTopLevelDirectory(staging: File) {
            if (File(staging, SKILL_FILE).isFile) return
            val top = staging.listFiles().orEmpty().singleOrNull()
            if (top == null || !top.isDirectory || !File(top, SKILL_FILE).isFile) {
                throw AgentSkillFormatException("SKILL.md 必须位于压缩包根部或唯一的顶层目录中")
            }
            val lifted = File(staging.parentFile, "${staging.name}-lift")
            if (!top.renameTo(lifted)) throw IllegalStateException("无法整理技能包目录")
            lifted.listFiles().orEmpty().forEach { child ->
                if (!child.renameTo(File(staging, child.name))) throw IllegalStateException("无法整理技能包目录")
            }
            lifted.delete()
        }

        fun decodeText(bytes: ByteArray): String? {
            if (bytes.any { it == 0.toByte() }) return null
            return runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .removePrefix("\uFEFF")
            }.getOrNull()
        }

        /** 复制不超过 [limit] 字节，超出时删除半个文件并返回 null。 */
        private fun copyBounded(input: InputStream, out: File, limit: Long): Long? {
            var total = 0L
            val chunk = ByteArray(64 * 1024)
            out.outputStream().use { output ->
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > limit) break
                    output.write(chunk, 0, read)
                }
            }
            if (total > limit) {
                out.delete()
                return null
            }
            return total
        }
    }
}
