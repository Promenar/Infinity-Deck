package com.promenar.infinity.data.files

import android.os.SystemClock
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.exec.AndroidProcessGroupSignaller
import com.promenar.infinity.data.exec.BuiltinCommandRunner
import com.promenar.infinity.data.exec.BuiltinExecEnvironment
import com.promenar.infinity.data.git.BuiltinGitInspectCommandRunner
import com.promenar.infinity.data.git.BuiltinGitPythonRuntime
import com.promenar.infinity.data.git.GitChangesService
import com.promenar.infinity.data.git.GitInspector
import com.promenar.infinity.data.git.GitWorkspaceResolution
import com.promenar.infinity.data.git.GitWorkspaceTarget
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import java.io.File

/** 未绑定会话的项目目录复用现有只读 Git 检查器。 */
internal class WorkspaceGitInspector(private val app: InfinityApplication) {
    private val inspector by lazy {
        GitInspector(
            runtime = BuiltinGitPythonRuntime(BuiltinExecEnvironment.create(app, pipEnabled = false, skillsDirectory = app.skillCatalog.userRoot)),
            runner = BuiltinGitInspectCommandRunner(BuiltinCommandRunner(
                groupLauncher = listOf("/system/bin/setsid"),
                shell = "/system/bin/sh",
                signaller = AndroidProcessGroupSignaller,
                scratchDirectory = { File(app.cacheDir, "git-inspect-run") },
                clockNanos = SystemClock::elapsedRealtimeNanos,
            )),
            outputDirectory = { File(app.cacheDir, "git-inspect") },
        )
    }

    suspend fun status(directory: SessionWorkspace.Directory) = GitChangesService(
        locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(directory.root, SessionWorkspaceResolver.kindOf(directory))) },
        inspector = inspector,
    ).status(directory.workspace.id)
}
