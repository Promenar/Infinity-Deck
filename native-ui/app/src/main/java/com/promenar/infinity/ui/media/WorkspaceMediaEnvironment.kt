package com.promenar.infinity.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 会话内工作区媒体的渲染环境：会话 id、解析器、解析缓存与“在资源管理器中定位”入口。
 * 由会话界面提供；预览与其它页面未提供时，媒体引用显示为普通链接或不可用占位。
 */
@Stable
class WorkspaceMediaEnvironment(
    val sessionId: String,
    val resolver: WorkspaceMediaResolver,
    val cache: WorkspaceMediaResolutionCache = WorkspaceMediaResolutionCache.Shared,
    /** 打开资源管理器并定位到给定虚拟目录；为空时不显示定位入口。 */
    val onLocate: ((String) -> Unit)? = null,
) {
    override fun equals(other: Any?): Boolean = other is WorkspaceMediaEnvironment &&
        other.sessionId == sessionId && other.resolver === resolver && other.cache === cache && other.onLocate === onLocate

    override fun hashCode(): Int = sessionId.hashCode() * 31 + resolver.hashCode()
}

val LocalWorkspaceMedia = staticCompositionLocalOf<WorkspaceMediaEnvironment?> { null }

/**
 * 视觉实验室为演示会话登记的内存媒体解析器。会话页的媒体环境与图片取数器按会话 id 优先使用它；
 * 真实会话从不登记，仍走应用的工作区解析器。
 */
object LabWorkspaceMediaResolvers {
    private val resolvers = java.util.concurrent.ConcurrentHashMap<String, WorkspaceMediaResolver>()

    fun register(sessionId: String, resolver: WorkspaceMediaResolver) {
        resolvers[sessionId] = resolver
    }

    fun unregister(sessionId: String, resolver: WorkspaceMediaResolver) {
        resolvers.remove(sessionId, resolver)
    }

    fun forSession(sessionId: String): WorkspaceMediaResolver? = resolvers[sessionId]
}

/**
 * 按“会话 + 路径”缓存的解析结果。滚动复用与流式重组直接读取缓存，不重复解析；
 * 超过 [revalidateAfterMs] 的条目在下次进入组合时后台复核一次，文件被替换后卡片随之更新。
 */
class WorkspaceMediaResolutionCache(
    private val maxEntries: Int = 256,
    private val revalidateAfterMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val value: ResolvedWorkspaceMedia, val resolvedAtMs: Long)

    private val entries = object : LinkedHashMap<Pair<String, String>, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, Entry>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun get(sessionId: String, path: String): ResolvedWorkspaceMedia? = entries[sessionId to path]?.value

    @Synchronized
    fun put(sessionId: String, path: String, value: ResolvedWorkspaceMedia) {
        entries[sessionId to path] = Entry(value, clock())
    }

    /** 条目缺失或已过复核期时返回 true。 */
    @Synchronized
    fun needsResolve(sessionId: String, path: String): Boolean {
        val entry = entries[sessionId to path] ?: return true
        return clock() - entry.resolvedAtMs >= revalidateAfterMs
    }

    @Synchronized
    fun invalidate(sessionId: String, path: String) {
        entries.remove(sessionId to path)
    }

    companion object {
        val Shared = WorkspaceMediaResolutionCache()
    }
}

/**
 * 组合时解析一次 [path]：缓存命中立即返回（不闪烁），未命中或过期时在 IO 线程解析并写回缓存。
 * 返回 null 表示首次解析尚未完成。
 */
@Composable
fun rememberWorkspaceMediaResolution(
    environment: WorkspaceMediaEnvironment,
    path: String,
): ResolvedWorkspaceMedia? {
    // 引用相等策略：始终持有解析器最近一次返回的对象（open 按对象身份绑定会话与来源）。
    var state by remember(environment.sessionId, path, environment.cache) {
        mutableStateOf(environment.cache.get(environment.sessionId, path), androidx.compose.runtime.referentialEqualityPolicy())
    }
    LaunchedEffect(environment.sessionId, path, environment.resolver, environment.cache) {
        if (!environment.cache.needsResolve(environment.sessionId, path)) {
            state = environment.cache.get(environment.sessionId, path)
            return@LaunchedEffect
        }
        val resolved = runCatching {
            withContext(Dispatchers.IO) {
                resolvedWithIdentity(environment.resolver.resolve(environment.sessionId, path))
            }
        }.getOrElse { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            ResolvedWorkspaceMedia(
                com.promenar.infinity.domain.media.MediaResolution.Unavailable(
                    path,
                    com.promenar.infinity.domain.media.MediaUnavailableReason.UNREADABLE,
                ),
                null,
            )
        }
        environment.cache.put(environment.sessionId, path, resolved)
        state = resolved
    }
    return state
}

/** 进程内是否已清理过分享缓存；首次进入会话时清理一次，替代启动时清理。 */
private val shareCacheCleaned = java.util.concurrent.atomic.AtomicBoolean(false)

/**
 * 会话界面的媒体环境：[sessionId] 为空或应用未提供解析器（预览、截图）时返回 null。
 * 会话切换时暂停正在播放的媒体。
 */
@Composable
fun rememberSessionWorkspaceMedia(
    sessionId: String?,
    onLocate: (String) -> Unit,
): WorkspaceMediaEnvironment? {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 实验室沙箱里不解析真实工作区，也不动进程里正在播放的媒体与分享缓存。
    val sandboxed = com.promenar.infinity.ui.common.LocalLabSandbox.current
    val application = (context.applicationContext as? com.promenar.infinity.InfinityApplication)?.takeUnless { sandboxed }
    val currentOnLocate by androidx.compose.runtime.rememberUpdatedState(onLocate)
    val stableOnLocate = remember { { path: String -> currentOnLocate(path) } }
    LaunchedEffect(sessionId) { if (!sandboxed) MediaPlaybackCoordinator.Shared.pauseAll() }
    LaunchedEffect(application) {
        if (application != null && shareCacheCleaned.compareAndSet(false, true)) {
            withContext(Dispatchers.IO) { runCatching { MediaFileActions.cleanupShareCache(application) } }
        }
    }
    return remember(sessionId, application, sandboxed) {
        // 沙箱内只使用实验室为演示会话登记的解析器，没有登记时不解析媒体。
        val labResolver = if (sandboxed && sessionId != null) LabWorkspaceMediaResolvers.forSession(sessionId) else null
        when {
            sessionId == null -> null
            labResolver != null -> WorkspaceMediaEnvironment(sessionId, labResolver)
            application == null -> null
            else -> WorkspaceMediaEnvironment(sessionId, application.workspaceMediaResolver, onLocate = stableOnLocate)
        }
    }
}
