package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.jobs.*
import com.promenar.infinity.data.secret.SessionSecretVault
import com.promenar.infinity.data.secret.SecretOutputRedactor
import com.promenar.infinity.data.secret.SecretCapture
import com.promenar.infinity.data.git.remote.GitRemoteBroker
import com.promenar.infinity.data.git.remote.GitRemoteLease
import com.promenar.infinity.data.git.remote.GitRemoteSessions
import com.promenar.infinity.data.linux.LinuxGhConfig
import com.promenar.infinity.data.linux.LinuxLaunchBuilder
import com.promenar.infinity.data.linux.PackagedProot
import com.promenar.infinity.data.git.remote.LinuxGitEnvironment
import com.promenar.infinity.data.repository.*
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.jobs.*
import com.promenar.infinity.domain.linux.*
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.tool.*
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.ui.chat.manager.SystemPromptSections
import com.promenar.infinity.ui.chat.manager.skills.ShellExecSkill
import com.promenar.infinity.ui.chat.manager.skills.backgroundStartJson
import com.promenar.infinity.ui.chat.manager.skills.backgroundJobTools
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.workspace.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class LinuxExecIntegrationTest {
    @Test fun `前台Linux最终请求保留凭据与全部挂载信任配置`() = runBlocking<Unit> {
        val exec = safeDirectoryService()
        assertThat(shell(exec, "true").exitCode).isEqualTo(0)
        assertFinalGitEnvironment(terminalEnvironments.single())
    }

    @Test fun `后台Linux最终请求保留凭据与全部挂载信任配置`() = runBlocking<Unit> {
        val prepared = safeDirectoryService().prepareBackgroundCommand(BackgroundJobRunner.LINUX, "true", null,
            BackgroundJobLocation(workspace, workspace, true, false, sessionId = "s"), 1, "s")
        try {
            assertFinalGitEnvironment(prepared.request.environment)
        } finally { prepared.close() }
    }

    private val safeDirectoryLease = mapOf(
        GitRemoteBroker.ENV_HTTP_PROXY to "127.0.0.1:43123",
        GitRemoteBroker.ENV_NONCE to "synthetic-safe-directory-command",
        GitRemoteBroker.ENV_CREDENTIAL_HOSTS to "git.test,git.example.test",
    )

    private fun safeDirectoryService(): BuiltinExecService {
        runtime.launchBuilder = LinuxLaunchBuilder(PackagedProot(File(root, "lib")),
            File(root, "alpine").apply { mkdirs() }, File(root, "cache"), tailnetEnvironment = { emptyMap() })
        val mount = File(root, "shared").apply { mkdirs() }
        return service(mounts = listOf(ExternalMount("probe", "gitprobe-root", "real_path", mount.path, "s", 0)),
            gitRemote = GitRemoteSessions {
                object : GitRemoteLease {
                    override val environment = safeDirectoryLease
                    override fun close() = Unit
                }
            })
    }

    private fun assertFinalGitEnvironment(environment: Map<String, String>) {
        val credentials = LinuxGitEnvironment.from(safeDirectoryLease)
        val start = credentials.getValue("GIT_CONFIG_COUNT").toInt()
        val roots = listOf("/workspace", "/workspace/*", "/mnt/gitprobe-root", "/mnt/gitprobe-root/*", "/mnt/skills", "/mnt/skills/*")
        val count = start + roots.size
        assertThat(environment["GIT_CONFIG_COUNT"]).isEqualTo(count.toString())
        assertThat(environment.keys.filter { it.startsWith("GIT_CONFIG_KEY_") }).containsExactlyElementsIn((0 until count).map { "GIT_CONFIG_KEY_$it" })
        assertThat(environment.keys.filter { it.startsWith("GIT_CONFIG_VALUE_") }).containsExactlyElementsIn((0 until count).map { "GIT_CONFIG_VALUE_$it" })
        (0 until start).forEach { index ->
            assertThat(environment["GIT_CONFIG_KEY_$index"]).isEqualTo(credentials["GIT_CONFIG_KEY_$index"])
            assertThat(environment["GIT_CONFIG_VALUE_$index"]).isEqualTo(credentials["GIT_CONFIG_VALUE_$index"])
        }
        roots.forEachIndexed { index, path ->
            assertThat(environment["GIT_CONFIG_KEY_${start + index}"]).isEqualTo("safe.directory")
            assertThat(environment["GIT_CONFIG_VALUE_${start + index}"]).isEqualTo(path)
        }
    }

    @Test fun `前后台Linux都追加git配置且内置命令不追加`() = runBlocking<Unit> {
        val leaseEnvironment = mapOf(
            GitRemoteBroker.ENV_HTTP_PROXY to "127.0.0.1:43123",
            GitRemoteBroker.ENV_NONCE to "synthetic-exec-command",
            GitRemoteBroker.ENV_CREDENTIAL_HOSTS to "git.test",
        )
        var closed = 0
        val remote = GitRemoteSessions {
            object : GitRemoteLease {
                override val environment = leaseEnvironment
                override fun close() { closed++ }
            }
        }
        val exec = service(gitRemote = remote)
        val printEnvironment = "printf '%s|%s' \"\$GIT_CONFIG_COUNT\" \"\$GIT_TERMINAL_PROMPT\""
        val linux = shell(exec, printEnvironment)
        assertThat(linux.exitCode).isEqualTo(0)
        assertThat(linux.stdout).isEqualTo("3|0")
        assertThat(closed).isEqualTo(1)
        val location = BackgroundJobLocation(workspace, workspace, true, false, sessionId = "s")
        val backgroundLinux = exec.prepareBackgroundCommand(BackgroundJobRunner.LINUX, "true", null, location, 1, "s")
        try {
            (leaseEnvironment + LinuxGitEnvironment.from(leaseEnvironment)).forEach { (key, value) ->
                assertThat(backgroundLinux.request.environment[key]).isEqualTo(value)
            }
        } finally { backgroundLinux.close() }
        assertThat(closed).isEqualTo(2)
        mode = SessionRunMode.BUILTIN
        val builtin = shell(exec, printEnvironment)
        assertThat(builtin.exitCode).isEqualTo(0)
        assertThat(builtin.stdout).isEqualTo("|")
        val backgroundBuiltin = exec.prepareBackgroundCommand(BackgroundJobRunner.SHELL, "true", null, location, 1, "s")
        try {
            assertThat(backgroundBuiltin.request.environment.keys.none { it.startsWith("GIT_") }).isTrue()
            assertThat(backgroundBuiltin.request.environment[GitRemoteBroker.ENV_NONCE]).isEqualTo(leaseEnvironment[GitRemoteBroker.ENV_NONCE])
        } finally { backgroundBuiltin.close() }
        assertThat(closed).isEqualTo(4)
    }

    @Test fun `GitHub代理就绪时前后台Linux启动规格携带gh配置以挂载代理目录`() = runBlocking<Unit> {
        val leaseEnvironment = mapOf(
            GitRemoteBroker.ENV_HTTP_PROXY to "127.0.0.1:43123",
            GitRemoteBroker.ENV_NONCE to "synthetic-gh-command",
            GitRemoteBroker.ENV_CREDENTIAL_HOSTS to "github.com",
            GitRemoteBroker.ENV_GH_SOCKET to LinuxGhConfig.GUEST_SOCKET,
        )
        val remote = GitRemoteSessions {
            object : GitRemoteLease {
                override val environment = leaseEnvironment
                override fun close() = Unit
            }
        }
        val exec = service(gitRemote = remote)
        assertThat(shell(exec, "true").exitCode).isEqualTo(0)
        assertThat(runtime.specs.last().environment["GH_CONFIG_DIR"]).isEqualTo(LinuxGhConfig.GUEST_CONFIG)
        val prepared = exec.prepareBackgroundCommand(BackgroundJobRunner.LINUX, "true", null,
            BackgroundJobLocation(workspace, workspace, true, false, sessionId = "s"), 1, "s")
        try {
            assertThat(runtime.specs.last().environment["GH_CONFIG_DIR"]).isEqualTo(LinuxGhConfig.GUEST_CONFIG)
        } finally { prepared.close() }
    }

    @Test fun `无凭据的前后台Linux也禁用git交互提示`() = runBlocking<Unit> {
        val exec = service()
        assertThat(shell(exec, "printf '%s' \"\$GIT_TERMINAL_PROMPT\"").stdout).isEqualTo("0")
        val prepared = exec.prepareBackgroundCommand(BackgroundJobRunner.LINUX, "true", null,
            BackgroundJobLocation(workspace, workspace, true, false, sessionId = "s"), 1, "s")
        try {
            assertThat(prepared.request.environment["GIT_TERMINAL_PROMPT"]).isEqualTo("0")
            assertThat(prepared.request.environment).doesNotContainKey("GIT_CONFIG_COUNT")
        } finally { prepared.close() }
    }

    @Test fun `前台与后台Linux命令都传递会话唤醒收件箱`() = runBlocking<Unit> {
        val box = File(root, "wake-box").apply { mkdirs() }
        val exec = service(wakeInbox = { assertThat(it).isEqualTo("s"); box })
        assertThat(shell(exec, "true").exitCode).isEqualTo(0)
        assertThat(runtime.specs.last().wakeInbox).isEqualTo(box)
        val prepared = exec.prepareBackgroundCommand(BackgroundJobRunner.LINUX, "true", null,
            BackgroundJobLocation(workspace, workspace, true, false, sessionId = "s"), 1, "s")
        assertThat(runtime.specs.last().wakeInbox).isEqualTo(box)
        prepared.close()
    }
    @Test fun `Linux捕获关闭PTY保持stdout与stderr独立并忽略让出阈值`() = runBlocking<Unit> {
        val vault = SessionSecretVault()
        val exec = service(vault = vault)
        val jobs = manager(exec)
        val skill = ShellExecSkill(exec, jobs)
        val args = Json.parseToJsonElement("""{"command":"printf fixture-linux-secret; printf diagnostic >&2","yield_after_seconds":1,"capture_secret":{"name":"TOKEN","label":"令牌","from":"stdout"}}""").jsonObject
        val result = Json.parseToJsonElement(skill.execute(args, object : SkillExecutionContext {
            override val sessionId = "s"
            override val agentId = "a"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
        }).content).jsonObject
        assertThat(result["stdout"]?.jsonPrimitive?.content).isEqualTo("‹已捕获为 TOKEN · 20 字节›")
        assertThat(result["stderr"]?.jsonPrimitive?.content).isEqualTo("diagnostic")
        assertThat(result["stderr_merged"]?.jsonPrimitive?.boolean).isFalse()
        assertThat(vault.revealBytes("s", "TOKEN")?.toString(Charsets.UTF_8)).isEqualTo("fixture-linux-secret")
        assertThat(result).doesNotContainKey("job_id")
        val record = jobs.list("s").single()
        assertThat(record.state).isEqualTo("EXITED")
        assertThat(jobs.readLog("s", record.logWorkspacePath!!).text).doesNotContain("fixture-linux-secret")
        assertThat(record.commandResult.toString()).doesNotContain("fixture-linux-secret")
    }

    @Test fun `Linux捕获跨过自动让出阈值仍完整返回且保留原文隔离`() = runBlocking<Unit> {
        val vault = SessionSecretVault()
        val exec = service(vault = vault)
        val jobs = manager(exec)
        val result = ShellExecSkill(exec, jobs).execute(Json.parseToJsonElement("""{"command":"sleep 2; printf fixture-linux-secret; printf diagnostic >&2","timeout_seconds":5,"yield_after_seconds":1,"on_exit":"none","capture_secret":{"name":"TOKEN","label":"令牌","from":"stdout"}}""").jsonObject,
            object : SkillExecutionContext {
                override val sessionId = "s"
                override val agentId = "a"
                override val workspacePath: String? = null
                override val workspaceRootUuid = "root"
            })
        val value = Json.parseToJsonElement(result.content).jsonObject
        assertThat(result.status).isEqualTo("success")
        assertThat(value).doesNotContainKey("job_id")
        assertThat(value["stdout"]?.jsonPrimitive?.content).isEqualTo("‹已捕获为 TOKEN · 20 字节›")
        assertThat(value["stderr"]?.jsonPrimitive?.content).isEqualTo("diagnostic")
        assertThat(vault.revealBytes("s", "TOKEN")?.toString(Charsets.UTF_8)).isEqualTo("fixture-linux-secret")
        val record = jobs.list("s").single()
        assertThat(record.state).isEqualTo("EXITED")
        assertThat(jobs.readLog("s", record.logWorkspacePath!!).text).doesNotContain("fixture-linux-secret")
    }

    @Test fun `Linux文件捕获沿用guest工作目录且保留文件`() = runBlocking<Unit> {
        val vault = SessionSecretVault()
        val result = service(vault = vault).runShell("root", "s", "printf fixture-linux-file > value.txt", null, 10,
            capture = SecretCapture("TOKEN", "令牌", "file", "value.txt"))
        assertThat(result.succeeded).isTrue()
        assertThat(vault.revealBytes("s", "TOKEN")?.toString(Charsets.UTF_8)).isEqualTo("fixture-linux-file")
        assertThat(File(workspace, "value.txt").exists()).isTrue()
    }

    @Test fun `Linux Python捕获和引用注入使用guest解释器保留原代码`() = runBlocking<Unit> {
        org.junit.Assume.assumeTrue(File("/usr/bin/python3").canExecute())
        val vault = SessionSecretVault()
        val exec = service(vault = vault)
        val code = "print(\"fixture-python's-secret\")"
        val capture = exec.runPython("root", "s", code, null, 10,
            capture = SecretCapture("TOKEN", "令牌", "stdout"))
        assertThat(capture.succeeded).isTrue()
        assertThat(capture.stdout).contains("已捕获为 TOKEN")
        val output = exec.runPython("root", "s", "import os; print(os.environ['TOKEN']) # \$TOKEN", null, 10)
        assertThat(output.stdout.trim().lineSequence().last()).isEqualTo("‹保密:TOKEN›")
        assertThat(output.stdout).doesNotContain("fixture-python\'s-secret")
        val prepared = exec.prepareBackgroundCommand(BackgroundJobRunner.PYTHON, code, null,
            BackgroundJobLocation(workspace, workspace, true, false, sessionId = "s"), 1, "s")
        try {
            assertThat(prepared.request.argv.last()).startsWith("python3 -c ")
            assertThat(runner.run(prepared.request.copy(terminal = false)).stdout.trim()).isEqualTo("fixture-python's-secret")
        } finally { prepared.close() }
        val backgroundCode = "import os; print(os.environ['TOKEN']) # \$TOKEN"
        val jobs = manager(exec, vault = vault)
        val job = jobs.startBuiltin("s", "root", BackgroundJobRunner.PYTHON, backgroundCode, null, 1, BackgroundJobExitAction.NONE)
        val result = jobs.waitFor("s", job.id, 5)
        assertThat(result.record.runnerKind).isEqualTo(BackgroundJobRunner.LINUX)
        assertThat(result.record.summary).isEqualTo(backgroundCode)
        assertThat(result.tail.text.trim().lineSequence().last()).isEqualTo("‹保密:TOKEN›")
    }

    private lateinit var root: File
    private lateinit var workspace: File
    private lateinit var runtime: FakeLinuxRuntime
    private lateinit var runner: BuiltinCommandRunner
    private val terminalEnvironments = mutableListOf<Map<String, String>>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val keepKinds = mutableListOf<ExecRunKind>()
    private var homeResolutions = 0
    private var reconciles = 0
    private var mode: SessionRunMode? = SessionRunMode.LINUX

    @Before fun setUp() {
        root = Files.createTempDirectory("linux-exec").toFile()
        workspace = File(root, "workspace").apply { mkdirs() }
        runtime = FakeLinuxRuntime(root)
        runner = BuiltinCommandRunner(
            groupLauncher = listOf("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or exit 127"),
            shell = "/bin/sh",
            signaller = { group, signal -> ProcessBuilder("/bin/kill", "-$signal", "-$group").start().waitFor() },
            scratchDirectory = { File(root, "scratch") }, killGraceMillis = 50, pollMillis = 20,
            terminalFactory = { argv, directory, environment ->
                terminalEnvironments += environment.toMap()
                val process = ProcessBuilder(listOf("/usr/bin/perl", "-e", "setpgrp(0, 0); exec @ARGV or exit 127") + argv)
                    .directory(directory).redirectErrorStream(true).apply { environment().clear(); environment().putAll(environment) }.start()
                object : Process() {
                    override fun getInputStream() = process.inputStream
                    override fun getErrorStream() = process.errorStream
                    override fun getOutputStream() = object : java.io.OutputStream() {
                        override fun write(b: Int) { process.outputStream.write(if (b == 13) 10 else b) }
                        override fun flush() = process.outputStream.flush()
                        override fun close() = process.outputStream.close()
                    }
                    override fun waitFor() = process.waitFor()
                    override fun waitFor(time: Long, unit: java.util.concurrent.TimeUnit) = process.waitFor(time, unit)
                    override fun exitValue() = process.exitValue()
                    override fun destroy() = process.destroy()
                    override fun destroyForcibly(): Process = process.destroyForcibly()
                    override fun isAlive() = process.isAlive
                }
            },
        )
    }

    @After fun tearDown() = runBlocking {
        com.promenar.infinity.startup.StartupWriteBarrier.resetForTesting()
        scopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() }
        root.deleteRecursively()
        Unit
    }

    private val keepAlive = object : ExecKeepAlive {
        override suspend fun <T> hold(run: ExecRunInfo, stop: () -> Unit, block: suspend () -> T): ExecHeld<T> {
            synchronized(keepKinds) { keepKinds += run.kind }
            return ExecHeld(block(), ExecForegroundState.STARTED)
        }
    }

    private fun service(provenance: LinuxPackageProvenance? = null, mounts: List<ExternalMount> = emptyList(), host: WorkspaceCommandHost? = null, selected: com.promenar.infinity.data.workspace.SessionWorkspace = com.promenar.infinity.data.workspace.SessionWorkspace.Private,
        resolveSelected: suspend () -> com.promenar.infinity.data.workspace.SessionWorkspace = { selected },
        beginMutation: suspend (String) -> (suspend () -> Unit) = { {} },
        requestReconcile: (String) -> Unit = {}, wakeInbox: suspend (String) -> File? = { null }, gitRemote: GitRemoteSessions = GitRemoteSessions.None, vault: SessionSecretVault? = null) = BuiltinExecService(
        workspace = host ?: object : WorkspaceCommandHost {
            override suspend fun <T> runCommandInWorkspace(workspaceRootUuid: String, sessionId: String?, block: suspend (File) -> T): WorkspaceCommandOutcome<T> {
                val result = block(workspace)
                reconciles++
                return WorkspaceCommandOutcome(result, WorkspaceChangeReport(added = workspace.listFiles().orEmpty().filter { it.isFile }.map { "/${it.name}" }))
            }
        },
        runner = runner,
        environment = BuiltinExecEnvironment(PackagedPython(File(root, "absent")), File(root, "home"), File(root, "tmp"), { null }, inherited = mapOf("PATH" to "/usr/bin:/bin")),
        shell = "/bin/sh", keepAlive = keepAlive, linuxRuntime = runtime, linuxProvenance = provenance, sessionWorkspace = { resolveSelected() },
        visibleMounts = { assertThat(it).isEqualTo("s"); mounts }, sessionTitle = { "会话标题" }, sessionMode = { mode }, sessionHome = { homeResolutions++; workspace },
        beginSessionMutation = beginMutation, requestSessionHomeReconcile = requestReconcile, wakeInbox = wakeInbox, gitRemote = gitRemote, vault = vault, redactor = vault?.let(::SecretOutputRedactor),
    )

    private suspend fun shell(service: BuiltinExecService, command: String, timeout: Int = 3, live: ToolLiveOutputSink = ToolLiveOutputSink.None) =
        service.runShell("root", "s", command, null, timeout, live)


    @Test fun `Linux私有工作区命令不进入同步对账宿主`() = runBlocking<Unit> {
        val blockingHost = object : WorkspaceCommandHost {
            override suspend fun <T> runCommandInWorkspace(workspaceRootUuid: String, sessionId: String?, block: suspend (File) -> T): WorkspaceCommandOutcome<T> {
                awaitCancellation()
            }
        }
        val result = withTimeout(2_000) { shell(service(host = blockingHost), "echo fast") }
        assertThat(result.stdout.trim()).isEqualTo("fast")
        assertThat(result.toJson()["workspace_changes"]).isEqualTo(JsonNull)
    }

    @Test fun `Linux前台命令立即返回且慢对账随后完成`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reconciler = com.promenar.infinity.data.repository.SessionWorkspaceReconciler(scope) { entered.complete(Unit); release.await() }
        try {
            val result = withTimeout(2_000) { shell(service(requestReconcile = reconciler::request), "echo fast") }
            assertThat(result.stdout.trim()).isEqualTo("fast")
            withTimeout(2_000) { entered.await() }
            assertThat(reconciler.sessions.value).containsExactly("s")
            release.complete(Unit)
            withTimeout(2_000) { reconciler.awaitSettled("s") }
        } finally { release.complete(Unit) }
    }

    @Test fun `身份恢复失败仍派发索引请求且释放命令租约`() = runBlocking<Unit> {
        var requested = false
        val result = shell(service(beginMutation = { { error("身份恢复失败") } }, requestReconcile = { requested = true }), "true")
        assertThat(result.errorCode).isEqualTo("EXEC_WORKSPACE_UNAVAILABLE")
        assertThat(requested).isTrue()
        withTimeout(1_000) { com.promenar.infinity.data.repository.WorkspaceExternalMutationCoordinator.awaitIdle(workspace.toPath()) }
    }

    @Test fun `Linux自选工作区前台后台命令与环境变量使用登记目录`() = runBlocking<Unit> {
        val identity = com.promenar.infinity.data.repository.PersistentFileIdentity.encode("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ino=1")
        val selected = com.promenar.infinity.data.workspace.SessionWorkspace.Directory(
            com.promenar.infinity.data.local.db.entity.WorkspaceEntity("linux", "linux_home", "app", "/opt/app#$identity", 0), workspace)
        val exec = service(selected = selected)
        assertThat(shell(exec, "echo selected").exitCode).isEqualTo(0)
        assertThat(runtime.specs.last().workingDirectory).isEqualTo("/opt/app")
        assertThat(runtime.specs.last().environment["INFINITY_WORKSPACE"]).isEqualTo("/opt/app")
        val prepared = exec.prepareBackgroundCommand(BackgroundJobRunner.LINUX, "echo background", null,
            BackgroundJobLocation(workspace, workspace, true, false, workspaceKindOverride = "project", sessionId = "s"), 1, "s")
        assertThat(runtime.specs.last().workingDirectory).isEqualTo("/opt/app")
        assertThat(runtime.specs.last().environment["INFINITY_WORKSPACE"]).isEqualTo("/opt/app")
        prepared.close()
    }

    @Test fun `命令准备期间切换Linux目录则拒绝混合宿主根与cwd`() = runBlocking<Unit> {
        val identity = com.promenar.infinity.data.repository.PersistentFileIdentity.encode("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ino=1")
        val first = com.promenar.infinity.data.workspace.SessionWorkspace.Directory(
            com.promenar.infinity.data.local.db.entity.WorkspaceEntity("one", "linux_home", "a", "/opt/a#$identity", 0), workspace)
        val otherRoot = File(root, "other").apply { mkdir() }
        val second = first.copy(workspace = first.workspace.copy(id = "two", location = "/srv/b#$identity"), root = otherRoot)
        var count = 0
        val exec = service(resolveSelected = { if (count++ == 0) first else second })
        assertThat(shell(exec, "echo must-not-run").errorCode).isEqualTo("EXEC_WORKSPACE_UNAVAILABLE")
        assertThat(runtime.specs).isEmpty()
        val failure = runCatching { exec.prepareBackgroundCommand(BackgroundJobRunner.LINUX, "echo never", null,
            BackgroundJobLocation(workspace, workspace, false, false, sessionId = "s"), 1, "s") }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(runtime.specs).isEmpty()
        var sameRootCount = 0
        val sameRoot = service(resolveSelected = { if (sameRootCount++ == 0) first else first.copy(workspace = first.workspace.copy(id = "rebound")) })
        assertThat(shell(sameRoot, "echo never").errorCode).isEqualTo("EXEC_WORKSPACE_UNAVAILABLE")
        assertThat(runtime.specs).isEmpty()
    }

    @Test fun `前台让出保留进程直到输入完成且拒绝跨会话输入`() = runBlocking<Unit> {
        val exec = service()
        val jobs = manager(exec)
        val store = ToolLiveOutputStore()
        val live = store.open("step")
        val context = object : SkillExecutionContext {
            override val sessionId = "s"
            override val agentId = "a"
            override val workspaceRootUuid = "root"
            override val workspacePath: String? = null
            override val runMode = SessionRunMode.LINUX
            override val liveOutput = live
        }
        val skill = ShellExecSkill(exec, jobs)
        val result = skill.execute(buildJsonObject {
            put("command", "printf '\u001b[32m请输入：\u001b[0m'; read answer; printf '回答=%s\n' \"\$answer\"; touch done")
            put("yield_after_seconds", "1"); put("timeout_seconds", "2")
        }, context)
        assertThat(result.status).isEqualTo("success")
        val json = Json.parseToJsonElement(result.content).jsonObject
        assertThat(json.getValue("state").jsonPrimitive.content).isEqualTo("running")
        assertThat(json.getValue("stdout").jsonPrimitive.content).isEqualTo("请输入：")
        assertThat(json.getValue("argument_notes").jsonArray).hasSize(2)
        val id = json.getValue("job_id").jsonPrimitive.content
        assertThat(reconciles).isEqualTo(0)
        store.close("step", live)
        assertThat(store.snapshot("step")?.text).isEqualTo("请输入：")
        assertThat((runCatching { jobs.input("other", id, "bad") }.exceptionOrNull() as? JobAccessException)?.code).isEqualTo("JOB_NOT_FOUND")
        delay(1_200)
        assertThat(jobs.status("s", id).record.running).isTrue()
        val input = jobs.input("s", id, "yes")
        assertThat(input.tail.text).contains("回答=yes")
        val ended = jobs.waitFor("s", id, 5)
        assertThat(ended.record.state).isEqualTo("EXITED")
        assertThat(ended.record.commandResult?.get("workspace_changes")).isEqualTo(JsonNull)
        assertThat(reconciles).isEqualTo(0)
        assertThat(File(workspace, "done").exists()).isTrue()
        withTimeout(5_000) { while (store.snapshot("step") != null) delay(10) }
        assertThat((runCatching { jobs.input("s", id, "bad") }.exceptionOrNull() as? JobAccessException)?.code).isEqualTo("JOB_NOT_RUNNING")
    }

    @Test fun `让出阈值等于超时且调用线程繁忙仍保留进程`() = runBlocking<Unit> {
        val exec = service()
        val jobs = manager(exec)
        val task = async {
            jobs.runLinuxForeground("s", "root", "touch started; sleep 30", 1, 120, BackgroundJobExitAction.NONE, ToolLiveOutputSink.None) { attachment, live ->
                exec.runShell("root", "s", "touch started; sleep 30", null, 1, live, attachment = attachment)
            }
        }
        withTimeout(5_000) { while (!File(workspace, "started").exists()) delay(10) }
        // 调用线程繁忙不应让 runner 先把等值时限判成超时。
        Thread.sleep(1_500)
        val result = task.await()
        assertThat(result.toJson()["state"]?.jsonPrimitive?.content).isEqualTo("running")
        val id = result.toJson().getValue("job_id").jsonPrimitive.content
        assertThat(jobs.status("s", id).record.running).isTrue()
        jobs.stop("s", id)
    }

    @Test fun `预留落盘时取消不会遗留无执行者的运行记录`() = runBlocking<Unit> {
        val exec = service()
        var cancelOwner: Job? = null
        val jobs = manager(exec, jobNow = { cancelOwner?.cancel(); cancelOwner = null; System.currentTimeMillis() })
        jobs.initialize()
        val task = launch {
            cancelOwner = currentCoroutineContext()[Job]
            jobs.runLinuxForeground("s", "root", "sleep 30", 1, 120, BackgroundJobExitAction.NONE, ToolLiveOutputSink.None) { attachment, live ->
                exec.runShell("root", "s", "sleep 30", null, 30, live, attachment = attachment)
            }
        }
        withTimeout(5_000) { task.join() }
        assertThat(jobs.list("s").none { it.running }).isTrue()
        withTimeout(2_000) { jobs.stopSession("s") }
    }

    @Test fun `取消并行Linux调用只停止本次进程`() = runBlocking<Unit> {
        val exec = service()
        val jobs = manager(exec)
        suspend fun run(command: String) = jobs.runLinuxForeground("s", "root", command, 1, 120, BackgroundJobExitAction.NONE, ToolLiveOutputSink.None) { attachment, live ->
            exec.runShell("root", "s", command, null, 30, live, attachment = attachment)
        }
        val first = run("sleep 30")
        val id = first.toJson().getValue("job_id").jsonPrimitive.content
        val waiting = launch { run("sleep 30; touch should_not_run") }
        withTimeout(5_000) { while (jobs.list("s").count { it.running } < 2) delay(10) }
        delay(100)
        withTimeout(2_000) { waiting.cancelAndJoin() }
        assertThat(jobs.status("s", id).record.running).isTrue()
        assertThat(File(workspace, "should_not_run").exists()).isFalse()
        jobs.stop("s", id)
    }

    @Test fun `任务输入支持空文本EOF且写入超时返回明确错误`() = runBlocking<Unit> {
        val exec = service()
        val jobs = manager(exec)
        var slow = false
        val inputs = mutableListOf<Pair<String, Boolean>>()
        val running = jobs.runLinuxForeground("s", "root", "interactive", 1, 120, BackgroundJobExitAction.NONE, ToolLiveOutputSink.None) { attachment, _ ->
            val control = attachment.control
            control.input = { text, eof -> if (slow) Thread.sleep(30_000) else synchronized(inputs) { inputs += text to eof } }
            control.started.complete(Unit); control.yielded.complete(Unit)
            try { while (!control.stopRequested) delay(10) } finally { control.input = null }
            CommandToolResult(exitCode = 0)
        }
        val id = running.toJson().getValue("job_id").jsonPrimitive.content
        jobs.input("s", id, "", newline = false, eof = true)
        assertThat(inputs).containsExactly("" to true)
        slow = true
        assertThat((runCatching { jobs.input("s", id, "a") }.exceptionOrNull() as? JobAccessException)?.code).isEqualTo("JOB_INPUT_TIMEOUT")
        jobs.stop("s", id)
    }

    @Test fun `后台终端窗口填满重复输出后等待仍能识别下一行`() = runBlocking<Unit> {
        val exec = service()
        val jobs = manager(exec)
        val command = "i=0; while [ \"\$i\" -lt 7000 ]; do printf 'tick\\n'; i=\$((i + 1)); done; touch filled; while [ ! -f release ]; do sleep 0.05; done; printf 'tick\\n'; sleep 30"
        val running = async(ToolCallControlContext("s", "repeat-command", "repeat-step")) {
            jobs.runLinuxForeground("s", "root", command, 0, 120, BackgroundJobExitAction.NONE, ToolLiveOutputSink.None) { attachment, live ->
                exec.runShell("root", "s", command, null, 30, live, attachment = attachment)
            }
        }
        withTimeout(5_000) { ToolExecutionControls.observe("s", "repeat-command").first { it != null } }
        ToolExecutionControls.moveToBackground("s", "repeat-command")
        val id = running.await().toJson().getValue("job_id").jsonPrimitive.content
        withTimeout(5_000) { while (!File(workspace, "filled").exists()) delay(10) }
        val waiting = async(ToolCallControlContext("s", "repeat-wait", "repeat-wait-step")) { jobs.waitFor("s", id, 5, untilOutput = "^tick$") }
        withTimeout(5_000) { ToolExecutionControls.observe("s", "repeat-wait").first { it != null } }
        File(workspace, "release").createNewFile()
        assertThat(waiting.await().waitReason).isEqualTo("output")
        jobs.stop("s", id)
    }

    @Test fun `目录准备前转后台后运行记录在目录就绪时及时更新`() = runBlocking<Unit> {
        val exec = service()
        val jobs = manager(exec)
        val prepare = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val result = async(ToolCallControlContext("s", "prepare-call", "prepare-step")) {
            jobs.runLinuxForeground("s", "root", "preparing", 0, 120, BackgroundJobExitAction.NONE, ToolLiveOutputSink.None) { attachment, _ ->
                prepare.await()
                attachment.onDirectory(workspace)
                published.complete(Unit)
                while (!attachment.control.stopRequested) delay(10)
                CommandToolResult(exitCode = 0)
            }
        }
        withTimeout(5_000) { ToolExecutionControls.observe("s", "prepare-call").first { it != null } }
        ToolExecutionControls.moveToBackground("s", "prepare-call")
        val id = withTimeout(5_000) { result.await() }.toJson().getValue("job_id").jsonPrimitive.content
        prepare.complete(Unit)
        withTimeout(5_000) { published.await() }
        assertThat(jobs.status("s", id).record.workspaceDirectory).isEqualTo(workspace.path)
        jobs.stop("s", id)
    }

    @Test fun `零自动让出时用户可转后台并保留命令进程`() = runBlocking<Unit> {
        val exec = service()
        val jobs = manager(exec)
        val result = async(ToolCallControlContext("s", "manual-call", "step-manual")) {
            jobs.runLinuxForeground("s", "root", "sleep 30", 0, 120, BackgroundJobExitAction.NONE, ToolLiveOutputSink.None) { attachment, live ->
                exec.runShell("root", "s", "sleep 30", null, 30, live, attachment = attachment)
            }
        }
        withTimeout(5_000) { ToolExecutionControls.observe("s", "manual-call").first { it != null } }
        assertThat(ToolExecutionControls.moveToBackground("s", "manual-call")).isTrue()
        val id = withTimeout(5_000) { result.await() }.toJson().getValue("job_id").jsonPrimitive.content
        assertThat(jobs.status("s", id).record.running).isTrue()
        jobs.stop("s", id)
    }

    @Test fun `插队在自动阈值之前让出前台命令`() = runBlocking<Unit> {
        val exec = service()
        val jobs = manager(exec)
        val queue = kotlinx.coroutines.flow.MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
        val result = async(SteeringContext(queue)) {
            jobs.runLinuxForeground("s", "root", "sleep 30", 0, 120, BackgroundJobExitAction.NONE, ToolLiveOutputSink.None) { attachment, live ->
                exec.runShell("root", "s", "sleep 30", null, 30, live, attachment = attachment)
            }
        }
        queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
            com.promenar.infinity.data.chat.queue.QueuedMessage("q", "steer", createdAt = 0, steering = true)))
        val id = withTimeout(5_000) { result.await() }.toJson().getValue("job_id").jsonPrimitive.content
        assertThat(jobs.status("s", id).record.running).isTrue()
        jobs.stop("s", id)
    }

    @Test fun `零让出阈值按原超时结束且返回前台结果`() = runBlocking<Unit> {
        val exec = service()
        val jobs = manager(exec)
        val result = jobs.runLinuxForeground("s", "root", "sleep 30", 0, 120, BackgroundJobExitAction.NONE, ToolLiveOutputSink.None) { attachment, live ->
            exec.runShell("root", "s", "sleep 30", null, 1, live, attachment = attachment)
        }
        assertThat(result.errorCode).isEqualTo("EXEC_TIMEOUT")
        assertThat(result.toJson()).doesNotContainKey("job_id")
        assertThat(reconciles).isEqualTo(0)
    }

    @Test fun `隔离工作目录的前后台Linux命令均在生成启动规格前拒绝`() = runBlocking {
        val manager = com.promenar.infinity.startup.StartupIssueManager()
        com.promenar.infinity.startup.StartupWriteBarrier.install(manager)
        manager.bindRoot("root", workspace.canonicalPath, "s")
        manager.recover(com.promenar.infinity.startup.StartupStep.WORKSPACE_JOURNAL,
            com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.WORKSPACE_ROOT, "root", "工作区")) { error("冲突") }
        val service = service()
        assertThat(runCatching { shell(service, "touch blocked") }.exceptionOrNull())
            .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
        assertThat(runCatching { service.prepareBackgroundCommand(BackgroundJobRunner.LINUX, "touch blocked", null,
            BackgroundJobLocation(workspace, workspace, false, false), 1, "s") }.exceptionOrNull())
            .isInstanceOf(com.promenar.infinity.startup.StartupIsolationException::class.java)
        assertThat(runtime.specs).isEmpty()
        assertThat(File(workspace, "blocked").exists()).isFalse()
    }

    @Test fun `宿主身份包装在 guest PATH 下仍可落盘并保持 guest 环境`() {
        val stat = File(root, "stat").apply { writeText("1 (shell) " + (listOf("S") + List(18) { "0" } + "123").joinToString(" ")) }
        val pid = File(root, "pid")
        val request = BuiltinCommandRequest(listOf("/bin/sh", "-c", "printf '%s' \"\$PATH\""), workspace, mapOf("PATH" to "/guest/bin"), timeoutSeconds = 3)
        // 固定 stat 验证身份落盘；同名函数模拟宿主 shell 没有 printf 内建且 guest PATH 不提供宿主工具。
        val command = runner.launchCommand(request, pid, true).map {
            if (it.contains("__infinity_stat=")) "printf() { return 127; }; " + it.replace("/proc/\$\$/stat", "'${stat.path}'") else it
        }
        assertThat(command.dropLast(request.argv.size).takeLast(3).all { File(it).isAbsolute && File(it).canExecute() }).isTrue()
        fun execute(argv: List<String>): Pair<Int, String> {
            val process = ProcessBuilder(argv).redirectErrorStream(true).apply { environment().clear(); environment().putAll(request.environment) }.start()
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() to output
        }
        val broken = command.map { if(it.contains("__infinity_stat=")) it.replace("\"\$4\"", "cat").replace("\"\$5\"", "mv") else it }
        assertThat(execute(broken).first).isEqualTo(125)
        val missingPrintf = command.map {
            if(it.contains("__infinity_stat=")) it.replace("__infinity_printf=\$6", "__infinity_printf=printf").replace("\"\$6\"", "printf") else it
        }
        assertThat(execute(missingPrintf).first).isEqualTo(125)
        val result = execute(command)
        assertThat(result.first).isEqualTo(0)
        assertThat(result.second).isEqualTo("/guest/bin")
        assertThat(File(pid.path + ".identity").readText().lines().last()).isEqualTo("123")
    }

    @Test fun `Linux 启动规格绑定工作区真实挂载和技能且不泄漏系统 Python 环境`() = runBlocking<Unit> {
        val mount = File(root, "mounted").apply { mkdirs() }
        val mounts = listOf(
            ExternalMount("p", "资料", "real_path", mount.path, "s", 0),
            ExternalMount("saf", "saf", "saf_tree", "content://tree", "s", 0),
            ExternalMount("gone", "gone", "real_path", mount.path, "s", 0, "撤权"),
            ExternalMount(ExternalMountPaths.SKILLS_MOUNT_ID, "skills", ExternalMountPaths.SKILLS_SOURCE, "", "s", 0),
        )
        val result = shell(service(mounts = mounts), "printf '%s' \"\$INFINITY_WORKSPACE\"; echo ok > out.txt")
        val spec = runtime.specs.single()
        assertThat(spec.argv).containsExactly("/bin/sh", "-c", "printf '%s' \"\$INFINITY_WORKSPACE\"; echo ok > out.txt").inOrder()
        assertThat(spec.workspace).isEqualTo(workspace)
        assertThat(spec.sessionHome).isEqualTo(workspace)
        assertThat(homeResolutions).isEqualTo(1)
        assertThat(spec.mounts.map { it.guestPath }).containsExactly("/mnt/资料", "/mnt/skills")
        assertThat(spec.mounts.first().host).isEqualTo(mount)
        assertThat(spec.environment).containsEntry("INFINITY_SKILLS", "/mnt/skills")
        assertThat(spec.environment).doesNotContainKey("INFINITY_PYTHON")
        assertThat(result.stdout).isEqualTo("/workspace")
        assertThat(result.toJson()["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
        assertThat(result.toJson()["workspace_changes"]).isEqualTo(JsonNull)
        assertThat(keepKinds).containsExactly(ExecRunKind.LINUX)
        assertThat(reconciles).isEqualTo(0)
    }

    @Test fun `Linux 会话启动前不可用时明确失败且不会执行系统 shell`() = runBlocking<Unit> {
        runtime.unavailableOnPrepare = true
        val result = shell(service(), "echo forbidden > fallback.txt")
        assertThat(result.errorCode).isEqualTo("EXEC_LINUX_UNAVAILABLE")
        assertThat(result.toJson()["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
        assertThat(result.toJson()).doesNotContainKey("runtime_fallback")
        assertThat(File(workspace, "fallback.txt").exists()).isFalse()
    }

    @Test fun `Linux 会话在每一种不可用状态均拒绝执行且不回退`() = runBlocking<Unit> {
        states().filterNot { it is LinuxRuntimeState.Ready }.forEach { state ->
            runtime.state.value = state
            val result = shell(service(), "touch forbidden")
            assertThat(result.errorCode).isEqualTo("EXEC_LINUX_UNAVAILABLE")
            assertThat(result.toJson()["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
        }
        assertThat(File(workspace, "forbidden").exists()).isFalse()
        assertThat(runtime.specs).isEmpty()
    }

    @Test fun `内置会话不随 Linux 状态或默认设置切换`() = runBlocking<Unit> {
        mode = SessionRunMode.BUILTIN
        for (state in states()) {
            runtime.state.value = state
            for (enabled in listOf(false, true)) {
                runtime.agentEnabled.value = enabled
                assertThat(shell(service(), "printf builtin").stdout).isEqualTo("builtin")
            }
        }
        assertThat(runtime.specs).isEmpty()
    }

    @Test fun `未确定模式拒绝Python而Linux会话使用guest解释器`() = runBlocking<Unit> {
        mode = null
        assertThat(shell(service(), "touch forbidden").errorCode).isEqualTo("EXEC_RUN_MODE_REQUIRED")
        assertThat(service().runPython("root", "s", "print(1)", null, 1).errorCode).isEqualTo("EXEC_RUN_MODE_REQUIRED")
        mode = SessionRunMode.LINUX
        assertThat(service().runPython("root", "s", "print(1)", null, 10).stdout.trim().lineSequence().last()).isEqualTo("1")
        assertThat(File(workspace, "forbidden").exists()).isFalse()
    }

    @Test fun `Linux 会话不随新会话默认模式改用系统`() = runBlocking<Unit> {
        runtime.agentEnabled.value = false
        assertThat(shell(service(), "printf linux").toJson()["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
        assertThat(runtime.specs).hasSize(1)
    }

    @Test fun `审批后模式改变不得在另一种环境执行命令`() = runBlocking<Unit> {
        for ((current, expected) in listOf(SessionRunMode.LINUX to SessionRunMode.BUILTIN, SessionRunMode.BUILTIN to SessionRunMode.LINUX)) {
            mode = current
            val result = service().runShell("root", "s", "touch forbidden", null, 1, expectedMode = expected)
            assertThat(result.errorCode).isEqualTo("EXEC_MODE_CHANGED")
        }
        assertThat(File(workspace, "forbidden").exists()).isFalse()
        assertThat(runtime.specs).isEmpty()
    }

    @Test fun `后台任务在审批后切换模式时拒绝预留或启动`() = runBlocking<Unit> {
        mode = SessionRunMode.LINUX
        val jobs = manager(service())
        val failure = runCatching { jobs.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "touch forbidden", null, 1, BackgroundJobExitAction.NONE, expectedMode = SessionRunMode.BUILTIN) }.exceptionOrNull()
        assertThat((failure as? JobAccessException)?.code).isEqualTo("EXEC_MODE_CHANGED")
        assertThat(jobs.list("s")).isEmpty()
        assertThat(File(workspace, "forbidden").exists()).isFalse()
    }

    @Test fun `Linux 输出保留 guest 工作区并虚拟化宿主路径及实时输出`() = runBlocking<Unit> {
        val live = StringBuilder()
        val sink = object : ToolLiveOutputSink by ToolLiveOutputSink.None {
            override fun emit(stream: ToolOutputStream, text: String) { synchronized(live) { live.append(text) } }
            override fun replace(stream: ToolOutputStream, text: String) { synchronized(live) { live.clear(); live.append(text) } }
        }
        val result = shell(service(), "printf '/workspace/out.txt\\n'; pwd", live = sink)
        assertThat(result.stdout).isEqualTo("/workspace/out.txt\n\$INFINITY_WORKSPACE\n")
        assertThat(live.toString()).isEqualTo(result.stdout)
        assertThat(result.stdout).doesNotContain(workspace.path)
    }

    @Test fun `软件包来源在命令前后记录失败退出仍归属当前会话`() = runBlocking<Unit> {
        val provenance = RecordingProvenance(workspace)
        val result = shell(service(provenance), "touch installed; exit 7")
        assertThat(result.exitCode).isEqualTo(7)
        assertThat(provenance.events).containsExactly("before", "after", "attribute").inOrder()
        assertThat(provenance.origin).isEqualTo(LinuxPackageOrigin.Agent("s", "会话标题"))
    }

    @Test fun `来源快照和归属失败不改变工具结果且系统路径不调用来源记录`() = runBlocking<Unit> {
        for (stage in listOf("before", "after", "attribute")) {
            File(workspace, "installed").delete()
            val provenance = RecordingProvenance(workspace, stage)
            assertThat(shell(service(provenance), "touch installed; printf ok").stdout).isEqualTo("ok")
            assertThat(provenance.events).contains("after")
        }
        val provenance = RecordingProvenance(workspace)
        mode = SessionRunMode.BUILTIN
        shell(service(provenance), "true")
        assertThat(provenance.events).isEmpty()
    }

    @Test fun `没有新增包或起始快照不可用时只采集两次快照`() = runBlocking<Unit> {
        val unchanged = RecordingProvenance(workspace)
        assertThat(shell(service(unchanged), "true").exitCode).isEqualTo(0)
        assertThat(unchanged.events).containsExactly("before", "after").inOrder()
        var snapshots = 0
        var attributions = 0
        val missing = object : LinuxPackageProvenance {
            override suspend fun worldSnapshot(): Set<String>? = if (snapshots++ == 0) null else setOf("git")
            override suspend fun attributeNewPackages(before: Set<String>, origin: LinuxPackageOrigin) { attributions++ }
        }
        assertThat(shell(service(missing), "true").exitCode).isEqualTo(0)
        assertThat(snapshots).isEqualTo(2)
        assertThat(attributions).isEqualTo(0)
    }

    @Test fun `超时和用户停止执行结束快照且不等待索引对账`() = runBlocking<Unit> {
        val provenance = RecordingProvenance(workspace)
        val result = shell(service(provenance), "touch installed; sleep 30", timeout = 1)
        assertThat(result.errorCode).isEqualTo("EXEC_TIMEOUT")
        assertThat(result.guidance).contains("已安排后台整理")
        assertThat(result.guidance).doesNotContain("已按当前磁盘状态对账")
        assertThat(provenance.events).containsExactly("before", "after", "attribute").inOrder()
        assertThat(reconciles).isEqualTo(0)
        val stopped = shell(service(), "sleep 30", live = object : ToolLiveOutputSink by ToolLiveOutputSink.None {
            override fun emit(stream: ToolOutputStream, text: String) = Unit
            override fun onStop(stop: () -> Unit) = stop()
        })
        assertThat(stopped.errorCode).isEqualTo("EXEC_STOPPED")
    }

    @Test fun `取消命令也完成软件包归属的收尾`() = runBlocking<Unit> {
        val provenance = RecordingProvenance(workspace)
        val task = launch(Dispatchers.IO) { shell(service(provenance), "touch installed; sleep 30") }
        withTimeout(5000) { while (!File(workspace, "installed").exists()) delay(10) }
        task.cancelAndJoin()
        assertThat(provenance.events).containsExactly("before", "after", "attribute").inOrder()
    }

    @Test fun `分类与审批目标区分 Linux 但所有工作区命令仍逐条批准`() {
        val tool = ProtocolTool(function = ProtocolToolFunction("shell_exec", "", "{}"), risk = ToolRisk.PROCESS, sourceId = "builtin")
        for (linux in listOf(true, false)) {
            assertThat(CommandToolClassification.backendFor("shell_exec", linux)).isEqualTo(if(linux) CommandToolBackend.LINUX else CommandToolBackend.BUILTIN_SHELL)
            assertThat(CommandToolClassification.backendFor("python_exec", linux)).isEqualTo(if (linux) CommandToolBackend.LINUX else CommandToolBackend.BUILTIN_PYTHON)
            assertThat(ToolExecutionTargets.extract(tool, """{"command":"echo ok"}""", usesLinux = linux)).isEqualTo("""["process","${if(linux) "linux" else "builtin"}","echo ok"]""")
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.WORKSPACE, "shell_exec", ToolRisk.PROCESS, usesLinux = linux)).isTrue()
            assertThat(ToolExecutionPolicy.requiresApproval(ExecutionMode.FULL_ACCESS, "shell_exec", ToolRisk.PROCESS, usesLinux = linux)).isFalse()
        }
    }

    @Test fun `提示词按固定会话模式而不按实时状态选择后端`() {
        for (state in states()) {
            val linux = SystemPromptSections.toolGuidance(setOf("shell_exec"), generic = false, linuxState = state, runMode = SessionRunMode.LINUX)
            // Linux 环境细节只随 shell_exec 工具说明发送，系统提示给出指向。
            assertThat(linux).contains(SystemPromptSections.LINUX_SHELL_POINTER)
            listOf("apk add", "/workspace", "/home/session", "/mnt/<别名>", "/root", "元数据密集").forEach { assertThat(SystemPromptSections.LINUX_SHELL_GUIDANCE).contains(it) }
            assertThat(linux).doesNotContain("当前使用系统")
            val builtin = SystemPromptSections.toolGuidance(setOf("shell_exec", "python_exec"), generic = false, linuxState = state, runMode = SessionRunMode.BUILTIN)
            assertThat(builtin).contains("/system/bin/sh")
            assertThat(builtin).doesNotContain("Alpine Linux（musl）")
        }
    }

    @Test fun `可达范围列出 Linux 状态和跳转标识`() {
        val expected = listOf(ReachBackendStatus.UNAVAILABLE, ReachBackendStatus.NOT_INSTALLED, ReachBackendStatus.INSTALLING, ReachBackendStatus.BROKEN, ReachBackendStatus.READY, ReachBackendStatus.INSTALLING, ReachBackendStatus.BROKEN)
        // Play 渠道仍列出 Linux 行，状态为渠道不可用（界面灰显并说明原因）；其它不可用原因（如 ABI）显示为不可用。
        assertThat(reachBackends(true, false, null, 0, states().first(), true).single { it.kind == ReachBackendKind.LINUX }.status)
            .isEqualTo(ReachBackendStatus.CHANNEL_UNAVAILABLE)
        val abi = LinuxRuntimeState.Unavailable(LinuxUnavailableReason.UNSUPPORTED_ABI)
        (listOf(abi) + states().drop(1)).zip(expected).forEach { (state, status) ->
            val backend = reachBackends(true, false, null, 0, state, true).single { it.kind == ReachBackendKind.LINUX }
            assertThat(backend.status).isEqualTo(status)
            assertThat(backend.destination).isEqualTo("linux_environment")
        }
        assertThat(linuxReachStatus(readyState(), false)).isEqualTo(ReachBackendStatus.DISABLED)
    }

    private fun manager(exec: BuiltinExecService, jobNow: () -> Long = System::currentTimeMillis, vault: SessionSecretVault? = null, beforePrepare: suspend () -> Unit = {}): BackgroundJobManager {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return BackgroundJobManager(scope, BackgroundJobStore(File(root,"store")), File(root,"runs"), runner,
            prepareWorkspace = { BackgroundJobLocation(workspace, workspace, false, false) },
            prepareCommand = { kind, source, stdin, location, minutes -> beforePrepare(); exec.prepareBackgroundCommand(kind, source, stdin, location, minutes, location.sessionId) },
            keepAlive = keepAlive, linuxRuntime = runtime, sessionMode = { mode }, now = jobNow,
            vault = vault, redactor = vault?.let(::SecretOutputRedactor),
        )
    }

    @Test fun `后台 shell 创建时固定 Linux 执行器并归属软件包`() = runBlocking<Unit> {
        val provenance = RecordingProvenance(workspace)
        val manager = manager(service(provenance))
        val record = manager.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "touch installed; echo background", null, 1, BackgroundJobExitAction.NONE)
        assertThat(record.runnerKind).isEqualTo(BackgroundJobRunner.LINUX)
        assertThat(backgroundStartJson(record)["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
        val result = manager.waitFor("s", record.id, 5)
        assertThat(result.record.state).isEqualTo("EXITED")
        assertThat(result.tail.text).contains("background")
        assertThat(keepKinds).containsExactly(ExecRunKind.LINUX)
        assertThat(provenance.origin).isEqualTo(LinuxPackageOrigin.Agent("s", "会话标题"))
        val context = object : SkillExecutionContext {
            override val sessionId = "s"
            override val agentId = "agent"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
        }
        val status = backgroundJobTools(manager).first { it.id == "job_status" }.execute(buildJsonObject { put("job_id",record.id) }, context)
        assertThat(Json.parseToJsonElement(status.content).jsonObject["runtime"]?.jsonPrimitive?.content).isEqualTo("linux")
    }

    @Test fun `后台环境在创建后启动前卸载则失败且不回退系统`() = runBlocking<Unit> {
        val manager = manager(service()) { runtime.uninstall() }
        assertThat(runCatching { manager.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "touch forbidden", null, 1, BackgroundJobExitAction.NONE) }.exceptionOrNull()).isInstanceOf(LinuxUnavailableException::class.java)
        val record = manager.list("s").single()
        assertThat(record.runnerKind).isEqualTo(BackgroundJobRunner.LINUX)
        assertThat(record.state).isEqualTo("FAILED")
        assertThat(record.guidance).contains("Linux 环境")
        assertThat(File(workspace,"forbidden").exists()).isFalse()
    }

    @Test fun `后台执行期间卸载停止整个任务并提供可读失败原因`() = runBlocking<Unit> {
        val provenance = RecordingProvenance(workspace)
        val manager = manager(service(provenance))
        val record = manager.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "touch installed; sleep 30", null, 1, BackgroundJobExitAction.NONE)
        withTimeout(5000) { while(!File(workspace,"installed").exists()) delay(10) }
        runtime.uninstall()
        val result = manager.waitFor("s", record.id, 5)
        assertThat(result.record.state).isEqualTo("FAILED")
        assertThat(result.record.guidance).contains("Linux 环境当前不可用")
        assertThat(provenance.events).containsExactly("before", "after", "attribute").inOrder()
    }

    @Test fun `后台内置会话在 Linux 可用时仍使用系统执行器`() = runBlocking<Unit> {
        mode = SessionRunMode.BUILTIN
        val manager = manager(service())
        val record = manager.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "echo system", null, 1, BackgroundJobExitAction.NONE)
        assertThat(record.runnerKind).isEqualTo(BackgroundJobRunner.SHELL)
        assertThat(manager.waitFor("s", record.id, 5).record.state).isEqualTo("EXITED")
        assertThat(runtime.specs).isEmpty()
    }

    private class RecordingProvenance(private val workspace: File, private val failAt: String? = null) : LinuxPackageProvenance {
        val events = mutableListOf<String>()
        var origin: LinuxPackageOrigin? = null
        override suspend fun worldSnapshot(): Set<String> {
            val stage = if(events.isEmpty()) "before" else "after"
            events += stage
            if(failAt == stage) error("来源记录失败")
            return if(File(workspace,"installed").exists()) setOf("base", "git") else setOf("base")
        }
        override suspend fun attributeNewPackages(before: Set<String>, origin: LinuxPackageOrigin) {
            events += "attribute"
            assertThat(before).containsExactly("base")
            if(failAt == "attribute") error("归属失败")
            this.origin = origin
        }
    }
}

private fun readyState() = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "test", "host", "test", 0, null))
private fun states(): List<LinuxRuntimeState> {
    val progress = LinuxInstallProgress(emptyList(), null)
    return listOf(LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION), LinuxRuntimeState.NotInstalled(0), LinuxRuntimeState.Installing(progress), LinuxRuntimeState.InstallInterrupted(progress, null), readyState(), LinuxRuntimeState.Maintaining(LinuxMaintenance.UNINSTALLING), LinuxRuntimeState.Broken(LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED)))
}

