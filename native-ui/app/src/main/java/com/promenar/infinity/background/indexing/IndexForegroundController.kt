package com.promenar.infinity.background.indexing

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.promenar.infinity.data.rag.VectorizationQueue
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 在主线程协调启动，索引队列本身不依赖前台服务。 */
internal class IndexForegroundController(
    context: Context,
    private val scope: CoroutineScope,
    private val queueState: StateFlow<VectorizationQueue.QueueState>,
    private val visible: StateFlow<Boolean>,
) {
    private val applicationContext = context.applicationContext
    private val gate = IndexForegroundStartGate()

    fun start() {
        scope.launch {
            combine(queueState, visible) { state, isVisible ->
                (reduceIndexForeground(state) is IndexForegroundDecision.Active) to isVisible
            }.collect { (hasDocuments, visible) ->
                if (gate.observe(hasDocuments, visible)) requestService()
            }
        }
    }

    fun suppressRestart() = gate.suppressRestart()

    fun serviceStopped(queueWasEmpty: Boolean) {
        val hasDocuments = reduceIndexForeground(queueState.value) is IndexForegroundDecision.Active
        if (gate.serviceStopped(queueWasEmpty, hasDocuments, visible.value)) requestService()
    }

    private fun requestService() {
        if (!visible.value) {
            gate.startRejected()
            return
        }
        try {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, IndexForegroundService::class.java),
            )
        } catch (error: RuntimeException) {
            // 包括系统禁止后台启动、权限不足及服务状态异常；索引继续在进程内运行。
            gate.startRejected()
            InfinityLogger.log("[IndexForeground] 启动失败：${error.javaClass.simpleName}")
        }
    }
}
