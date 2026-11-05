package com.promenar.infinity.data.files

import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.git.GitWorkspaceTarget
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver

/** 未绑定会话的目录和会话共用仓库状态缓存。 */
internal class WorkspaceGitInspector(private val app: InfinityApplication) {
    private fun target(directory: SessionWorkspace.Directory) = GitWorkspaceTarget(directory.root, SessionWorkspaceResolver.kindOf(directory))

    suspend fun status(directory: SessionWorkspace.Directory) = app.gitStatusCache.status(
        target(directory),
    )

    suspend fun cachedStatus(directory: SessionWorkspace.Directory) = app.gitStatusCache.cachedStatus(target(directory))
}
