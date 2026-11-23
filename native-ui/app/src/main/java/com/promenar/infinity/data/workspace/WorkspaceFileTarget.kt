package com.promenar.infinity.data.workspace

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.isKnowledgeBaseSource
import com.promenar.infinity.data.storage.isUserSkills
import com.promenar.infinity.data.storage.isWorkspaceRoot
import com.promenar.infinity.data.storage.requireWritable
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import java.io.File

internal data class WorkspaceFileTarget(
    val sessionId: String,
    val root: FileEntry?,
    val directory: File?,
    val mount: ExternalMount?,
    val workspaceMount: ExternalMount? = null,
    val destination: WorkspaceFileDestinationBinding? = null,
    val directoryBackend: ExternalFolderBackend? = null,
    val privatePathPrefix: String? = null,
    val linuxNamespace: Boolean = false,
    val sourceIdentity: String? = null,
    val accessPrefix: String = "",
) {
    val identity: List<String?> get() = listOf(root?.uuid, root?.hash, root?.physicalRootPath, directory?.path, mount?.id, mount?.sourceType, mount?.sourceId, mount?.readOnly?.toString(), workspaceMount?.id, workspaceMount?.readOnly?.toString(), destination?.workspaceId, destination?.destination?.name, linuxNamespace.toString(), sourceIdentity)
    val mountIds: Map<String, String> get() = listOfNotNull(workspaceMount, mount).associate { it.alias to it.id }
    fun relative(path: String): String = privatePathPrefix?.let { prefix ->
        if (path == prefix || path.startsWith("$prefix/")) path.removePrefix(prefix).ifEmpty { "/" }
        else com.promenar.infinity.domain.storage.ExternalFolderPaths.normalize(path)
    } ?: ExternalMountPaths.parse(path)?.second
        ?: com.promenar.infinity.domain.storage.ExternalFolderPaths.normalize(path)
    fun materialized(path: String): String = if (root != null || directoryBackend != null) relative(path) else path
    fun accessPath(path: String): String = if (privatePathPrefix != null && (path == privatePathPrefix || path.startsWith("$privatePathPrefix/"))) {
        (accessPrefix + relative(path).takeUnless { it == "/" }.orEmpty()).ifEmpty { "/" }
    } else if (!path.startsWith('/')) {
        com.promenar.infinity.domain.storage.ExternalFolderPaths.normalize(path)
    } else path
    val virtualRoot: String get() = privatePathPrefix ?: when {
        mount == null || mount.isWorkspaceRoot -> "/"
        mount.isUserSkills -> "/skills"
        else -> "/mnt/${mount.alias}"
    }
}

