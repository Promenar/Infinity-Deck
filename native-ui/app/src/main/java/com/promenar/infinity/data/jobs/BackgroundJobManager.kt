package com.promenar.infinity.data.jobs

import com.promenar.infinity.data.exec.*
import com.promenar.infinity.data.secret.SessionSecretVault
import com.promenar.infinity.data.secret.SecretOutputRedactor
import com.promenar.infinity.domain.secret.SecretIoLimits
import com.promenar.infinity.data.termux.*
import com.promenar.infinity.data.ssh.*
import com.promenar.infinity.domain.jobs.*
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import com.promenar.infinity.domain.trigger.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JobAccessException(val code: String, override val message: String) : IllegalArgumentException(message)
data class BackgroundJobLocation(val commandDirectory: File, val workspaceDirectory: File, val isolated: Boolean, val termuxShared: Boolean, val sessionId: String? = null, val workspaceKindOverride: String? = null,
    /** 执行器类型和原始代码分别传递，避免把发送用 shell 包装记为命令来源。 */
    val pythonSource: Boolean = false) {
    val workspaceKind: String get() = workspaceKindOverride ?: if (isolated) "private" else if (termuxShared) "shared" else "project"
}
class PreparedBackgroundCommand(
    val request: BuiltinCommandRequest,
    val close: () -> Unit = {},
    val afterRun: suspend () -> Unit = {},
)
data class BackgroundJobStatus(val record: BackgroundJobRecord, val tail: JobLogTail, val timedOut: Boolean = false,
    val waitReason: String? = null, val matchedLine: String? = null, val lastOutputSecondsAgo: Long? = null)