private class FakeLinuxRuntime(root: File) : LinuxRuntime {
    override val state = MutableStateFlow<LinuxRuntimeState>(readyState())
    override val agentEnabled = MutableStateFlow(true)
    override val paths = LinuxPaths(File(root,"rootfs"), File(root,"rootfs/root"))
    val specs = mutableListOf<LinuxLaunchSpec>()
    var unavailableOnPrepare = false
    var launchBuilder: LinuxLaunchBuilder? = null
    override suspend fun setAgentEnabled(enabled: Boolean) { agentEnabled.value = enabled }
    override fun startInstall(options: LinuxInstallOptions) = Unit
    override fun cancelInstall() = Unit
    override suspend fun storageUsage() = LinuxStorageUsage(0,0,0,0)
    override suspend fun clearDownloadCache() = 0L
    override suspend fun runDiagnostics() = LinuxDiagnostics(true,0,0, emptyList())
    override suspend fun reset() = Unit
    override suspend fun uninstall() { state.value = LinuxRuntimeState.NotInstalled(0) }
    override suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch {
        if(unavailableOnPrepare || state.value !is LinuxRuntimeState.Ready) throw LinuxUnavailableException(LinuxRuntimeState.NotInstalled(0))
        specs += spec
        val environment = launchBuilder?.build(spec, maintainHomeConfiguration = false)?.environment
            ?: (mapOf("PATH" to "/usr/bin:/bin") + spec.environment)
        return LinuxProcessLaunch("/bin/sh", listOf("-c",spec.argv.last()), environment, checkNotNull(spec.workspace))
    }
}
