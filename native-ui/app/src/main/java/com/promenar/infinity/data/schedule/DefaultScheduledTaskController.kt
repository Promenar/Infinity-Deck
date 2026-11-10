package com.promenar.infinity.data.schedule

import android.content.Context
import androidx.room.withTransaction
import com.promenar.infinity.background.schedule.AndroidScheduleBackend
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.ScheduledTaskEntity
import com.promenar.infinity.domain.schedule.*
import com.promenar.infinity.domain.trigger.*
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 厂商系统会把精确闹钟对齐到约 1.5 分钟的窗口内送达；超过该容差才视为错过后补发。 */
internal const val CATCH_UP_TOLERANCE_MILLIS = 5 * 60_000L

/** 启用中定时任务的数量上限；设置页文案与错误消息引用同一常量。 */
internal const val MAX_SESSION_SCHEDULES = 5
internal const val MAX_GLOBAL_SCHEDULES = 20

interface ScheduleBackend {
    fun canUseExact(): Boolean
    fun schedule(task: ScheduledTaskEntity)
    fun cancel(task: ScheduledTaskEntity)
}

/** 数据库占位跨进程保留；调度回调只能消费一次到期，暂停和删除不会被完成回调撤销。 */
class DefaultScheduledTaskController(
    context: Context,
    private val database: InfinityDatabase,
    private val dispatcher: SystemTurnDispatcher,
    private val backend: ScheduleBackend = AndroidScheduleBackend(context),
    private val withdrawPending: ((String) -> Unit)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ScheduledTaskController {
    private val dao = database.scheduledTaskDao()
    private val mutex = Mutex()
    private val running = mutableMapOf<String, Job>()
    private val suspendedSessions = mutableSetOf<String>()

    override fun observe(sessionId: String?): Flow<List<ScheduledTask>> = dao.observe(sessionId).map { tasks -> tasks.map { it.toDomain() } }
    suspend fun find(id: String): ScheduledTask? = withContext(Dispatchers.IO) { dao.get(id)?.toDomain() }
    suspend fun list(sessionId: String): List<ScheduledTask> = withContext(Dispatchers.IO) {
        dao.getAll().filter { it.sessionId == sessionId }.map { it.toDomain() }
    }

    suspend fun create(sessionId: String, args: ScheduleCreateArgs): ScheduleCreateResult = withContext(Dispatchers.IO) {
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, subsystem = com.promenar.infinity.startup.StartupSubsystem.SCHEDULED_TASKS)
        mutex.withLock {
            require(sessionId !in suspendedSessions) { "会话正在删除，请稍后重试。" }
            val now = clock()
            val exact = args.exactRequested && backend.canUseExact()
            ScheduleCalculation.validate(args, now, exact)
            val task = database.withTransaction {
                require(database.sessionDao().getById(sessionId) != null) { "会话不存在。" }
                checkCapacity(sessionId)
                val existingJobs = dao.getAll().map { it.jobId }.toSet()
                var jobId: Int
                do { jobId = UUID.randomUUID().hashCode() and Int.MAX_VALUE } while (jobId in existingJobs)
                val trigger = args.trigger
                val type = when (trigger) { is ScheduleTrigger.Once -> "once"; is ScheduleTrigger.Interval -> "interval"; is ScheduleTrigger.Cron -> "cron" }
                val value = when (trigger) { is ScheduleTrigger.Once -> trigger.localDateTime; is ScheduleTrigger.Interval -> trigger.everyMinutes.toString(); is ScheduleTrigger.Cron -> trigger.expression }
                val zone = when (trigger) { is ScheduleTrigger.Once -> trigger.zoneId; is ScheduleTrigger.Cron -> trigger.zoneId; else -> ZoneId.systemDefault().id }
                ScheduledTaskEntity("sch_${UUID.randomUUID()}", sessionId, args.name, args.prompt, type, value, zone,
                    args.exactRequested, exact, true, ScheduleCalculation.nextAfter(trigger, now, exact), 0,
                    args.maxRuns, args.expiresAtMillis, null, null, now, jobId).also { dao.insert(it) }
            }
            try {
                backend.schedule(task)
            } catch (_: Exception) {
                runCatching { backend.cancel(task) }
                dao.delete(task.id)
                throw IllegalArgumentException("系统无法保存排期，定时任务未创建，请稍后重试。")
            }
            ScheduleCreateResult(task.toDomain(), if (args.exactRequested && !exact) "当前渠道或精确闹钟权限不可用：应用在前台时按设定间隔执行，退到后台时重复任务间隔至少 15 分钟。" else null)
        }
    }

    private suspend fun checkCapacity(sessionId: String) {
        require(dao.countEnabled(sessionId) < MAX_SESSION_SCHEDULES) { "本会话最多启用 $MAX_SESSION_SCHEDULES 个定时任务。" }
        require(dao.countEnabled(null) < MAX_GLOBAL_SCHEDULES) { "全局最多启用 $MAX_GLOBAL_SCHEDULES 个定时任务。" }
    }

    override suspend fun pause(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val task = dao.get(id) ?: return@withLock
            dao.update(task.copy(enabled = false, nextFireAtMillis = null))
            backend.cancel(task)
            withdraw(id)
        }
    }

    override suspend fun resume(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val task = dao.get(id) ?: return@withLock
            com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = task.sessionId, subsystem = com.promenar.infinity.startup.StartupSubsystem.SCHEDULED_TASKS)
            if (task.enabled) return@withLock
            require(task.sessionId !in suspendedSessions) { "会话正在删除，请稍后重试。" }
            val now = clock()
            require(!ScheduleCalculation.exhausted(task.runCount, task.maxRuns, task.expiresAtMillis, now)) { "任务已达到次数上限或过期。" }
            require(task.triggerType != "once" || task.runCount == 0) { "单次任务已执行。" }
            checkCapacity(task.sessionId)
            val exact = task.exactRequested && backend.canUseExact()
            val next = if (task.triggerType == "once") ScheduleCalculation.resolveLocal(task.triggerValue, task.zoneId) else ScheduleCalculation.nextAfter(task.trigger(), now, exact)
            require(next != null && (task.expiresAtMillis == null || next < task.expiresAtMillis)) { "到期时间前没有可用触发时间。" }
            val resumed = task.copy(enabled = true, exactEffective = exact, nextFireAtMillis = next)
            val updated = resumed.copy(catchUp = isLate(resumed, now))
            dao.update(updated)
            try {
                backend.schedule(updated)
            } catch (_: Exception) {
                runCatching { backend.cancel(updated) }
                dao.update(task)
                throw IllegalArgumentException("系统无法保存排期，任务保持暂停，请稍后重试。")
            }
        }
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.get(id)?.let { backend.cancel(it) }
            dao.delete(id)
            withdraw(id)
        }
    }

    suspend fun pauseForSession(sessionId: String, id: String) { requireOwned(sessionId, id); pause(id) }
    suspend fun resumeForSession(sessionId: String, id: String) { requireOwned(sessionId, id); resume(id) }
    suspend fun deleteForSessionTask(sessionId: String, id: String) { requireOwned(sessionId, id); delete(id) }
    private suspend fun requireOwned(sessionId: String, id: String) = withContext(Dispatchers.IO) {
        require(dao.get(id)?.sessionId == sessionId) { "本会话不存在该定时任务。" }
    }

    /** 删除事务之前撤销排期；保留行，使数据库删除失败时能恢复原任务。 */
    suspend fun cancelForSession(sessionId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            suspendedSessions.add(sessionId)
            dao.getAll().filter { it.sessionId == sessionId }.forEach { task ->
                backend.cancel(task)
                withdraw(task.id)
            }
        }
    }

    /** 删除事务的 finally 调用：成功时已无记录，失败时重排仍存在的任务。 */
    suspend fun resumeSchedulingForSession(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock { suspendedSessions.remove(sessionId) }
        reconcile()
    }

    suspend fun deleteForSession(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.getAll().filter { it.sessionId == sessionId }.forEach { backend.cancel(it); withdraw(it.id) }
            dao.deleteForSession(sessionId)
        }
    }

    private fun withdraw(id: String) {
        if (withdrawPending != null) withdrawPending.invoke(id) else running[id]?.cancel()
    }

    /** 启动业务 writer 完成后调用；遗留占位只记失败，不重放已领取的轮次。 */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.getAll().filter { it.claimToken != null && it.id !in running }.forEach { task ->
                val now = clock()
                val enabled = task.enabled && task.triggerType != "once" && !ScheduleCalculation.exhausted(task.runCount, task.maxRuns, task.expiresAtMillis, now)
                dao.update(task.copy(claimToken = null, lastResult = ScheduleRunResult.FAILED.name, enabled = enabled,
                    nextFireAtMillis = if (enabled) ScheduleCalculation.nextAfterRun(task.trigger(), now, task.exactEffective) else null, lastRunToken = task.claimToken))
            }
        }
        reconcile()
    }

    /** 广播仅重排；过期的多个触发合并成一次，广播本身不发起生成。 */
    suspend fun reconcile(preservePeriodicTaskId: String? = null) = withContext(Dispatchers.IO) {
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(subsystem = com.promenar.infinity.startup.StartupSubsystem.SCHEDULED_TASKS)
        mutex.withLock {
            val now = clock()
            dao.getAll().forEach { task ->
                if (com.promenar.infinity.startup.StartupWriteBarrier.isReadOnly(sessionId = task.sessionId)) {
                    backend.cancel(task)
                    return@forEach
                }
                if (task.sessionId in suspendedSessions) return@forEach
                if (!task.enabled) { backend.cancel(task); return@forEach }
                if (task.claimToken != null) return@forEach
                val exact = task.exactRequested && backend.canUseExact()
                val exhausted = ScheduleCalculation.exhausted(task.runCount, task.maxRuns, task.expiresAtMillis, now)
                val next = task.nextFireAtMillis
                val candidate = task.copy(exactEffective = exact)
                val updated = candidate.copy(enabled = !exhausted && next != null,
                    nextFireAtMillis = if (exhausted) null else next, catchUp = next != null && task.catchUp)
                dao.update(updated)
                val preserve = task.id == preservePeriodicTaskId && updated.enabled && !updated.exactEffective &&
                    updated.triggerType == "interval" && !updated.catchUp && !isLate(updated, now)
                if (!preserve) {
                    backend.cancel(task)
                    if (updated.enabled) scheduleOrDisable(updated)
                }
            }
        }
    }

    /** 前台计时器与系统后台共同入口；后台提前回调不能领取前台计划，重复回调不能重复领取。 */
    suspend fun fire(id: String, reschedule: Boolean = true, executionToken: String? = null, background: Boolean = false) = withContext(Dispatchers.IO) {
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(subsystem = com.promenar.infinity.startup.StartupSubsystem.SCHEDULED_TASKS)
        val caller = currentCoroutineContext().job
        val token = executionToken ?: UUID.randomUUID().toString()
        var result = ScheduleRunResult.FAILED
        var catchUp = false
        try {
            val claimed = mutex.withLock {
                val now = clock()
                val existing = dao.get(id) ?: return@withLock null
                com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = existing.sessionId)
                if (existing.sessionId in suspendedSessions) return@withLock null
                if (ScheduleCalculation.exhausted(existing.runCount, existing.maxRuns, existing.expiresAtMillis, now)) {
                    dao.update(existing.copy(enabled = false, nextFireAtMillis = null))
                    backend.cancel(existing)
                    return@withLock null
                }
                if (background && backgroundFireAt(existing)?.let { now < it } == true) return@withLock null
                if (dao.claim(id, token, now) != 1) return@withLock null
                // claim 会覆盖上次触发时刻，迟到必须按领取前的后台时刻判断。
                catchUp = existing.catchUp || isLate(existing, now)
                dao.get(id)?.also { running[id] = caller }
            } ?: return@withContext
            result = when (val outcome = dispatcher.request(SystemTurnRequest(claimed.sessionId, SystemTurnKind.SCHEDULE,
                claimed.id, claimed.name, claimed.prompt, claimed.runCount, catchUp))) {
                SystemTurnOutcome.Completed -> ScheduleRunResult.COMPLETED
                SystemTurnOutcome.NeedsUser -> ScheduleRunResult.NEEDS_USER
                is SystemTurnOutcome.Failed -> ScheduleRunResult.FAILED
                is SystemTurnOutcome.Skipped -> if (outcome.reason == SystemTurnSkipReason.BUSY_TIMEOUT) ScheduleRunResult.SKIPPED_BUSY else ScheduleRunResult.SKIPPED_OTHER
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result = ScheduleRunResult.FAILED
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                mutex.withLock {
                    if (running[id] === caller) running.remove(id)
                    val current = dao.get(id)
                    if (current?.claimToken == token) finish(current, result, null, reschedule)
                }
            }
        }
    }

    suspend fun stopped(id: String, stopReason: Int, executionToken: String? = null): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            val task = dao.get(id) ?: return@withLock false
            if (task.sessionId in suspendedSessions) return@withLock false
            if (executionToken != null && (task.claimToken ?: task.lastRunToken) != executionToken) return@withLock false
            if (task.claimToken != null) finish(task, ScheduleRunResult.FAILED, stopReason)
            else if (task.lastResult == ScheduleRunResult.FAILED.name) {
                val updated = task.copy(stopReason = stopReason)
                dao.update(updated)
                backend.cancel(task)
                if (updated.enabled) scheduleOrDisable(updated)
            }
            else return@withLock false
            running[id]?.cancel()
            true
        }
    }

    private suspend fun finish(task: ScheduledTaskEntity, result: ScheduleRunResult, stopReason: Int?, reschedule: Boolean = true) {
        val now = clock()
        val exact = task.exactRequested && backend.canUseExact()
        var enabled = task.enabled && task.triggerType != "once" && !ScheduleCalculation.exhausted(task.runCount, task.maxRuns, task.expiresAtMillis, now)
        val trigger = task.trigger()
        val planned = if (enabled) ScheduleCalculation.nextAfterRun(trigger, task.lastFiredAtMillis ?: now, exact) else null
        // 间隔轮询超时后留出完整等待间隔；cron 保留跨点合并补发语义。
        val intervalOverrun = trigger is ScheduleTrigger.Interval && planned != null && planned <= now
        val catchUp = !intervalOverrun && planned != null && planned <= now
        val next = if (intervalOverrun) ScheduleCalculation.nextAfter(trigger, now, exact) else planned?.coerceAtLeast(now)
        if (next == null || (task.expiresAtMillis != null && next >= task.expiresAtMillis)) enabled = false
        val updated = task.copy(claimToken = null, lastResult = result.name, enabled = enabled,
            exactEffective = exact, nextFireAtMillis = if (enabled) next else null, catchUp = enabled && catchUp, stopReason = stopReason, lastRunToken = task.claimToken)
        dao.update(updated)
        if (reschedule && task.sessionId !in suspendedSessions) {
            backend.cancel(task)
            if (updated.enabled) scheduleOrDisable(updated)
        }
    }

    private fun backgroundFireAt(task: ScheduledTaskEntity): Long? = task.nextFireAtMillis?.let { next ->
        ScheduleCalculation.backgroundFireAt(task.trigger(), next, task.lastFiredAtMillis,
            task.createdAtMillis, task.exactEffective)
    }

    private fun isLate(task: ScheduledTaskEntity, now: Long): Boolean =
        backgroundFireAt(task)?.let { (now - it).coerceAtLeast(0) > CATCH_UP_TOLERANCE_MILLIS } ?: false

    private suspend fun scheduleOrDisable(task: ScheduledTaskEntity) {
        try {
            backend.schedule(task)
        } catch (_: Exception) {
            runCatching { backend.cancel(task) }
            dao.update(task.copy(enabled = false, nextFireAtMillis = null, lastResult = ScheduleRunResult.FAILED.name))
        }
    }
}
