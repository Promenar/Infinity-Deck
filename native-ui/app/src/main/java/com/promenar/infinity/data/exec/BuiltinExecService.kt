package com.promenar.infinity.data.exec

import com.promenar.infinity.data.secret.SessionSecretVault
import com.promenar.infinity.data.secret.SecretOutputRedactor
import com.promenar.infinity.data.secret.SecretCapture
import com.promenar.infinity.domain.secret.SecretIoLimits
import com.promenar.infinity.data.git.remote.GitRemoteSessions
import com.promenar.infinity.data.git.remote.LinuxGitEnvironment
import com.promenar.infinity.data.repository.WorkspaceChangeReport
import com.promenar.infinity.data.repository.WorkspaceCommandHost
import com.promenar.infinity.data.repository.WorkspaceCommandOutcome
import com.promenar.infinity.data.repository.isGitInternalWorkspacePath
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.domain.tool.CommandTruncation
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import com.promenar.infinity.domain.linux.LinuxRuntime
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.linux.LinuxPackageProvenance
import com.promenar.infinity.domain.linux.LinuxUnavailableException
import com.promenar.infinity.domain.storage.ExternalMount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 内置执行后端：命令以 Infinity Workbench uid 直接在会话工作区物理根内运行。内置模式的私有工作区期间持有工作区写锁，结束后对账索引；Linux 模式在结束后合并调度私有根对账；
 * 自选目录工作区不进 Room，直接在该目录运行，不持锁、不对账，结果不含 `workspace_changes`。
 * 命令可读写应用私有目录并访问网络，不构成隔离；调用方按执行模式审批。
 * 运行期间经 [keepAlive] 持有唤醒锁与前台服务通知，通知的停止按钮结束整个进程组并返回 `EXEC_STOPPED`。
 */
