package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.exec.*
import com.promenar.infinity.data.jobs.*
import com.promenar.infinity.data.linux.UnavailableLinuxRuntime
import com.promenar.infinity.data.repository.WorkspaceChangeReport
import com.promenar.infinity.data.repository.WorkspaceCommandHost
import com.promenar.infinity.data.repository.WorkspaceCommandOutcome
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.tool.ToolExecutionPolicy
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitExecSkillTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun honorsReadWriteApprovalClassification() {
        fun approval(arguments: String, mode: ExecutionMode) =
            ToolExecutionPolicy.requiresApproval(mode, "git_exec", ToolRisk.PROCESS, arguments)
        assertFalse(approval("""{"args":["status","--short"]}""", ExecutionMode.WORKSPACE))
        assertTrue(approval("""{"args":["add","item"]}""", ExecutionMode.WORKSPACE))
        assertTrue(approval("""{"args":["push","origin","HEAD"],"background":true}""", ExecutionMode.WORKSPACE))
        assertFalse(approval("""{"args":["add","item"]}""", ExecutionMode.FULL_ACCESS))
    }

    @Test fun foregroundUsesWorkspaceLockAndReconcilesBeforeInvalidation() = fixture { skill, _, workspace, events ->
        val nested = File(workspace, "sub").apply { mkdir() }
        val result = skill.execute(request("status", cwd = "sub"), context(SessionRunMode.BUILTIN))
        val json = Json.parseToJsonElement(result.content).jsonObject
        assertEquals("success", result.status)
        assertEquals("native-git", json["runtime"]?.jsonPrimitive?.content)
        assertEquals(nested.canonicalPath, json.getValue("stdout").jsonPrimitive.content.trim())
        assertEquals(listOf("lock", "reconcile", "invalidate"), events)
        assertTrue("workspace_changes" in json)
    }

    @Test fun linuxBackgroundResolvesGuestCwdAndTracksJobUntilReconciliation() = fixture(SessionRunMode.LINUX) { skill, jobs, workspace, events ->
        val folder = File(workspace, "sub").apply { mkdir() }
        val result = skill.execute(request("status", cwd = "/home/session/sub", background = true), context(SessionRunMode.LINUX))
        val json = Json.parseToJsonElement(result.content).jsonObject
        val jobId = json.getValue("job_id").jsonPrimitive.content
        val record = withTimeout(10_000) {
            while (true) {
                val found = jobs.list("session").first { it.id == jobId }
                if (!found.running) return@withTimeout found
                delay(20)
            }
            error("任务记录未找到")
        }
        assertEquals("EXITED", record.state)
        assertEquals(folder.canonicalPath, record.commandResult?.get("stdout")?.jsonPrimitive?.content?.trim())
        assertEquals(listOf("lock", "reconcile", "invalidate"), events)
        val denied = skill.execute(request("status", cwd = "/etc"), context(SessionRunMode.LINUX))
        assertEquals("TOOL_PATH", Json.parseToJsonElement(denied.content).jsonObject.getValue("error_code").jsonPrimitive.content)
    }

    private fun request(argument: String, cwd: String, background: Boolean = false) = buildJsonObject {
        putJsonArray("args") { add(argument) }
        put("cwd", cwd)
        put("background", background)
        put("on_exit", "none")
    }
    private fun context(mode: SessionRunMode) = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
        override val runMode = mode
    }

    private fun fixture(
        mode: SessionRunMode = SessionRunMode.BUILTIN,
        body: suspend (GitExecSkill, BackgroundJobManager, File, List<String>) -> Unit,
    ) = runBlocking {
        assumeTrue(File("/usr/bin/perl").canExecute())
        val workspace = temp.newFolder("workspace").canonicalFile
        val libs = temp.newFolder("libs")
        val rootfs = temp.newFolder("rootfs")
        val home = File(rootfs, "root").apply { mkdir() }
        File(rootfs, "tmp").mkdir()
        val cache = temp.newFolder("cache")
        File(libs, "libnxgit.so").apply { writeText("#!/bin/sh\n/bin/pwd\n"); setExecutable(true) }
        File(libs, "libnxgitssh.so").createNewFile()
        File(libs, "libnxgit-remote-https.so").createNewFile()
        val tools = PackagedHostTools(libs)
        val runner = BuiltinCommandRunner(
            listOf("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or exit 127"), "/bin/sh",
            { group, signal -> ProcessBuilder("/bin/kill", "-$signal", "-$group").start().waitFor() },
            { File(cache, "scratch") },
        )
        val linux = object : LinuxRuntime by UnavailableLinuxRuntime(LinuxUnavailableReason.DISTRIBUTION, LinuxPaths(rootfs, home)) {
            override val state = MutableStateFlow<LinuxRuntimeState>(LinuxRuntimeState.Ready(
                LinuxEnvironmentInfo("Alpine", "test", "aarch64", "test", 0, null),
            ))
            override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch = error("git_exec 不启动 proot")
        }
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val host = object : WorkspaceCommandHost {
            override suspend fun <T> runCommandInWorkspace(workspaceRootUuid: String, sessionId: String?, block: suspend (File) -> T): WorkspaceCommandOutcome<T> {
                assertEquals("root", workspaceRootUuid)
                assertEquals("session", sessionId)
                events += "lock"
                return try { WorkspaceCommandOutcome(block(workspace), WorkspaceChangeReport()) }
                finally { events += "reconcile" }
            }
        }
        val environment = BuiltinExecEnvironment(PackagedPython(libs), temp.newFolder("home"), temp.newFolder("tmp"), { null },
            inherited = emptyMap(), tailnetEnvironment = { emptyMap() })
        val service = BuiltinExecService(host, runner, environment, shell = "/bin/sh", linuxRuntime = linux, sessionMode = { mode },
            sessionHome = { workspace }, hostGit = GitHostBroker(tools, GitHostConfig(cache, tools)), invalidateGit = { events += "invalidate" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val jobs = BackgroundJobManager(scope, BackgroundJobStore(temp.newFolder("jobs")), temp.newFolder("runs"), runner,
            prepareWorkspace = { error("git_exec 使用当前工作区") },
            prepareCommand = { _, _, _, _, _ -> error("git_exec 使用结构化执行") })
        try { body(GitExecSkill(service, jobs), jobs, workspace, events) } finally {
            jobs.list("session").filter { it.running }.forEach { jobs.stop(it.id) }
            scope.cancel()
            cache.listFiles().orEmpty().forEach { it.setWritable(true) }
        }
    }
}
