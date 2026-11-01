package com.promenar.infinity.data.repository

import com.promenar.infinity.infra.util.Sha256Utils
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 私有根按会话合并，分支共享根按根 UUID 合并；每个目标最多一个执行者和一个待跑标记。 */
class SessionWorkspaceReconciler(
    private val scope: CoroutineScope,
    private val pendingDirectory: File? = null,
    private val retryDelayMillis: Long = 5_000,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val reconcileSharedRoot: suspend (String) -> Unit = { error("未配置共享根对账") },
    private val reconcile: suspend (String) -> Unit,
) {
    private data class Target(val id: String, val sharedRoot: Boolean = false) {
        fun encode() = "${if (sharedRoot) "root" else "session"}\n$id"
    }
    private class Pending {
        var requested = true
        val settled = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Exception>()
    }
    private val monitor = Any()
    private val pending = mutableMapOf<Target, Pending>()
    private val writers = mutableMapOf<Target, Int>()
    private val mutableSessions = MutableStateFlow<Set<String>>(emptySet())
    private val mutableSharedRoots = MutableStateFlow<Set<String>>(emptySet())
    private val mutableFailures = MutableStateFlow<Map<String, String>>(emptyMap())
    private val mutableSharedRootFailures = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessions: StateFlow<Set<String>> = mutableSessions.asStateFlow()
    val sharedRoots: StateFlow<Set<String>> = mutableSharedRoots.asStateFlow()
    val failures: StateFlow<Map<String, String>> = mutableFailures.asStateFlow()
    val sharedRootFailures: StateFlow<Map<String, String>> = mutableSharedRootFailures.asStateFlow()

    /** 命令开始或副本合入前记录恢复意图，不启动扫描，不接触工作区根锁。 */
    fun markDirty(sessionId: String) = synchronized(monitor) {
        markDirty(Target(sessionId))
    }

    fun markSharedRootDirty(rootUuid: String) = synchronized(monitor) {
        markDirty(Target(rootUuid, sharedRoot = true))
    }

    private fun markDirty(target: Target) {
        persist(target)
        writers[target] = (writers[target] ?: 0) + 1
    }

    fun mutationFinished(sessionId: String) = synchronized(monitor) {
        mutationFinished(Target(sessionId))
    }

    fun sharedRootMutationFinished(rootUuid: String) = synchronized(monitor) {
        mutationFinished(Target(rootUuid, sharedRoot = true))
    }

    private fun mutationFinished(target: Target) {
        val count = writers[target] ?: 0
        if (count <= 1) writers.remove(target) else writers[target] = count - 1
    }

    fun request(sessionId: String) = synchronized(monitor) {
        request(Target(sessionId))
    }

    /** 共享根按稳定的根身份合并，父会话删除后也能恢复。 */
    fun requestSharedRoot(rootUuid: String) = synchronized(monitor) {
        request(Target(rootUuid, sharedRoot = true))
    }

    private fun request(target: Target) {
        persist(target)
        enqueue(target)
    }

    private fun publish() {
        mutableSessions.value = pending.keys.filterNot { it.sharedRoot }.mapTo(mutableSetOf()) { it.id }
        mutableSharedRoots.value = pending.keys.filter { it.sharedRoot }.mapTo(mutableSetOf()) { it.id }
    }

    private fun enqueue(target: Target) {
        pending[target]?.let { it.requested = true; return }
        val work = Pending()
        pending[target] = work
        publish()
        scope.launch(dispatcher) {
            try {
                while (true) {
                    synchronized(monitor) { work.requested = false }
                    try {
                        if (target.sharedRoot) reconcileSharedRoot(target.id) else reconcile(target.id)
                        val finished = synchronized(monitor) {
                            val failures = if (target.sharedRoot) mutableSharedRootFailures else mutableFailures
                            failures.value = failures.value - target.id
                            if (work.requested) false else {
                                // 持久请求只在最后一次扫描成功后清除；请求和确认使用同一把短锁。
                                if (target !in writers) pendingFile(target)?.let { file ->
                                    check(!file.exists() || file.delete()) { "无法确认工作区对账请求" }
                                }
                                pending.remove(target)
                                publish()
                                work.settled.complete(Unit)
                                true
                            }
                        }
                        if (finished) break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: WorkspaceScanInvalidatedException) { continue }
                    catch (failure: Exception) {
                        work.failed.complete(failure)
                        synchronized(monitor) {
                            val failures = if (target.sharedRoot) mutableSharedRootFailures else mutableFailures
                            failures.value = failures.value + (target.id to "工作区索引对账失败（${failure.javaClass.simpleName}），已安排重试。")
                        }
                        delay(retryDelayMillis)
                    }
                }
            } finally {
                synchronized(monitor) {
                    if (pending[target] === work) {
                        pending.remove(target)
                        publish()
                        work.settled.cancel()
                    }
                }
            }
        }.invokeOnCompletion {
            // 执行体开始前取消时也要唤醒等待者，磁盘请求留给下次恢复。
            synchronized(monitor) {
                if (pending[target] === work) {
                    pending.remove(target)
                    publish()
                    work.settled.cancel()
                }
            }
        }
    }

    suspend fun awaitSettled(sessionId: String) {
        awaitSettled(Target(sessionId))
    }

    suspend fun awaitSharedRootSettled(rootUuid: String) {
        awaitSettled(Target(rootUuid, sharedRoot = true))
    }

    private suspend fun awaitSettled(target: Target) {
        while (true) {
            val work = synchronized(monitor) { pending[target] } ?: return
            kotlinx.coroutines.selects.select<Unit> {
                work.settled.onAwait { }
                work.failed.onAwait { throw it }
            }
        }
    }

    /** 启动恢复屏障解除后调用；进程中断或尚未发起扫描的请求均重新接管。 */
    fun resumePending() = synchronized(monitor) {
        pendingDirectory?.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}")) }?.forEach { file ->
            val encoded = file.readText()
            check(file.name == Sha256Utils.hash(encoded)) { "工作区对账请求身份无效" }
            val kind = encoded.substringBefore('\n')
            check(kind == "session" || kind == "root") { "工作区对账请求类型无效" }
            val target = Target(encoded.substringAfter('\n'), sharedRoot = kind == "root")
            require(target.id.isNotBlank())
            enqueue(target)
        }
    }

    private fun pendingFile(target: Target) = pendingDirectory?.let { File(it, Sha256Utils.hash(target.encode())) }
    private fun persist(work: Target) {
        require(work.id.isNotBlank())
        val target = pendingFile(work) ?: return
        if (target.isFile) return
        val parent = checkNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法保存工作区对账请求" }
        val temporary = File(parent, "${target.name}.tmp")
        FileOutputStream(temporary).use { it.write(work.encode().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temporary.renameTo(target)) { "无法保存工作区对账请求" }
    }
}
