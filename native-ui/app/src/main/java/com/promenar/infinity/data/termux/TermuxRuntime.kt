package com.promenar.infinity.data.termux

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Parcel
import com.promenar.infinity.InfinityApplication
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TermuxCommandRequest(val command: String, val workdir: String? = null, val timeoutSeconds: Int = 60, val stdin: String? = null) {
    init {
        require(command.isNotBlank() && '\u0000' !in command && command.toByteArray(Charsets.UTF_8).size < 131072) { "命令为空、含 NUL 或超过单参数上限" }
        require(timeoutSeconds in 1..600) { "超时必须为 1–600 秒" }
        require(workdir == null || workdir.length <= 4096 && '\u0000' !in workdir &&
            (workdir.startsWith('/') || workdir.startsWith("~/") || workdir.startsWith("\$PREFIX/"))) { "工作目录必须是绝对路径、~/ 或 \$PREFIX/ 路径" }
        require(command.length.toLong() + (workdir?.length ?: 0) + (stdin?.length ?: 0) <= 180_000) { "请求内容超过 Intent 安全上限，请通过文件传递较大输入" }
    }
}

object TermuxPreferences {
    const val ENABLED_KEY = "termux_integration_enabled"
    fun isEnabled(settings: SharedPreferences): Boolean = settings.getBoolean(ENABLED_KEY, false)
}

class TermuxRuntime(context: Context) {
    private val context = context.applicationContext
    internal val requests = TermuxRequestRegistry()

    fun availability(): TermuxAvailability {
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo("com.termux", PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") context.packageManager.getPackageInfo("com.termux", 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return TermuxAvailability(false, null, false)
        }
        return TermuxAvailability(true, info.versionName, context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }

    suspend fun execute(command: String, workdir: String? = null, timeoutSeconds: Int = 60, stdin: String? = null): TermuxCommandResult =
        try { executeRequest(TermuxCommandRequest(command, workdir, timeoutSeconds, stdin)) }
        catch (_: IllegalArgumentException) { invalidArguments() }

    suspend fun executeRequest(request: TermuxCommandRequest): TermuxCommandResult = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE)
        if (!TermuxPreferences.isEnabled(preferences)) return@withContext TermuxCommandResult(
            errorCode = "TERMUX_INTEGRATION_DISABLED", guidance = "请先在设置 → 工具 → Termux 接入与诊断启用 Termux 集成。",
        )
        val state = availability()
        if (state.errorCode != null) return@withContext TermuxCommandResult(errorCode = state.errorCode, guidance = state.guidance)
        val id = UUID.randomUUID().toString()
        val callback = PendingIntent.getBroadcast(context, 0, TermuxIntents.callback(context, id), PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        try {
            requests.execute(id, request.timeoutSeconds * 1000L, dispatch = {
                check(TermuxPreferences.isEnabled(preferences)) { "Termux 集成已关闭" }
                checkNotNull(TermuxIntents.dispatch(context, TermuxIntents.command(context, request, callback))) {
                    "系统未返回 Termux 服务组件，调用可能被系统的应用关联启动限制拦截"
                }
            }, cleanup = callback::cancel)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: SecurityException) {
            TermuxCommandResult(errorCode = "TERMUX_PERMISSION_DENIED", guidance = "Termux 拒绝调用；请在 Infinity Deck 的系统应用权限页授予 RUN_COMMAND 权限。")
        } catch (_: IllegalArgumentException) { invalidArguments()
        } catch (failure: Exception) {
            TermuxCommandResult(
                errorCode = "TERMUX_START_FAILED",
                guidance = "无法启动 Termux 后台命令；请确认 Termux 已初始化、集成开关已开启。ColorOS、MIUI 等系统还需在应用耗电管理中把 Infinity Deck 和 Termux 都设为“完全允许后台行为”，重新安装后可能需要再次设置。",
                errmsg = listOfNotNull(failure.javaClass.simpleName, failure.message?.take(300)).joinToString(": "),
            )
        } finally { callback.cancel() }
    }

    private fun invalidArguments() = TermuxCommandResult(errorCode = "TERMUX_INVALID_ARGUMENTS", guidance = "请检查命令、绝对工作目录、输入长度与 1–600 秒的超时参数。")
    companion object { const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND" }
}

internal object TermuxIntents {
    private const val RESULT_ACTION = "com.promenar.infinity.TERMUX_RESULT"
    fun command(context: Context, request: TermuxCommandRequest, callback: PendingIntent): Intent {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", request.command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", request.workdir ?: "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
            putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", context.packageName)
            request.stdin?.let { putExtra("com.termux.RUN_COMMAND_STDIN", it) }
        }
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            require(parcel.dataSize() <= 400 * 1024) { "Intent 内容超过安全上限" }
        } finally { parcel.recycle() }
        return intent
    }

    /**
     * 优先普通启动：部分厂商系统（真机 ColorOS 16）会让 startForegroundService 静默返回 null。
     * 仅当系统因后台启动限制明确拒绝时，才退回前台服务启动；RunCommandService 自身会调用 startForeground。
     */
    fun dispatch(context: Context, intent: Intent): android.content.ComponentName? = try {
        context.startService(intent)
    } catch (_: IllegalStateException) {
        context.startForegroundService(intent)
    }

    fun callback(context: Context, requestId: String): Intent = Intent(context, TermuxResultReceiver::class.java).apply {
        action = RESULT_ACTION
        // 唯一性和关联身份位于既定 data，不使用可由回传覆盖的 extras。
        data = Uri.Builder().scheme("infinity-termux").authority(context.packageName).appendPath(requestId).build()
    }

    fun requestId(intent: Intent): String? {
        if (intent.action != RESULT_ACTION || intent.component?.className != TermuxResultReceiver::class.java.name) return null
        val uri = intent.data ?: return null
        if (uri.scheme != "infinity-termux" || uri.authority.isNullOrBlank() || uri.query != null || uri.fragment != null) return null
        return uri.pathSegments.singleOrNull()?.takeIf(String::isNotBlank)
    }

    @Suppress("DEPRECATION")
    fun result(intent: Intent): TermuxCommandResult = try {
        val bundle = intent.getBundleExtra("result")
        TermuxResultParser.parse(bundle?.let { data ->
            listOf("stdout", "stderr", "exitCode", "err", "errmsg", "stdout_original_length", "stderr_original_length")
                .associateWith { data.get(it) }
        })
    } catch (_: Exception) { TermuxResultParser.parse(null) }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = TermuxIntents.requestId(intent) ?: return
        val app = context.applicationContext as? InfinityApplication ?: return
        app.termuxRuntime.requests.complete(id, TermuxIntents.result(intent))
    }
}