/** 媒体与画布共用命名空间；主根失效不隐式回退，显式私有目标独立解析。 */
internal class WorkspaceFileTargetResolver(
    private val sessions: ISessionRepository,
    private val workspace: IWorkspaceRepository,
    private val mounts: ExternalWorkspaceAccess,
    private val sessionWorkspace: suspend (String) -> SessionWorkspace,
    private val userSkillsDirectory: File,
    private val registeredWorkspace: (suspend (String) -> SessionWorkspace)? = null,
    private val directoryBackend: ((SessionWorkspace.Directory) -> ExternalFolderBackend)? = null,
    private val pathPresenter: WorkspaceFilePathPresenter = BuiltinWorkspaceFilePathPresenter,
    private val modeFor: (suspend (String) -> com.promenar.infinity.domain.model.SessionRunMode?)? = null,
    private val linuxFiles: com.promenar.infinity.domain.linux.LinuxFileSystem? = null,
    private val linuxDirectoryBackend: ((SessionWorkspace.Directory) -> ExternalFolderBackend)? = directoryBackend,
) {
    suspend fun defaultDestination(sessionId: String): WorkspaceFileDestinationBinding {
        val current = sessionWorkspace(sessionId)
        val destination = defaultWorkspaceFileDestination(current, mounts.visible(sessionId))
        return WorkspaceFileDestinationBinding(destination, (current as? SessionWorkspace.Directory)?.workspace?.id.takeIf { destination == WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE })
    }

    suspend fun primaryDestination(sessionId: String): WorkspaceFileDestinationBinding? = when (val current = sessionWorkspace(sessionId)) {
        SessionWorkspace.Private -> WorkspaceFileDestinationBinding(WorkspaceDefaultFileDestination.SESSION_PRIVATE)
        is SessionWorkspace.Directory -> WorkspaceFileDestinationBinding(WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE, current.workspace.id)
        is SessionWorkspace.Unavailable -> null
    }

    suspend fun present(sessionId: String, destination: WorkspaceFileDestinationBinding, path: String): String =
        presenter(sessionId).present(destination.destination, path, sessionWorkspace(sessionId) == SessionWorkspace.Private && destination.destination != WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE)

    /** 内置真实路径模式下，主工作区返回便携无前导 `/` 相对路径，独立私有空间返回真实 Android 路径。 */
    suspend fun presentBuiltinRealPath(sessionId: String, destination: WorkspaceFileDestinationBinding, relativePath: String): String {
        val clean = com.promenar.infinity.domain.storage.ExternalFolderPaths.normalize(relativePath)
        val privatePrimary = sessionWorkspace(sessionId) == SessionWorkspace.Private &&
            destination.destination != WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE
        if (destination.destination == WorkspaceDefaultFileDestination.SESSION_PRIVATE && !privatePrimary) {
            val session = sessions.getById(sessionId) ?: unavailable("会话不存在，无法访问工作区")
            val root = session.workspaceRootUuid?.let { workspace.getByUuid(it, it) }
                ?: workspace.ensureSessionRoot(sessionId)
            val rootPath = BuiltinRealPathContext.canonicalOrAbs(File(root.physicalRootPath))
            return BuiltinRealPathPaths.joinPath(rootPath, clean)
        }
        return clean.removePrefix("/")
    }

    /** 规范化内置媒体路径：优先识别本机真实工作区/私有/挂载/技能路径，兼容便携相对路径与仅存虚拟前缀。 */
    suspend fun normalizeBuiltinMediaPath(sessionId: String, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any(Character::isISOControl) || '\\' in trimmed) {
            throw ExternalFolderException("INVALID_PATH", "媒体路径无效")
        }
        if (trimmed.startsWith('/')) {
            val parts = trimmed.split('/').filter { it.isNotEmpty() }
            if (parts.any { it == "." || it == ".." }) {
                throw ExternalFolderException("INVALID_PATH", "媒体路径不允许包含 . 或 ..")
            }
            val collapsed = if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
            val canonical = canonicalizeAppAlias(collapsed)
            if (matchesBuiltinRealRoot(sessionId, canonical)) return canonical
            return com.promenar.infinity.domain.storage.ExternalFolderPaths.normalize(collapsed).also { ExternalMountPaths.parse(it) }
        }
        val rel = com.promenar.infinity.domain.storage.ExternalFolderPaths.normalize(trimmed)
        ExternalMountPaths.parse(rel)
        return rel.removePrefix("/")
    }

    private fun appDataRootPath(): String =
        BuiltinRealPathContext.canonicalOrAbs(userSkillsDirectory).substringBefore("/files/", missingDelimiterValue = "")

    private fun canonicalizeAppAlias(path: String): String {
        val appDataCanonical = appDataRootPath()
        if (appDataCanonical.isEmpty()) return path
        return BuiltinRealPathPaths.canonicalizeAppDataAlias(path, appDataCanonical)
    }

    private suspend fun matchesBuiltinRealRoot(sessionId: String, path: String): Boolean {
        if (ExternalMountPaths.parse(path) != null) return false
        if (BuiltinRealPathPaths.isSameOrBelow(path, BuiltinRealPathContext.canonicalOrAbs(userSkillsDirectory))) return true
        val current = sessionWorkspace(sessionId)
        if (current is SessionWorkspace.Directory && BuiltinRealPathPaths.isSameOrBelow(path, BuiltinRealPathContext.canonicalOrAbs(current.root))) {
            return true
        }
        val appData = appDataRootPath()
        if (current == SessionWorkspace.Private || (appData.isNotEmpty() && BuiltinRealPathPaths.isSameOrBelow(path, appData))) {
            val session = sessions.getById(sessionId)
            val privRoot = session?.workspaceRootUuid?.let { workspace.getByUuid(it, it) }?.physicalRootPath
            if (privRoot != null && BuiltinRealPathPaths.isSameOrBelow(path, BuiltinRealPathContext.canonicalOrAbs(File(privRoot)))) {
                return true
            }
        }
        return mounts.visible(sessionId).any {
            it.sourceType == "real_path" && BuiltinRealPathPaths.isSameOrBelow(path, BuiltinRealPathContext.canonicalOrAbs(File(it.sourceId)))
        }
    }

    suspend fun presenter(sessionId: String): WorkspaceFilePathPresenter =
        modeFor?.let { workspaceFilePathPresenter(it(sessionId)) } ?: pathPresenter

    suspend fun resolve(
        sessionId: String,
        path: String,
        writing: Boolean,
        destination: WorkspaceFileDestinationBinding? = null,
        initializeMissingPrivateRoot: Boolean = false,
    ): WorkspaceFileTarget {
        if (writing) com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId)
        val presenter = presenter(sessionId)
        // 持久镜像绑定逻辑落点；模式切换后先还原已知前缀，再使用当前模式寻址。
        val effectivePath = if (destination != null) {
            val relative = workspaceFileRelativePath(path)
            presenter.present(destination.destination, relative, privatePrimary = false)
        } else path
        if (presenter == LinuxWorkspaceFilePathPresenter) {
            return linuxTarget(sessionId, effectivePath, writing, destination, initializeMissingPrivateRoot)
        }
        return builtinTarget(sessionId, effectivePath, writing, destination, initializeMissingPrivateRoot)
    }

    private suspend fun builtinTarget(
        sessionId: String, path: String, writing: Boolean, destination: WorkspaceFileDestinationBinding?,
        initializeMissingPrivateRoot: Boolean,
    ): WorkspaceFileTarget {
        val privateRelative = pathPresenter.privateRelativePath(path)
        if (privateRelative != null || destination?.destination == WorkspaceDefaultFileDestination.SESSION_PRIVATE) {
            return privateTarget(sessionId, writing, destination, privateRelative?.let { path.removeSuffix(it).ifEmpty { SessionPrivateWorkspacePaths.ROOT } },
                initializeMissingPrivateRoot)
        }
        val current = sessionWorkspace(sessionId)
        if (destination?.destination == WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE) {
            val id = destination.workspaceId ?: unavailable("画布镜像缺少工作区身份")
            val bound = if (current is SessionWorkspace.Directory && current.workspace.id == id) current
                else registeredWorkspace?.invoke(id) ?: unavailable("画布原工作区不可用")
            if (bound !is SessionWorkspace.Directory) unavailable("画布原工作区不可用")
            val backend = directoryBackend?.invoke(bound)
            if (backend != null) {
                if (writing) com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = bound.root.canonicalPath)
                return WorkspaceFileTarget(sessionId, null, bound.root, null, destination = destination, directoryBackend = backend)
            }
            if (current !is SessionWorkspace.Directory || current.workspace.id != id) unavailable("默认媒体主工作区已变化，已停止保存")
        }
        if (current is SessionWorkspace.Unavailable) unavailable("当前会话工作区不可用，请检查目录与授权")
        val workspaceMount = mounts.workspaceMount(sessionId)
        if (current is SessionWorkspace.Directory && workspaceMount == null || current is SessionWorkspace.Private && workspaceMount != null) {
            unavailable("会话工作区正在变化，请重新执行文件操作")
        }
        if (current is SessionWorkspace.Directory && workspaceMount?.sourceId != current.workspace.id) {
            unavailable("会话主工作区身份正在变化，请重新执行文件操作")
        }
        // 内置真实路径寻址：按真实前缀识别技能、主工作区、会话私有目录及 real_path 挂载；虚拟挂载前缀直接交由下方挂载解析。
        if (path.startsWith('/') && ExternalMountPaths.parse(path) == null) {
            val canonical = canonicalizeAppAlias(path)
            val skillsPath = BuiltinRealPathContext.canonicalOrAbs(userSkillsDirectory)
            if (BuiltinRealPathPaths.isSameOrBelow(canonical, skillsPath)) {
                val mount = mounts.visible(sessionId).singleOrNull { it.isUserSkills }
                    ?: unavailable("用户技能挂载不可用")
                if (writing) mount.requireWritable()
                if (writing) com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = skillsPath)
                return WorkspaceFileTarget(sessionId, null, userSkillsDirectory.canonicalFile, mount, workspaceMount, destination = destination, privatePathPrefix = skillsPath, accessPrefix = "/skills")
            }
            if (current is SessionWorkspace.Directory) {
                val wsPath = BuiltinRealPathContext.canonicalOrAbs(current.root)
                if (BuiltinRealPathPaths.isSameOrBelow(canonical, wsPath)) {
                    val mount = workspaceMount ?: unavailable("会话主工作区挂载不可用")
                    if (writing) mount.requireWritable()
                    if (writing) com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = wsPath)
                    return WorkspaceFileTarget(sessionId, null, current.root, mount, workspaceMount, destination = destination, privatePathPrefix = wsPath, accessPrefix = "")
                }
            }
            val appData = appDataRootPath()
            if (current == SessionWorkspace.Private || (appData.isNotEmpty() && BuiltinRealPathPaths.isSameOrBelow(canonical, appData))) {
                val privateRootPath = sessions.getById(sessionId)?.workspaceRootUuid?.let { workspace.getByUuid(it, it) }?.physicalRootPath
                    ?.let { BuiltinRealPathContext.canonicalOrAbs(File(it)) }
                if (privateRootPath != null && BuiltinRealPathPaths.isSameOrBelow(canonical, privateRootPath)) {
                    return privateTarget(sessionId, writing, destination, prefix = privateRootPath, initializeMissingPrivateRoot = initializeMissingPrivateRoot)
                }
            }
            val realMountMatch = mounts.visible(sessionId)
                .filter { it.sourceType == "real_path" && !it.isUserSkills }
                .map { it to BuiltinRealPathContext.canonicalOrAbs(File(it.sourceId)) }
                .sortedByDescending { it.second.length }
                .firstOrNull { BuiltinRealPathPaths.isSameOrBelow(canonical, it.second) }
            if (realMountMatch != null) {
                val (mount, mountRoot) = realMountMatch
                if (mount.unavailableReason != null) unavailable("挂载已失效或不属于当前会话")
                if (writing) mount.requireWritable()
                if (writing) com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = mountRoot)
                return WorkspaceFileTarget(sessionId, null, File(mount.sourceId), mount, workspaceMount, destination = destination, privatePathPrefix = mountRoot, accessPrefix = "/mnt/${mount.alias}")
            }
        }
        val alias = ExternalMountPaths.aliasOf(path, workspaceMount != null)
        if (alias != null) {
            val mount = if (alias == ExternalMountPaths.WORKSPACE_ALIAS) workspaceMount
                else mounts.visible(sessionId).singleOrNull { it.alias == alias }
            if (mount == null || mount.unavailableReason != null) unavailable("挂载已失效或不属于当前会话")
            if (writing) {
                if (mount.isKnowledgeBaseSource) throw ExternalFolderException("READ_ONLY", "知识库挂载为只读")
                mount.requireWritable()
            }
            val directory = when {
                mount.isWorkspaceRoot -> (current as? SessionWorkspace.Directory)?.root
                mount.isUserSkills -> userSkillsDirectory.canonicalFile
                mount.sourceType == "real_path" -> File(mount.sourceId)
                mount.sourceType == "saf_tree" -> null
                else -> unavailable("不支持该挂载来源")
            }
            if (writing) com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = directory?.canonicalPath)
            return WorkspaceFileTarget(sessionId, null, directory, mount, workspaceMount, destination = destination)
        }
        if (path == "/mnt") return WorkspaceFileTarget(sessionId, null, null, null, workspaceMount)
        return privateTarget(sessionId, writing, initializeMissingPrivateRoot = initializeMissingPrivateRoot)
    }

    internal suspend fun linuxWorkingDirectory(sessionId: String): String {
        val selected = when (val current = sessionWorkspace(sessionId)) {
            is SessionWorkspace.Directory -> current.workspace
            is SessionWorkspace.Unavailable -> current.workspace
            SessionWorkspace.Private -> null
        }
        return selected?.let(LinuxWorkspaceLocation::workingDirectory) ?: LinuxSessionPaths.WORKSPACE_ROOT
    }

    private suspend fun linuxTarget(
        sessionId: String, path: String, writing: Boolean, destination: WorkspaceFileDestinationBinding?,
        initializeMissingPrivateRoot: Boolean,
    ): WorkspaceFileTarget {
        val current = sessionWorkspace(sessionId)
        val selectedDirectory = (current as? SessionWorkspace.Directory)?.workspace?.takeIf {
            it.kind == com.promenar.infinity.data.local.db.entity.WorkspaceEntity.KIND_LINUX_HOME
        }?.let(LinuxWorkspaceLocation::workingDirectory)
            ?: (current as? SessionWorkspace.Unavailable)?.workspace?.takeIf {
                it.kind == com.promenar.infinity.data.local.db.entity.WorkspaceEntity.KIND_LINUX_HOME
            }?.let(LinuxWorkspaceLocation::workingDirectory)
        val normalized = LinuxSessionPaths.normalize(path, selectedDirectory ?: LinuxSessionPaths.WORKSPACE_ROOT)
        val routed = LinuxSessionPaths.route(normalized, current == SessionWorkspace.Private && destination?.destination != WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE, selectedDirectory)
        val target = when (routed.kind) {
            LinuxSessionPathKind.PRIVATE -> privateTarget(sessionId, writing, destination,
                if (normalized == LinuxSessionPaths.PRIVATE_ROOT || normalized.startsWith("${LinuxSessionPaths.PRIVATE_ROOT}/")) LinuxSessionPaths.PRIVATE_ROOT else LinuxSessionPaths.WORKSPACE_ROOT,
                initializeMissingPrivateRoot)
            LinuxSessionPathKind.WORKSPACE -> {
                val boundId = destination?.workspaceId
                val bound = if (boundId != null && (current as? SessionWorkspace.Directory)?.workspace?.id != boundId) {
                    registeredWorkspace?.invoke(boundId) ?: unavailable("画布原工作区不可用")
                } else current
                if (bound !is SessionWorkspace.Directory) unavailable("当前会话工作区不可用，请检查目录与授权")
                val delegate = linuxDirectoryBackend?.invoke(bound) ?: unavailable("当前会话工作区不可用，请检查目录与授权")
                val backend = com.promenar.infinity.startup.StartupGuardedExternalFolderBackend(delegate, sessionId) { bound.root }
                if (writing) com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = bound.root.canonicalPath)
                WorkspaceFileTarget(sessionId, null, bound.root, null, directoryBackend = backend,
                    destination = destination ?: WorkspaceFileDestinationBinding(WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE, bound.workspace.id),
                    privatePathPrefix = selectedDirectory?.takeIf { normalized == it || normalized.startsWith("$it/") } ?: LinuxSessionPaths.WORKSPACE_ROOT)
            }
            LinuxSessionPathKind.EXTERNAL -> {
                val alias = ExternalMountPaths.parse(routed.path)?.first ?: unavailable("挂载已失效或不属于当前会话")
                val mount = mounts.visible(sessionId).singleOrNull { it.alias == alias }
                if (writing) {
                    if (mount?.isKnowledgeBaseSource == true) throw ExternalFolderException("READ_ONLY", "知识库挂载为只读")
                    mount?.requireWritable()
                }
                if (mount == null || mount.sourceType != "real_path" && !mount.isUserSkills) unavailable("挂载已失效或不属于当前会话")
                builtinTarget(sessionId, routed.path, writing, destination, initializeMissingPrivateRoot).let {
                    if (mount.isUserSkills) it.copy(privatePathPrefix = LinuxSessionPaths.SKILLS_ROOT, accessPrefix = "/skills") else it
                }
            }
            LinuxSessionPathKind.MOUNTS -> WorkspaceFileTarget(sessionId, null, null, null)
            LinuxSessionPathKind.ROOTFS -> {
                // 媒体只读 rootfs；文件写入仍由带逐次审批的 Linux 文件工具负责。
                if (writing) throw ExternalFolderException("READ_ONLY", "目标位置为只读，无法保存媒体")
                val files = linuxFiles ?: unavailable("Linux 根目录不可用")
                val backend = LinuxSessionRootfsBackend(files)
                val directory = files.hostFile("/") ?: unavailable("Linux 根目录不可用")
                val identity = backend.stat("/")?.identity ?: unavailable("无法绑定 Linux 根目录身份")
                WorkspaceFileTarget(sessionId, null, directory, null, directoryBackend = backend, sourceIdentity = identity)
            }
        }
        val identity = target.sourceIdentity ?: target.directory?.let { directory ->
            val attributes = java.nio.file.Files.readAttributes(directory.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isDirectory || attributes.isSymbolicLink) unavailable("Linux 目录来源不能是符号链接")
            attributes.fileKey()?.toString() ?: unavailable("无法绑定 Linux 目录来源身份")
        }
        return target.copy(linuxNamespace = true, sourceIdentity = identity)
    }

    private suspend fun privateTarget(
        sessionId: String,
        writing: Boolean,
        destination: WorkspaceFileDestinationBinding? = null,
        prefix: String? = null,
        initializeMissingPrivateRoot: Boolean = false,
    ): WorkspaceFileTarget {
        val session = sessions.getById(sessionId) ?: unavailable("会话不存在，无法访问工作区")
        val mayInitialize = writing || initializeMissingPrivateRoot && session.workspaceRootUuid == null
        val root = session.workspaceRootUuid?.let { workspace.getByUuid(it, it) }
            ?: if (mayInitialize) workspace.ensureSessionRoot(sessionId) else unavailable("会话私有工作区尚不可用")
        if (writing) com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, rootId = root.uuid, path = root.physicalRootPath)
        return WorkspaceFileTarget(sessionId, root, File(root.physicalRootPath), null, destination = destination, privatePathPrefix = prefix)
    }

    suspend fun checkCurrent(target: WorkspaceFileTarget, path: String) {
        val prefix = target.privatePathPrefix
        val presented = if (target.destination == null && prefix != null && path != prefix && !path.startsWith("$prefix/")) prefix + path.takeUnless { it == "/" }.orEmpty() else path
        val logicalPath = if (target.destination != null && prefix != null && (path == prefix || path.startsWith("$prefix/"))) target.relative(path) else presented
        if (resolve(target.sessionId, logicalPath, writing = false, target.destination).identity != target.identity) {
            unavailable("会话工作区或挂载在操作期间发生变化，已停止访问")
        }
    }

    private fun unavailable(message: String): Nothing = throw ExternalFolderException("UNAVAILABLE", message)
}
