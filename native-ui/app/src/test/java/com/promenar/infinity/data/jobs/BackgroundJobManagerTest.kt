package com.promenar.infinity.data.jobs

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.exec.*
import com.promenar.infinity.data.secret.SessionSecretVault
import com.promenar.infinity.data.secret.SecretOutputRedactor
import com.promenar.infinity.domain.secret.SecretSource
import com.promenar.infinity.domain.jobs.*
import com.promenar.infinity.domain.trigger.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test

class BackgroundJobManagerTest {
    @Test fun `Termux重复尾部通过真实字节游标触发输出等待`() = runBlocking {
        val root = Files.createTempDirectory("job-termux-output-position").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val bytes = java.util.concurrent.atomic.AtomicLong(5)
            val encoded = java.util.Base64.getEncoder().encodeToString("ready".toByteArray())
            val backend = com.promenar.infinity.data.termux.TermuxBackgroundJobs {
                com.promenar.infinity.data.termux.TermuxCommandResult(stdout = "INFINITY_JOB_V1\n-\n1\n0\n0\n${bytes.get()}\n$encoded\n", exitCode = 0)
            }
            val store = BackgroundJobStore(File(root, "store"))
            store.put(sample().copy(runner = "TERMUX", runDirectory = File(root, "runs/job-a").path,
                termuxDirectory = "/termux/jobs/job-a", termuxPid = 123))
            val listening = CompletableDeferred<Unit>()
            val live = io.mockk.spyk(ToolLiveOutputStore())
            io.mockk.every { live.listenOutput("job:job-a", any()) } answers {
                callOriginal().also { listening.complete(Unit) }
            }
            val manager = manager(root, scope, store, termux = backend, live = live)
            manager.initialize()
            val waiting = async(ToolCallControlContext("s", "termux-wait", "termux-step")) { manager.waitFor("s", "job-a", 5, untilOutput = "^ready$") }
            // 控制租约覆盖基线查询；输出观察窗口从真实订阅完成后开始。
            withTimeout(5_000) { listening.await() }
            assertThat(waiting.isCompleted).isFalse()
            bytes.set(10)
            val returned = waiting.await()
            assertThat(returned.waitReason).isEqualTo("output")
            assertThat(returned.matchedLine).isEqualTo("ready")
            assertThat(returned.tail.text).doesNotContain("[+")
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun `Termux基线完成与输出订阅之间的并发刷新不能丢增量`() = runBlocking {
        val root = Files.createTempDirectory("job-termux-subscribe-race").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val bytes = java.util.concurrent.atomic.AtomicLong(5)
            val encoded = java.util.Base64.getEncoder().encodeToString("ready".toByteArray())
            val backend = com.promenar.infinity.data.termux.TermuxBackgroundJobs {
                com.promenar.infinity.data.termux.TermuxCommandResult(stdout = "INFINITY_JOB_V1\n-\n1\n0\n0\n${bytes.get()}\n$encoded\n", exitCode = 0)
            }
            val store = BackgroundJobStore(File(root, "store"))
            store.put(sample().copy(runner = "TERMUX", runDirectory = File(root, "runs/job-a").path,
                termuxDirectory = "/termux/jobs/job-a", termuxPid = 123))
            lateinit var jobManager: BackgroundJobManager
            val live = io.mockk.spyk(ToolLiveOutputStore())
            val refreshFinished = java.util.concurrent.CountDownLatch(1)
            io.mockk.every { live.listenOutput("job:job-a", any()) } answers {
                // 停在真实订阅前，让另一个查询尝试推进字节游标；基线锁必须覆盖这个窗口。
                bytes.set(10)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try { jobManager.status("s", "job-a") } finally { refreshFinished.countDown() }
                }
                assertThat(refreshFinished.await(1, java.util.concurrent.TimeUnit.SECONDS)).isFalse()
                callOriginal()
            }
            jobManager = manager(root, scope, store, termux = backend, live = live)
            jobManager.initialize()
            val returned = withTimeout(5_000) {
                withContext(Dispatchers.IO) { jobManager.waitFor("s", "job-a", 4, untilOutput = "^ready$") }
            }
            assertThat(returned.waitReason).isEqualTo("output")
            assertThat(returned.matchedLine).isEqualTo("ready")
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun `Termux输出基线查询未返回时插队立即得到本地状态`() = runBlocking {
        val root = Files.createTempDirectory("job-termux-wait-interrupt").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val querying = CompletableDeferred<Unit>()
            val backend = com.promenar.infinity.data.termux.TermuxBackgroundJobs {
                querying.complete(Unit)
                awaitCancellation()
            }
            val store = BackgroundJobStore(File(root, "store"))
            store.put(sample().copy(runner = "TERMUX", runDirectory = File(root, "runs/job-a").path,
                termuxDirectory = "/termux/jobs/job-a", termuxPid = 123))
            val jobs = manager(root, scope, store, termux = backend)
            jobs.initialize()
            val queue = kotlinx.coroutines.flow.MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
            val waiting = async(SteeringContext(queue) + ToolCallControlContext("s", "termux-check", "step")) {
                jobs.waitFor("s", "job-a", 600, untilOutput = "ready")
            }
            withTimeout(5_000) { querying.await(); ToolExecutionControls.observe("s", "termux-check").first { it != null } }
            queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
                com.promenar.infinity.data.chat.queue.QueuedMessage("q", "插队", createdAt = 0, steering = true)))
            val returned = withTimeout(1_000) { waiting.await() }
            assertThat(returned.waitReason).isEqualTo("steering")
            assertThat(returned.record.running).isTrue()
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun `Termux前台在启动回执未返回时也可插队转后台`() = runBlocking {
        val root = Files.createTempDirectory("job-termux-foreground").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CompletableDeferred<Unit>()
        try {
            val started = CompletableDeferred<Unit>()
            val directory = java.util.Base64.getEncoder().encodeToString("/termux/jobs/test".toByteArray())
            val backend = com.promenar.infinity.data.termux.TermuxBackgroundJobs { request ->
                if ("launch=" in request.command) { started.complete(Unit); release.await() }
                com.promenar.infinity.data.termux.TermuxCommandResult(stdout = "INFINITY_JOB_V1\n$directory\n12345\n", exitCode = 0)
            }
            val jobs = manager(root, scope, BackgroundJobStore(File(root, "store")), termux = backend)
            val queue = kotlinx.coroutines.flow.MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
            val context = object : com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext {
                override val sessionId = "s"
                override val agentId = "a"
                override val workspacePath: String? = null
                override val workspaceRootUuid = "root"
            }
            val skill = com.promenar.infinity.ui.chat.manager.skills.TermuxExecSkill({ error("不使用单次前台广播") }, jobs) { true }
            val waiting = async(SteeringContext(queue) + ToolCallControlContext("s", "termux-exec", "step")) {
                skill.execute(kotlinx.serialization.json.Json.parseToJsonElement("{\"command\":\"sleep 30\",\"on_exit\":\"none\"}") as kotlinx.serialization.json.JsonObject, context)
            }
            withTimeout(5_000) { started.await(); ToolExecutionControls.observe("s", "termux-exec").first { it != null } }
            queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
                com.promenar.infinity.data.chat.queue.QueuedMessage("q", "插队", createdAt = 0, steering = true)))
            val result = withTimeout(1_000) { waiting.await() }
            assertThat(result.status).isEqualTo("success")
            assertThat(result.content).contains("job_id")
            assertThat(jobs.list("s").single().running).isTrue()
            assertThat(release.isCompleted).isFalse()
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun `文件已出现时立即返回并拒绝越界路径`() = fixture { m ->
        val job = m.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "sleep 60", null, 1, BackgroundJobExitAction.NONE)
        File(job.workspaceDirectory!!, "ready").createNewFile()
        val result = m.waitFor("s", job.id, 600, untilFile = "/workspace/ready")
        assertThat(result.waitReason).isEqualTo("file")
        assertThat(result.record.running).isTrue()
        assertThat(runCatching { m.waitFor("s", job.id, 1, untilFile = "../../escape") }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { m.waitFor("s", job.id, 1, untilOutput = "[") }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        m.stop(job.id)
    }