/** 唤醒的整个挂起区间由调用者的保活登记覆盖。 */
class BackgroundJobCompletion(
    private val dispatcher: () -> SystemTurnDispatcher? = { null },
    private val notify: suspend (BackgroundJobRecord) -> Unit = {},
) {
    suspend fun dispatch(record: BackgroundJobRecord, tail: JobLogTail) {
        when (BackgroundJobExitAction.fromWire(record.onExit)) {
            BackgroundJobExitAction.NOTIFY -> notify(record)
            BackgroundJobExitAction.WAKE -> {
                val target = dispatcher()
                if (target == null) notify(record) else {
                    try {
                        target.request(SystemTurnRequest(record.sessionId, SystemTurnKind.JOB_EXIT, record.id, record.summary, buildString {
                            appendLine("后台任务 ${record.id} 已结束")
                            appendLine("命令：${record.summary}")
                            appendLine("状态：${record.state}；退出码：${record.exitCode ?: "无"}；耗时：${elapsedSeconds(record)} 秒")
                            appendLine("日志通过 job_status 或 job_wait 读取。")
                            record.guidance?.let { appendLine(it) }
                            record.toolResult?.let { appendLine("结果：\n${it.content}") }
                            appendLine("日志末尾：\n${tail.text.takeLast(2_000)}")
                        }))
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { notify(record) }
                }
            }
            else -> Unit
        }
    }
    companion object {
        fun elapsedSeconds(record: BackgroundJobRecord, now: Long = System.currentTimeMillis()): Long =
            ((record.endedAtMillis ?: now) - record.startedAtMillis).coerceAtLeast(0) / 1000
    }
}

/** 应用级任务所有者；发起/等待工具的取消不会传递到已经接管的运行任务。 */
class BackgroundJobManager(
    private val scope: CoroutineScope,
    private val store: BackgroundJobStore,
    private val runRoot: File,
    private val runner: BuiltinCommandRunner,
    private val prepareWorkspace: suspend (BackgroundJobRecord) -> BackgroundJobLocation,
    private val prepareCommand: suspend (BackgroundJobRunner, String, String?, BackgroundJobLocation, Int) -> PreparedBackgroundCommand,
    private val reconcile: suspend (BackgroundJobRecord, Boolean, Boolean) -> String? = { _, _, _ -> null },
    private val termux: TermuxBackgroundJobs? = null,
    private val keepAlive: ExecKeepAlive = NoExecKeepAlive,
    private val liveOutput: ToolLiveOutputStore = ToolLiveOutputStore(),
    private val completion: BackgroundJobCompletion = BackgroundJobCompletion(),
    val keepAliveMode: String = "foreground_only",
    private val now: () -> Long = System::currentTimeMillis,
    private val executionGate: com.promenar.infinity.data.session.SessionExecutionGate? = null,
    private val sessionExists: suspend (String) -> Boolean = { true },
    private val linuxRuntime: com.promenar.infinity.domain.linux.LinuxRuntime? = null,
    private val sessionMode: suspend (String) -> com.promenar.infinity.domain.model.SessionRunMode? = { com.promenar.infinity.domain.model.SessionRunMode.BUILTIN },
    private val messages: RunModeMessages = RunModeMessages(),
    private val vault: SessionSecretVault? = null,
    redactor: SecretOutputRedactor? = null,
    private val requestReconcile: (BackgroundJobRecord) -> Unit = {},
    private val reconcileWarning: (BackgroundJobRecord) -> String? = { null },
) : BackgroundJobController {
    private val redactor = redactor ?: vault?.let(::SecretOutputRedactor)
    private class Active(val control: BuiltinCommandControl = BuiltinCommandControl()) {
        val terminal = CompletableDeferred<Unit>()
        var worker: Job? = null
        val inputGate = Mutex()
        val completion = CompletableDeferred<Pair<BackgroundJobRecord, JobLogTail>>()
        var sink: ToolLiveOutputSink = ToolLiveOutputSink.None
        @Volatile var lastOutputAtMillis: Long? = null
        var lastLogBytes: Long? = null
        var lastTail: String = ""
        var lastSafeTail: String = ""
        var termuxOutput: ToolLiveOutputSink? = null
        var termuxOutputFinished: Boolean = false
        @Volatile var suppressCompletion: Boolean = false
        var sshControl: SshExecControl? = null
    }
    private val gate = Mutex()
    private val refreshGate = Mutex()
    private val publishGate = Mutex()
    private val pendingCleanup = mutableSetOf<String>()
    private val active = ConcurrentHashMap<String, Active>()
    private val deletedSessions = ConcurrentHashMap.newKeySet<String>()
    private val state = MutableStateFlow<List<BackgroundJob>>(emptyList())
    private val initializationMutex = Mutex()
    private var initialized = false

    suspend fun initialize() {
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(subsystem = com.promenar.infinity.startup.StartupSubsystem.BACKGROUND_JOBS)
        initializationMutex.withLock {
            if (!initialized) withContext(Dispatchers.IO) { recover(); initialized = true }
        }
    }
    override fun observe(sessionId: String?): Flow<List<BackgroundJob>> = flow {
        initialize()
        emitAll(state.map { jobs -> jobs.filter { sessionId == null || it.sessionId == sessionId } })
    }
    private suspend fun publish() = publishGate.withLock {
        val records=store.all()
        state.value = records.map { it.job() }.sortedByDescending { it.startedAtMillis }
        // 只清理持久层明确淘汰的任务，不能用并发快照扫描“孤儿”。
        pendingCleanup += store.drainPruned().map{it.id}
        val eligible=pendingCleanup.filter{!active.containsKey(it)}
        eligible.forEach { id -> if(runCatching { removeRunDirectory(id) }.isSuccess) pendingCleanup.remove(id) }
    }
    private fun removeRunDirectory(id:String) {
        val directory=File(runRoot,id)
        if(id.matches(Regex("job-[A-Za-z0-9-]+")) && directory.canonicalFile.parentFile==runRoot.canonicalFile) {
            deleteJobTree(directory)
        }
    }
    private suspend fun recover() {
        store.prune()
        for (record in store.all()) {
            if (!record.running || record.runnerKind == BackgroundJobRunner.TERMUX) continue
            if (record.runnerKind == BackgroundJobRunner.IN_APP) {
                store.put(record.copy(state = "INTERRUPTED", endedAtMillis = now(), guidance = "应用重启，进程内任务已中断。"))
                continue
            }
            val missingSecrets = record.secretNames.filter { name -> vault?.revealBytes(record.sessionId, name)?.let { it.fill(0); true } != true }
            val secretWarning = missingSecrets.takeIf { it.isNotEmpty() }?.let { "JOB_SECRET_UNAVAILABLE：本会话保密库已清除，缺少槽位 ${it.joinToString()}；任务不会恢复执行。" }
            if (record.runnerKind == BackgroundJobRunner.SSH) {
                store.put(record.copy(state = "INTERRUPTED", endedAtMillis = now(), guidance = secretWarning ?: if (record.transfer == null) SSH_DISCONNECTED_GUIDANCE else "应用重启，传输已中断；请检查临时文件清理状态后重试。",
                    transfer = record.transfer?.copy(bytesPerSecond = null, failure = SftpTransferFailure.NETWORK.name)))
                continue
            }
            val cleaned=runner.interruptBackground(File(record.runDirectory,"pgid"))
            val reconcileWarning = try { reconcile(record, false, true) } catch (_: Exception) { "工作区恢复失败；任务副本和日志已保留。" }
            val warning=listOfNotNull(secretWarning,reconcileWarning,if(cleaned)null else "应用重启后无法核验旧进程组身份，未发送信号；请确认旧命令已停止。").joinToString(" ").ifEmpty{null}
            store.put(record.copy(state="INTERRUPTED",endedAtMillis=now(),guidance=warning,logMigrationDeferred=!cleaned))
            requestReconcile(record)
        }
        for (record in store.all()) {
            try {
                if (record.logMigrationDeferred) continue
                if (record.runnerKind != BackgroundJobRunner.TERMUX) BackgroundJobLogs.migrate(record, runRoot)
                store.put(record.copy(logWorkspacePath = BackgroundJobLogs.reference(record.id)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                store.put(record.copy(guidance = listOfNotNull(record.guidance,
                    "日志迁移未完成（${failure::class.java.simpleName}），旧日志已保留。").distinct().joinToString(" ")))
            }
        }
        publish()
        for(record in store.all().filter { it.running && it.runnerKind == BackgroundJobRunner.TERMUX }) monitorTermux(record)
    }
    private suspend fun <T> admitted(sessionId:String, block:suspend()->T):T {
        suspend fun checked():T {
            if(!sessionExists(sessionId)) throw JobAccessException("JOB_SESSION_DELETED","会话不存在，不能启动后台任务。")
            return block()
        }
        return if(executionGate==null) checked() else executionGate.withNewSessionAdmission(sessionId) {checked()}
    }
    private suspend fun reserve(sessionId:String, root:String, kind:BackgroundJobRunner, source:String, minutes:Int, action:BackgroundJobExitAction, sshControl: SshExecControl? = null, remoteHost: String? = null, transfer: SftpTransferRecord? = null, title: String? = null):BackgroundJobRecord = gate.withLock {
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, rootId = root, subsystem = com.promenar.infinity.startup.StartupSubsystem.BACKGROUND_JOBS)
        executionGate?.requireSessionWritable(sessionId)
        require(minutes in 1..1440) { "max_runtime_minutes 必须为 1–1440" }
        if(sessionId in deletedSessions) throw JobAccessException("JOB_SESSION_DELETED","会话正在删除，不能启动后台任务。")
        val running=store.all().filter { it.running }
        if(running.count{it.sessionId==sessionId} >= 3) throw JobAccessException("JOB_SESSION_LIMIT","本会话已有 3 个运行中任务，请先用 job_wait 等待或用 job_stop 停止。")
        if(running.size >= 6) throw JobAccessException("JOB_GLOBAL_LIMIT","全局已有 6 个运行中任务，请先结束部分后台任务。")
        val id="job-${UUID.randomUUID()}"
        val secretNames = try { vault?.environment(sessionId,source)?.keys?.sorted().orEmpty() }
        catch (_: IllegalStateException) { throw JobAccessException("JOB_SECRET_UNAVAILABLE", "引用的保密槽位已销毁或不能作为环境变量，任务未启动。") }
        BackgroundJobRecord(id,sessionId,root,kind.name,ExecRunInfo.summarize(source),startedAtMillis=now(),maxRuntimeMinutes=minutes,onExit=action.wireName,logWorkspacePath=BackgroundJobLogs.reference(id),runDirectory=File(runRoot,id).path,directWorkspace=kind==BackgroundJobRunner.SSH,remoteHost=remoteHost,transfer=transfer,secretNames=secretNames,title=title).also {
            store.put(it); active[id]=Active().also { handle -> handle.sshControl = sshControl; handle.control.logStartedAtMillis = it.startedAtMillis }; publish()
        }
    }
    suspend fun startBuiltin(sessionId:String, workspaceRootUuid:String, kind:BackgroundJobRunner, source:String, stdin:String?, minutes:Int, action:BackgroundJobExitAction, expectedMode: com.promenar.infinity.domain.model.SessionRunMode? = null, title:String? = null):BackgroundJobRecord = scope.async(Dispatchers.IO) { admitted(sessionId) {
        initialize()
        require(kind in setOf(BackgroundJobRunner.SHELL, BackgroundJobRunner.PYTHON, BackgroundJobRunner.LINUX))
        val mode = sessionMode(sessionId) ?: throw JobAccessException("EXEC_RUN_MODE_REQUIRED", messages(RunModeMessage.MODE_REQUIRED))
        if (expectedMode != null && mode != expectedMode) throw JobAccessException("EXEC_MODE_CHANGED", messages(RunModeMessage.MODE_CHANGED))
        val linux = mode == com.promenar.infinity.domain.model.SessionRunMode.LINUX
        if (!linux && kind == BackgroundJobRunner.LINUX) {
            throw JobAccessException("EXEC_MODE_TOOL_UNAVAILABLE", messages(RunModeMessage.BUILTIN_TOOL_BLOCKED))
        }
        val actualKind = if (kind in setOf(BackgroundJobRunner.SHELL, BackgroundJobRunner.PYTHON) && linux) BackgroundJobRunner.LINUX else kind
        val record=reserve(sessionId,workspaceRootUuid,actualKind,source,minutes,action,title=title)
        val handle=active.getValue(record.id)
        val started=CompletableDeferred<BackgroundJobRecord>()
        handle.worker=scope.launch(Dispatchers.IO) {
            val sink=liveOutput.open("job:${record.id}", inferOutputEvents = false)
            handle.control.outputEvents = { _, text ->
                if (text.isNotEmpty()) handle.lastOutputAtMillis = now()
                liveOutput.publishOutput("job:${record.id}", text)
            }
            sink.onStop(handle.control::requestStop)
            try {
                keepAlive.hold(runInfo(record), handle.control::requestStop) {
                    var current=record
                    var command:PreparedBackgroundCommand?=null
                    try {
                        val location=prepareWorkspace(record)
                        current=record.copy(workspaceDirectory=location.workspaceDirectory.path,isolatedWorkspace=location.isolated)
                        store.put(current)
                        val log=logFile(current)
                        log.parentFile?.mkdirs(); if(!log.exists()) log.createNewFile()
                        val environment = try { vault?.environment(sessionId, source).orEmpty() }
                        catch (_: IllegalStateException) { throw JobAccessException("JOB_SECRET_UNAVAILABLE", "引用的保密槽位已销毁或不能作为环境变量，任务未执行。") }
                        environment.values.forEach { value ->
                            val bytes = value.toByteArray(Charsets.UTF_8)
                            try { redactor?.registerJobInput(sessionId, record.id, bytes) } finally { bytes.fill(0) }
                        }
                        if (!environment.keys.containsAll(record.secretNames)) throw JobAccessException("JOB_SECRET_UNAVAILABLE", "引用的保密槽位已销毁，任务未执行。")
                        val original = prepareCommand(actualKind,source,stdin,location.copy(sessionId=record.sessionId, pythonSource = kind == BackgroundJobRunner.PYTHON),minutes)
                        val prepared = PreparedBackgroundCommand(original.request.copy(environment = original.request.environment + environment,
                            outputTransform = redactor?.let { secretCommandOutput(sessionId, it) }), original.close, original.afterRun)
                        command=prepared
                        var linuxRemoved = false
                        val output = coroutineScope {
                            val watcher = if (actualKind == BackgroundJobRunner.LINUX) launch {
                                val runtime = checkNotNull(linuxRuntime)
                                runtime.state.first { it !is com.promenar.infinity.domain.linux.LinuxRuntimeState.Ready }
                                linuxRemoved = true
                                handle.control.requestStop()
                            } else null
                            try {
                                runner.runBackground(prepared.request,handle.control,log,File(record.runDirectory,"pgid"),onStarted={started.complete(current)},onOutput=sink::emit,onTerminalOutput={ sink.replace(ToolOutputStream.STDOUT, it) })
                            } finally {
                                withContext(NonCancellable) { watcher?.cancelAndJoin(); prepared.afterRun() }
                            }
                        }
                        val terminal=when {linuxRemoved -> "FAILED"; output.stoppedByUser -> "STOPPED"; output.timedOut -> "TIMED_OUT"; else -> "EXITED"}
                        finish(current.copy(state=terminal,exitCode=output.exitCode,endedAtMillis=now(),guidance=if(linuxRemoved) messages(RunModeMessage.LINUX_UNAVAILABLE) else if(actualKind == BackgroundJobRunner.LINUX) LinuxMissingCommandGuidance.hint(output.exitCode, readJobLogTail(log).text, messages) else null),handle)
                        if(!started.isCompleted) started.completeExceptionally(JobAccessException("JOB_STOPPED","任务在进程启动前被停止。"))
                    } catch (failure:Exception) {
                        val terminal=current.copy(state=if(failure is CancellationException) "INTERRUPTED" else "FAILED",endedAtMillis=now(),guidance=if(failure is JobAccessException) "${failure.code}：${failure.message}" else if(failure is com.promenar.infinity.domain.linux.LinuxUnavailableException) messages(RunModeMessage.LINUX_UNAVAILABLE) else "后台任务无法运行（${failure.javaClass.simpleName}），请检查执行后端和工作区。")
                        withContext(NonCancellable) {
                            if(store.all().find{it.id==record.id}?.running==true) finish(terminal,handle)
                        }
                        if(!started.isCompleted) started.completeExceptionally(failure)
                    } finally { command?.close?.invoke() }
                }
            } catch (failure:Exception) {
                if(!started.isCompleted) started.completeExceptionally(failure)
                withContext(NonCancellable) {
                    val latest=store.all().find{it.id==record.id}
                    if(latest?.running==true) finish(latest.copy(state="FAILED",endedAtMillis=now(),guidance="任务保活或运行初始化失败，请检查后台运行授权。"),handle)
                }
            } finally {
                handle.terminal.complete(Unit)
                redactor?.clearJob(record.sessionId, record.id)
                active.remove(record.id,handle)
                liveOutput.close("job:${record.id}",sink)
            }
        }
        started.await()
    } }.await()

    /** 应用级协程从启动起拥有命令；让出仅扩大时限并把控制柄暴露给 job_*。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun runLinuxForeground(
        sessionId: String, root: String, source: String, yieldSeconds: Int, minutes: Int,
        action: BackgroundJobExitAction, live: ToolLiveOutputSink,
        title: String? = null,
        block: suspend (LinuxExecAttachment, ToolLiveOutputSink) -> com.promenar.infinity.domain.tool.CommandToolResult,
    ): com.promenar.infinity.domain.tool.CommandToolResult = runForeground(sessionId, root, source, yieldSeconds, minutes, action, live, title, BackgroundJobRunner.LINUX, block)

    suspend fun runForeground(
        sessionId: String, root: String, source: String, yieldSeconds: Int, minutes: Int,
        action: BackgroundJobExitAction, live: ToolLiveOutputSink, title: String? = null,
        kind: BackgroundJobRunner = BackgroundJobRunner.LINUX,
        block: suspend (LinuxExecAttachment, ToolLiveOutputSink) -> com.promenar.infinity.domain.tool.CommandToolResult,
    ): com.promenar.infinity.domain.tool.CommandToolResult {
        require(kind in setOf(BackgroundJobRunner.LINUX, BackgroundJobRunner.SHELL, BackgroundJobRunner.PYTHON))
        val result = CompletableDeferred<com.promenar.infinity.domain.tool.CommandToolResult>()
        val yielded = java.util.concurrent.atomic.AtomicBoolean(false)
        var ownedHandle: Active? = null
        var ownedRecord: BackgroundJobRecord? = null
        var directory: File? = null
        try {
            withContext(NonCancellable) {
                initialize()
                val record = admitted(sessionId) { reserve(sessionId, root, kind, source, minutes, action, title = title) }
                    .copy(directWorkspace = true)
                ownedRecord = record
                val handle = active.getValue(record.id)
                ownedHandle = handle
                handle.control.yieldAfterSeconds = yieldSeconds
                handle.control.yieldedTimeoutSeconds = minutes * 60
                store.put(record)
                val release = live.retain()
                val sink = liveOutput.open("job:${record.id}", inferOutputEvents = false)
                handle.control.outputEvents = { _, text ->
                if (text.isNotEmpty()) handle.lastOutputAtMillis = now()
                liveOutput.publishOutput("job:${record.id}", text)
            }
                sink.onStop(handle.control::requestStop)
                val tee = object : ToolLiveOutputSink by live {
                    override fun emit(stream: ToolOutputStream, text: String) { live.emit(stream, text); sink.emit(stream, text) }
                    override fun replace(stream: ToolOutputStream, text: String) { live.replace(stream, text); sink.replace(stream, text) }
                }
                handle.worker = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                    var runtimeRemoved = false
                    try {
                        val value = coroutineScope {
                            val watcher = linuxRuntime?.takeIf { kind == BackgroundJobRunner.LINUX }?.let { runtime -> launch {
                                runtime.state.first { it !is com.promenar.infinity.domain.linux.LinuxRuntimeState.Ready }
                                runtimeRemoved = true; handle.control.requestStop()
                            } }
                            try { block(LinuxExecAttachment(handle.control, File(record.runDirectory, "pgid"), logFile(record)) { ready ->
                                directory = ready
                                store.updateIfPresent(record.id) { latest -> if (latest.running) latest.copy(workspaceDirectory = ready.path) else latest }
                            }, tee) }
                            finally { withContext(NonCancellable) { watcher?.cancelAndJoin() } }
                        }
                        withContext(NonCancellable) {
                            val safeValue = value.redacted(sessionId, redactor)
                            val terminal = record.copy(
                                workspaceDirectory = directory?.path,
                                state = when { runtimeRemoved -> "FAILED"; value.errorCode == "EXEC_STOPPED" -> "STOPPED"; value.timedOut -> "TIMED_OUT"; value.errorCode != null -> "FAILED"; else -> "EXITED" },
                                exitCode = value.exitCode, endedAtMillis = now(), commandResult = safeValue.toJson(), lastOutputAtMillis = handle.lastOutputAtMillis,
                                guidance = if (runtimeRemoved) messages(RunModeMessage.LINUX_UNAVAILABLE) else safeValue.guidance,
                            )
                            store.put(terminal); publish(); handle.terminal.complete(Unit)
                            result.complete(safeValue)
                            if (yielded.get() && sessionAllowed(record) && !handle.suppressCompletion) {
                                try { completion.dispatch(terminal, readJobLogTail(logFile(terminal))) } catch (_: Exception) { }
                            }
                            requestReconcile(terminal)
                        }
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            store.put(record.copy(workspaceDirectory = directory?.path, lastOutputAtMillis = handle.lastOutputAtMillis, state = if (handle.control.stopRequested) "STOPPED" else if (failure is CancellationException) "INTERRUPTED" else "FAILED", endedAtMillis = now(), guidance = "命令执行失败（${failure.javaClass.simpleName}）。"))
                            publish(); result.completeExceptionally(failure)
                            requestReconcile(record)
                        }
                    } finally {
                        handle.terminal.complete(Unit); redactor?.clearJob(record.sessionId, record.id); active.remove(record.id, handle)
                        release(); liveOutput.close("job:${record.id}", sink)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val record = checkNotNull(ownedRecord)
            val handle = checkNotNull(ownedHandle)
            val steering = currentCoroutineContext()[SteeringContext]
            val completed = ToolExecutionControls.controlled(ToolExecutionControls.Kind.FOREGROUND, background = handle.control::requestYield) {
                coroutineScope {
                    val insertion = steering?.let { launch(start = CoroutineStart.UNDISPATCHED) {
                        it.await()
                        handle.control.requestYield()
                    } }
                    try {
                        kotlinx.coroutines.selects.select<com.promenar.infinity.domain.tool.CommandToolResult?> {
                            result.onAwait { it }
                            handle.control.yielded.onAwait { null }
                        }
                    } finally { insertion?.cancel() }
                }
            }
            currentCoroutineContext().ensureActive()
            if (completed != null) return completed
            // 无挂起区间先转移所有权，避免调用取消把已让出的进程停止。
            yielded.set(true)
            withContext(NonCancellable) { store.updateIfPresent(record.id) { it.copy(workspaceDirectory = directory?.path) } }
            if (result.isCompleted) return result.await()
            val output = handle.control.outputSnapshot
            val rawTail = if (output == null) withContext(Dispatchers.IO) { readJobLogTail(logFile(record)) } else null
            val fields = kotlinx.serialization.json.buildJsonObject {
                put("runtime", kotlinx.serialization.json.JsonPrimitive(if (kind == BackgroundJobRunner.LINUX) "linux" else "system"))
                put("stderr_merged", kotlinx.serialization.json.JsonPrimitive(true))
                put("state", kotlinx.serialization.json.JsonPrimitive("running")); put("job_id", kotlinx.serialization.json.JsonPrimitive(record.id))
                put("yielded_after_seconds", kotlinx.serialization.json.JsonPrimitive(((now() - record.startedAtMillis).coerceAtLeast(0) / 1000)))
                put("workspace_mode", kotlinx.serialization.json.JsonPrimitive("direct_reconcile_on_exit"))
            }
            return com.promenar.infinity.domain.tool.CommandToolResult(
                guidance = "命令仍在运行；用 job_status/job_wait 查看，用 job_input 回答提示，用 job_stop 结束。",
                stdout = (output?.stdout ?: rawTail?.text.orEmpty()).let { text -> directory?.let { BuiltinExecService.virtualize(text, it) } ?: text },
                stderr = output?.stderr.orEmpty(), stdoutOriginalLength = output?.stdoutOriginalLength ?: rawTail?.takeUnless { it.truncated }?.text?.length?.toLong(),
                stderrOriginalLength = output?.stderrOriginalLength ?: 0, stdoutTruncated = output?.stdoutTruncated ?: rawTail?.truncated ?: false,
                stderrTruncated = output?.stderrTruncated ?: false, backendFields = fields,
            )
        } catch (failure: Throwable) {
            if (!yielded.get()) withContext(NonCancellable) {
                ownedHandle?.let { handle ->
                    handle.control.requestStop()
                    if (handle.worker != null) handle.worker?.cancelAndJoin() else {
                        ownedRecord?.let { record ->
                            store.put(record.copy(state = "FAILED", endedAtMillis = now(), guidance = "任务启动未完成。"))
                            active.remove(record.id, handle); publish()
                        }
                        handle.terminal.complete(Unit)
                    }
                }
            }
            throw failure
        }
    }

    data class InAppRunResult(val result: com.promenar.infinity.data.model.ToolResult? = null,
        val job: BackgroundJobRecord? = null, val yieldedAfterSeconds: Long? = null)

    /** 将挂起任务交给应用作用域，前台取消在交接前停止 worker。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun runInApp(sessionId: String, root: String, summary: String, yieldSeconds: Int,
        background: Boolean, minutes: Int, action: BackgroundJobExitAction, live: ToolLiveOutputSink,
        block: suspend (ToolLiveOutputSink) -> com.promenar.infinity.data.model.ToolResult): InAppRunResult {
        require(minutes in 1..60 && yieldSeconds in 0..minutes * 60)
        val result = CompletableDeferred<com.promenar.infinity.data.model.ToolResult>()
        val ownership = CompletableDeferred<Boolean>()
        var record: BackgroundJobRecord? = null
        var handle: Active? = null
        var detached = false
        try {
            withContext(NonCancellable) {
                initialize()
                val reserved = admitted(sessionId) { reserve(sessionId, root, BackgroundJobRunner.IN_APP, summary, minutes, action) }
                record = reserved
                val activeHandle = active.getValue(reserved.id)
                handle = activeHandle
                val release = live.retain()
                val sink = liveOutput.open("job:${reserved.id}")
                activeHandle.sink = sink
                sink.onStop { scope.launch { stop(reserved.id) } }
                live.onStop { scope.launch { stop(reserved.id) } }
                activeHandle.worker = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                    try {
                        keepAlive.hold(runInfo(reserved), { scope.launch { stop(reserved.id) } }) {
                            try {
                                val value = RotatingJobLog(logFile(reserved), startedAtMillis = reserved.startedAtMillis, now = now).use { log ->
                                    val output = object : ToolLiveOutputSink by live {
                                        override fun emit(stream: ToolOutputStream, text: String) {
                                            log.write(text.toByteArray(Charsets.UTF_8)); sink.emit(stream, text); live.emit(stream, text)
                                            activeHandle.lastOutputAtMillis = now()
                                        }
                                    }
                                    withTimeout(minutes * 60_000L) { block(output) }
                                }
                                withContext(NonCancellable) {
                                    activeHandle.inputGate.withLock {
                                        val stopped = activeHandle.control.stopRequested
                                        val settled = if (stopped) com.promenar.infinity.data.model.ToolResult(value.id, "任务已停止。", "error") else value
                                        val terminal = reserved.copy(state = if (stopped) "STOPPED" else if (value.status == "success") "EXITED" else "FAILED",
                                            exitCode = if (stopped) null else if (value.status == "success") 0 else 1, endedAtMillis = now(),
                                            toolResult = settled, lastOutputAtMillis = activeHandle.lastOutputAtMillis)
                                        store.put(terminal); publish(); activeHandle.terminal.complete(Unit); result.complete(settled)
                                        if (ownership.await() && sessionAllowed(reserved) && !activeHandle.suppressCompletion)
                                            runCatching { completion.dispatch(terminal, readJobLogTail(logFile(terminal))) }
                                    }
                                }
                            } catch (failure: Throwable) {
                                withContext(NonCancellable) {
                                    val timedOut = failure is TimeoutCancellationException
                                    val terminal = activeHandle.inputGate.withLock {
                                        val stopped = activeHandle.control.stopRequested
                                        val message = if (stopped) "任务已停止。" else if (timedOut) "任务超过最长运行时间。" else "任务执行失败：${failure.message ?: failure.javaClass.simpleName}"
                                        val value = com.promenar.infinity.data.model.ToolResult("job:${reserved.id}", message, "error")
                                        reserved.copy(state = if (stopped) "STOPPED" else if (timedOut) "TIMED_OUT" else if (failure is CancellationException) "INTERRUPTED" else "FAILED",
                                            endedAtMillis = now(), guidance = message, toolResult = value, lastOutputAtMillis = activeHandle.lastOutputAtMillis).also {
                                            store.put(it); publish(); activeHandle.terminal.complete(Unit)
                                            if (failure is CancellationException && !stopped && !timedOut) result.completeExceptionally(failure) else result.complete(value)
                                        }
                                    }
                                    if (ownership.await() && sessionAllowed(reserved) && !activeHandle.suppressCompletion)
                                        runCatching { completion.dispatch(terminal, readJobLogTail(logFile(terminal))) }
                                }
                            }
                        }
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            if (!result.isCompleted) {
                                val message = "任务无法启动：${failure.message ?: failure.javaClass.simpleName}"
                                val terminal = reserved.copy(state = if (activeHandle.control.stopRequested) "STOPPED" else "FAILED", endedAtMillis = now(), guidance = message)
                                store.put(terminal); publish(); activeHandle.terminal.complete(Unit)
                                result.complete(com.promenar.infinity.data.model.ToolResult("job:${reserved.id}", message, "error"))
                                if (ownership.await() && sessionAllowed(reserved) && !activeHandle.suppressCompletion)
                                    runCatching { completion.dispatch(terminal, readJobLogTail(logFile(terminal))) }
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            activeHandle.terminal.complete(Unit); active.remove(reserved.id, activeHandle)
                            release(); liveOutput.close("job:${reserved.id}", sink)
                        }
                    }
                }
            }
            val reserved = checkNotNull(record)
            if (background) {
                detached = true; ownership.complete(true)
                return InAppRunResult(job = reserved)
            }
            val completed = awaitForegroundResult(result, yieldSeconds)
            if (completed != null) { ownership.complete(false); return InAppRunResult(result = completed) }
            currentCoroutineContext().ensureActive()
            detached = true; ownership.complete(true)
            return InAppRunResult(job = reserved, yieldedAfterSeconds = ((now() - reserved.startedAtMillis).coerceAtLeast(0) / 1000))
        } catch (failure: Throwable) {
            if (!detached) withContext(NonCancellable) {
                ownership.complete(false)
                handle?.control?.requestStop()
                if (handle?.worker != null) handle?.worker?.cancelAndJoin() else record?.let { reserved ->
                    store.updateIfPresent(reserved.id) { it.copy(state = "FAILED", endedAtMillis = now(), guidance = "任务启动未完成。") }
                    active.remove(reserved.id); publish(); handle?.terminal?.complete(Unit)
                }
            }
            throw failure
        }
    }

    /** 临时路径与任务同一份持久记录保存，断连或重启后按主机身份回收。 */
    internal suspend fun recordSftpTemporary(sessionId: String, jobId: String, path: String?, identity: String) {
        owned(sessionId, jobId)
        store.updateIfPresent(jobId) { current -> current.copy(transfer = current.transfer?.copy(
            remoteTemporaryPath = path, remoteTemporaryIdentity = if (path == null) null else identity)) }
    }

    private suspend fun recoverSftpCleanup(runtime: SshRuntime, alias: String) {
        for (record in store.all().filter { !it.running && it.remoteHost == alias && it.transfer?.remoteTemporaryPath != null }) {
            val transfer = requireNotNull(record.transfer)
            val cleaned = try { runtime.recoverTransferTemporary(alias, transfer.remotePath, requireNotNull(transfer.remoteTemporaryPath),
                transfer.remoteTemporaryIdentity ?: continue) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            if (cleaned) store.updateIfPresent(record.id) { it.copy(transfer = it.transfer?.copy(remoteTemporaryPath = null, remoteTemporaryIdentity = null)) }
        }
    }

    /** 前台命令复用 Agent 连接；传输及让出后的命令归任务持有。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun runSsh(
        runtime: SshRuntime, snapshot: SshHost, sessionId: String, root: String, source: String,
        timeoutSeconds: Int, maxOutputChars: Int, yieldSeconds: Int, background: Boolean,
        minutes: Int, action: BackgroundJobExitAction, live: ToolLiveOutputSink,
        transfer: SftpTransfer? = null,
        title: String? = null,
        capture: com.promenar.infinity.data.secret.SecretCapture? = null,
        executeTransfer: (suspend (String, SshExecControl, suspend (Long, Long, Long?) -> Unit) -> com.promenar.infinity.domain.tool.CommandToolResult)? = null,
    ): SshJobRunResult = runSshOnIo(transfer) { trace ->
        require(timeoutSeconds in 1..600 && minutes in 1..1440 && yieldSeconds in 0..(if (transfer == null) timeoutSeconds else minutes * 60))
        SshPolicy.outputLimit(maxOutputChars)
        if (source.isBlank() || source.length > 65_536 || '\u0000' in source) throw SshFailure("INVALID_COMMAND")
        val result = CompletableDeferred<com.promenar.infinity.domain.tool.CommandToolResult>()
        val ownership = CompletableDeferred<Boolean>()
        val control = SshExecControl(minutes * 60, background)
        var ownedRecord: BackgroundJobRecord? = null
        var ownedHandle: Active? = null
        var detached = false
        try {
            withContext(NonCancellable) {
                initialize()
                recoverSftpCleanup(runtime, snapshot.alias)
                val record = admitted(sessionId) { reserve(sessionId, root, BackgroundJobRunner.SSH, source, minutes, action, control, snapshot.alias, transfer?.let(SftpTransferRecord::from), title) }
                ownedRecord = record
                val handle = active.getValue(record.id)
                ownedHandle = handle
                handle.sshControl = control
                control.cancelPending = { if (!control.started.isCompleted) handle.worker?.cancel() }
                if (background || transfer != null || capture != null) runtime.registerJob(record.id, sessionId, snapshot.alias, control::requestStop, control::requestTimeLimit)
                val release = live.retain()
                val sink = liveOutput.open("job:${record.id}")
                handle.sink = sink
                if (transfer == null) sink.onStop(control::requestStop)
                else sink.onStop { scope.launch { stop(record.id) } }
                handle.worker = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                    try {
                        val environment = try { vault?.environment(sessionId, source).orEmpty() }
                        catch (_: IllegalStateException) { throw JobAccessException("JOB_SECRET_UNAVAILABLE", "引用的保密槽位已销毁，任务未执行。") }
                        if (!environment.keys.containsAll(record.secretNames)) throw JobAccessException("JOB_SECRET_UNAVAILABLE", "引用的保密槽位已销毁，任务未执行。")
                        val value = RotatingJobLog(logFile(record), startedAtMillis = record.startedAtMillis, now = now).use { log ->
                            val rawTee = object : ToolLiveOutputSink by live {
                                override fun emit(stream: ToolOutputStream, text: String) {
                                    log.write(text.toByteArray(Charsets.UTF_8))
                                    sink.emit(stream, text)
                                    live.emit(stream, text)
                                }
                                override fun onStop(stop: () -> Unit) { sink.onStop(stop); live.onStop(stop) }
                            }
                            val tee = redactor?.sink(sessionId, rawTee) ?: rawTee
                            if (transfer != null) tee.emit(ToolOutputStream.STDOUT, sftpProgressText(transfer.totalBytes))
                            trace?.workerStarted()
                            try {
                                withContext(trace ?: kotlin.coroutines.EmptyCoroutineContext) {
                                    runtime.withHostSnapshot(snapshot) {
                                        if (executeTransfer != null) executeTransfer("job:${record.id}", control) { total, done, speed ->
                                            store.updateIfPresent(record.id) { current -> current.copy(transfer = current.transfer?.copy(
                                                totalBytes = total, transferredBytes = done, bytesPerSecond = speed)) }
                                            publish()
                                            val text = sftpProgressText(total, done, speed)
                                            tee.emit(ToolOutputStream.STDOUT, text)
                                        } else if (capture != null) runtime.capture("job:${record.id}", snapshot.alias, source,
                                            timeoutSeconds, sessionId, capture, control)
                                        else runtime.exec(if (background) "job:${record.id}" else "agent:$sessionId", snapshot.alias, source,
                                            timeoutSeconds, maxOutputChars, tee, control, secretSessionId = sessionId).commandResult(if (control.background) control.maxRuntimeSeconds else timeoutSeconds)
                                    }
                                }.let { output ->
                                    trace?.next(null)
                                    if (trace == null) output else output.copy(backendFields = kotlinx.serialization.json.JsonObject(output.backendFields + trace.fields()))
                                }.also { output ->
                                    if (transfer != null) tee.emit(if (output.errorCode == null) ToolOutputStream.STDOUT else ToolOutputStream.STDERR,
                                        "\n" + (output.errorCode?.let { "$it: ${output.guidance}" } ?: "传输完成，SHA-256 校验通过。") + "\n")
                                }
                            } catch (failure: Throwable) {
                                if (transfer != null) tee.emit(ToolOutputStream.STDERR,
                                    "\n传输失败：${control.stopCause.get() ?: (failure as? SshFailure)?.code ?: "SSH_OPERATION_FAILED"}\n")
                                throw failure
                            } finally {
                                trace?.let { it.next(null); tee.emit(ToolOutputStream.STDOUT, "\n${it.summary()}\n") }
                                redactor?.finish(tee)
                            }
                        }
                        withContext(NonCancellable) {
                            val settled = if (transfer == null && control.stopRequested.get()) value.copy(
                                errorCode = "SSH_STOPPED", guidance = SSH_DISCONNECTED_GUIDANCE, exitCode = null, timedOut = false,
                            ) else value
                            val safeSettled = settled.redacted(sessionId, redactor)
                            val current = store.all().first { it.id == record.id }
                            val terminal = current.copy(
                                transfer = current.transfer?.copy(bytesPerSecond = null,
                                    sha256 = (safeSettled.backendFields["sha256"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                                    failure = safeSettled.errorCode?.let { sftpFailure(it).name }),
                                state = when { safeSettled.errorCode == "SSH_STOPPED" -> "STOPPED"; safeSettled.timedOut -> "TIMED_OUT"; safeSettled.errorCode != null -> "FAILED"; else -> "EXITED" },
                                exitCode = safeSettled.exitCode, endedAtMillis = now(), commandResult = safeSettled.toJson(),
                                guidance = safeSettled.guidance,
                            )
                            handle.inputGate.withLock {
                                // 先持久化终态，再归还连接；job_stop 在同一把任务锁内重新核对状态。
                                store.put(terminal)
                                runtime.finishJob(record.id)
                                publish(); handle.terminal.complete(Unit); result.complete(safeSettled)
                            }
                            if (ownership.await() && sessionAllowed(record) && !handle.suppressCompletion) {
                                try { completion.dispatch(terminal, readJobLogTail(logFile(terminal))) } catch (_: Exception) { }
                            }
                        }
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            val serviceLimited = transfer != null && control.stopCause.get() == "SERVICE_TIME_LIMIT"
                            val stoppedResult = if (control.stopRequested.get()) com.promenar.infinity.domain.tool.CommandToolResult(
                                errorCode = if (serviceLimited) "SSH_SERVICE_TIME_LIMIT" else "SSH_STOPPED",
                                guidance = if (transfer != null) {
                                    if (serviceLimited) "前台服务运行时限已到，传输已停止；请检查任务状态后手动重试。"
                                    else "传输已停止；请检查任务状态后决定是否重试。"
                                } else SSH_DISCONNECTED_GUIDANCE,
                                timedOut = serviceLimited,
                                backendFields = trace?.fields() ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                            ) else null
                            val current = store.all().first { it.id == record.id }
                            val terminal = current.copy(
                                transfer = current.transfer?.copy(bytesPerSecond = null, failure = when {
                                    control.stopRequested.get() -> sftpFailure(control.stopCause.get() ?: "STOPPED").name
                                    failure is CancellationException -> SftpTransferFailure.NETWORK.name
                                    else -> sftpFailure((failure as? SshFailure)?.code ?: "SSH_OPERATION_FAILED").name
                                }),
                                state = if (serviceLimited) "TIMED_OUT" else if (control.stopRequested.get()) "STOPPED" else if (failure is CancellationException) "INTERRUPTED" else "FAILED",
                                endedAtMillis = now(), guidance = stoppedResult?.guidance ?: if (failure is CancellationException) SSH_DISCONNECTED_GUIDANCE
                                    else if (failure is JobAccessException) "${failure.code}：${failure.message}" else "SSH 后台命令无法执行，请检查主机配置和网络；不要自动重试。",
                                commandResult = stoppedResult?.toJson(),
                            )
                            runtime.finishJob(record.id)
                            store.put(terminal); publish(); handle.terminal.complete(Unit)
                            if (stoppedResult != null) result.complete(stoppedResult) else result.completeExceptionally(failure)
                            if (ownership.await() && sessionAllowed(record) && !handle.suppressCompletion) {
                                try { completion.dispatch(terminal, readJobLogTail(logFile(terminal))) } catch (_: Exception) { }
                            }
                        }
                    } finally {
                        withContext(NonCancellable) {
                            try { runtime.finishJob(record.id) } finally {
                                handle.terminal.complete(Unit); active.remove(record.id, handle)
                                control.cancelPending = null
                                release(); liveOutput.close("job:${record.id}", sink)
                            }
                        }
                    }
                }
            }
            val record = checkNotNull(ownedRecord)
            if (background) {
                detached = true
                ownership.complete(true)
                return@runSshOnIo SshJobRunResult(job = record)
            }
            var interruptedBeforeStart = false
            val completed = awaitForegroundResult(result, yieldSeconds, control.started)
            if (completed != null) {
                ownership.complete(false)
                return@runSshOnIo SshJobRunResult(command = completed)
            }
            currentCoroutineContext().ensureActive()
            val promoted = withContext(NonCancellable) {
                val promote = control.promote
                if (promote == null) {
                    // 连接尚未建立，没有已启动的远端命令；取消准备并配对中断结果。
                    interruptedBeforeStart = true
                    ownership.complete(false)
                    control.requestStop()
                    ownedHandle?.worker?.cancelAndJoin()
                    false
                } else promote.invoke(record.id, sessionId).also { transferred ->
                    if (transferred) { detached = true; ownership.complete(true) }
                }
            }
            if (!promoted) {
                ownership.complete(false)
                if (interruptedBeforeStart) return@runSshOnIo SshJobRunResult(command = com.promenar.infinity.domain.tool.CommandToolResult(
                    errorCode = "EXEC_INTERRUPTED", guidance = "用户插队，SSH 连接准备已中断，命令未执行。"))
                return@runSshOnIo SshJobRunResult(command = result.await())
            }
            // 与取消清理串行交接；一旦归属已转移，调用者取消不再终止任务。
            detached = true
            ownership.complete(true)
            SshJobRunResult(job = record, yieldedAfterSeconds = yieldSeconds)
        } catch (failure: Throwable) {
            if (!detached) withContext(NonCancellable) {
                ownership.complete(false)
                control.requestStop()
                val handle = ownedHandle
                if (handle?.worker != null) handle.worker?.cancelAndJoin() else {
                    ownedRecord?.let { record ->
                        store.put(record.copy(state = "FAILED", endedAtMillis = now(), guidance = "SSH 任务启动未完成。"))
                        runtime.finishJob(record.id)
                        active.remove(record.id)
                        publish()
                    }
                }
            }
            throw failure
        }
    }

    /** 前台等待、手动转后台与插队共用同一选择点。SSH 在连接就绪后开始计时。 */
    private suspend fun <T> awaitForegroundResult(result: Deferred<T>, yieldSeconds: Int, started: Deferred<*>? = null): T? {
        val steering = currentCoroutineContext()[SteeringContext]
        return ToolExecutionControls.controlled(ToolExecutionControls.Kind.FOREGROUND, releaseToBackground = true) { release ->
            coroutineScope {
                val insertion = steering?.let { async(start = CoroutineStart.UNDISPATCHED) { it.await() } }
                val automatic = if (yieldSeconds > 0) async(if (started == null) Dispatchers.Default else kotlin.coroutines.EmptyCoroutineContext) {
                    if (started != null) kotlinx.coroutines.selects.select<Unit> { result.onAwait { }; started.onAwait { } }
                    delay(yieldSeconds * 1_000L)
                } else null
                try {
                    kotlinx.coroutines.selects.select<T?> {
                        result.onAwait { it }; release.onAwait { null }; insertion?.onAwait { null }; automatic?.onAwait { null }
                    }
                } finally { insertion?.cancel(); automatic?.cancel() }
            }
        }
    }

    private suspend fun <T> runSshOnIo(transfer: SftpTransfer?, block: suspend (SftpTransferTrace?) -> T): T {
        val trace = transfer?.let { SftpTransferTrace() }
        return withContext(Dispatchers.IO) { block(trace) }
    }

    /** 保密输入只经字节接口写入终端，登记遮蔽后发送，调用方数组无论成功失败均清零。 */
    suspend fun input(sessionId: String, id: String, value: ByteArray, newline: Boolean = true, eof: Boolean = false): BackgroundJobStatus {
        try { return withContext(Dispatchers.IO) {
            require(value.isNotEmpty() && value.size <= SecretIoLimits.MAX_SECRET_BYTES && 0.toByte() !in value) { "保密输入为空、过长或包含 NUL。" }
            val record = owned(sessionId, id)
            if (!record.running) throw JobAccessException("JOB_NOT_RUNNING", "任务已经结束，不能写入输入。")
            if (record.runnerKind == BackgroundJobRunner.SSH) throw JobAccessException("JOB_INPUT_UNSUPPORTED", "SSH 后台命令不支持输入。")
            val handle = active[id] ?: throw JobAccessException("JOB_INPUT_UNAVAILABLE", "任务输入不可用。")
            val redact = redactor ?: throw JobAccessException("JOB_SECRET_REDACTOR_UNAVAILABLE", "保密输出遮蔽不可用，输入未发送。")
            handle.inputGate.withLock {
                if (handle.control.inputBytes == null) throw JobAccessException("JOB_INPUT_UNAVAILABLE", "任务没有可写的交互输入。")
                redact.registerJobInput(sessionId, id, value)
                val bytes = if (newline) value + byteArrayOf(13) else value.copyOf()
                try {
                    withTimeout(5_000) { runInterruptible { handle.control.writeInput(bytes, eof) } }
                } catch (_: TimeoutCancellationException) {
                    throw JobAccessException("JOB_INPUT_TIMEOUT", "输入写入超时，请查询任务状态。")
                } catch (_: java.io.IOException) {
                    throw JobAccessException("JOB_INPUT_FAILED", "输入写入失败，请查询任务状态。")
                } finally { bytes.fill(0) }
            }
            status(sessionId, id)
        } } finally { value.fill(0) }
    }

    suspend fun input(sessionId: String, id: String, value: CharArray, newline: Boolean = true, eof: Boolean = false): BackgroundJobStatus {
        var bytes: ByteArray? = null
        try {
            val encoded = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(value))
            bytes = ByteArray(encoded.remaining()).also(encoded::get)
            if (encoded.hasArray()) encoded.array().fill(0)
            return input(sessionId, id, bytes, newline, eof)
        } finally { value.fill('\u0000'); bytes?.fill(0) }
    }

    suspend fun input(sessionId: String, id: String, text: String, newline: Boolean = true, eof: Boolean = false): BackgroundJobStatus = withContext(Dispatchers.IO) {
        require(text.length <= 32_000 && '\u0000' !in text) { "text 应为不含 NUL 的字符串，最多32000字符。" }
        val record = owned(sessionId, id)
        if (record.runnerKind == BackgroundJobRunner.SSH) throw JobAccessException("JOB_INPUT_UNSUPPORTED", "SSH 后台命令不支持输入；交互式程序请使用 ssh_session_*。")
        if (!record.running) throw JobAccessException("JOB_NOT_RUNNING", "任务已经结束，不能写入输入。")
        val handle = active[id] ?: throw JobAccessException("JOB_INPUT_UNAVAILABLE", "任务输入不可用，请查询任务状态。")
        if (handle.control.input == null) throw JobAccessException("JOB_INPUT_UNAVAILABLE", "该任务没有交互输入；需要交互的 Linux shell 命令不要传 stdin。")
        handle.inputGate.withLock {
            val before = status(sessionId, id).tail.text
            try {
                withTimeout(5_000) { runInterruptible { handle.control.writeInput(text + if (newline) "\r" else "", eof) } }
            } catch (_: TimeoutCancellationException) {
                throw JobAccessException("JOB_INPUT_TIMEOUT", "输入写入超时，请用 job_status 查看状态后再决定是否重试。")
            } catch (failure: java.io.IOException) {
                if (store.all().any { it.id == id && !it.running } || handle.control.input == null) throw JobAccessException("JOB_NOT_RUNNING", "任务已经结束，不能写入输入。")
                throw JobAccessException("JOB_INPUT_FAILED", "输入写入失败，请用 job_status 查看状态。")
            }
            delay(1_000)
            val after = status(sessionId, id)
            after.copy(tail = after.tail.copy(text = if (after.tail.text.startsWith(before)) after.tail.text.drop(before.length) else after.tail.text))
        }
    }

    suspend fun startTermux(sessionId:String, workspaceRootUuid:String, source:String, workdir:String?, stdin:String?, minutes:Int, action:BackgroundJobExitAction, title:String? = null, waitForStart: Boolean = true):BackgroundJobRecord = scope.async(Dispatchers.IO) { admitted(sessionId) {
        initialize()
        TermuxCommandRequest(source,workdir,stdin=stdin)
        val backend=termux ?: throw JobAccessException("JOB_TERMUX_UNAVAILABLE","Termux 后台接入不可用，请检查工具设置。")
        val record=reserve(sessionId,workspaceRootUuid,BackgroundJobRunner.TERMUX,source,minutes,action,title=title)
        val handle=active.getValue(record.id)
        val started=CompletableDeferred<BackgroundJobRecord>()
        handle.worker=scope.launch(Dispatchers.IO) {
            val sink=liveOutput.open("job:${record.id}")
            handle.sink=termuxLogSink(record, handle, sink)
            sink.onStop {scope.launch{stop(record.id)}}
            var requested=false
            try {
                keepAlive.hold(runInfo(record), {scope.launch{stop(record.id)}}) {
                    try {
                        val location=prepareWorkspace(record)
                        val shared=location.workspaceDirectory.takeIf{location.termuxShared}
                        val routed=store.updateIfPresent(record.id) {latest -> if(latest.running) latest.copy(workspaceDirectory=shared?.path,logWorkspacePath=BackgroundJobLogs.reference(record.id)) else latest}
                        if(routed?.running==true) {
                            // 定位先落盘；启动回包丢失也能按任务号恢复控制。
                            requested=true
                            val remote=backend.start(record.id,source,workdir,stdin,minutes,shared)
                            store.updateIfPresent(record.id) { latest -> if(latest.running) latest.copy(termuxDirectory=remote.directory,termuxPid=remote.pid) else latest }
                        }
                    } catch(cancelled:CancellationException) {throw cancelled
                    } catch(failure:Exception) {
                        val unknown=requested && failure !is IllegalArgumentException
                        val saved=store.updateIfPresent(record.id) { latest -> if(!latest.running) latest else if(unknown) latest.copy(guidance="Termux 启动结果尚未确认；请用 job_status 查询，或用 job_stop 撤销该任务，避免重复执行。")
                            else latest.copy(state="FAILED",endedAtMillis=now(),guidance="Termux 参数或工作区准备失败，请检查工作目录与输入长度。") }
                        if(saved!=null && !saved.running) {handle.completion.complete(saved to JobLogTail("",false));handle.terminal.complete(Unit)}
                    }
                    publish()
                    started.complete(store.all().find{it.id==record.id} ?: record)
                    monitorTermuxLoop(record,handle)
                }
            } catch(failure:Exception) {
                if(!started.isCompleted) started.completeExceptionally(failure)
                withContext(NonCancellable) {
                    if(!requested) store.updateIfPresent(record.id) {latest -> if(latest.running) latest.copy(state="FAILED",endedAtMillis=now(),guidance="任务保活初始化失败，请检查后台运行授权。") else latest}
                    publish()
                }
            } finally {
                handle.terminal.complete(Unit)
                handle.termuxOutput?.let { redactor?.finish(it) }
                redactor?.clearJob(record.sessionId, record.id)
                active.remove(record.id,handle)
                liveOutput.close("job:${record.id}",sink)
            }
        }
        if (waitForStart) started.await() else record
    } }.await()
    private fun monitorTermux(record:BackgroundJobRecord) {
        val handle=Active()
        if(active.putIfAbsent(record.id,handle)!=null) return
        handle.worker=scope.launch(Dispatchers.IO) {
            val sink=liveOutput.open("job:${record.id}")
            handle.sink=termuxLogSink(record, handle, sink)
            sink.onStop {scope.launch{stop(record.id)}}
            try {
                keepAlive.hold(runInfo(record),{scope.launch{stop(record.id)}}) { monitorTermuxLoop(record,handle) }
            } finally {handle.termuxOutput?.let { redactor?.finish(it) };handle.terminal.complete(Unit);active.remove(record.id,handle);liveOutput.close("job:${record.id}",sink)}
        }
    }
    private fun termuxLogSink(record: BackgroundJobRecord, handle: Active, live: ToolLiveOutputSink) = object : ToolLiveOutputSink by live {
        private fun append(text: String) {
            if (text.isNotEmpty()) RotatingJobLog(logFile(record), startedAtMillis = record.startedAtMillis, now = now).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        override fun emit(stream: ToolOutputStream, text: String) { append(text); live.emit(stream, text) }
        override fun replace(stream: ToolOutputStream, text: String) {
            // 此入口只接收遮蔽器发布的安全快照，未确认的秘密短尾不会落盘。
            val delta = newOutputSuffix(handle.lastSafeTail, text)
            append(delta)
            handle.lastSafeTail = text
            live.replace(stream, text)
        }
    }

    private suspend fun monitorTermuxLoop(record:BackgroundJobRecord,handle:Active) {
        while(currentCoroutineContext().isActive) {
            if(!sessionAllowed(record)) {delay(100);continue}
            val current=store.all().find{it.id==record.id}
            if(current==null || !current.running) break
            try { refreshTermux(record) } catch(cancelled:CancellationException){throw cancelled} catch(_:Exception) { /* 接入暂不可用时保留运行态，查询返回可操作错误。 */ }
            if(store.all().none{it.id==record.id && it.running}) break
            delay(2_000)
        }
        if(sessionAllowed(record) && !handle.suppressCompletion) {
            val (ended,tail)=handle.completion.await()
            completion.dispatch(ended,tail)
        }
    }
    private fun sessionAllowed(record:BackgroundJobRecord)=record.sessionId !in deletedSessions
    private fun termuxHandle(record:BackgroundJobRecord)=TermuxJobHandle(checkNotNull(record.termuxDirectory),checkNotNull(record.termuxPid))
    private suspend fun refreshTermux(record: BackgroundJobRecord, onRefreshed: (() -> Unit)? = null): JobLogTail = refreshGate.withLock {
        refreshTermuxLocked(record).also { onRefreshed?.invoke() }
    }

    /** 刷新游标与新监听登记共用临界区，监控查询不能从两者之间穿过。 */
    private suspend fun refreshTermuxLocked(record: BackgroundJobRecord): JobLogTail {
        var current=store.all().find{it.id==record.id} ?: return JobLogTail("",false)
        if(current.termuxPid==null) {
            val restored=checkNotNull(termux).recover(current.id,current.workspaceDirectory?.let(::File))
                ?: return JobLogTail("启动结果尚未确认；可用 job_stop 撤销，避免重复执行。",false)
            current=current.copy(termuxDirectory=restored.directory,termuxPid=restored.pid,guidance=null)
            store.put(current)
        }
        val backend = checkNotNull(termux)
        val result = try { backend.inspect(termuxHandle(current)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val recovered = backend.recover(current.id, current.workspaceDirectory?.let(::File)) ?: throw failure
            current = current.copy(termuxDirectory = recovered.directory, termuxPid = recovered.pid)
            store.put(current)
            backend.inspect(recovered)
        }
        if (!result.alive && redactor == null) {
            try {
                val migrated = backend.migrateCompleted(current.id, current.workspaceDirectory?.let(::File), termuxHandle(current))
                current = current.copy(termuxDirectory = migrated.directory, termuxPid = migrated.pid,
                    logWorkspacePath = BackgroundJobLogs.reference(current.id))
                store.put(current)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                current = current.copy(guidance = "Termux 日志迁移未完成（${failure::class.java.simpleName}），旧任务文件已保留。")
                store.put(current)
            }
        }
        // Termux 自己持有原始日志；存在保密库时不让外部脚本原样复制或迁移该日志。
        // 原位置保持可恢复，应用只发布经过遮蔽的状态与实时文本。
        val safeTail = redactor?.redact(current.sessionId, result.logTail) ?: result.logTail
        active[record.id]?.let { running ->
            val delta = newJobOutput(running.lastTail, running.lastLogBytes, result.logTail, result.logBytes)
            if (redactor != null) {
                if (!running.termuxOutputFinished) {
                    val sink = running.termuxOutput ?: redactor.sink(current.sessionId, running.sink).also { running.termuxOutput = it }
                    if (result.logBytes != null) sink.emit(ToolOutputStream.STDOUT, delta)
                    else sink.replace(ToolOutputStream.STDOUT, result.logTail)
                    if (!result.alive) { redactor.finish(sink); running.termuxOutputFinished = true }
                }
            } else if (delta.isNotEmpty()) {
                running.sink.emit(ToolOutputStream.STDOUT, delta)
            }
            running.lastTail = result.logTail
            running.lastLogBytes = result.logBytes
        }
        val tail=JobLogTail(safeTail.takeLast(8_000),result.truncated || safeTail.length>8_000)
        if(current.running && !result.alive) {
            val next=current.copy(state=when {result.timedOut->"TIMED_OUT";result.stopped->"STOPPED";result.exitCode!=null->"EXITED";else->"FAILED"},exitCode=result.exitCode,endedAtMillis=now())
            // 终态落盘与所有权交接不可被一次查询的取消拆开。
            withContext(NonCancellable) {
                store.put(next)
                active[record.id]?.terminal?.complete(Unit)
                active[record.id]?.completion?.complete(next to tail)
                publish()
            }
        }
        return tail
    }
    private suspend fun finish(record:BackgroundJobRecord,handle:Active) {
        var settled=record.copy(lastOutputAtMillis = handle.lastOutputAtMillis ?: record.lastOutputAtMillis)
        var reconciled=false
        val warning=try { (if (hasIsolatedWorkspace(record)) reconcile(record,true,false) else null).also { reconciled=true }} catch(_:Exception){"工作区合入或日志发布失败，临时副本已保留；请检查工作区后处理任务产物。"}
        if(reconciled && record.isolatedWorkspace) {
            // 完成状态与日志仍可查询，完整工作区副本不再长期保留。
            val directory=File(runRoot,record.id)
            if(directory.canonicalFile.parentFile==runRoot.canonicalFile) deleteJobTree(File(directory,"workspace"))
        }
        if(warning!=null) settled=settled.copy(guidance=listOfNotNull(settled.guidance,warning).joinToString(" "))
        settled = redactedRecord(settled)
        store.put(settled);publish();handle.terminal.complete(Unit)
        try {
            if(sessionAllowed(record)) {
                try { completion.dispatch(settled, readJobLogTail(logFile(settled)).let { it.copy(text = redactor?.redact(record.sessionId, it.text) ?: it.text) }) }
                catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { /* 完成通知失败不能改写真实进程终态。 */ }
            }
        } finally { requestReconcile(settled) }
    }
    private fun hasIsolatedWorkspace(record: BackgroundJobRecord) =
        !record.directWorkspace && (record.isolatedWorkspace || File(record.runDirectory, "baseline.json").isFile)
    private fun runInfo(record:BackgroundJobRecord)=ExecRunInfo(record.id,execRunKind(record.runnerKind),record.summary,record.maxRuntimeMinutes*60,record.startedAtMillis,background=true,host=record.remoteHost)
    private fun logFile(record:BackgroundJobRecord):File =
        BackgroundJobLogs.legacyFile(record) ?: BackgroundJobLogs.file(record, runRoot)
    private suspend fun owned(sessionId:String,id:String, initializeStore: Boolean = true):BackgroundJobRecord {
        if (initializeStore) initialize()
        return store.all().find{it.id==id && it.sessionId==sessionId && sessionId !in deletedSessions}
            ?: throw JobAccessException("JOB_NOT_FOUND","本会话找不到该任务，请用 job_list 获取当前会话的任务号。")
    }
    override suspend fun readLog(sessionId: String, reference: String): com.promenar.infinity.domain.jobs.BackgroundJobLog {
        val id = BackgroundJobLogs.jobId(reference)
            ?: throw JobAccessException("JOB_NOT_FOUND", "任务日志引用无效。")
        val status = status(sessionId, id)
        val record = owned(sessionId, id, initializeStore = false)
        val tail = withContext(Dispatchers.IO) {
            if (logFile(record).isFile) readJobLogTail(logFile(record), stripTimestamps = false) else status.tail
        }
        return com.promenar.infinity.domain.jobs.BackgroundJobLog(redactor?.redact(sessionId, tail.text) ?: tail.text, tail.truncated)
    }
    suspend fun list(sessionId:String):List<BackgroundJobRecord> { initialize(); return store.all().filter{it.sessionId==sessionId && sessionId !in deletedSessions}.map(::redactedRecord).sortedByDescending{it.startedAtMillis} }
    private fun redactedRecord(record: BackgroundJobRecord): BackgroundJobRecord {
        val redact = redactor ?: return record
        return record.copy(summary = redact.redact(record.sessionId, record.summary), guidance = record.guidance?.let { redact.redact(record.sessionId, it) },
            commandResult = record.commandResult?.let { value ->
                // 既有 SSH 结果在存盘前已遮蔽；读取历史记录时再进行完整 JSON 字符串值遮蔽。
                redactCommandJson(record.sessionId, value, redact)
            })
    }
    suspend fun status(sessionId:String,id:String, refreshRemote: Boolean = true):BackgroundJobStatus = withContext(Dispatchers.IO) {
        initialize()
        var record=owned(sessionId,id, initializeStore = false)
        val warning = reconcileWarning(record)
        val tail=if(record.runnerKind==BackgroundJobRunner.TERMUX && refreshRemote) refreshTermux(record) else {
            val snapshot = active[id]?.control?.outputSnapshot
            val raw = snapshot?.let { JobLogTail(it.stdout.takeLast(8_000), it.stdoutTruncated || it.stdout.length > 8_000) } ?: readJobLogTail(logFile(record))
            val directory = record.workspaceDirectory?.let(::File)
            if (directory == null) raw else raw.copy(text = BuiltinExecService.virtualize(raw.text, directory))
        }
        record=owned(sessionId,id, initializeStore = false)
        val lastOutputAt = active[id]?.lastOutputAtMillis ?: record.lastOutputAtMillis
            ?: logFile(record).takeIf { it.isFile && it.length() > 0 }?.lastModified()
        BackgroundJobStatus(redactedRecord(record.copy(guidance=listOfNotNull(record.guidance,warning).distinct().joinToString(" ").ifEmpty { null })),
            tail.copy(text = redactor?.redact(sessionId, tail.text) ?: tail.text),
            lastOutputSecondsAgo = lastOutputAt?.let { ((now() - it).coerceAtLeast(0)) / 1000 })
    }
    suspend fun waitFor(sessionId: String, id: String, timeoutSeconds: Int, untilOutput: String? = null, untilFile: String? = null, foreground: Boolean = false): BackgroundJobStatus {
        require(timeoutSeconds in 1..600) { "timeout_seconds 必须为 1–600" }
        initialize()
        val record = owned(sessionId, id, initializeStore = false)
        val pattern = untilOutput?.let { require(it.length <= 2_000) { "until_output 正则过长" }; Regex(it) }
        val root = untilFile?.let {
            // SSH 任务没有本机执行工作区，不能把远端路径映射到本机。
            require(record.runnerKind != BackgroundJobRunner.SSH) { "SSH 任务不支持 until_file" }
            val directory = record.workspaceDirectory?.let(::File) ?: throw IllegalArgumentException("任务工作区尚未就绪，不能使用 until_file")
            jobWaitFile(directory, it)
            directory
        }
        val steering = currentCoroutineContext()[SteeringContext]
        var unsubscribe: (() -> Unit)? = null
        try {
            val outcome = ToolExecutionControls.controlled(if (foreground) ToolExecutionControls.Kind.FOREGROUND else ToolExecutionControls.Kind.WAITING,
                releaseToBackground = foreground) { release ->
                coroutineScope {
                    val operation = async {
                        // 远端基线查询也属于可释放等待；查询取消不会停止原任务。
                        val condition = pattern?.let(::JobOutputCondition)
                        val subscribe = { unsubscribe = condition?.let { liveOutput.listenOutput("job:$id", it::accept) } }
                        if (pattern != null && record.runnerKind == BackgroundJobRunner.TERMUX) refreshTermux(record, subscribe)
                        else subscribe()
                        coroutineScope {
                            val terminal = async(start = CoroutineStart.UNDISPATCHED) {
                                if (record.running) {
                                    val handle = active[id]
                                    if (handle != null) handle.terminal.await()
                                    else state.first { jobs -> jobs.none { it.id == id && it.state == BackgroundJobState.RUNNING } }
                                }
                            }
                            val file = root?.let { directory -> async { awaitJobFile(directory, checkNotNull(untilFile)) } }
                            try {
                                kotlinx.coroutines.selects.select<JobWaitOutcome> {
                                    terminal.onAwait { JobWaitOutcome(JobWaitReason.TERMINAL) }
                                    condition?.matched?.onAwait { JobWaitOutcome(JobWaitReason.OUTPUT, it) }
                                    file?.onAwait { JobWaitOutcome(JobWaitReason.FILE) }
                                }
                            } finally { terminal.cancel(); file?.cancel() }
                        }
                    }
                    val insertion = steering?.let { async(start = CoroutineStart.UNDISPATCHED) { it.await() } }
                    try {
                        withTimeoutOrNull(timeoutSeconds * 1_000L) {
                            kotlinx.coroutines.selects.select<JobWaitOutcome> {
                                operation.onAwait { it }
                                release.onAwait { JobWaitOutcome(JobWaitReason.RELEASED) }
                                insertion?.onAwait { JobWaitOutcome(JobWaitReason.STEERING) }
                            }
                        } ?: JobWaitOutcome(JobWaitReason.TIMEOUT)
                    } finally { withContext(NonCancellable) { operation.cancelAndJoin(); insertion?.cancelAndJoin() } }
                }
            }
            // 唤醒后直接回传已落盘快照，避免新的 Termux 广播查询延迟插队或手动释放。
            return status(sessionId, id, refreshRemote = false).copy(timedOut = outcome.reason == JobWaitReason.TIMEOUT,
                waitReason = outcome.reason.name.lowercase(), matchedLine = outcome.matchedLine?.let { redactor?.redact(sessionId, it) ?: it })
        } finally { unsubscribe?.invoke() }
    }
    override suspend fun stop(jobId:String):Boolean = withContext(Dispatchers.IO) {
        initialize()
        val record=store.all().find{it.id==jobId && it.running} ?: return@withContext false
        val handle=active[jobId]
        if (record.runnerKind == BackgroundJobRunner.IN_APP) {
            val accepted = if (handle == null) false else handle.inputGate.withLock {
                if (store.all().none { it.id == jobId && it.running }) false else {
                    handle.control.requestStop(); handle.worker?.cancel(); true
                }
            }
            if (!accepted) return@withContext false
        } else if (record.runnerKind == BackgroundJobRunner.SSH) {
            val accepted = if (handle == null) false else handle.inputGate.withLock {
                if (store.all().none { it.id == jobId && it.running }) false
                else { handle.sshControl?.requestStop(); true }
            }
            if (!accepted) return@withContext false
        } else if(record.runnerKind==BackgroundJobRunner.TERMUX) {
            refreshGate.withLock {
                val latest=store.all().find{it.id==jobId} ?: return@withLock
                if(!latest.running) return@withLock
                val result=if(latest.termuxPid==null) checkNotNull(termux).stopUnconfirmed(latest.id,latest.workspaceDirectory?.let(::File))
                    else checkNotNull(termux).stop(termuxHandle(latest))
                if(result?.alive==true) throw JobAccessException("JOB_STOP_UNCONFIRMED","Termux 进程组尚未确认停止，请检查 Termux 后重试 job_stop。")
                val next=latest.copy(state=when {result==null->"STOPPED";result.timedOut->"TIMED_OUT";result.stopped->"STOPPED";result.exitCode!=null->"EXITED";else->"FAILED"},exitCode=result?.exitCode,endedAtMillis=now())
                withContext(NonCancellable) {
                    store.put(next)
                    handle?.terminal?.complete(Unit)
                    handle?.completion?.complete(next to JobLogTail(result?.logTail.orEmpty(),result?.truncated ?: false))
                    publish()
                }
            }
        } else {
            handle?.control?.requestStop()
            if (record.directWorkspace && handle != null && !handle.control.started.isCompleted) handle.worker?.cancel()
        }
        handle?.terminal?.await()
        true
    }
    suspend fun stop(sessionId:String,id:String):BackgroundJobStatus { owned(sessionId,id);stop(id);return status(sessionId,id) }
    /** 删除事务准备阶段只停止任务，事务失败后会话仍可使用。 */
    suspend fun stopSession(sessionId:String) = withContext(NonCancellable + Dispatchers.IO) {
        initialize()
        gate.withLock {deletedSessions+=sessionId}
        try {
            val records=store.all().filter{it.sessionId==sessionId}
            records.forEach { active[it.id]?.suppressCompletion=true }
            for(record in records.filter{it.running}) stop(record.id)
            records.forEach {active[it.id]?.worker?.cancelAndJoin()}
        } finally {
            store.all().filter{it.sessionId==sessionId}.forEach{active[it.id]?.suppressCompletion=false}
            gate.withLock {deletedSessions-=sessionId}
        }
    }
    suspend fun deleteSession(sessionId:String) = withContext(NonCancellable + Dispatchers.IO) {
        initialize()
        gate.withLock {deletedSessions+=sessionId}
        var deleted=false
        try {
            val records=store.all().filter{it.sessionId==sessionId}
            records.forEach { active[it.id]?.suppressCompletion=true }
            for(record in records.filter{it.running}) stop(record.id)
            records.forEach {active[it.id]?.worker?.cancelAndJoin()}
            records.forEach {removeRunDirectory(it.id)}
            store.deleteSession(sessionId)
            deleted=true
            publish()
        } finally {
            if(!deleted) {
                store.all().filter{it.sessionId==sessionId}.forEach{active[it.id]?.suppressCompletion=false}
                gate.withLock{deletedSessions-=sessionId}
            }
        }
    }
}

/** 后台任务在运行通知与运行详情中的类型；SSH 有自己的类型，不当作本机 shell 显示。 */
internal fun execRunKind(runner: BackgroundJobRunner): ExecRunKind = when (runner) {
    BackgroundJobRunner.PYTHON -> ExecRunKind.PYTHON
    BackgroundJobRunner.LINUX -> ExecRunKind.LINUX
    BackgroundJobRunner.SSH -> ExecRunKind.SSH
    BackgroundJobRunner.SHELL, BackgroundJobRunner.TERMUX, BackgroundJobRunner.IN_APP -> ExecRunKind.SHELL
}
