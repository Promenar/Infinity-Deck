package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.secret.SessionSecretVault
import com.promenar.infinity.data.secret.SecretOutputRedactor
import com.promenar.infinity.data.secret.SecretCapture
import com.promenar.infinity.domain.secret.SecretSource
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import com.promenar.infinity.data.git.remote.GitCredentialProxy
import com.promenar.infinity.data.git.remote.GitNonceRegistry
import com.promenar.infinity.data.git.remote.GitRemoteBroker
import com.promenar.infinity.data.git.remote.GitRemoteLease
import com.promenar.infinity.data.git.remote.GitRemoteSessions
import com.promenar.infinity.data.git.remote.SecretGitCredentialStore
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.repository.WorkspaceChangeReport
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.repository.WorkspaceCommandHost
import com.promenar.infinity.data.repository.WorkspaceCommandOutcome
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.PythonExecSkill
import com.promenar.infinity.ui.chat.manager.skills.ShellExecSkill
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BuiltinExecServiceTest {
    private lateinit var work: File
    private lateinit var workspace: File
    private var changes: WorkspaceChangeReport? = WorkspaceChangeReport()
    private var reconcileError: String? = null
    private val sessions = mutableListOf<String?>()

    private val host = object : WorkspaceCommandHost {
        override suspend fun <T> runCommandInWorkspace(
            workspaceRootUuid: String,
            sessionId: String?,
            block: suspend (File) -> T,
        ): WorkspaceCommandOutcome<T> {
            sessions += sessionId
            if (workspaceRootUuid == "missing") throw SecurityException("工作区根不存在或无效")
            return WorkspaceCommandOutcome(block(workspace), changes, reconcileError)
        }
    }

    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }

    @Before
    fun setUp() {
        assumeTrue("需要 /usr/bin/perl 建立进程组", File("/usr/bin/perl").canExecute())
        work = Files.createTempDirectory("builtin-exec").toFile()
        workspace = File(work, "workspace").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::work.isInitialized) work.deleteRecursively()
    }

    private fun service(
        pythonDir: File = File(work, "no-python"),
        keepAlive: ExecKeepAlive = NoExecKeepAlive,
        clockNanos: () -> Long = System::nanoTime,
        sessionWorkspace: suspend (String?) -> SessionWorkspace = { SessionWorkspace.Private },
        gitRemote: GitRemoteSessions = GitRemoteSessions.None,
        monitor: RuntimeDataMonitor? = null,
        vault: SessionSecretVault? = null,
        commandRunner: BuiltinCommandRunner? = null,
    ) = BuiltinExecService(
        workspace = host,
        runner = commandRunner ?: BuiltinCommandRunner(
            groupLauncher = listOf("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or exit 127"),
            shell = "/bin/sh",
            signaller = { group, signal -> ProcessBuilder("/bin/kill", "-$signal", "-$group").start().waitFor() },
            scratchDirectory = { File(work, "scratch") },
            killGraceMillis = 300,
            clockNanos = clockNanos,
        ),
        environment = BuiltinExecEnvironment(
            python = PackagedPython(pythonDir),
            homeDirectory = File(work, "home"),
            tempDirectory = File(work, "tmp"),
            caBundle = { null },
            inherited = mapOf("PATH" to "/usr/bin:/bin", "SECRET_TOKEN" to "x"),
        ),
        shell = "/bin/sh",
        unavailablePythonAbi = { "x86" },
        keepAlive = keepAlive,
        sessionWorkspace = sessionWorkspace,
        gitRemote = gitRemote,
        runtimeDataMonitor = monitor,
        vault = vault,
        redactor = vault?.let(::SecretOutputRedactor),
    )

    @Test fun `系统shell插队转后台后原进程完成且日志身份可恢复`() = verifyForegroundTransfer(python = false)
    @Test fun `Python插队转后台后持久身份可停止原进程`() = verifyForegroundTransfer(python = true)

    private fun verifyForegroundTransfer(python: Boolean) = runBlocking<Unit> {
        val pythonBinary = listOf("/usr/bin/python3", "/opt/homebrew/bin/python3").firstOrNull { File(it).canExecute() }
        if (python) assumeTrue("需要宿主 Python 验证托管进程", pythonBinary != null)
        val packaged = File(work, "transfer-python").apply { mkdirs() }
        File(packaged, PackagedPython.STDLIB).writeText("")
        File(packaged, PackagedPython.LAUNCHER).apply {
            writeText("#!/bin/sh\nunset PYTHONHOME PYTHONPATH\nexec '$pythonBinary' \"\$@\"\n")
            setExecutable(true)
        }
        val runner = BuiltinCommandRunner(listOf("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or exit 127"),
            "/bin/sh", { group, signal -> ProcessBuilder("/bin/kill", "-$signal", "-$group").start().waitFor() },
            { File(work, "transfer-scratch") }, killGraceMillis = 100,
            processIdentity = { pid ->
                if (File("/proc/self/stat").isFile) runCatching { File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19] }.getOrNull()
                else "fixture-$pid"
            })
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val jobs = com.promenar.infinity.data.jobs.BackgroundJobManager(scope,
            com.promenar.infinity.data.jobs.BackgroundJobStore(File(work, "transfer-store")), File(work, "transfer-runs"), runner,
            prepareWorkspace = { com.promenar.infinity.data.jobs.BackgroundJobLocation(workspace, workspace, false, false) },
            prepareCommand = { _, _, _, _, _ -> error("前台托管保留原命令") })
        try {
            val exec = service(pythonDir = packaged, commandRunner = runner)
            val queue = kotlinx.coroutines.flow.MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
            val skill = if (python) PythonExecSkill(exec, jobs) else ShellExecSkill(exec, jobs)
            val input = if (python) args("code" to "from pathlib import Path; import time; Path('ready').touch(); print('ready', flush=True);\nwhile not Path('release').exists(): time.sleep(.02)\nPath('done').touch()")
                else args("command" to "touch ready; echo ready; echo warning >&2; while [ ! -f release ]; do sleep 0.02; done; touch done; echo done")
            val operation = async(SteeringContext(queue) + ToolCallControlContext("session", "transfer", "step")) { skill.execute(input, context) }
            withTimeout(5_000) { while (!File(workspace, "ready").exists()) delay(10) }
            queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
                com.promenar.infinity.data.chat.queue.QueuedMessage("normal", "排队", createdAt = 0)))
            delay(50)
            assertThat(operation.isCompleted).isFalse()
            queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
                com.promenar.infinity.data.chat.queue.QueuedMessage("urgent", "插队", createdAt = 0, steering = true)))
            val response = withTimeout(5_000) { operation.await() }
            val id = json(response.content).getValue("job_id").jsonPrimitive.content
            val current = jobs.status("session", id)
            assertThat(current.record.running).isTrue()
            val processGroup = File(current.record.runDirectory, "pgid")
            assertThat(File(processGroup.path + ".identity").isFile).isTrue()
            if (python) assertThat(runner.interruptBackground(processGroup)).isTrue()
            else File(workspace, "release").createNewFile()
            val ended = jobs.waitFor("session", id, 5)
            assertThat(ended.record.running).isFalse()
            assertThat(File(workspace, "done").exists()).isEqualTo(!python)
            if (!python) {
                assertThat(ended.tail.text).contains("done")
                assertThat(ended.tail.text).doesNotContain("[+")
                assertThat(File(current.record.runDirectory, "output.log").readText()).contains("[+")
                assertThat(ended.record.commandResult!!.getValue("stderr").jsonPrimitive.content).contains("warning")
            }
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.let { it.cancel(); it.join() } }
    }

    @Test
    fun `捕获完整stdout不进入实时面板且失败不返回原文`() = runBlocking<Unit> {
        val vault = SessionSecretVault()
        val exec = service(vault = vault)
        val shown = mutableListOf<String>()
        val live = object : ToolLiveOutputSink by ToolLiveOutputSink.None {
            override fun emit(stream: ToolOutputStream, text: String) { shown += text }
        }
        val capture = SecretCapture("PASSWORD", "初始密码", "stdout")
        val result = exec.runShell("root", "session", "printf '%040000d' 0", null, 10, live, capture = capture)
        assertThat(result.succeeded).isTrue()
        assertThat(result.stdout).isEqualTo("‹已捕获为 PASSWORD · 40000 字节›")
        assertThat(vault.revealBytes("session", "PASSWORD")?.size).isEqualTo(40_000)
        assertThat(shown).isEmpty()
        val failed = exec.runShell("root", "session", "printf fixture-failed-password; exit 7", null, 10, live, capture = capture)
        assertThat(failed.errorCode).isEqualTo("SECRET_CAPTURE_COMMAND_FAILED")
        assertThat(failed.toJson().toString()).doesNotContain("fixture-failed-password")
        assertThat(shown).isEmpty()
    }

    @Test
    fun `文件捕获及引用注入返回值均遮蔽`() = runBlocking<Unit> {
        val vault = SessionSecretVault()
        val exec = service(vault = vault)
        val captured = exec.runShell("root", "session", "printf fixture-file-secret > value.txt", null, 10,
            capture = SecretCapture("TOKEN", "文件令牌", "file", "value.txt"))
        assertThat(captured.succeeded).isTrue()
        assertThat(captured.stdout).contains("已捕获为 TOKEN")
        assertThat(File(workspace, "value.txt").exists()).isTrue()
        val output = exec.runShell("root", "session", "printf '%s' \"\$TOKEN\"", null, 10)
        assertThat(output.stdout).isEqualTo("‹保密:TOKEN›")
        vault.destroy("session", "TOKEN")
        val missing = exec.runShell("root", "session", "printf '%s' \"\$TOKEN\"", null, 10)
        assertThat(missing.errorCode).isEqualTo("SECRET_SLOT_UNAVAILABLE")
    }

    @Test
    fun `捕获与显式后台互斥且不启动命令`() = runBlocking<Unit> {
        val args = Json.parseToJsonElement("""{"command":"touch must-not-run","background":true,"capture_secret":{"name":"TOKEN","label":"令牌","from":"stdout"}}""").jsonObject
        val result = json(ShellExecSkill(service(vault = SessionSecretVault())).execute(args, context).content)
        assertThat(result["error_code"]?.jsonPrimitive?.content).isEqualTo("SECRET_CAPTURE_BACKGROUND_UNSUPPORTED")
        assertThat(File(workspace, "must-not-run").exists()).isFalse()
    }

    /** 记录保活请求；[stopOnStart] 模拟用户立即点击通知的停止按钮。 */
    private class RecordingKeepAlive(
        private val foreground: ExecForegroundState,
        private val stopOnStart: Boolean = false,
    ) : ExecKeepAlive {
        val runs = mutableListOf<ExecRunInfo>()
        var released = false
        override suspend fun <T> hold(run: ExecRunInfo, stop: () -> Unit, block: suspend () -> T): ExecHeld<T> {
            runs += run
            if (stopOnStart) stop()
            try {
                return ExecHeld(block(), foreground)
            } finally {
                released = true
            }
        }
    }

    private fun json(content: String): JsonObject = Json.parseToJsonElement(content).jsonObject
    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (key, value) ->
        key to if (value is Int) JsonPrimitive(value) else JsonPrimitive(value.toString())
    })

    @Test
    fun `shell 和 python 结果均包含关键数据变化且普通运行不增加字段`() = runBlocking<Unit> {
        val data = File(work, "app-data").apply { mkdirs() }
        val skill = File(data, "files/agent_skills/foo/SKILL.md").apply { parentFile.mkdirs(); writeText("before") }
        val monitor = RuntimeDataMonitor(data)
        val shell = ShellExecSkill(service(monitor = monitor))
        val changed = json(shell.execute(args("command" to "printf changed >> '${skill.path}'; exit 2"), context).content)
        assertThat(changed.getValue("runtime_changes").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("files/agent_skills/foo/SKILL.md")
        assertThat(changed.getValue("exit_code").jsonPrimitive.int).isEqualTo(2)
        assertThat(json(shell.execute(args("command" to "true"), context).content)).doesNotContainKey("runtime_changes")

        val python = listOf("/usr/bin/python3", "/opt/homebrew/bin/python3").firstOrNull { File(it).canExecute() }
        assumeTrue("需要宿主 Python 验证 python_exec 进程路径", python != null)
        val packaged = File(work, "packaged-python").apply { mkdirs() }
        File(packaged, PackagedPython.STDLIB).writeText("")
        File(packaged, PackagedPython.LAUNCHER).apply {
            writeText("#!/bin/sh\nunset PYTHONHOME PYTHONPATH\nexec '$python' \"\$@\"\n")
            setExecutable(true)
        }
        val result = PythonExecSkill(service(pythonDir = packaged, monitor = monitor)).execute(
            args("code" to "from pathlib import Path; Path('${skill.path}').unlink()"), context,
        )
        assertThat(result.status).isEqualTo("success")
        assertThat(json(result.content).getValue("runtime_changes").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("files/agent_skills/foo/SKILL.md (deleted)")
    }

    @Test
    fun `shell_exec 在工作区根运行并返回统一结果格式`() = runBlocking<Unit> {
        changes = WorkspaceChangeReport(added = listOf("/out.txt"))
        val result = ShellExecSkill(service()).execute(
            args("command" to "pwd; echo \"\$INFINITY_BACKEND:\${SECRET_TOKEN:-unset}:\$HOME\" ; cat; echo err >&2; exit 4", "stdin" to "in"),
            context,
        )
        val value = json(result.content)
        assertThat(result.status).isEqualTo("error")
        assertThat(value.keys.take(4)).containsExactly("error_code", "guidance", "exit_code", "timed_out").inOrder()
        assertThat(value.getValue("exit_code").jsonPrimitive.int).isEqualTo(4)
        val stdout = value.getValue("stdout").jsonPrimitive.content.lines()
        assertThat(stdout[0]).isEqualTo("\$INFINITY_WORKSPACE")
        assertThat(stdout[1]).isEqualTo("builtin:unset:${File(work, "home").path}")
        assertThat(stdout[2]).isEqualTo("in")
        assertThat(value.getValue("stderr").jsonPrimitive.content).isEqualTo("err\n")
        assertThat(value.getValue("truncation").jsonPrimitive.content).isEqualTo("none")
        val workspaceChanges = value.getValue("workspace_changes").jsonObject
        assertThat(workspaceChanges.getValue("added").jsonArray.map { it.jsonPrimitive.content }).containsExactly("/out.txt")
        assertThat(sessions).containsExactly("session")
    }

    @Test
    fun `自选工作区直接在目录运行，不经工作区锁与对账`() = runBlocking<Unit> {
        val project = File(work, "project").apply { mkdirs() }
        val directory = SessionWorkspace.Directory(WorkspaceEntity("ws", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1), project)
        val requested = mutableListOf<String?>()
        val value = json(ShellExecSkill(service(sessionWorkspace = { requested += it; directory })).execute(
            args("command" to "pwd; echo \"\$INFINITY_WORKSPACE_KIND\"; echo hi > made.txt"), context,
        ).content)
        assertThat(value.getValue("stdout").jsonPrimitive.content.lines().take(2)).containsExactly("\$INFINITY_WORKSPACE", "project").inOrder()
        assertThat(File(project, "made.txt").readText()).isEqualTo("hi\n")
        assertThat(File(workspace, "made.txt").exists()).isFalse()
        assertThat(value).doesNotContainKey("workspace_changes")
        assertThat(value.getValue("workspace_kind").jsonPrimitive.content).isEqualTo("project")
        assertThat(value.getValue("workspace_tracking").jsonPrimitive.content).isEqualTo("none")
        assertThat(requested).containsExactly("session")
        assertThat(sessions).isEmpty()
    }

    @Test
    fun `私有工作区的命令环境标明工作区类型`() = runBlocking<Unit> {
        val value = json(ShellExecSkill(service()).execute(args("command" to "echo \"\$INFINITY_WORKSPACE_KIND\""), context).content)
        assertThat(value.getValue("stdout").jsonPrimitive.content).isEqualTo("private\n")
    }

    @Test
    fun `自选工作区不可用时拒绝执行且不回退到私有工作区`() = runBlocking<Unit> {
        val unavailable = SessionWorkspace.Unavailable("ws", null, "项目文件夹不存在")
        for (result in listOf(
            ShellExecSkill(service(sessionWorkspace = { unavailable })).execute(args("command" to "touch leaked.txt"), context),
            PythonExecSkill(service(sessionWorkspace = { unavailable })).execute(args("code" to "print(1)"), context),
        )) {
            val value = json(result.content)
            assertThat(result.status).isEqualTo("error")
            assertThat(value.getValue("error_code").jsonPrimitive.content).isIn(listOf("EXEC_WORKSPACE_UNAVAILABLE", "EXEC_PYTHON_UNAVAILABLE"))
        }
        val shell = json(ShellExecSkill(service(sessionWorkspace = { unavailable })).execute(args("command" to "touch leaked.txt"), context).content)
        assertThat(shell.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_WORKSPACE_UNAVAILABLE")
        assertThat(shell.getValue("guidance").jsonPrimitive.content).contains("项目文件夹不存在")
        assertThat(File(workspace, "leaked.txt").exists()).isFalse()
        assertThat(sessions).isEmpty()
    }

    @Test
    fun `退出码为 0 记为成功，命令中的位置参数不含命令本身`() = runBlocking<Unit> {
        val result = ShellExecSkill(service()).execute(args("command" to "echo \"[\$#][\$0]\""), context)
        assertThat(result.status).isEqualTo("success")
        assertThat(json(result.content).getValue("stdout").jsonPrimitive.content).isEqualTo("[0][sh]\n")
    }

    @Test
    fun `没有内置 Python 时 shell 中的 python3 给出指引，python_exec 返回不可用错误`() = runBlocking<Unit> {
        val shell = json(ShellExecSkill(service()).execute(args("command" to "python3 -V"), context).content)
        assertThat(shell.getValue("exit_code").jsonPrimitive.int).isEqualTo(127)
        assertThat(shell.getValue("stderr").jsonPrimitive.content).contains("termux_exec")
        val git = json(ShellExecSkill(service()).execute(args("command" to "git status"), context).content)
        assertThat(git.getValue("exit_code").jsonPrimitive.int).isEqualTo(127)
        assertThat(git.getValue("stderr").jsonPrimitive.content).contains("git: ")
        val python = PythonExecSkill(service()).execute(args("code" to "print(1)"), context)
        assertThat(python.status).isEqualTo("error")
        val value = json(python.content)
        assertThat(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_PYTHON_UNAVAILABLE")
        assertThat(value.getValue("guidance").jsonPrimitive.content).contains("x86")
    }

    @Test
    fun `超时返回 EXEC_TIMEOUT 且退出码为空`() = runBlocking<Unit> {
        val value = json(ShellExecSkill(service()).execute(args("command" to "sleep 30", "timeout_seconds" to 1), context).content)
        assertThat(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_TIMEOUT")
        assertThat(value.getValue("timed_out").jsonPrimitive.boolean).isTrue()
        assertThat(value.getValue("exit_code").jsonPrimitive.content).isEqualTo("null")
    }

    @Test
    fun `对账失败与工作区不可用分别给出错误码`() = runBlocking<Unit> {
        changes = null
        reconcileError = "工作区索引对账失败（IOException）"
        val failed = json(ShellExecSkill(service()).execute(args("command" to "true"), context).content)
        assertThat(failed.getValue("error_code").jsonPrimitive.content).isEqualTo("WORKSPACE_RECONCILE_FAILED")
        assertThat(failed.getValue("exit_code").jsonPrimitive.int).isEqualTo(0)
        assertThat(failed.getValue("workspace_changes").toString()).isEqualTo("null")

        val missing = object : SkillExecutionContext by context { override val workspaceRootUuid = "missing" }
        val unavailable = json(ShellExecSkill(service()).execute(args("command" to "true"), missing).content)
        assertThat(unavailable.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_WORKSPACE_UNAVAILABLE")
    }

    @Test
    fun `参数校验失败不启动进程`() = runBlocking<Unit> {
        listOf(
            args("command" to " "),
            args("command" to "ls", "timeout_seconds" to 601),
            args("command" to "x".repeat(32_001)),
            args("command" to "ls", "workdir" to "/"),
        ).forEach { invalid ->
            val value = json(ShellExecSkill(service()).execute(invalid, context).content)
            assertThat(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_INVALID_ARGUMENTS")
        }
        val tooLong = json(PythonExecSkill(service()).execute(args("code" to "中".repeat(40_001)), context).content)
        assertThat(tooLong.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_INVALID_ARGUMENTS")
        assertThat(sessions).isEmpty()
    }

    @Test
    fun `变化列表超过 200 项时截断并给出计数，保留路径与特殊条目给出警告`() {
        val report = WorkspaceChangeReport(
            added = (1..150).map { "/a$it" },
            modified = (1..100).map { "/m$it" },
            reservedChanges = listOf("/.recycle_bin"),
            unsupported = listOf("/link"),
        )
        val fields = BuiltinExecService.workspaceFields(report)
        val changesJson = fields.getValue("workspace_changes").jsonObject
        assertThat(changesJson.getValue("added").jsonArray).hasSize(150)
        assertThat(changesJson.getValue("modified").jsonArray).hasSize(50)
        assertThat(changesJson.getValue("counts").jsonObject.getValue("modified").jsonPrimitive.int).isEqualTo(100)
        assertThat(changesJson.getValue("truncated").jsonPrimitive.boolean).isTrue()
        val warnings = fields.getValue("workspace_warnings").jsonArray.map { it.jsonObject.getValue("code").jsonPrimitive.content }
        assertThat(warnings).containsExactly("WORKSPACE_RESERVED_PATH", "WORKSPACE_UNSUPPORTED_ENTRY").inOrder()
    }

    @Test
    fun `git 内部对象不逐条列出，只给总数，git 目录本身与工作文件照常列出`() {
        val report = WorkspaceChangeReport(
            added = listOf("/repo/.git", "/repo/.git/HEAD", "/repo/.git/objects/ab/cdef", "/repo/a.txt", "/.github/ci.yml"),
            modified = listOf("/repo/.git/index"),
            moved = listOf("/repo/.git/x" to "/repo/.git/y", "/b" to "/c"),
        )
        val changesJson = BuiltinExecService.workspaceFields(report).getValue("workspace_changes").jsonObject
        assertThat(changesJson.getValue("added").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("/repo/.git", "/repo/a.txt", "/.github/ci.yml").inOrder()
        assertThat(changesJson.getValue("modified").jsonArray).isEmpty()
        assertThat(changesJson.getValue("moved").jsonArray).hasSize(1)
        assertThat(changesJson.getValue("counts").jsonObject.getValue("added").jsonPrimitive.int).isEqualTo(3)
        assertThat(changesJson.getValue("git_internal_changes").jsonPrimitive.int).isEqualTo(4)
        assertThat(BuiltinExecService.workspaceFields(WorkspaceChangeReport(added = listOf("/a")))
            .getValue("workspace_changes").jsonObject.containsKey("git_internal_changes")).isFalse()
    }

    @Test
    fun `输出中的工作区物理路径替换为环境变量`() {
        val root = File(work, "ws")
        assertThat(BuiltinExecService.virtualize("${root.path}/a.txt and ${root.path}", root))
            .isEqualTo("\$INFINITY_WORKSPACE/a.txt and \$INFINITY_WORKSPACE")
    }

    @Test
    fun `实时输出中被读取块切开的工作区路径仍被替换，结束时补发暂存的块尾`() {
        val root = File(work, "ws")
        val seen = mutableListOf<Pair<com.promenar.infinity.domain.tool.ToolOutputStream, String>>()
        val tee = LiveWorkspaceOutput(root) { stream, text -> seen.add(stream to text) }
        val out = com.promenar.infinity.domain.tool.ToolOutputStream.STDOUT
        val err = com.promenar.infinity.domain.tool.ToolOutputStream.STDERR
        val split = root.path.length / 2
        tee.accept(out, "see ${root.path.take(split)}")
        tee.accept(err, "e1\n")
        tee.accept(out, "${root.path.drop(split)}/a.txt\n")
        tee.accept(out, "tail /")
        tee.finish()
        assertThat(seen.filter { it.first == out }.joinToString("") { it.second })
            .isEqualTo("see \$INFINITY_WORKSPACE/a.txt\ntail /")
        assertThat(seen.filter { it.first == err }.map { it.second }).containsExactly("e1\n")
        assertThat(seen.joinToString("") { it.second }).doesNotContain(root.path)
    }

    @Test
    fun `通知停止结束命令并返回 EXEC_STOPPED，保活登记命令摘要`() = runBlocking<Unit> {
        val keepAlive = RecordingKeepAlive(ExecForegroundState.STARTED, stopOnStart = true)
        val started = System.nanoTime()
        val result = service(keepAlive = keepAlive).runShell("root", "s", "sleep 30\necho never", null, 60)
        val value = json(result.toJson().toString())
        assertThat(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_STOPPED")
        assertThat(value.getValue("guidance").jsonPrimitive.content).contains("不要自动重试")
        assertThat(result.exitCode).isNull()
        assertThat(result.timedOut).isFalse()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(10_000)
        assertThat(keepAlive.runs.single().kind).isEqualTo(ExecRunKind.SHELL)
        assertThat(keepAlive.runs.single().summary).isEqualTo("sleep 30 …")
        assertThat(keepAlive.runs.single().timeoutSeconds).isEqualTo(60)
        assertThat(keepAlive.released).isTrue()
    }

    @Test
    fun `实时输出隐去工作区物理路径，聊天界面停止返回 EXEC_STOPPED`() = runBlocking<Unit> {
        val store = ToolLiveOutputStore()
        val sink = store.open("step")
        val printed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val watcher = launch(kotlinx.coroutines.Dispatchers.IO) {
            while (store.snapshot("step")?.text?.contains("ready") != true) kotlinx.coroutines.delay(20)
            printed.complete(Unit)
        }
        val run = async(kotlinx.coroutines.Dispatchers.IO) { service().runShell("root", "s", "pwd; echo ready; sleep 30; echo never", null, 60, sink) }
        kotlinx.coroutines.withTimeout(10_000) { printed.await() }
        watcher.join()
        assertThat(store.snapshot("step")?.stoppable).isTrue()
        assertThat(store.snapshot("step")?.text).isEqualTo("\$INFINITY_WORKSPACE\nready\n")

        store.requestStop("step")
        val value = json(kotlinx.coroutines.withTimeout(10_000) { run.await() }.toJson().toString())
        assertThat(value.getValue("error_code").jsonPrimitive.content).isEqualTo("EXEC_STOPPED")
        assertThat(value.getValue("guidance").jsonPrimitive.content).contains("聊天界面")
        assertThat(value.getValue("stdout").jsonPrimitive.content).isEqualTo("\$INFINITY_WORKSPACE\nready\n")
    }

    @Test
    fun `进程被系统暂停时不计入超时并给出后台设置指引`() = runBlocking<Unit> {
        // 第 3 次读时钟起整体前跳 10 秒，模拟冻结后解冻。
        var reads = 0
        val clock = { System.nanoTime() + if (++reads >= 3) 10_000_000_000L else 0L }
        val keepAlive = RecordingKeepAlive(ExecForegroundState.NOT_ALLOWED)
        val result = service(keepAlive = keepAlive, clockNanos = clock).runShell("root", "s", "sleep 1; echo ok", null, 2)
        val value = json(result.toJson().toString())
        assertThat(result.succeeded).isTrue()
        assertThat(result.stdout).isEqualTo("ok\n")
        assertThat(value.getValue("paused_ms").jsonPrimitive.content.toLong()).isAtLeast(9_000L)
        val guidance = value.getValue("guidance").jsonPrimitive.content
        assertThat(guidance).contains("不计入超时")
        assertThat(guidance).contains("未允许启动前台服务")
        assertThat(guidance).contains("完全允许后台行为")
    }

    @Test
    fun `没有暂停时不输出 paused_ms 与后台指引`() = runBlocking<Unit> {
        val result = service(keepAlive = RecordingKeepAlive(ExecForegroundState.STARTED)).runShell("root", "s", "true", null, 5)
        val value = json(result.toJson().toString())
        assertThat(value.containsKey("paused_ms")).isFalse()
        assertThat(value["guidance"]?.toString()).isEqualTo("null")
    }

    /** 记录每条命令签发与关闭的远端认证作用域。 */
    private class RecordingRemote(private val failOpen: Boolean = false) : GitRemoteSessions {
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val firstOpened = CompletableDeferred<Unit>()
        override suspend fun open(): GitRemoteLease {
            if (failOpen) error("代理启动失败")
            val nonce = "nonce-${opened.size}"
            opened += nonce
            firstOpened.complete(Unit)
            return object : GitRemoteLease {
                override val environment = mapOf(GitRemoteBroker.ENV_NONCE to nonce)
                override fun close() { closed += nonce }
            }
        }
    }

    @Test
    fun `每条命令签发独立的远端作用域并在结束时关闭`() = runBlocking<Unit> {
        val remote = RecordingRemote()
        val first = service(gitRemote = remote).runShell("root", "s", "echo \$INFINITY_GIT_NONCE", null, 10)
        val second = service(gitRemote = remote).runShell("root", "s", "echo \$INFINITY_GIT_NONCE; exit 3", null, 10)
        assertThat(first.stdout).isEqualTo("nonce-0\n")
        assertThat(second.stdout).isEqualTo("nonce-1\n")
        assertThat(remote.closed).containsExactly("nonce-0", "nonce-1").inOrder()

        val python = File(work, "python-missing")
        service(pythonDir = python, gitRemote = remote).runPython("root", "s", "print(1)", null, 5)
        assertThat(remote.closed).hasSize(remote.opened.size)
    }

    @Test
    fun `超时与停止时也关闭远端作用域`() = runBlocking<Unit> {
        val remote = RecordingRemote()
        val timedOut = service(gitRemote = remote).runShell("root", "s", "sleep 30", null, 1)
        assertThat(timedOut.timedOut).isTrue()
        assertThat(remote.closed).containsExactly("nonce-0")

        val stopped = service(keepAlive = RecordingKeepAlive(ExecForegroundState.STARTED, stopOnStart = true), gitRemote = remote)
            .runShell("root", "s", "sleep 30", null, 60)
        assertThat(stopped.exitCode).isNull()
        assertThat(remote.closed).containsExactly("nonce-0", "nonce-1").inOrder()
    }

    @Test
    fun `协程取消时关闭远端作用域`() = runBlocking<Unit> {
        val remote = RecordingRemote()
        val job = async { service(gitRemote = remote).runShell("root", "s", "sleep 30", null, 60) }
        withTimeout(10_000) { remote.firstOpened.await() }
        delay(300)
        job.cancel()
        runCatching { job.await() }
        assertThat(remote.closed).containsExactly("nonce-0")
    }

    @Test
    fun `作用域签发失败时命令照常执行且不带远端变量`() = runBlocking<Unit> {
        val result = service(gitRemote = RecordingRemote(failOpen = true)).runShell("root", "s", "echo \"[\${INFINITY_GIT_NONCE:-}]\"", null, 10)
        assertThat(result.stdout).isEqualTo("[]\n")
    }

    @Test
    fun `真实代理只把nonce与代理地址交给子进程，令牌不进入环境且命令结束后nonce失效`() = runBlocking<Unit> {
        val token = "nxt_ShouldNeverReachTheChildProcess42"
        val secrets = object : SecretStore {
            val values = mutableMapOf<String, ByteArray>()
            override fun put(id: SecretId, value: ByteArray) { values[id.value] = value.copyOf() }
            override fun get(id: SecretId): ByteArray? = values[id.value]?.copyOf()
            override fun contains(id: SecretId): Boolean = id.value in values
            override fun remove(id: SecretId) { values.remove(id.value) }
        }
        val credentials = SecretGitCredentialStore(secrets).apply { save("github.com", "octocat", token.toByteArray()) }
        val nonces = GitNonceRegistry()
        val proxy = GitCredentialProxy(credentials, nonces, okhttp3.OkHttpClient())
        GitRemoteBroker(credentials, nonces, proxy, sshBridge = null).use { broker ->
            val result = service(gitRemote = broker).runShell("root", "s", "env", null, 10)
            val env = result.stdout.lines().filter { it.startsWith("INFINITY_GIT_") }.associate { it.substringBefore('=') to it.substringAfter('=') }
            assertThat(env.keys).containsExactly(GitRemoteBroker.ENV_NONCE, GitRemoteBroker.ENV_HTTP_PROXY, GitRemoteBroker.ENV_CREDENTIAL_HOSTS)
            assertThat(env.getValue(GitRemoteBroker.ENV_HTTP_PROXY)).isEqualTo("127.0.0.1:${proxy.port}")
            assertThat(env.getValue(GitRemoteBroker.ENV_CREDENTIAL_HOSTS)).isEqualTo("github.com")
            assertThat(result.stdout).doesNotContain(token)
            assertThat(result.stderr).doesNotContain(token)
            assertThat(result.toJson().toString()).doesNotContain(token)
            assertThat(nonces.resolve(env.getValue(GitRemoteBroker.ENV_NONCE))).isNull()
            assertThat(nonces.activeCount).isEqualTo(0)
        }
    }
}
