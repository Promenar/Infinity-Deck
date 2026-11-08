package com.promenar.infinity.background.exec

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.promenar.infinity.data.exec.ExecForegroundState
import com.promenar.infinity.data.exec.ExecHeld
import com.promenar.infinity.data.exec.ExecKeepAlive
import com.promenar.infinity.data.exec.ExecRunInfo
import com.promenar.infinity.data.exec.ExecRunRegistry

/**
 * 执行及结束处理期间持有 `PARTIAL_WAKE_LOCK`，在退出 hold 时释放；Play 渠道仅在应用可见时持有。
 * [foregroundServiceAvailable] 时启动 [ExecForegroundService] 显示命令摘要与停止按钮。
 * 前台服务被系统拒绝时命令照常运行，只是没有通知。
 */
class AndroidExecKeepAlive(
    context: Context,
    private val registry: ExecRunRegistry,
    private val foregroundServiceAvailable: Boolean,
) : ExecKeepAlive {
    private val applicationContext = context.applicationContext
    private val power = applicationContext.getSystemService(PowerManager::class.java)

    override suspend fun <T> hold(run: ExecRunInfo, stop: () -> Unit, block: suspend () -> T): ExecHeld<T> {
        val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
        val lifecycle = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { if (!wakeLock.isHeld) wakeLock.acquire() }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { if (wakeLock.isHeld) wakeLock.release() }
        }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (foregroundServiceAvailable) wakeLock.acquire()
                else {
                    lifecycle.addObserver(observer)
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) && !wakeLock.isHeld) wakeLock.acquire()
                }
            }
            registry.register(ExecRunRegistry.Active(run, stop))
            val foreground = if (foregroundServiceAvailable) startForeground() else ExecForegroundState.UNSUPPORTED
            return ExecHeld(block(), foreground)
        } finally {
            registry.unregister(run.id)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) {
                if (!foregroundServiceAvailable) lifecycle.removeObserver(observer)
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun startForeground(): ExecForegroundState = try {
        ContextCompat.startForegroundService(applicationContext, ExecForegroundService.startIntent(applicationContext))
        ExecForegroundState.STARTED
    } catch (_: ForegroundServiceStartNotAllowedException) {
        ExecForegroundState.NOT_ALLOWED
    } catch (_: SecurityException) {
        ExecForegroundState.NOT_ALLOWED
    } catch (_: IllegalStateException) {
        ExecForegroundState.NOT_ALLOWED
    }

    private companion object {
        const val WAKE_LOCK_TAG = "Infinity Deck:builtin-exec"
    }
}
