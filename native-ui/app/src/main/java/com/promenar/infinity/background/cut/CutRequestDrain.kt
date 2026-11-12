package com.promenar.infinity.background.cut

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 在服务所属串行调度器上核对请求世代，避免结束旧请求时漏接新请求。 */
internal suspend fun drainCutRequests(run: suspend () -> Unit, hasWork: suspend () -> Boolean, version: () -> Long) {
    do {
        val startedAt = version()
        try { run() }
        catch (cancelled: CancellationException) {
            // 单个导出取消后服务还能接手新请求；服务自身取消则立即退出。
            currentCoroutineContext().ensureActive()
        }
        if (version() == startedAt && !hasWork() && version() == startedAt) return
    } while (true)
}