    @Test fun `输出条件只匹配等待之后的新输出且返回触发原因`() = fixture { m ->
        val job = m.startBuiltin("s", "root", BackgroundJobRunner.SHELL,
            "echo historical; while [ ! -f release ]; do sleep 0.05; done; echo service-ready; sleep 60", null, 1, BackgroundJobExitAction.NONE)
        val waiting = async(ToolCallControlContext("s", "output-call", "output-step")) { m.waitFor("s", job.id, 5, untilOutput = "service-ready") }
        withTimeout(5_000) { ToolExecutionControls.observe("s", "output-call").first { it != null } }
        File(job.workspaceDirectory!!, "release").createNewFile()
        val result = waiting.await()
        assertThat(result.waitReason).isEqualTo("output")
        assertThat(result.matchedLine).contains("service-ready")
        assertThat(result.record.running).isTrue()
        assertThat(result.tail.text).doesNotContain("[+")
        assertThat(result.lastOutputSecondsAgo).isNotNull()
        m.stop(job.id)
    }
    @Test fun `用户立即检查和插队提前返回但后台任务继续`() = fixture { m ->
        val job = m.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "sleep 60", null, 1, BackgroundJobExitAction.NONE)
        val waiting = async(com.promenar.infinity.data.exec.ToolCallControlContext("s", "wait-call", "step")) { m.waitFor("s", job.id, 600) }
        withTimeout(5_000) {
            com.promenar.infinity.data.exec.ToolExecutionControls.observe("s", "wait-call").first { it != null }
        }
        assertThat(com.promenar.infinity.data.exec.ToolExecutionControls.checkNow("s", "wait-call")).isTrue()
        assertThat(waiting.await().waitReason).isEqualTo("released")
        val queue = kotlinx.coroutines.flow.MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
        val inserted = async(com.promenar.infinity.data.exec.SteeringContext(queue)) { m.waitFor("s", job.id, 600) }
        queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
            com.promenar.infinity.data.chat.queue.QueuedMessage("q", "steer", createdAt = 0, steering = true)))
        assertThat(inserted.await().waitReason).isEqualTo("steering")
        assertThat(m.status("s", job.id).record.running).isTrue()
        m.stop(job.id)
    }
    @Test fun `后台日志仅保存在应用任务目录且工具读取私有日志`() = fixture { m ->
        val job = m.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "echo private-output", null, 1, BackgroundJobExitAction.NONE)
        val result = m.waitFor("s", job.id, 5)
        assertThat(result.tail.text).contains("private-output")
        assertThat(File(job.workspaceDirectory!!, "jobs/${job.id}.log").exists()).isFalse()
        assertThat(File(job.runDirectory, "output.log").readText()).contains("private-output")
        assertThat(m.readLog("s", job.logWorkspacePath!!).text).contains("private-output")
        assertThat(runCatching { m.readLog("other", job.logWorkspacePath) }.exceptionOrNull()).isInstanceOf(JobAccessException::class.java)
    }
    @Test fun `后台立即返回且取消发起轮次不影响任务`() = fixture { m ->
        val started=CompletableDeferred<BackgroundJobRecord>()
        val caller=launch { started.complete(m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"while [ ! -f finish ]; do sleep 0.05; done; echo done",null,1,BackgroundJobExitAction.NONE)); awaitCancellation() }
        val job=withTimeout(5000){started.await()}
        assertThat(m.status("s",job.id).record.running).isTrue(); caller.cancelAndJoin()
        File(job.workspaceDirectory!!,"finish").createNewFile()
        val result=m.waitFor("s",job.id,5)
        assertThat(result.timedOut).isFalse()
        assertThat(result.record.state).isEqualTo("EXITED")
        assertThat(result.tail.text).contains("done")
    }
    @Test fun `恢复无法核验旧writer停止时保留日志目录项和后续追加`() = runBlocking {
        val root = Files.createTempDirectory("job-deferred-log").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val workspace = File(root, "workspace").apply { mkdirs() }
            val run = File(root, "runs/job-a").apply { mkdirs() }
            File(run, "pgid").writeText("777")
            val log = File(workspace, "jobs/job-a.log").apply { parentFile!!.mkdirs(); writeText("first\n") }
            val store = BackgroundJobStore(File(root, "store"))
            store.put(sample().copy(runDirectory = run.path, workspaceDirectory = workspace.path))
            java.io.FileOutputStream(log, true).use { writer ->
                val manager = manager(root, scope, store)
                manager.initialize()
                writer.write("after-recovery\n".toByteArray())
                writer.flush()
                val status = manager.status("s", "job-a")
                assertThat(status.record.logMigrationDeferred).isTrue()
                assertThat(status.tail.text).contains("after-recovery")
                assertThat(log.exists()).isTrue()
                assertThat(File(run, "output.log").exists()).isFalse()
            }
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `Termux状态查询取消保持原异常且不发额外恢复调用`() = runBlocking {
        val root = Files.createTempDirectory("job-termux-cancel").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = BackgroundJobStore(File(root, "store"))
            store.put(sample().copy(runner = "TERMUX", state = "EXITED", exitCode = 0,
                runDirectory = File(root, "runs/job-a").path, termuxDirectory = "/private/jobs/job-a", termuxPid = 123))
            val cancelled = CancellationException("test-cancel")
            var calls = 0
            val backend = com.promenar.infinity.data.termux.TermuxBackgroundJobs { calls++; throw cancelled }
            val manager = manager(root, scope, store, termux = backend)
            manager.initialize()
            val failure = runCatching { manager.status("s", "job-a") }.exceptionOrNull()
            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(failure?.message).isEqualTo("test-cancel")
            assertThat(calls).isEqualTo(1)
            assertThat(manager.list("s").single().guidance).isNull()
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `会话隔离与等待超时取消不停止任务`() = fixture { m ->
        val job=m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"sleep 60",null,1,BackgroundJobExitAction.NONE)
        assertThat(runCatching { m.status("other",job.id) }.exceptionOrNull()).isInstanceOf(JobAccessException::class.java)
        assertThat(m.waitFor("s",job.id,1).timedOut).isTrue()
        val waiter=launch { m.waitFor("s",job.id,600) }; delay(50); waiter.cancelAndJoin()
        assertThat(m.status("s",job.id).record.running).isTrue()
        assertThat(m.stop(job.id)).isTrue()
        assertThat(m.status("s",job.id).record.state).isEqualTo("STOPPED")
    }
    @Test fun `每会话三项全局六项并发上限与删除停止`() = fixture { m ->
        repeat(3) { m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"sleep 60",null,1,BackgroundJobExitAction.NONE) }
        assertThat(runCatching { m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"true",null,1,BackgroundJobExitAction.NONE) }.exceptionOrNull()).isInstanceOf(JobAccessException::class.java)
        repeat(3) { m.startBuiltin("t","root",BackgroundJobRunner.SHELL,"sleep 60",null,1,BackgroundJobExitAction.NONE) }
        assertThat(runCatching { m.startBuiltin("u","root",BackgroundJobRunner.SHELL,"true",null,1,BackgroundJobExitAction.NONE) }.exceptionOrNull()).isInstanceOf(JobAccessException::class.java)
        m.deleteSession("s"); m.deleteSession("t")
        assertThat(m.list("s")).isEmpty()
    }
    @Test fun `结束分发notify与wake且调度器缺失退化`() = runBlocking {
        var notices=0
        val requests=mutableListOf<SystemTurnRequest>()
        val dispatcher=object:SystemTurnDispatcher { override suspend fun request(request:SystemTurnRequest):SystemTurnOutcome {requests+=request; return SystemTurnOutcome.Completed} }
        val completion=BackgroundJobCompletion({dispatcher}) { notices++ }
        val record=sample().copy(state="EXITED",exitCode=0,endedAtMillis=10)
        completion.dispatch(record.copy(onExit="none"),JobLogTail("tail",false))
        completion.dispatch(record.copy(onExit="notify"),JobLogTail("tail",false))
        completion.dispatch(record.copy(onExit="wake"),JobLogTail("tail",false))
        assertThat(notices).isEqualTo(1)
        assertThat(requests.single().body).contains("tail")
        BackgroundJobCompletion({null}){notices++}.dispatch(record.copy(onExit="wake"),JobLogTail("",false))
        assertThat(notices).isEqualTo(2)
    }
    @Test fun `重启内置记录标记中断`() = runBlocking {
        val root=Files.createTempDirectory("job-recover").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val store=BackgroundJobStore(File(root,"store"))
            store.put(sample().copy(runDirectory=File(root,"runs/job-a").path))
            val m=manager(root,scope,store); m.initialize()
            assertThat(m.list("s").single().state).isEqualTo("INTERRUPTED")
        } finally {scope.cancel(); root.deleteRecursively()}
    }
    @Test fun `唤醒调度器返回前保持登记`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-wake").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val held=java.util.concurrent.atomic.AtomicInteger()
        val keep=object:ExecKeepAlive {override suspend fun <T> hold(run:ExecRunInfo,stop:()->Unit,block:suspend()->T):ExecHeld<T> {held.incrementAndGet();return try{ExecHeld(block(),ExecForegroundState.STARTED)}finally{held.decrementAndGet()}}}
        val dispatcher=object:SystemTurnDispatcher {override suspend fun request(request:SystemTurnRequest):SystemTurnOutcome {entered.complete(Unit);release.await();return SystemTurnOutcome.Completed}}
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")),completion=BackgroundJobCompletion({dispatcher}),keepAlive=keep)
        try {
            val job=m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"echo done",null,1,BackgroundJobExitAction.WAKE)
            withTimeout(5000){entered.await()}
            assertThat(m.status("s",job.id).record.state).isEqualTo("EXITED")
            assertThat(held.get()).isEqualTo(1)
            release.complete(Unit)
            withTimeout(5000){while(held.get()>0)delay(10)}
        } finally{release.complete(Unit);scope.cancel();root.deleteRecursively()}
    }
    @Test fun `查询状态日志和等待终态不调用慢对账`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("job-read-only").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        try {
            val store = BackgroundJobStore(File(root, "store"))
            val run = File(root, "runs/job-a").apply { mkdirs() }
            File(run, "output.log").writeText("ready")
            store.put(sample().copy(state = "EXITED", runDirectory = run.path))
            val m = manager(root, scope, store, reconcile = { _, _, _ -> calls.incrementAndGet(); awaitCancellation() })
            m.initialize()
            withTimeout(1_000) {
                assertThat(m.status("s", "job-a").record.state).isEqualTo("EXITED")
                assertThat(m.readLog("s", BackgroundJobLogs.reference("job-a")).text).contains("ready")
                assertThat(m.waitFor("s", "job-a", 1).timedOut).isFalse()
            }
            assertThat(calls.get()).isEqualTo(0)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun `后台启动和直接任务完成不等待对账`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("job-start-reconcile").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val notified = CompletableDeferred<BackgroundJobRecord>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        try {
            val m = manager(root, scope, BackgroundJobStore(File(root, "store")),
                reconcile = { _, finished, _ -> if (finished) null else { calls.incrementAndGet(); awaitCancellation() } },
                completion = BackgroundJobCompletion { notified.complete(it) })
            val job = withTimeout(2_000) { m.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "echo done", null, 1, BackgroundJobExitAction.NOTIFY) }
            withTimeout(2_000) { notified.await() }
            assertThat(m.status("s", job.id).record.state).isEqualTo("EXITED")
            assertThat(calls.get()).isEqualTo(0)
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun `终态发布通知后才请求索引且隔离合入警告进入通知`() = runBlocking<Unit> {
        for (isolated in listOf(false, true)) {
            val root = Files.createTempDirectory("job-completion-order").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            val requested = CompletableDeferred<Unit>()
            val store = BackgroundJobStore(File(root, "store"))
            try {
                val m = manager(root, scope, store, isolated = isolated,
                    reconcile = { _, finished, _ -> check(isolated && finished); events += "merge"; "合入冲突：/output.txt（前台版本已保留）" },
                    completion = BackgroundJobCompletion { record ->
                        assertThat(store.all().single().state).isEqualTo("EXITED")
                        assertThat(record.guidance?.contains("合入冲突")).isEqualTo(if (isolated) true else null)
                        events += "notify"
                    },
                    requestReconcile = { events += "request"; requested.complete(Unit) })
                m.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "echo done", null, 1, BackgroundJobExitAction.NOTIFY)
                withTimeout(2_000) { requested.await() }
                assertThat(events.toList()).containsExactlyElementsIn(if (isolated) listOf("merge", "notify", "request") else listOf("notify", "request")).inOrder()
            } finally { scope.coroutineContext[Job]?.cancelAndJoin(); root.deleteRecursively() }
        }
    }

    @Test fun `Termux启动回包晚于状态终态时仍唤醒一次并保活`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-termux-race").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val launching=CompletableDeferred<Unit>();val reply=CompletableDeferred<Unit>()
        val waking=CompletableDeferred<Unit>();val finishWake=CompletableDeferred<Unit>()
        val count=java.util.concurrent.atomic.AtomicInteger();val held=java.util.concurrent.atomic.AtomicInteger()
        val encoded=java.util.Base64.getEncoder().encodeToString("/termux/jobs/job-test".toByteArray())
        val handle="INFINITY_JOB_V1\n$encoded\n12345\n"
        val backend=com.promenar.infinity.data.termux.TermuxBackgroundJobs { request ->
            val output=when {
                "launch=" in request.command -> {launching.complete(Unit);reply.await();handle}
                "code=-" in request.command -> "INFINITY_JOB_V1\n0\n0\n0\n0\n4\ndGFpbA==\n"
                else -> handle
            }
            com.promenar.infinity.data.termux.TermuxCommandResult(stdout=output,exitCode=0)
        }
        val keep=object:ExecKeepAlive {override suspend fun <T> hold(run:ExecRunInfo,stop:()->Unit,block:suspend()->T):ExecHeld<T>{held.incrementAndGet();return try{ExecHeld(block(),ExecForegroundState.STARTED)}finally{held.decrementAndGet()}}}
        val dispatcher=object:SystemTurnDispatcher {override suspend fun request(request:SystemTurnRequest):SystemTurnOutcome{count.incrementAndGet();waking.complete(Unit);finishWake.await();return SystemTurnOutcome.Completed}}
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")),completion=BackgroundJobCompletion({dispatcher}),keepAlive=keep,termux=backend)
        try {
            val caller=async {m.startTermux("s","root","true",null,null,1,BackgroundJobExitAction.WAKE)}
            withTimeout(5000){launching.await()}
            val id=m.list("s").single().id
            assertThat(m.status("s",id).record.state).isEqualTo("EXITED")
            assertThat(held.get()).isEqualTo(1)
            reply.complete(Unit);withTimeout(5000){caller.await();waking.await()}
            assertThat(count.get()).isEqualTo(1);assertThat(held.get()).isEqualTo(1)
            finishWake.complete(Unit)
            withTimeout(5000){while(held.get()>0)delay(10)}
        } finally{reply.complete(Unit);finishWake.complete(Unit);scope.cancel();deleteJobTree(root)}
    }
    @Test fun `保活初始化失败不会让启动永久挂起`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-hold-failed").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val keep=object:ExecKeepAlive {override suspend fun <T> hold(run:ExecRunInfo,stop:()->Unit,block:suspend()->T):ExecHeld<T>{error("unavailable")}}
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")),keepAlive=keep,termux=com.promenar.infinity.data.termux.TermuxBackgroundJobs{error("不应启动远端命令")})
        try {
            val error=withTimeout(5000){runCatching {m.startBuiltin("s","root",BackgroundJobRunner.SHELL,"true",null,1,BackgroundJobExitAction.NONE)}.exceptionOrNull()}
            assertThat(error).isInstanceOf(IllegalStateException::class.java)
            withTimeout(5000){while(m.list("s").single().running)delay(10)}
            val termuxError=withTimeout(5000){runCatching {m.startTermux("t","root","true",null,null,1,BackgroundJobExitAction.NONE)}.exceptionOrNull()}
            assertThat(termuxError).isInstanceOf(IllegalStateException::class.java)
            withTimeout(5000){while(m.list("t").single().running)delay(10)}
            assertThat(m.list("t").single().state).isEqualTo("FAILED")
        } finally{scope.cancel();deleteJobTree(root)}
    }
    @Test fun `删除停止失败后运行任务保留监控并正常通知`() = runBlocking<Unit> {
        val root=Files.createTempDirectory("job-delete-failed").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val stopping=CompletableDeferred<Unit>();val releaseStop=CompletableDeferred<Unit>();val notified=CompletableDeferred<Unit>()
        val done=java.util.concurrent.atomic.AtomicBoolean(false);val held=java.util.concurrent.atomic.AtomicInteger()
        val encoded=java.util.Base64.getEncoder().encodeToString("/termux/jobs/job-test".toByteArray())
        val handle="INFINITY_JOB_V1\n$encoded\n12345\n"
        val backend=com.promenar.infinity.data.termux.TermuxBackgroundJobs {request ->
            when {
                "launch=" in request.command -> com.promenar.infinity.data.termux.TermuxCommandResult(stdout=handle,exitCode=0)
                "kill -TERM --" in request.command -> {stopping.complete(Unit);releaseStop.await();com.promenar.infinity.data.termux.TermuxCommandResult(errorCode="TERMUX_PERMISSION_DENIED")}
                else -> com.promenar.infinity.data.termux.TermuxCommandResult(stdout="INFINITY_JOB_V1\n${if(done.get()) "0\n0" else "-\n1"}\n0\n0\n0\n\n",exitCode=0)
            }
        }
        val keep=object:ExecKeepAlive {override suspend fun <T> hold(run:ExecRunInfo,stop:()->Unit,block:suspend()->T):ExecHeld<T>{held.incrementAndGet();return try{ExecHeld(block(),ExecForegroundState.STARTED)}finally{held.decrementAndGet()}}}
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")),termux=backend,keepAlive=keep,completion=BackgroundJobCompletion{notified.complete(Unit)})
        try {
            m.startTermux("s","root","true",null,null,1,BackgroundJobExitAction.NOTIFY)
            val deletion=async {runCatching{m.stopSession("s")}.exceptionOrNull()}
            withTimeout(5000){stopping.await()}
            delay(2200)
            assertThat(held.get()).isEqualTo(1)
            releaseStop.complete(Unit)
            assertThat(deletion.await()).isNotNull()
            assertThat(m.list("s").single().running).isTrue()
            done.set(true)
            withTimeout(5000){notified.await()}
            assertThat(m.list("s").single().state).isEqualTo("EXITED")
        } finally {releaseStop.complete(Unit);scope.cancel();deleteJobTree(root)}
    }
    private fun sample()=BackgroundJobRecord("job-a","s","root","SHELL","echo",startedAtMillis=System.currentTimeMillis(),maxRuntimeMinutes=1,onExit="none",logWorkspacePath="jobs/job-a.log",runDirectory="")
    @Test fun `后台注入与落盘遮蔽且重启明确报告缺槽位`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("job-secret").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val vault = SessionSecretVault()
            vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "fixture-background-secret".toByteArray())
            val store = BackgroundJobStore(File(root, "store"))
            val manager = manager(root, scope, store, vault = vault)
            val job = manager.startBuiltin("s", "root", BackgroundJobRunner.SHELL, "printf '%s' \"\$TOKEN\"", null, 1, BackgroundJobExitAction.NONE)
            val result = manager.waitFor("s", job.id, 5)
            assertThat(result.tail.text).isEqualTo("‹保密:TOKEN›")
            assertThat(readJobLogTail(File(job.runDirectory, "output.log")).text).isEqualTo("‹保密:TOKEN›")
            assertThat(File(root, "store/records.json").readText()).doesNotContain("fixture-background-secret")
            assertThat(result.record.secretNames).containsExactly("TOKEN")
            store.put(result.record.copy(state = "RUNNING"))
            val restarted = manager(root, scope, store, vault = SessionSecretVault())
            restarted.initialize()
            assertThat(restarted.status("s", job.id).record.guidance).contains("JOB_SECRET_UNAVAILABLE")
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `保密输入失败也清零字节与字符数组`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("job-secret-input").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = manager(root, scope, BackgroundJobStore(File(root, "store")), vault = SessionSecretVault())
            val bytes = "fixture-byte-input".toByteArray()
            val chars = "fixture-char-input".toCharArray()
            runCatching { manager.input("s", "missing", bytes) }
            runCatching { manager.input("s", "missing", chars) }
            assertThat(bytes.all { it == 0.toByte() }).isTrue()
            assertThat(chars.all { it == '\u0000' }).isTrue()
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `Termux输出先遮蔽且保密会话保留外部原始日志不迁移`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("job-secret-termux").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val vault = SessionSecretVault()
            vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "fixture-termux-secret".toByteArray())
            val store = BackgroundJobStore(File(root, "store"))
            store.put(sample().copy(runner = "TERMUX", state = "EXITED", exitCode = 0,
                runDirectory = File(root, "runs/job-a").path, workspaceDirectory = root.path,
                termuxDirectory = "/termux/jobs/job-a", termuxPid = 123))
            var calls = 0
            val backend = com.promenar.infinity.data.termux.TermuxBackgroundJobs {
                calls++
                val content = java.util.Base64.getEncoder().encodeToString("fixture-termux-secret".toByteArray())
                com.promenar.infinity.data.termux.TermuxCommandResult(stdout = "INFINITY_JOB_V1\n0\n0\n0\n0\n21\n$content\n", exitCode = 0)
            }
            val manager = manager(root, scope, store, termux = backend, vault = vault)
            val status = manager.status("s", "job-a")
            assertThat(status.tail.text).isEqualTo("‹保密:TOKEN›")
            assertThat(status.record.termuxDirectory).isEqualTo("/termux/jobs/job-a")
            assertThat(calls).isEqualTo(1)
        } finally { scope.cancel(); root.deleteRecursively() }
    }

    @Test fun `Termux实时快照保留秘密短尾直到完整匹配`() = runBlocking<Unit> {
        val root = Files.createTempDirectory("job-secret-termux-live").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val vault = SessionSecretVault()
            vault.put("s", "TOKEN", "令牌", SecretSource.UserInput, "fixture-termux-secret".toByteArray())
            val store = BackgroundJobStore(File(root, "store"))
            store.put(sample().copy(runner = "TERMUX", runDirectory = File(root, "runs/job-a").path,
                termuxDirectory = "/termux/jobs/job-a", termuxPid = 123))
            val content = java.util.concurrent.atomic.AtomicReference("fixture-termux-")
            val backend = com.promenar.infinity.data.termux.TermuxBackgroundJobs {
                val text = content.get()
                val encoded = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
                com.promenar.infinity.data.termux.TermuxCommandResult(stdout = "INFINITY_JOB_V1\n-\n1\n0\n0\n${text.length}\n$encoded\n", exitCode = 0)
            }
            val live = ToolLiveOutputStore()
            val manager = manager(root, scope, store, termux = backend, vault = vault, live = live)
            manager.initialize()
            withTimeout(5_000) { while (live.snapshot("job:job-a") == null) delay(10) }
            manager.status("s", "job-a")
            assertThat(live.snapshot("job:job-a")?.text).doesNotContain("fixture-termux-")
            content.set("fixture-termux-secret" + " padding".repeat(30))
            manager.status("s", "job-a")
            assertThat(live.snapshot("job:job-a")?.text).contains("‹保密:TOKEN›")
            assertThat(live.snapshot("job:job-a")?.text).doesNotContain("fixture-termux-secret")
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); root.deleteRecursively() }
    }

    private fun fixture(block:suspend CoroutineScope.(BackgroundJobManager)->Unit)=runBlocking {
        val root=Files.createTempDirectory("job-manager").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val m=manager(root,scope,BackgroundJobStore(File(root,"store")))
        try { block(m) } finally { for(s in listOf("s","t")) m.list(s).filter{it.running}.forEach{m.stop(it.id)};scope.cancel(); root.deleteRecursively() }
    }
    private fun manager(root:File,scope:CoroutineScope,store:BackgroundJobStore,
        reconcile:suspend(BackgroundJobRecord,Boolean,Boolean)->String?={_,_,_->null},
        completion:BackgroundJobCompletion=BackgroundJobCompletion(),keepAlive:ExecKeepAlive=NoExecKeepAlive,
        termux:com.promenar.infinity.data.termux.TermuxBackgroundJobs?=null,
        vault:SessionSecretVault?=null, live:ToolLiveOutputStore=ToolLiveOutputStore(),
        isolated: Boolean = false, requestReconcile: (BackgroundJobRecord) -> Unit = {})=BackgroundJobManager(
        scope,store,File(root,"runs"),
        BuiltinCommandRunner(listOf("/usr/bin/perl","-e","setpgrp(0, 0); exec @ARGV or exit 127"),"/bin/sh",ProcessGroupSignaller {pid,signal->ProcessBuilder("/bin/kill","-$signal","-$pid").start().waitFor()}, {File(root,"scratch")},killGraceMillis=100),
        prepareWorkspace={ BackgroundJobLocation(root,root,isolated,false) },
        reconcile=reconcile,requestReconcile=requestReconcile,completion=completion,keepAlive=keepAlive,termux=termux,vault=vault,redactor=vault?.let(::SecretOutputRedactor),liveOutput=live,
        prepareCommand={_,source,stdin,location,minutes -> PreparedBackgroundCommand(BuiltinCommandRequest(listOf("/bin/sh","-c",source),location.commandDirectory,mapOf("PATH" to "/usr/bin:/bin"),stdin,minutes*60)) },
    )
}
