package com.promenar.infinity.data.git

import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * 把会话工作区解析为检查目标：自选目录直接取其根；私有工作区取会话根的物理目录（规范化后，
 * Android 的 filesDir 位于系统符号链接 /data/user/0 之下）；不可用时给出原因，不回退到私有工作区。
 */
class SessionGitWorkspaceLocator(
    private val resolveWorkspace: suspend (String) -> SessionWorkspace,
    private val privateRoot: suspend (String) -> String,
    private val linuxPathMappings: suspend (String) -> Map<String, String>? = { null },
) {
    suspend fun locate(sessionId: String): GitWorkspaceResolution = try {
        when (val workspace = resolveWorkspace(sessionId)) {
            is SessionWorkspace.Directory -> GitWorkspaceResolution.Ready(GitWorkspaceTarget(workspace.root, workspace.kind,
                linuxPathMappings(sessionId) ?: emptyMap<String, String>().takeIf {
                    workspace.kind == com.promenar.infinity.data.branch.BranchWorktreeWorkspaceResolver.KIND
                }))
            is SessionWorkspace.Unavailable -> GitWorkspaceResolution.Unavailable(workspace.reason)
            SessionWorkspace.Private -> {
                val root = withContext(Dispatchers.IO) { File(privateRoot(sessionId)).canonicalFile }
                GitWorkspaceResolution.Ready(GitWorkspaceTarget(root, SessionWorkspaceResolver.KIND_PRIVATE, linuxPathMappings(sessionId)))
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        GitWorkspaceResolution.Unavailable(failure.message ?: failure.javaClass.simpleName)
    }
}

/**
 * Git 变更面板的数据入口：解析会话工作区后经 [GitInspector] 读取状态、内容与历史，
 * 未暂存与未跟踪差异的工作区一侧由 [GitWorkTreeReader] 按目录读取。全部只读。
 */
class GitChangesService(
    private val locate: suspend (String) -> GitWorkspaceResolution,
    private val inspector: GitInspector,
    private val workTree: GitWorkTreeReader = NioGitWorkTreeReader(),
) {
    suspend fun status(sessionId: String): GitInspectResult<GitStatus> = withTarget(sessionId) { target ->
        // 没有 .git 时直接判定，不为每次打开资源管理器启动解释器。
        if (!hasGitDirectory(target.root)) return@withTarget GitInspectResult.Failure(GitInspectError.NotRepository)
        when (val result = inspector.status(target)) {
            // 私有空间目录名是会话 id 的摘要；没有远端时检查端回落到目录名，对用户没有意义，交由界面以分支作标题。
            is GitInspectResult.Success -> if (
                target.kind == SessionWorkspaceResolver.KIND_PRIVATE && result.value.repositoryName == target.root.name
            ) GitInspectResult.Success(result.value.copy(repositoryName = null)) else result
            else -> result
        }
    }

    /** 只检查工作区里有没有 `.git`，不启动解释器；用于在读取状态前先显示入口占位。 */
    suspend fun isRepository(sessionId: String): Boolean = when (val resolution = locate(sessionId)) {
        is GitWorkspaceResolution.Unavailable -> false
        is GitWorkspaceResolution.Ready -> hasGitDirectory(resolution.target.root)
    }

    suspend fun log(sessionId: String, limit: Int = GitInspectLimits.DEFAULT_COMMITS): GitInspectResult<GitCommitLog> =
        withTarget(sessionId) { inspector.log(it, limit) }

    suspend fun commit(sessionId: String, sha: String): GitInspectResult<GitCommitDetail> =
        withTarget(sessionId) { inspector.commit(it, sha) }

    suspend fun branches(sessionId: String): GitInspectResult<GitBranchList> =
        withTarget(sessionId) { inspector.branches(it) }

    /** 已暂存：HEAD 对索引；未暂存：索引对工作区；未跟踪：空对工作区。 */
    suspend fun changeDiff(sessionId: String, group: GitChangeGroup, change: GitFileChange): GitInspectResult<GitDiffSides> =
        withTarget(sessionId) { target ->
            val oldPath = change.oldPath ?: change.path
            when (group) {
                GitChangeGroup.STAGED -> readPair(
                    target,
                    GitBlobRef(GitRevisions.HEAD, oldPath),
                    GitBlobRef(GitRevisions.INDEX, change.path),
                    change,
                )
                GitChangeGroup.UNSTAGED -> {
                    when (val index = inspector.read(target, listOf(GitBlobRef(GitRevisions.INDEX, oldPath)))) {
                        is GitInspectResult.Failure -> index
                        is GitInspectResult.Success -> readWorkTree(target, change.path).let { workTreeSide ->
                            when (workTreeSide) {
                                is GitInspectResult.Failure -> workTreeSide
                                is GitInspectResult.Success ->
                                    GitInspectResult.Success(GitDiffSides(change.path, oldPath, index.value.single(), workTreeSide.value))
                            }
                        }
                    }
                }
                GitChangeGroup.UNTRACKED -> readWorkTree(target, change.path).map {
                    GitDiffSides(change.path, oldPath, GitFileContent.Absent, it)
                }
            }
        }

    /** 提交相对第一个父提交的单文件差异；根提交的旧侧为空。 */
    suspend fun commitFileDiff(sessionId: String, sha: String, change: GitFileChange): GitInspectResult<GitDiffSides> {
        if (!GitRevisions.isFullSha(sha)) {
            return GitInspectResult.Failure(GitInspectError.InspectFailed("INVALID_ARGUMENT", "提交 sha 无效"))
        }
        return withTarget(sessionId) { target ->
            readPair(
                target,
                GitBlobRef(GitRevisions.parentOf(sha), change.oldPath ?: change.path),
                GitBlobRef(GitRevisions.commit(sha), change.path),
                change,
            )
        }
    }

    private suspend fun readPair(
        target: GitWorkspaceTarget,
        old: GitBlobRef,
        new: GitBlobRef,
        change: GitFileChange,
    ): GitInspectResult<GitDiffSides> = inspector.read(target, listOf(old, new)).map { (oldSide, newSide) ->
        GitDiffSides(change.path, old.path, oldSide, newSide)
    }

    private suspend fun readWorkTree(target: GitWorkspaceTarget, path: String): GitInspectResult<GitFileContent> = try {
        GitInspectResult.Success(workTree.read(target.root, path))
    } catch (unsafe: GitUnsafePathException) {
        GitInspectResult.Failure(GitInspectError.UnsafePath(unsafe.path))
    } catch (failure: IOException) {
        GitInspectResult.Failure(GitInspectError.WorkTreeReadFailed(path, failure.message ?: failure.javaClass.simpleName))
    } catch (denied: SecurityException) {
        GitInspectResult.Failure(GitInspectError.WorkTreeReadFailed(path, denied.message ?: "访问被拒绝"))
    }

    private suspend fun <T> withTarget(
        sessionId: String,
        block: suspend (GitWorkspaceTarget) -> GitInspectResult<T>,
    ): GitInspectResult<T> = when (val resolution = locate(sessionId)) {
        is GitWorkspaceResolution.Unavailable -> GitInspectResult.Failure(GitInspectError.WorkspaceUnavailable(resolution.reason))
        is GitWorkspaceResolution.Ready -> block(resolution.target)
    }

    private suspend fun hasGitDirectory(root: File): Boolean = withContext(Dispatchers.IO) {
        runCatching { Files.exists(root.toPath().resolve(".git"), LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)
    }
}