class BuiltinExecService(
    private val workspace: WorkspaceCommandHost,
    private val runner: BuiltinCommandRunner,
    private val environment: BuiltinExecEnvironment,
    private val shell: String = "/system/bin/sh",
    private val unavailablePythonAbi: () -> String = { "unknown" },
    private val keepAlive: ExecKeepAlive = NoExecKeepAlive,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    /** 会话当前工作区；默认一律为私有工作区。 */
    private val sessionWorkspace: suspend (sessionId: String?) -> SessionWorkspace = { SessionWorkspace.Private },
    /** 内置 git 的远端认证作用域：每条命令签发一次性 nonce，命令结束（含超时、取消、异常）即吊销。 */
    private val gitRemote: GitRemoteSessions = GitRemoteSessions.None,
    private val runtimeDataMonitor: RuntimeDataMonitor? = null,
    private val linuxRuntime: LinuxRuntime? = null,
    linuxProvenance: LinuxPackageProvenance? = null,
    private val visibleMounts: suspend (String) -> List<ExternalMount> = { emptyList() },
    private val sessionTitle: suspend (String) -> String? = { null },
    private val sessionMode: suspend (String?) -> SessionRunMode? = { SessionRunMode.BUILTIN },
    private val sessionHome: suspend (String) -> File? = { null },
    private val requestSessionHomeReconcile: (String) -> Unit = {},
    private val beginSessionMutation: suspend (String) -> (suspend () -> Unit) = { {} },
    private val messages: RunModeMessages = RunModeMessages(),
    /** 分支保留创建 worktree 时的父挂载；解析失败须抛出异常，null 只表示普通会话。 */
    private val linuxLaunchBinding: suspend (sessionId: String) -> com.promenar.infinity.data.branch.BranchLinuxCommandWorkspace? = { null },
    private val wakeInbox: suspend (String) -> File? = { null },
    private val vault: SessionSecretVault? = null,
    redactor: SecretOutputRedactor? = null,
) {
    private val redactor = redactor ?: vault?.let(::SecretOutputRedactor)
    suspend fun runModeForSession(sessionId: String?): SessionRunMode? = sessionMode(sessionId)

    val pipAvailable: Boolean get() = environment.pipAvailable
    private val packageProvenance = LinuxExecProvenance(linuxProvenance)

    /** [gitEnvironment] 需进入启动规格：gh 代理目录是否挂载由启动规格中的 `GH_CONFIG_DIR` 决定。 */
    private suspend fun linuxRequest(root: File, kind: String, sessionId: String?, source: String, stdin: String?, seconds: Int, expectedWorkspace: SessionWorkspace? = null,
                                     gitEnvironment: Map<String, String> = emptyMap()): BuiltinCommandRequest = withContext(Dispatchers.IO) {
        val system = environment.forWorkspace(root, kind)
        // 私有主工作区直接复用已解析的根；Linux 命令不等待全量索引扫描。
        val home = if (kind == SessionWorkspaceResolver.KIND_PRIVATE) root else sessionId?.let { sessionHome(it) }
            ?: throw IllegalStateException("会话专用空间不可用")
        val launchBinding = sessionId?.let { linuxLaunchBinding(it) }
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = root.canonicalPath)
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = home.canonicalPath)
        launchBinding?.let { com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, path = it.baseRoot.canonicalPath) }
        val selected = if (launchBinding == null) sessionWorkspace(sessionId) else null
        if (selected is SessionWorkspace.Unavailable || selected is SessionWorkspace.Directory && selected.root.canonicalFile != root.canonicalFile ||
            launchBinding == null && expectedWorkspace != null && selected != expectedWorkspace) {
            throw SecurityException("会话工作区正在变化，请重新执行文件操作")
        }
        val workspaceDirectory = (selected as? SessionWorkspace.Directory)?.takeIf {
            it.kind == com.promenar.infinity.data.local.db.entity.WorkspaceEntity.KIND_LINUX_HOME
        }?.let { com.promenar.infinity.data.workspace.LinuxWorkspaceLocation.workingDirectory(it.workspace) }
        val spec = linuxExecSpec(source, root, kind, sessionId?.let { visibleMounts(it) }.orEmpty(), File(system.getValue("INFINITY_SKILLS")), home, launchBinding, workspaceDirectory,
            wakeInbox = sessionId?.let { wakeInbox(it) })
        val launch = checkNotNull(linuxRuntime).prepareLaunch(spec.copy(environment = spec.environment + gitEnvironment))
        BuiltinCommandRequest(launch.command, launch.hostWorkingDirectory, launch.environment + ("TERM" to "xterm-256color"), stdin, seconds, terminal = true)
    }

    suspend fun runShell(
        workspaceRootUuid: String,
        sessionId: String?,
        command: String,
        stdin: String?,
        timeoutSeconds: Int,
        live: ToolLiveOutputSink = ToolLiveOutputSink.None,
        expectedMode: SessionRunMode? = null,
        attachment: LinuxExecAttachment? = null,
        capture: SecretCapture? = null,
    ): CommandToolResult {
        val mode = sessionMode(sessionId) ?: return modeRequired()
        if (expectedMode != null && mode != expectedMode) return modeChanged()
        return execute(workspaceRootUuid, sessionId, timeoutSeconds, if (mode == SessionRunMode.LINUX) ExecRunKind.LINUX else ExecRunKind.SHELL, command, live, stdin, attachment, capture) { root, kind ->
            BuiltinCommandRequest(
                argv = listOf(shell, "-c", environment.shellPrelude() + "\n__infinity_command=\"\$1\"; shift; eval \"\$__infinity_command\"", "sh", command),
                workingDirectory = root,
                environment = environment.forWorkspace(root, kind),
                stdin = stdin,
                timeoutSeconds = timeoutSeconds,
            )
        }
    }

    suspend fun runPython(
        workspaceRootUuid: String,
        sessionId: String?,
        code: String,
        stdin: String?,
        timeoutSeconds: Int,
        live: ToolLiveOutputSink = ToolLiveOutputSink.None,
        expectedMode: SessionRunMode? = null,
        capture: SecretCapture? = null,
        attachment: LinuxExecAttachment? = null,
    ): CommandToolResult {
        val mode = sessionMode(sessionId)
        if (expectedMode != null && mode != expectedMode) return modeChanged()
        when (mode) {
            null -> return modeRequired()
            SessionRunMode.LINUX -> return execute(workspaceRootUuid, sessionId, timeoutSeconds, ExecRunKind.LINUX,
                code, live, stdin = stdin, attachment = attachment, capture = capture, linuxSource = linuxPythonCommand(code)) { _, _ ->
                error("Linux Python 使用 guest 启动路径")
            }
            SessionRunMode.BUILTIN -> Unit
        }
        if (!environment.pythonAvailable) {
            return CommandToolResult(
                errorCode = "EXEC_PYTHON_UNAVAILABLE",
                guidance = "当前设备 ABI（${unavailablePythonAbi()}）没有内置 Python，内置 Python 只随 arm64-v8a 发行；" +
                    "请改用 termux_exec 或 ssh_exec。shell_exec 仍可使用系统命令。",
            )
        }
        return execute(workspaceRootUuid, sessionId, timeoutSeconds, ExecRunKind.PYTHON, code, live, stdin = stdin, attachment = attachment, capture = capture) { root, kind ->
            BuiltinCommandRequest(
                argv = listOf(environment.pythonLauncher.path, "-c", code),
                workingDirectory = root,
                environment = environment.forPython(root, kind),
                stdin = stdin,
                timeoutSeconds = timeoutSeconds,
            )
        }
    }

    /** 后台工作区与生命周期由 BackgroundJobManager 接管，仅共享运行环境和 git 认证租约。 */
    suspend fun prepareBackgroundCommand(
        kind: com.promenar.infinity.domain.jobs.BackgroundJobRunner,
        source: String,
        stdin: String?,
        location: com.promenar.infinity.data.jobs.BackgroundJobLocation,
        minutes: Int,
        sessionId: String? = null,
    ): com.promenar.infinity.data.jobs.PreparedBackgroundCommand {
        val resolvedSessionId = sessionId ?: location.sessionId
        val linux = kind == com.promenar.infinity.domain.jobs.BackgroundJobRunner.LINUX ||
            kind == com.promenar.infinity.domain.jobs.BackgroundJobRunner.PYTHON && sessionMode(resolvedSessionId) == SessionRunMode.LINUX
        if (kind == com.promenar.infinity.domain.jobs.BackgroundJobRunner.PYTHON && !linux && !environment.pythonAvailable) {
            throw IllegalArgumentException("当前设备没有内置 Python，请改用 termux_exec 或 ssh_exec。")
        }
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = resolvedSessionId, path = location.commandDirectory.canonicalPath)
        val root = location.commandDirectory
        val workspaceKind = location.workspaceKind
        val secrets = resolvedSessionId?.let { vault?.environment(it, source) }.orEmpty()
        val lease = try { gitRemote.open() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { GitRemoteSessions.None.open() }
        return try {
            if (linux) {
                val runtime = linuxRuntime
                if (runtime == null || runtime.state.value !is com.promenar.infinity.domain.linux.LinuxRuntimeState.Ready) {
                    throw LinuxUnavailableException(runtime?.state?.value ?: com.promenar.infinity.domain.linux.LinuxRuntimeState.NotInstalled(0))
                }
                val launchSource = if (kind == com.promenar.infinity.domain.jobs.BackgroundJobRunner.PYTHON || location.pythonSource) linuxPythonCommand(source) else source
                val gitEnvironment = LinuxGitEnvironment.from(lease.environment)
                val request = linuxRequest(root, workspaceKind, resolvedSessionId, launchSource, stdin, minutes * 60, gitEnvironment = gitEnvironment)
                val title = titleOrNull(resolvedSessionId)
                val before = packageProvenance.before()
                val finishMutation = resolvedSessionId?.let { beginSessionMutation(it) }
                val finishWorkspace = try { com.promenar.infinity.data.repository.WorkspaceExternalMutationCoordinator.begin(root.toPath()) }
                catch (failure: Throwable) { withContext(kotlinx.coroutines.NonCancellable) { finishMutation?.invoke() }; throw failure }
                return com.promenar.infinity.data.jobs.PreparedBackgroundCommand(
                    request.copy(environment = linuxCommandEnvironment(request.environment, lease.environment, secrets)), lease::close,
                    afterRun = { try { packageProvenance.after(before, resolvedSessionId, title) } finally { try { finishMutation?.invoke() } finally { finishWorkspace() } } },
                )
            }
            val python = kind == com.promenar.infinity.domain.jobs.BackgroundJobRunner.PYTHON
            val argv = if (python) listOf(environment.pythonLauncher.path, "-c", source)
                else listOf(shell, "-c", environment.shellPrelude() + "\n__infinity_command=\"\$1\"; shift; eval \"\$__infinity_command\"", "sh", source)
            com.promenar.infinity.data.jobs.PreparedBackgroundCommand(
                BuiltinCommandRequest(argv, root, (if (python) environment.forPython(root, workspaceKind) else environment.forWorkspace(root, workspaceKind)) + lease.environment + secrets, stdin, minutes * 60),
                lease::close,
            )
        } catch (failure: Throwable) { lease.close(); throw failure }
    }

    private suspend fun titleOrNull(sessionId: String?): String? = withContext(Dispatchers.IO) {
        try { sessionId?.let { sessionTitle(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private suspend fun execute(
        workspaceRootUuid: String,
        sessionId: String?,
        timeoutSeconds: Int,
        kind: ExecRunKind,
        source: String,
        live: ToolLiveOutputSink,
        stdin: String? = null,
        attachment: LinuxExecAttachment? = null,
        capture: SecretCapture? = null,
        linuxSource: String? = null,
        request: (File, String) -> BuiltinCommandRequest,
    ): CommandToolResult {
        if (capture != null && (vault == null || sessionId == null)) return CommandToolResult(errorCode = "SECRET_VAULT_UNAVAILABLE", guidance = "本会话保密库不可用，命令未执行。")
        val runtime = if (kind == ExecRunKind.LINUX) "linux" else "system"
        val secrets = try { sessionId?.let { vault?.environment(it, source) }.orEmpty() }
        catch (_: IllegalStateException) { return CommandToolResult(errorCode = "SECRET_SLOT_UNAVAILABLE", guidance = "引用的保密槽位已销毁或不能作为环境变量，命令未执行。") }
        val releaseSecrets = retainSecretEnvironment(sessionId, secrets, redactor)
        try {
        val safeLive = if (capture != null) ToolLiveOutputSink.None else live
        fun executionFields() = buildJsonObject { put("runtime", runtime); if (kind == ExecRunKind.LINUX) put("stderr_merged", capture == null) }
        if (kind == ExecRunKind.LINUX && linuxRuntime?.state?.value !is com.promenar.infinity.domain.linux.LinuxRuntimeState.Ready) {
            return linuxUnavailable()
        }
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(rootId = workspaceRootUuid, sessionId = sessionId)
        val target = sessionWorkspace(sessionId)
        if (target is SessionWorkspace.Unavailable) {
            return CommandToolResult(
                errorCode = "EXEC_WORKSPACE_UNAVAILABLE",
                guidance = "会话的自选工作区不可用（${target.reason}），命令不会改在会话私有工作区运行；请检查目录与授权，或在会话设置中更换工作区。",
                backendFields = executionFields(),
            )
        }
        val directory = target as? SessionWorkspace.Directory
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(rootId = workspaceRootUuid, sessionId = sessionId, path = directory?.root?.canonicalPath)
        val workspaceKind = SessionWorkspaceResolver.kindOf(target)
        var physicalRoot: File? = null
        val control = attachment?.control ?: BuiltinCommandControl()
        safeLive.onStop(control::requestStop)
        val run = ExecRunInfo(UUID.randomUUID().toString(), kind, ExecRunInfo.summarize(source), timeoutSeconds, wallClockMillis())
        // 代理不可用时命令照常执行，内置 git 退回直连。
        val lease = try { gitRemote.open() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { GitRemoteSessions.None.open() }
        var runtimeFields = JsonObject(emptyMap())
        suspend fun runCommand(root: File): BuiltinCommandOutput {
            attachment?.onDirectory?.invoke(root)
            val base = if (kind == ExecRunKind.LINUX) {
                linuxRequest(root, workspaceKind, sessionId, linuxSource ?: source, stdin, timeoutSeconds, target, LinuxGitEnvironment.from(lease.environment))
            } else request(root, workspaceKind)
            val commandRequest = base.copy(environment = if (kind == ExecRunKind.LINUX) linuxCommandEnvironment(base.environment, lease.environment, secrets)
                else base.environment + lease.environment + secrets,
                terminal = base.terminal && capture == null,
                outputLimitOverride = if (capture != null) SecretIoLimits.MAX_SECRET_BYTES + 1 else null,
                outputTransform = if (capture == null && sessionId != null && redactor != null) secretCommandOutput(sessionId, redactor) else null)
            val linux = runtime == "linux"
            val title = if (linux) titleOrNull(sessionId) else null
            val packagesBefore = if (linux) packageProvenance.before() else null
            var before: RuntimeDataMonitor.Snapshot? = null
            val finishMutation = if (linux && sessionId != null) beginSessionMutation(sessionId) else null
            val finishWorkspace = try {
                if (linux) com.promenar.infinity.data.repository.WorkspaceExternalMutationCoordinator.begin(root.toPath()) else null
            } catch (failure: Throwable) { withContext(kotlinx.coroutines.NonCancellable) { finishMutation?.invoke() }; throw failure }
            try {
                before = runtimeDataMonitor?.capture()
                if (linux && commandRequest.terminal) return runner.runTerminal(commandRequest, control,
                    onTerminalOutput = { safeLive.replace(ToolOutputStream.STDOUT, virtualize(it, root)) },
                    processGroupFile = attachment?.processGroupFile, terminalLog = attachment?.logFile)
                if (attachment != null && capture == null) {
                    return com.promenar.infinity.data.jobs.RotatingJobLog(attachment.logFile,
                        startedAtMillis = control.logStartedAtMillis ?: wallClockMillis()).use { log ->
                        val tee = LiveWorkspaceOutput(root) { stream, text ->
                            log.write(text.toByteArray(Charsets.UTF_8))
                            control.outputEvents?.invoke(stream, text)
                            safeLive.emit(stream, text)
                        }
                        try { runner.runAttached(commandRequest, control, attachment.processGroupFile, tee::accept) }
                        finally { tee.finish() }
                    }
                }
                if (safeLive === ToolLiveOutputSink.None) return runner.runAttached(commandRequest, control, attachment?.processGroupFile)
                val tee = LiveWorkspaceOutput(root, safeLive::emit)
                return try { runner.run(commandRequest, control, tee::accept) } finally { tee.finish() }
            } finally {
                if (linux) {
                    try { packageProvenance.after(packagesBefore, sessionId, title) }
                    finally {
                        try {
                            withContext(kotlinx.coroutines.NonCancellable) {
                                try { finishMutation?.invoke() } finally { finishWorkspace?.invoke() }
                            }
                        } finally {
                            if (sessionId != null && attachment == null) requestSessionHomeReconcile(sessionId)
                        }
                    }
                }
                val snapshot = before
                if (snapshot != null && runtimeDataMonitor != null) runtimeFields = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runtimeDataMonitor.changesSince(snapshot)
                }
            }
        }
        val held = try {
            keepAlive.hold(run, control::requestStop) {
                if (directory != null || kind == ExecRunKind.LINUX) {
                    val root = directory?.root ?: sessionId?.let { sessionHome(it) }
                        ?: throw IllegalStateException("会话专用空间不可用")
                    physicalRoot = root
                    val output = try { runCommand(root) } catch (_: IOException) { null }
                    WorkspaceCommandOutcome(output, changes = null)
                } else workspace.runCommandInWorkspace(workspaceRootUuid, sessionId) { root ->
                    physicalRoot = root
                    try {
                        runCommand(root)
                    } catch (failure: IOException) {
                        null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LinuxUnavailableException) {
            return linuxUnavailable()
        } catch (_: SecurityException) {
            return CommandToolResult(
                errorCode = "EXEC_WORKSPACE_UNAVAILABLE",
                guidance = "会话工作区不可用或正在被删除；请确认会话仍存在后重试。",
                backendFields = JsonObject(runtimeFields + executionFields()),
            )
        } catch (_: IllegalStateException) {
            return CommandToolResult(
                errorCode = "EXEC_WORKSPACE_UNAVAILABLE",
                guidance = "会话工作区当前不可修改（可能有尚未恢复的文件操作）；稍后重试，或先用文件工具确认工作区状态。",
                backendFields = JsonObject(runtimeFields + executionFields()),
            )
        } finally {
            lease.close()
        }
        val outcome = held.value
        val fields = JsonObject((if (directory != null) directoryFields(workspaceKind) else workspaceFields(outcome.changes)) + runtimeFields + executionFields())
        val output = outcome.value ?: return CommandToolResult(
            errorCode = "EXEC_START_FAILED",
            guidance = if (kind == ExecRunKind.LINUX) messages(RunModeMessage.LINUX_UNAVAILABLE) else "无法启动命令进程；请确认设备上的 /system/bin/sh 与 setsid 可用。",
            backendFields = fields,
        )
        val root = checkNotNull(physicalRoot)
        val settled = if (kind == ExecRunKind.LINUX) "会话私有索引已安排后台整理。" else if (directory != null) "自选工作区不做对账，可用 git status 查看改动。" else "工作区已按当前磁盘状态对账。"
        val (errorCode, guidance) = when {
            output.stoppedByUser -> "EXEC_STOPPED" to
                "命令已被停止（job_stop、通知栏或聊天界面的停止），已结束整个进程组；${settled}若不是你调用 job_stop 停止的，不要自动重试，先询问用户。"
            output.timedOut -> "EXEC_TIMEOUT" to
                "命令在 $timeoutSeconds 秒内未结束，已结束整个进程组；${settled}可增大 timeout_seconds（最多 600）或拆分任务。"
            output.killedExternally -> "EXEC_PROCESS_KILLED" to
                "命令进程被系统结束（SIGKILL），可能是内存不足或后台被冻结；$settled"
            outcome.reconcileError != null -> "WORKSPACE_RECONCILE_FAILED" to
                "命令已执行，但工作区索引未能更新（${outcome.reconcileError}）；文件工具可能看到旧状态，可再运行一次 shell_exec（如 true）重新对账。"
            else -> null to warningGuidance(outcome.changes)
        }
        val result = CommandToolResult(
            errorCode = errorCode,
            guidance = listOfNotNull(guidance, if (kind == ExecRunKind.LINUX) LinuxMissingCommandGuidance.hint(output.exitCode, output.stdout + "\n" + output.stderr, messages) else null, pauseGuidance(output.pausedMillis, held.foreground)).joinToString("").ifEmpty { null },
            exitCode = output.exitCode,
            timedOut = output.timedOut,
            stdout = if (capture == null) virtualize(output.stdout, root) else output.stdout,
            stderr = virtualize(output.stderr, root),
            stdoutOriginalLength = output.stdoutOriginalLength,
            stderrOriginalLength = output.stderrOriginalLength,
            stdoutTruncated = output.stdoutTruncated,
            stderrTruncated = output.stderrTruncated,
            removedWhenTruncated = CommandTruncation.HEAD_REMOVED,
            backendFields = JsonObject(
                fields +
                    (if (output.pausedMillis >= PAUSE_REPORT_MILLIS) mapOf("paused_ms" to JsonPrimitive(output.pausedMillis)) else emptyMap()),
            ),
        )
        val captured = if (capture == null) result else capture.complete(checkNotNull(sessionId), checkNotNull(vault), result) { path ->
            withContext(Dispatchers.IO) {
                if (kind == ExecRunKind.LINUX) {
                    require(path.isNotBlank() && '\u0000' !in path) { "SECRET_CAPTURE_FILE_INVALID" }
                    val quoted = "'${path.replace("'", "'\\''")}'"
                    val read = linuxRequest(root, workspaceKind, sessionId,
                        "test -f $quoted && head -c ${SecretIoLimits.MAX_SECRET_BYTES + 1} $quoted | base64", null, minOf(timeoutSeconds, 60), target)
                    val encoded = runner.run(read.copy(terminal = false, outputLimitOverride = (SecretIoLimits.MAX_SECRET_BYTES + 1) * 2), BuiltinCommandControl())
                    require(encoded.exitCode == 0 && !encoded.timedOut && !encoded.stdoutTruncated) { "SECRET_CAPTURE_READ_FAILED" }
                    java.util.Base64.getMimeDecoder().decode(encoded.stdout).also { bytes ->
                        if (bytes.size > SecretIoLimits.MAX_SECRET_BYTES) { bytes.fill(0); throw IllegalArgumentException("SECRET_TOO_LARGE") }
                    }
                } else {
                    val file = (if (File(path).isAbsolute) File(path) else File(root, path)).canonicalFile
                    require(file.isFile && file.length() <= SecretIoLimits.MAX_SECRET_BYTES) { "SECRET_CAPTURE_FILE_INVALID" }
                    file.inputStream().use { input ->
                        val buffer = ByteArray(SecretIoLimits.MAX_SECRET_BYTES + 1)
                        try {
                            var count = 0
                            while (count < buffer.size) {
                                val read = input.read(buffer, count, buffer.size - count)
                                if (read < 0) break
                                count += read
                            }
                            require(count <= SecretIoLimits.MAX_SECRET_BYTES) { "SECRET_TOO_LARGE" }
                            buffer.copyOf(count)
                        } finally { buffer.fill(0) }
                    }
                }
            }
        }
        return captured.redacted(sessionId, redactor)
        } finally { releaseSecrets() }
    }

    private fun modeChanged() = CommandToolResult(errorCode = "EXEC_MODE_CHANGED", guidance = messages(RunModeMessage.MODE_CHANGED))

    private fun modeRequired() = CommandToolResult(errorCode = "EXEC_RUN_MODE_REQUIRED", guidance = messages(RunModeMessage.MODE_REQUIRED))

    private fun linuxUnavailable() = CommandToolResult(
        errorCode = "EXEC_LINUX_UNAVAILABLE", guidance = messages(RunModeMessage.LINUX_UNAVAILABLE),
        backendFields = buildJsonObject { put("runtime", "linux") },
    )

    /** 进程被系统暂停时提示原因与设置；暂停时间不计入超时。 */
    private fun pauseGuidance(pausedMillis: Long, foreground: ExecForegroundState): String? {
        if (pausedMillis < PAUSE_REPORT_MILLIS) return null
        val seconds = (pausedMillis + 500) / 1000
        val service = when (foreground) {
            ExecForegroundState.STARTED -> ""
            ExecForegroundState.UNSUPPORTED -> "当前版本命令期间不启动前台服务，离开 Infinity Workbench 后更容易被暂停。"
            ExecForegroundState.NOT_ALLOWED -> "本次系统未允许启动前台服务（应用在后台）。"
        }
        return "命令运行期间本应用被系统暂停约 $seconds 秒（后台冻结或设备休眠），暂停时间不计入超时。$service" +
            "如需离开 Infinity Workbench 时命令继续运行，请在系统的应用耗电管理中把 Infinity Workbench 设为“完全允许后台行为”（ColorOS、MIUI 等）。"
    }

    private fun warningGuidance(changes: WorkspaceChangeReport?): String? {
        if (changes == null) return null
        return buildList {
            if (changes.reservedChanges.isNotEmpty()) add("命令改动了系统保留路径（.infinity*、.recycle_bin 或顶层 mnt），这些路径不进入索引，回收区或版本历史可能已损坏。")
            if (changes.unsupported.isNotEmpty()) add("命令创建了符号链接、特殊文件或文件工具不支持的名称，这些条目不进入索引。")
        }.joinToString("").ifEmpty { null }
    }

    companion object {
        const val MAX_LISTED_PATHS = 200
        /** 低于该值的暂停多为调度抖动，不提示。 */
        const val PAUSE_REPORT_MILLIS = 3_000L
        private const val MAX_WARNING_PATHS = 20

        /** 输出中的工作区物理路径替换为 `$INFINITY_WORKSPACE`，既不暴露应用目录，也可直接用于下一条命令。 */
        internal fun virtualize(text: String, root: File): String = virtualize(text, workspacePaths(root))

        internal fun virtualize(text: String, paths: List<String>): String =
            paths.fold(text) { result, path -> result.replace(path, "\$INFINITY_WORKSPACE") }

        /** 工作区的书写路径与规范路径，长的在前，避免短路径先替换掉长路径的一部分。 */
        internal fun workspacePaths(root: File): List<String> =
            listOf(root.path, runCatching { root.canonicalPath }.getOrDefault(root.path)).distinct()
                .filter { it.length > 1 }
                .sortedByDescending { it.length }

        /** 自选目录工作区不逐次统计改动，只说明工作区类型。 */
        internal fun directoryFields(kind: String): JsonObject = buildJsonObject {
            put("workspace_kind", kind)
            put("workspace_tracking", "none")
        }

        internal fun workspaceFields(changes: WorkspaceChangeReport?): JsonObject = buildJsonObject {
            if (changes == null) {
                put("workspace_changes", kotlinx.serialization.json.JsonNull)
                return@buildJsonObject
            }
            // .git 内部对象照常入索引，但不逐条列出，免得一次提交的对象文件挤占真正的改动。
            val added = changes.added.filterNot(::isGitInternalWorkspacePath)
            val modified = changes.modified.filterNot(::isGitInternalWorkspacePath)
            val removed = changes.removed.filterNot(::isGitInternalWorkspacePath)
            val moved = changes.moved.filterNot { (from, to) -> isGitInternalWorkspacePath(from) || isGitInternalWorkspacePath(to) }
            val gitInternal = changes.added.size + changes.modified.size + changes.removed.size + changes.moved.size -
                (added.size + modified.size + removed.size + moved.size)
            val total = added.size + modified.size + removed.size + moved.size
            var budget = MAX_LISTED_PATHS
            fun take(paths: List<String>): List<String> = paths.take(budget).also { budget -= it.size }
            putJsonObject("workspace_changes") {
                putJsonArray("added") { take(added).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("modified") { take(modified).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("removed") { take(removed).forEach { add(JsonPrimitive(it)) } }
                putJsonArray("moved") {
                    moved.take(budget).also { budget -= it.size }.forEach { (from, to) ->
                        add(buildJsonObject { put("from", from); put("to", to) })
                    }
                }
                put("counts", buildJsonObject {
                    put("added", added.size)
                    put("modified", modified.size)
                    put("removed", removed.size)
                    put("moved", moved.size)
                })
                if (gitInternal > 0) put("git_internal_changes", gitInternal)
                put("truncated", total > MAX_LISTED_PATHS)
            }
            val warnings = buildList {
                if (changes.reservedChanges.isNotEmpty()) add("WORKSPACE_RESERVED_PATH" to changes.reservedChanges)
                if (changes.unsupported.isNotEmpty()) add("WORKSPACE_UNSUPPORTED_ENTRY" to changes.unsupported)
            }
            if (warnings.isNotEmpty()) {
                put("workspace_warnings", JsonArray(warnings.map { (code, paths) ->
                    buildJsonObject {
                        put("code", code)
                        put("count", paths.size)
                        putJsonArray("paths") { paths.take(MAX_WARNING_PATHS).forEach { add(JsonPrimitive(it)) } }
                    }
                }))
            }
        }
    }
}

/**
 * 实时输出逐块替换工作区物理路径。读取块可能恰好切在路径中间，因此块尾若是某条路径的前缀就先暂存，
 * 与下一块拼接后再替换；命令结束时 [finish] 补发暂存内容。stdout 与 stderr 由两个读取线程并发调用。
 */
internal class LiveWorkspaceOutput(root: File, private val emit: (ToolOutputStream, String) -> Unit) {
    private val paths = BuiltinExecService.workspacePaths(root)
    private val pending = HashMap<ToolOutputStream, String>()

    @Synchronized
    fun accept(stream: ToolOutputStream, text: String) {
        val replaced = BuiltinExecService.virtualize(pending.remove(stream).orEmpty() + text, paths)
        val hold = heldSuffixLength(replaced)
        if (hold > 0) pending[stream] = replaced.takeLast(hold)
        replaced.dropLast(hold).takeIf(String::isNotEmpty)?.let { emit(stream, it) }
    }

    @Synchronized
    fun finish() {
        pending.forEach { (stream, text) -> emit(stream, text) }
        pending.clear()
    }

    /** 块尾与某条路径开头重合的最长长度（不含整条路径，整条已被替换）。 */
    private fun heldSuffixLength(text: String): Int {
        for (length in minOf(text.length, (paths.maxOfOrNull { it.length } ?: 1) - 1) downTo 1) {
            val suffix = text.substring(text.length - length)
            if (paths.any { it.length > length && it.startsWith(suffix) }) return length
        }
        return 0
    }
}
