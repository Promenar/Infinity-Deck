package com.promenar.infinity.data.skills

import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 文件为事实源；所有磁盘操作与同状态目录的实例在 IO 上串行执行。 */
class FileSkillCatalog internal constructor(
    override val userRoot: File,
    private val settings: SharedPreferences,
    private val stateDir: File,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val files: SkillCatalogFiles,
) : SkillCatalog {
    constructor(
        userRoot: File,
        settings: SharedPreferences,
        stateDir: File,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(userRoot, settings, stateDir, ioDispatcher, clock, SkillCatalogFiles())

    private val changes = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = changes.asStateFlow()
    private val mutex = locks.computeIfAbsent(stateDir.absoluteFile.normalize().path) { Mutex() }
    private val states = SkillCatalogStateStore(stateDir, files)
    private val locations = mutableMapOf<String, Location>()
    private val cache = mutableMapOf<Location, Cached>()
    // 按工作区分别记录，避免不同工作区的扫描交替时互相判定为变化、反复递增修订号。
    private val lastObservations = mutableMapOf<String?, Observation>()
    private var trashCleaned = false

    override suspend fun scan(workspace: SkillWorkspace?): SkillSnapshot = locked {
        if (!trashCleaned) {
            cleanTrash()
            trashCleaned = true
        }
        val found = discover(SkillScope.USER, userRoot, null) + if (workspace == null) emptyList() else {
            discover(SkillScope.PROJECT, File(workspace.root, ".agents/skills"), workspace)
        }
        val inspected = found.map { it to inspect(it) }
        val enabled = enabledNames()
        val previousState = states.read()
        var state = previousState
        inspected.forEach { (location, inspection) ->
            if (location.scope == SkillScope.USER && location.name in enabled && inspection.hash != null &&
                location.name !in state.userConfirmed
            ) {
                state = state.copy(userConfirmed = state.userConfirmed + (location.name to inspection.hash))
            }
            val confirmed = if (location.scope == SkillScope.USER) state.userConfirmed[location.name]
                else state.projectTrusted[location.projectKey!!]
            // 只有当前完整旧摘要仍匹配时才能证明变化仅来自摘要规则，随后保存排除缓存的新摘要。
            if (inspection.hash != null && confirmed != null && confirmed != inspection.hash &&
                runCatching { migrationHashes(location) }.getOrNull()?.let {
                    it.first == confirmed && it.second == inspection.hash
                } == true
            ) {
                state = if (location.scope == SkillScope.USER) {
                    state.copy(userConfirmed = state.userConfirmed + (location.name to inspection.hash))
                } else state.copy(projectTrusted = state.projectTrusted + (location.projectKey!! to inspection.hash))
            }
        }
        if (state != previousState) states.write(state)
        val snapshot = SkillSnapshot(workspace, inspected.map { (location, inspection) ->
            asDiscovered(location, inspection, state, enabled)
        })
        // 正文变化也属于可观察变化，即使元数据与停用状态恰好没有变化。
        val observation = Observation(snapshot, inspected.map { (location, inspection) -> location.id to inspection.hash })
        if (lastObservations.put(workspace?.key, observation) != observation) changes.value++
        snapshot
    }

    override suspend fun readDocument(skill: DiscoveredSkill): ParsedSkillDocument? = locked {
        try {
            val location = locate(skill)
            val inspection = inspect(location)
            if (inspection.brokenReason != null) return@locked null
            val text = readText(location, AgentSkillStore.SKILL_FILE, AgentSkillStore.MAX_READ_BYTES.toInt())
            SkillMarkdownParser.parse(text).takeIf { it.metadata.name == location.name }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            null
        }
    }

    override suspend fun readFile(skill: DiscoveredSkill, path: String): String = locked {
        try {
            val relative = AgentSkillStore.normalizeRelativePath(path)
            if (relative.split('/').any { it == ".git" }) throw AgentSkillFormatException("不允许读取 .git 路径")
            val location = locate(skill)
            val inspection = inspect(location)
            if (inspection.brokenReason != null) throw AgentSkillFormatException(inspection.brokenReason)
            if (relative != AgentSkillStore.SKILL_FILE && relative !in inspection.files) {
                throw AgentSkillFormatException("技能 ${location.name} 中没有文件：$relative")
            }
            readText(location, relative, AgentSkillStore.MAX_READ_BYTES.toInt())
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is AgentSkillFormatException) throw failure
            throw AgentSkillFormatException("无法读取技能文件：${failure.message ?: "文件已变化"}")
        }
    }

    override suspend fun setEnabled(skill: DiscoveredSkill, enabled: Boolean): Unit = locked {
        val location = locate(skill)
        val inspection = requireCurrent(location)
        val state = states.read()
        val previousEnabled = enabledNames()
        val current = asDiscovered(location, inspection, state, previousEnabled)
        check(current.status != SkillStatus.PENDING_TRUST) { "项目技能内容需要先确认信任" }
        if (location.scope == SkillScope.USER) {
            val next = state.copy(
                userConfirmed = if (enabled) state.userConfirmed + (location.name to inspection.hash!!) else state.userConfirmed,
                userDisabled = if (enabled) state.userDisabled - location.name else state.userDisabled + location.name,
            )
            val enabledNames = if (enabled) previousEnabled + location.name else previousEnabled - location.name
            updateState(state, next)
            if (enabledNames != previousEnabled) {
                writeEnabled(enabledNames)
                changes.value++
            }
        } else {
            val key = location.projectKey!!
            updateState(state, state.copy(projectDisabled = if (enabled) state.projectDisabled - key else state.projectDisabled + key))
        }
    }

    override suspend fun trust(skill: DiscoveredSkill): Unit = locked {
        check(skill.scope == SkillScope.PROJECT) { "仅项目级技能需要信任" }
        val location = locate(skill)
        val inspection = requireCurrent(location)
        val key = location.projectKey!!
        val state = states.read()
        updateState(state, state.copy(projectTrusted = state.projectTrusted + (key to inspection.hash!!), projectDisabled = state.projectDisabled - key))
    }

    override suspend fun acknowledgeModified(skill: DiscoveredSkill): Unit = locked {
        check(skill.scope == SkillScope.USER) { "仅用户级技能可以确认修改" }
        val location = locate(skill)
        val inspection = requireCurrent(location)
        val state = states.read()
        updateState(state, state.copy(userConfirmed = state.userConfirmed + (location.name to inspection.hash!!)))
    }

    override suspend fun delete(skill: DiscoveredSkill): Unit = locked {
        val location = locate(skill)
        checkParents(location)
        val previous = states.read()
        val enabled = enabledNames()
        val next = if (location.scope == SkillScope.USER) previous.copy(
            userConfirmed = previous.userConfirmed - location.name, userDisabled = previous.userDisabled - location.name,
        ) else {
            val key = location.projectKey!!
            previous.copy(projectTrusted = previous.projectTrusted - key, projectDisabled = previous.projectDisabled - key)
        }
        var target: File? = null
        if (Files.exists(location.dir.toPath(), NOFOLLOW_LINKS)) {
            val trash = trashRoot(create = true)
            val basename = "${clock()}-${location.scope.name.lowercase()}-${location.name}"
            target = File(trash, basename)
            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) target = File(trash, "$basename-${UUID.randomUUID()}")
            moveToTrash(location.dir, target)
        }
        try {
            if (next != previous) states.write(next)
            if (location.scope == SkillScope.USER && location.name in enabled) writeEnabled(enabled - location.name)
        } catch (failure: Exception) {
            // 状态清理失败时恢复技能目录，并尝试恢复原状态；回收副本保留为恢复依据。
            if (target != null) runCatching { restore(target, location.dir) }.exceptionOrNull()?.let(failure::addSuppressed)
            runCatching { states.write(previous) }.exceptionOrNull()?.let(failure::addSuppressed)
            if (location.scope == SkillScope.USER) runCatching { writeEnabled(enabled) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        cache.remove(location)
        changes.value++
        // 状态提交成功后清除暂存目录；清理失败要向调用方报告。
        target?.let(files::deleteTree)
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(ioDispatcher) { mutex.withLock { block() } }

    override suspend fun installApproved(files: Map<String, ByteArray>, overwrite: Boolean): DiscoveredSkill = locked {
        if (files.isEmpty() || files.size > AgentSkillStore.MAX_FILES) throw AgentSkillFormatException("技能包文件数无效")
        val payload = linkedMapOf<String, ByteArray>()
        var total = 0L
        files.forEach { (raw, bytes) ->
            val path = AgentSkillStore.normalizeRelativePath(raw)
            if (path.split('/').any { it == ".git" || it == "__pycache__" } || path.endsWith(".pyc")) {
                throw AgentSkillFormatException("安装快照不能包含运行缓存或 Git 内部文件")
            }
            if (bytes.size > AgentSkillStore.MAX_FILE_BYTES || bytes.size.toLong() > AgentSkillStore.MAX_TOTAL_BYTES - total) {
                throw AgentSkillFormatException("技能包超出大小限制")
            }
            total += bytes.size
            if (payload.put(path, bytes.copyOf()) != null) throw AgentSkillFormatException("技能包包含重复路径：$path")
        }
        val markdown = payload[AgentSkillStore.SKILL_FILE]?.let(AgentSkillStore::decodeText)
            ?: throw AgentSkillFormatException("技能包缺少 UTF-8 SKILL.md")
        val name = SkillMarkdownParser.parse(markdown).metadata.name
        val approvedDigest = MessageDigest.getInstance("SHA-256")
        payload.toSortedMap().forEach { (relative, bytes) ->
            val path = relative.toByteArray(Charsets.UTF_8)
            approvedDigest.update(ByteBuffer.allocate(4).putInt(path.size).array())
            approvedDigest.update(path)
            approvedDigest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
            approvedDigest.update(bytes)
        }
        val approvedHash = approvedDigest.digest().joinToString("") { "%02x".format(it) }
        check(userRoot.isDirectory || userRoot.mkdirs()) { "无法创建技能目录" }
        requireDirectory(userRoot.toPath())
        check(stateDir.isDirectory || stateDir.mkdirs()) { "无法创建技能状态目录" }
        requireDirectory(stateDir.toPath())
        val state = states.read()
        val enabled = enabledNames()
        var stateAttempted = false
        var inspected: Inspection? = null
        try {
            this.files.installTree(userRoot, stateDir, name, payload, overwrite, validate = { staging ->
                inspected = requireCurrent(Location(SkillScope.USER, name, staging, null))
                check(inspected!!.hash == approvedHash) { "安装暂存内容与批准的文件快照不一致" }
            }) { existed ->
                val next = state.copy(userConfirmed = state.userConfirmed + (name to approvedHash))
                stateAttempted = true
                states.write(next)
                // 旧版只有启用名单的停用选择，也按覆盖前目录存在且未启用来保留。
                val preserveDisabled = name in state.userDisabled || (existed || name in state.userConfirmed) && name !in enabled
                val nextEnabled = if (preserveDisabled) enabled - name else enabled + name
                writeEnabled(nextEnabled)
                val location = Location(SkillScope.USER, name, userRoot, null)
                locations[location.id] = location
                cache.remove(location)
                changes.value++
                asDiscovered(location, inspected!!, next, nextEnabled)
            }
        } catch (failure: Exception) {
            if (stateAttempted) {
                runCatching { states.write(state) }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { writeEnabled(enabled) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            cache.keys.removeAll { it.root.parentFile == stateDir && it.root.name.startsWith(".approved-") }
        }
    }

    private fun enabledNames(): Set<String> = settings.getStringSet(AgentSkillStore.ENABLED_KEY, null)?.toSet().orEmpty()

    private fun writeEnabled(names: Set<String>) {
        check(settings.edit().putStringSet(AgentSkillStore.ENABLED_KEY, names.toMutableSet()).commit()) { "无法保存技能启用状态" }
    }

    private fun updateState(previous: SkillCatalogState, next: SkillCatalogState) {
        if (previous != next) {
            states.write(next)
            changes.value++
        }
    }

    private fun discover(scope: SkillScope, root: File, workspace: SkillWorkspace?): List<Location> {
        return try {
            checkParents(Location(scope, "placeholder", root, workspace))
            root.listFiles()?.asSequence()?.filter { !it.name.startsWith('.') }?.filter {
                val attrs = attributes(it.toPath())
                attrs.isDirectory || attrs.isSymbolicLink
            }?.sortedBy { it.name }?.map { dir ->
                Location(scope, dir.name, root, workspace).also { locations[it.id] = it }
            }?.toList().orEmpty()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            emptyList()
        }
    }

    private fun locate(skill: DiscoveredSkill): Location {
        val location = locations[skill.id]
        check(location != null && location.name == skill.name && location.scope == skill.scope) { "技能句柄未知或与扫描来源不一致" }
        return location
    }

    private fun requireCurrent(location: Location): Inspection = inspect(location, force = true).also {
        check(it.brokenReason == null && it.hash != null) { it.brokenReason ?: "技能已不存在" }
    }

    private fun asDiscovered(location: Location, inspected: Inspection, state: SkillCatalogState, enabled: Set<String>): DiscoveredSkill {
        val status = when {
            inspected.brokenReason != null -> SkillStatus.BROKEN
            location.scope == SkillScope.USER -> if (location.name in enabled) SkillStatus.ENABLED else SkillStatus.DISABLED
            state.projectTrusted[location.projectKey!!] != inspected.hash -> SkillStatus.PENDING_TRUST
            location.projectKey in state.projectDisabled -> SkillStatus.DISABLED
            else -> SkillStatus.ENABLED
        }
        return DiscoveredSkill(
            id = location.id,
            scope = location.scope,
            name = location.name,
            description = inspected.metadata?.description.orEmpty(),
            displayName = inspected.presentation.displayName,
            shortDescription = inspected.presentation.shortDescription,
            virtualDir = if (location.scope == SkillScope.USER) "/skills/${location.name}" else "/.agents/skills/${location.name}",
            files = inspected.files,
            status = status,
            modifiedSinceConfirmed = location.scope == SkillScope.USER && status == SkillStatus.ENABLED &&
                state.userConfirmed[location.name]?.let { it != inspected.hash } == true,
            allowImplicitInvocation = inspected.presentation.allowImplicitInvocation,
            brokenReason = inspected.brokenReason,
            metadata = inspected.metadata,
        )
    }

    private fun inspect(location: Location, force: Boolean = false): Inspection {
        return try {
            val signature = inventory(location)
            val cached = cache[location]
            if (!force && cached?.signature == signature) return cached.inspection
            val digest = MessageDigest.getInstance("SHA-256")
            var markdown: ByteArray? = null
            var yaml: ByteArray? = null
            signature.forEach { entry ->
                val file = safeFile(location, entry.path)
                val path = entry.path.toByteArray(Charsets.UTF_8)
                // 长度前缀隔开路径、字节边界，避免不同文件组合产生相同输入序列。
                digest.update(ByteBuffer.allocate(4).putInt(path.size).array())
                digest.update(path)
                digest.update(ByteBuffer.allocate(8).putLong(entry.size).array())
                // 只有 SKILL.md 与 openai.yaml 需要解析，其余文件流式进入摘要，不整体读入内存。
                val keep = entry.path == AgentSkillStore.SKILL_FILE || entry.path == "agents/openai.yaml"
                val kept = if (keep) ByteArrayOutputStream() else null
                val read = files.stream(file, AgentSkillStore.MAX_FILE_BYTES.toLong(), location.workspace?.root ?: location.root) { chunk, count ->
                    digest.update(chunk, 0, count)
                    kept?.write(chunk, 0, count)
                }
                if (read != entry.size) throw IOException("扫描期间文件发生变化：${entry.path}")
                if (entry.path == AgentSkillStore.SKILL_FILE) markdown = kept!!.toByteArray()
                if (entry.path == "agents/openai.yaml") yaml = kept!!.toByteArray()
            }
            if (signature != inventory(location)) throw IOException("扫描期间技能目录发生变化")
            val bytes = markdown ?: throw AgentSkillFormatException("技能包缺少 SKILL.md")
            val text = AgentSkillStore.decodeText(bytes) ?: throw AgentSkillFormatException("SKILL.md 不是 UTF-8 文本")
            val document = SkillMarkdownParser.parse(text)
            if (document.metadata.name != location.name) throw AgentSkillFormatException("SKILL.md 的 name 与目录名不一致")
            val presentation = yaml?.let(AgentSkillStore::decodeText)?.let(SkillInterfaceParser::parse) ?: SkillInterface()
            val inspection = Inspection(
                document.metadata,
                signature.map { it.path }.filter { it != AgentSkillStore.SKILL_FILE },
                digest.digest().joinToString("") { "%02x".format(it) },
                presentation,
            )
            cache[location] = Cached(signature, inspection)
            inspection
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            cache.remove(location)
            Inspection(brokenReason = failure.message ?: "无法读取技能目录")
        }
    }

    private fun migrationHashes(location: Location): Pair<String, String> {
        val signature = inventory(location, includePythonCache = true)
        val digest = MessageDigest.getInstance("SHA-256")
        val normalized = MessageDigest.getInstance("SHA-256")
        signature.forEach { entry ->
            val path = entry.path.toByteArray(Charsets.UTF_8)
            val included = !isPythonCache(entry.path)
            if (included) {
                normalized.update(ByteBuffer.allocate(4).putInt(path.size).array())
                normalized.update(path)
                normalized.update(ByteBuffer.allocate(8).putLong(entry.size).array())
            }
            digest.update(ByteBuffer.allocate(4).putInt(path.size).array())
            digest.update(path)
            digest.update(ByteBuffer.allocate(8).putLong(entry.size).array())
            val read = files.stream(safeFile(location, entry.path), AgentSkillStore.MAX_FILE_BYTES.toLong(), location.workspace?.root ?: location.root) { bytes, count ->
                digest.update(bytes, 0, count)
                if (included) normalized.update(bytes, 0, count)
            }
            check(read == entry.size) { "迁移期间技能文件变化" }
        }
        check(signature == inventory(location, includePythonCache = true)) { "迁移期间技能目录变化" }
        return digest.digest().joinToString("") { "%02x".format(it) } to normalized.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isPythonCache(path: String): Boolean = path.split('/').any { it == "__pycache__" } || path.endsWith(".pyc")

    private fun inventory(location: Location, includePythonCache: Boolean = false): List<FileStamp> {
        checkParents(location)
        requireDirectory(location.dir.toPath())
        val signature = mutableListOf<FileStamp>()
        var total = 0L
        val root = location.dir.toPath()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                if (dir != root && (dir.fileName.toString() == ".git" ||
                        !includePythonCache && dir.fileName.toString() == "__pycache__")) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file).toString().replace(File.separatorChar, '/')
                if (!includePythonCache && isPythonCache(relative)) {
                    return FileVisitResult.CONTINUE
                }
                if (attrs.isSymbolicLink) throw AgentSkillFormatException("不允许符号链接：$relative")
                if (!attrs.isRegularFile) throw AgentSkillFormatException("不是普通文件：$relative")
                if (signature.size >= AgentSkillStore.MAX_FILES) throw AgentSkillFormatException("技能包文件数超过 ${AgentSkillStore.MAX_FILES}")
                if (attrs.size() > AgentSkillStore.MAX_FILE_BYTES) {
                    throw AgentSkillFormatException("文件超过 ${AgentSkillStore.MAX_FILE_BYTES / 1024 / 1024} MiB：$relative")
                }
                total += attrs.size()
                if (total > AgentSkillStore.MAX_TOTAL_BYTES) throw AgentSkillFormatException("技能包总量超过 ${AgentSkillStore.MAX_TOTAL_BYTES / 1024 / 1024} MiB")
                signature += FileStamp(relative, attrs.size(), attrs.lastModifiedTime(), attrs.fileKey()?.toString())
                return FileVisitResult.CONTINUE
            }
        })
        return signature.sortedBy { it.path }
    }

    private fun readText(location: Location, relative: String, limit: Int): String {
        val file = safeFile(location, relative)
        if (attributes(file.toPath()).size() > limit) throw AgentSkillFormatException("文件超过 ${limit / 1024} KiB 读取上限：$relative")
        val bytes = files.read(file, limit, location.workspace?.root ?: location.root)
        safeFile(location, relative)
        return AgentSkillStore.decodeText(bytes) ?: throw AgentSkillFormatException("不是 UTF-8 文本文件：$relative")
    }

    private fun safeFile(location: Location, relative: String): File {
        checkParents(location)
        requireDirectory(location.dir.toPath())
        var current = location.dir.toPath()
        val segments = relative.split('/')
        segments.forEachIndexed { index, segment ->
            current = current.resolve(segment)
            val attrs = attributes(current)
            if (attrs.isSymbolicLink) throw AgentSkillFormatException("不允许符号链接：$relative")
            if (index < segments.lastIndex && !attrs.isDirectory || index == segments.lastIndex && !attrs.isRegularFile) {
                throw AgentSkillFormatException("不是普通文件：$relative")
            }
        }
        return current.toFile()
    }

    private fun checkParents(location: Location) {
        if (location.workspace != null) {
            requireDirectory(location.workspace.root.toPath())
            requireDirectory(File(location.workspace.root, ".agents").toPath())
        }
        requireDirectory(location.root.toPath())
    }

    private fun requireDirectory(path: Path) {
        val attrs = attributes(path)
        if (attrs.isSymbolicLink) throw AgentSkillFormatException("不允许符号链接：${path.fileName}")
        if (!attrs.isDirectory) throw AgentSkillFormatException("技能目录不存在：${path.fileName}")
    }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun trashRoot(create: Boolean): File {
        check(!Files.isSymbolicLink(stateDir.toPath())) { "技能状态目录不能是符号链接" }
        val trash = File(stateDir, "trash")
        check(!Files.isSymbolicLink(trash.toPath())) { "技能回收区不能是符号链接" }
        if (create) check(trash.isDirectory || trash.mkdirs()) { "无法创建技能回收区" }
        return trash
    }

    private fun cleanTrash() {
        val trash = try { trashRoot(create = false) } catch (_: IllegalStateException) { return }
        val cutoff = clock() - 30L * 24 * 60 * 60 * 1000
        trash.listFiles().orEmpty().forEach { entry ->
            val timestamp = Regex("^(\\d+)-(user|project)-.+$", RegexOption.DOT_MATCHES_ALL)
                .matchEntire(entry.name)?.groupValues?.get(1)?.toLongOrNull()
            if (timestamp != null && timestamp < cutoff) {
                // 单条清理失败不影响扫描；只处理带有回收命名的条目。
                runCatching { files.deleteTree(entry) }
            }
        }
    }

    private fun moveToTrash(source: File, target: File) {
        if (files.rename(source, target)) return
        try {
            files.copyTree(source, target)
        } catch (failure: Exception) {
            runCatching { files.deleteTree(target) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        try {
            files.deleteTree(source)
        } catch (failure: Exception) {
            // 完整副本在源目录清除完成前保留，删除中途失败则合并恢复源目录。
            runCatching { files.copyTree(target, source) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun restore(source: File, target: File) {
        if (files.rename(source, target)) return
        files.copyTree(source, target)
        files.deleteTree(source)
    }

    private data class Location(val scope: SkillScope, val name: String, val root: File, val workspace: SkillWorkspace?) {
        val dir get() = File(root, name)
        val id get() = if (scope == SkillScope.USER) "user:$name" else "project:${workspace!!.key}:$name"
        val projectKey get() = workspace?.let { ProjectSkillKey(it.key, name) }
    }

    private data class FileStamp(val path: String, val size: Long, val modified: FileTime, val fileKey: String?)
    private data class Inspection(
        val metadata: AgentSkillMetadata? = null,
        val files: List<String> = emptyList(),
        val hash: String? = null,
        val presentation: SkillInterface = SkillInterface(),
        val brokenReason: String? = null,
    )
    private data class Cached(val signature: List<FileStamp>, val inspection: Inspection)
    private data class Observation(val snapshot: SkillSnapshot, val hashes: List<Pair<String, String?>>)

    private companion object {
        val locks = ConcurrentHashMap<String, Mutex>()
    }
}
