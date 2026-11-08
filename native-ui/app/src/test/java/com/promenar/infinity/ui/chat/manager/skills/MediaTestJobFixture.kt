package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.exec.BuiltinCommandRunner
import com.promenar.infinity.data.exec.ProcessGroupSignaller
import com.promenar.infinity.data.jobs.BackgroundJobLocation
import com.promenar.infinity.data.jobs.BackgroundJobManager
import com.promenar.infinity.data.jobs.BackgroundJobStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking

internal class MediaTestJobFixture {
    private val root = Files.createTempDirectory("media-test-jobs").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val jobs = BackgroundJobManager(scope, BackgroundJobStore(File(root, "store")), File(root, "runs"),
        BuiltinCommandRunner(listOf("/bin/sh"), "/bin/sh", ProcessGroupSignaller { _, _ -> }, { File(root, "scratch") }),
        prepareWorkspace = { BackgroundJobLocation(root, root, false, false) },
        prepareCommand = { _, _, _, _, _ -> error("不应调用命令") })
    fun close() = runBlocking { scope.coroutineContext[Job]?.cancelAndJoin(); root.deleteRecursively(); Unit }
}
