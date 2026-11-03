package com.promenar.infinity.data.tailnet

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** 内置 Tailscale 所处阶段。 */
enum class TailnetPhase {
    /** 安装包未包含 libnxtailnet.so（构建时以 -Pinfinity.tailnet=false 跳过）。 */
    UNAVAILABLE,
    DISABLED,
    STOPPED,
    STARTING,
    NEEDS_LOGIN,
    RUNNING,
    FAILED,
}

/** 失败原因码；界面与 tailnet_status 按码给出说明。 */
object TailnetFailure {
    const val LAUNCH_FAILED = "launch_failed"
    const val START_FAILED = "start_failed"
    const val EXITED = "exited"
}

data class TailnetState(
    val phase: TailnetPhase,
    val summary: TailnetStatusSummary? = null,
    val peers: List<TailnetPeer> = emptyList(),
    val failure: String? = null,
)

/** 设置页与 tailnet_status 使用的控制接口。 */
interface TailnetController {
    val available: Boolean
    val state: StateFlow<TailnetState>
    val settings: StateFlow<TailnetSettings>

    /** 开始跟随设置：启用时立即启动子进程（App 启动时调用一次）。 */
    fun start()
    /** 失败或退出后重新启动。 */
    fun retry()
    /** 读取最新状态与节点列表；未运行时返回当前状态。 */
    suspend fun refresh(): TailnetState
    /** 发起浏览器登录，返回登录链接；已在线或超时返回 null。 */
    suspend fun login(): String?
    /** 注销本机节点并删除已保存的 auth key。 */
    suspend fun logout(): Boolean

    suspend fun setEnabled(enabled: Boolean)
    suspend fun setEphemeral(ephemeral: Boolean)
    suspend fun setControlUrl(raw: String): Boolean
    suspend fun setAuthKey(raw: CharArray): Boolean
    suspend fun clearAuthKey()
    suspend fun revealAuthKey(): CharArray?
}

/**
 * 管理 libnxtailnet.so 子进程（不常驻后台服务：随 App 进程存在，App 进程结束时 stdin 关闭，子进程随之下线退出）。
 * 启用后在 App 启动时拉起；进程退出后在下一次需要 tailnet 路由时按需重启（失败后 [RETRY_BACKOFF_MS] 内不重试）。
 * 同时实现 [TailnetRouting]：tailnet 目标经本机代理，其它目标保持原有路径。
 * 进程启动、管道读写与状态目录操作都在 [ioDispatcher]（默认 Dispatchers.IO）。
 */
class TailnetManager(
    private val settingsStore: TailnetSettingsStore,
    private val launcher: TailnetProcessLauncher?,
    private val stateDirectory: File,
    private val hostname: String,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val systemRoutesTailnet: () -> Boolean = ::hasSystemTailnetInterface,
) : TailnetRouting, TailnetController, TailnetEndpointSource, TailnetConnector {

    private inner class Session(val config: LaunchConfig) {
        @Volatile var client: TailnetClient? = null
        @Volatile var stopping = false
        val endpoint = CompletableFuture<TailnetProxyEndpoint?>()
    }

    private data class LaunchConfig(val controlUrl: String, val ephemeral: Boolean, val authKeyRevision: Int)

    private val lock = Mutex()
    private val startGuard = Any()
    @Volatile private var session: Session? = null
    @Volatile private var startInFlight: CompletableFuture<TailnetProxyEndpoint?>? = null
    @Volatile private var lastFailureAt = Long.MIN_VALUE / 2
    @Volatile private var magicDnsSuffix: String? = null
    /** 已知节点名（短名与 MagicDNS 全名）；null 表示尚未取得。未在线时为空集。 */
    @Volatile private var peerNames: Set<String>? = null
    private var follower: Job? = null

    private val mutableState = MutableStateFlow(
        TailnetState(
            when {
                launcher == null -> TailnetPhase.UNAVAILABLE
                settingsStore.current.enabled -> TailnetPhase.STOPPED
                else -> TailnetPhase.DISABLED
            },
        ),
    )

    override val available: Boolean get() = launcher != null
    override val state: StateFlow<TailnetState> = mutableState.asStateFlow()
    override val settings: StateFlow<TailnetSettings> get() = settingsStore.settings

    private val enabled: Boolean get() = launcher != null && settingsStore.current.enabled

    // ---- TailnetRouting ----

    override val proxySelector: java.net.ProxySelector = TailnetProxySelector(this)
    override val proxyAuthenticator: okhttp3.Authenticator = TailnetProxyAuthenticator(this)

    override fun agentProxy(): java.net.Proxy? =
        if (enabled && state.value.phase == TailnetPhase.RUNNING) currentEndpoint()?.let {
            java.net.Proxy(java.net.Proxy.Type.HTTP, TailnetProxyAddress.of(it))
        } else null

    override fun isTailnetHost(host: String): Boolean =
        enabled && TailnetHosts.isTailnetHost(host, magicDnsSuffix, peerNames)

    // 短名可能是局域网主机，关闭时不按 tailnet 拦截（传入空的已知节点集合）。
    override fun isUnreachableWhileDisabled(host: String): Boolean =
        launcher != null && !settingsStore.current.enabled &&
            TailnetHosts.isTailnetHost(host, magicDnsSuffix, emptySet()) && !systemRoutesTailnet()

    override fun jschProxy(host: String): com.jcraft.jsch.Proxy? {
        if (!isTailnetHost(host)) return null
        return awaitEndpoint(TailnetProxySelector.ROUTE_WAIT_MS)?.let(TailnetProxyConfig::jsch)
    }

    override fun subprocessEnvironment(): Map<String, String> {
        if (!enabled) return emptyMap()
        return awaitEndpoint(ENVIRONMENT_WAIT_MS)?.let(TailnetProxyConfig::environment).orEmpty()
    }

    // ---- TailnetEndpointSource ----

    override fun currentEndpoint(): TailnetProxyEndpoint? {
        val current = session ?: return null
        if (current.stopping || current.client?.isExited == true) return null
        return current.endpoint.getNow(null)
    }

    override fun awaitEndpoint(timeoutMillis: Long): TailnetProxyEndpoint? {
        if (!enabled) return null
        currentEndpoint()?.let { return it }
        val future = requestStart() ?: return null
        return runCatching { future.get(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    // ---- TailnetConnector ----

    override suspend fun ensureStarted(force: Boolean, timeoutMillis: Long): Boolean {
        if (!enabled) return false
        currentEndpoint()?.let { return true }
        if (force) lastFailureAt = Long.MIN_VALUE / 2
        val future = requestStart() ?: return false
        // 只挂起等待，不取消共享的启动 future：其它等待方与路由选择器仍依赖它。
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                future.whenComplete { endpoint, _ -> if (continuation.isActive) continuation.resume(endpoint != null) }
            }
        } ?: false
    }

    /** 返回正在进行或新发起的启动；退避期内或已停用返回 null。 */
    private fun requestStart(): CompletableFuture<TailnetProxyEndpoint?>? = synchronized(startGuard) {
        val current = session
        if (current != null && !current.stopping && current.client?.isExited != true) return current.endpoint
        startInFlight?.let { return it }
        if (!enabled || clock() - lastFailureAt < RETRY_BACKOFF_MS) return null
        val future = CompletableFuture<TailnetProxyEndpoint?>()
        startInFlight = future
        scope.launch(ioDispatcher) {
            lock.withLock {
                val live = session
                if (live != null && !live.stopping && live.client?.isExited != true) {
                    live.endpoint.whenComplete { value, _ -> future.complete(value) }
                } else if (enabled) {
                    launchLocked(settingsStore.current, future)
                } else {
                    future.complete(null)
                }
            }
            synchronized(startGuard) { if (startInFlight === future) startInFlight = null }
        }
        future
    }

    // ---- TailnetController ----

    override fun start() {
        synchronized(startGuard) {
            if (follower != null) return
            follower = scope.launch(ioDispatcher) {
                settingsStore.settings.collect { settings -> lock.withLock { applyLocked(settings) } }
            }
        }
    }

    override fun retry() {
        lastFailureAt = Long.MIN_VALUE / 2
        requestStart()
    }

    private suspend fun applyLocked(settings: TailnetSettings) {
        if (launcher == null) {
            mutableState.value = TailnetState(TailnetPhase.UNAVAILABLE)
            return
        }
        if (!settings.enabled) {
            stopLocked()
            mutableState.value = TailnetState(TailnetPhase.DISABLED)
            return
        }
        val current = session
        val config = settings.launchConfig()
        if (current != null && !current.stopping && current.client?.isExited != true) {
            if (current.config != config) {
                val controlChanged = current.config.controlUrl != config.controlUrl
                stopLocked()
                // 换控制服务器：旧节点身份对新服务器无效，清空状态目录后重新登录。
                if (controlChanged) withContext(ioDispatcher) { stateDirectory.deleteRecursively() }
                launchLocked(settings, CompletableFuture())
            }
            return
        }
        if (mutableState.value.phase == TailnetPhase.DISABLED || mutableState.value.phase == TailnetPhase.UNAVAILABLE) {
            mutableState.value = TailnetState(TailnetPhase.STOPPED)
        }
        // 启用、或配置变化时重新尝试；失败退避只约束按需启动。
        if (current == null || current.client?.isExited == true) launchLocked(settings, CompletableFuture())
    }

    private fun TailnetSettings.launchConfig() = LaunchConfig(controlUrl, ephemeral, authKeyRevision)

    /** 在 [lock] 内调用：启动子进程并发送 start 请求，[future] 以代理端点（失败为 null）完成。 */
    private suspend fun launchLocked(settings: TailnetSettings, future: CompletableFuture<TailnetProxyEndpoint?>) {
        val launcher = launcher ?: run {
            future.complete(null)
            return
        }
        val next = Session(settings.launchConfig())
        next.endpoint.whenComplete { value, _ -> future.complete(value) }
        session = next
        magicDnsSuffix = null
        peerNames = null
        mutableState.value = TailnetState(TailnetPhase.STARTING)
        val client = try {
            withContext(ioDispatcher) {
                stateDirectory.mkdirs()
                TailnetClient(
                    process = launcher.launch(),
                    onState = { summary -> onSummary(next, summary) },
                    onExit = { onExit(next) },
                    ioDispatcher = ioDispatcher,
                )
            }
        } catch (_: IOException) {
            fail(next, TailnetFailure.LAUNCH_FAILED)
            return
        } catch (_: SecurityException) {
            fail(next, TailnetFailure.LAUNCH_FAILED)
            return
        }
        next.client = client
        val user = randomToken(PROXY_SECRET_LENGTH)
        val password = randomToken(PROXY_SECRET_LENGTH)
        val authKey = withContext(ioDispatcher) { settingsStore.readAuthKey() }
        val options = TailnetStartOptions(
            stateDir = stateDirectory.absolutePath,
            hostname = hostname,
            controlUrl = settings.controlUrl.ifEmpty { null },
            authKey = authKey?.let { String(it) },
            ephemeral = settings.ephemeral,
            proxyUser = user,
            proxyPassword = password,
        )
        authKey?.fill('\u0000')
        val result = try {
            client.start(options)
        } catch (_: IOException) {
            null
        } catch (_: TailnetCommandException) {
            null
        }
        if (result == null) {
            withContext(ioDispatcher) { client.close() }
            fail(next, TailnetFailure.START_FAILED)
            return
        }
        next.endpoint.complete(TailnetProxyEndpoint(result.proxyPort, user, password))
    }

    private fun fail(target: Session, code: String) {
        target.endpoint.complete(null)
        lastFailureAt = clock()
        if (session === target) {
            session = null
            mutableState.value = TailnetState(TailnetPhase.FAILED, failure = code)
        }
    }

    private fun onSummary(target: Session, summary: TailnetStatusSummary) {
        if (session !== target || target.stopping) return
        magicDnsSuffix = summary.magicDnsSuffix?.takeIf { it.isNotBlank() }
        val phase = phaseOf(summary)
        if (phase != TailnetPhase.RUNNING) peerNames = if (phase == TailnetPhase.STARTING) null else emptySet()
        mutableState.update { previous ->
            TailnetState(
                phase = phase,
                summary = summary,
                peers = if (phase == TailnetPhase.RUNNING) previous.peers else emptyList(),
                failure = if (phase == TailnetPhase.FAILED) TailnetFailure.START_FAILED else null,
            )
        }
        if (phase == TailnetPhase.FAILED) lastFailureAt = clock()
        if (phase == TailnetPhase.RUNNING) scope.launch(ioDispatcher) { refreshPeers(target) }
    }

    private fun onExit(target: Session) {
        target.endpoint.complete(null)
        if (session !== target) return
        session = null
        peerNames = null
        if (target.stopping) return
        lastFailureAt = clock()
        mutableState.value = TailnetState(TailnetPhase.FAILED, failure = TailnetFailure.EXITED)
    }

    private suspend fun stopLocked() {
        val current = session ?: return
        current.stopping = true
        session = null
        peerNames = null
        magicDnsSuffix = null
        current.endpoint.complete(null)
        withContext(ioDispatcher) { current.client?.close() }
        if (mutableState.value.phase != TailnetPhase.DISABLED) mutableState.value = TailnetState(TailnetPhase.STOPPED)
    }

    private suspend fun refreshPeers(target: Session): List<TailnetPeer>? {
        val client = target.client ?: return null
        val peers = runCatching { client.peers() }.getOrNull() ?: return null
        if (session !== target) return null
        peerNames = peers.flatMapTo(mutableSetOf()) { peer ->
            listOfNotNull(peer.name, peer.dnsName).map(TailnetHosts::normalize).filter { it.isNotEmpty() }
        }
        mutableState.update { previous -> if (previous.phase == TailnetPhase.RUNNING) previous.copy(peers = peers) else previous }
        return peers
    }

    private fun liveSession(): Session? = session?.takeIf { !it.stopping && it.client?.isExited != true && it.client != null }

    override suspend fun refresh(): TailnetState {
        if (!enabled) return mutableState.value
        val target = liveSession() ?: run {
            withContext(ioDispatcher) { awaitEndpoint(ENVIRONMENT_WAIT_MS) }
            liveSession()
        } ?: return mutableState.value
        val client = target.client ?: return mutableState.value
        val summary = runCatching { client.status() }.getOrNull() ?: return mutableState.value
        onSummary(target, summary)
        if (summary.running) refreshPeers(target)
        return mutableState.value
    }

    override suspend fun login(): String? {
        if (!enabled) return null
        withContext(ioDispatcher) { awaitEndpoint(LOGIN_START_WAIT_MS) } ?: return null
        val target = liveSession() ?: return null
        val client = target.client ?: return null
        mutableState.value.summary?.let { summary ->
            if (summary.running) return null
            summary.authUrl?.takeIf { it.isNotBlank() }?.let { return it }
        }
        runCatching { client.login() }.getOrElse { return null }
        val next = withTimeoutOrNull(LOGIN_URL_WAIT_MS) {
            state.first { it.phase == TailnetPhase.RUNNING || !it.summary?.authUrl.isNullOrBlank() || it.phase == TailnetPhase.FAILED }
        }
        return next?.summary?.authUrl?.takeIf { next.phase == TailnetPhase.NEEDS_LOGIN && it.isNotBlank() }
    }

    override suspend fun logout(): Boolean {
        val client = liveSession()?.client
        val loggedOut = client != null && runCatching { client.logout() }.isSuccess
        withContext(ioDispatcher) { settingsStore.clearAuthKey() }
        return loggedOut
    }

    override suspend fun setEnabled(enabled: Boolean) = withContext(ioDispatcher) {
        if (enabled) lastFailureAt = Long.MIN_VALUE / 2
        settingsStore.setEnabled(enabled)
    }

    override suspend fun setEphemeral(ephemeral: Boolean) = withContext(ioDispatcher) { settingsStore.setEphemeral(ephemeral) }

    override suspend fun setControlUrl(raw: String): Boolean = withContext(ioDispatcher) { settingsStore.setControlUrl(raw) }

    override suspend fun setAuthKey(raw: CharArray): Boolean = withContext(ioDispatcher) {
        try {
            settingsStore.setAuthKey(raw)
        } finally {
            raw.fill('\u0000')
        }
    }

    override suspend fun clearAuthKey() = withContext(ioDispatcher) { settingsStore.clearAuthKey() }

    override suspend fun revealAuthKey(): CharArray? = withContext(ioDispatcher) { settingsStore.readAuthKey() }

    private fun randomToken(length: Int): String = buildString(length) {
        repeat(length) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
    }

    companion object {
        const val EXECUTABLE_NAME = "libnxtailnet.so"
        const val RETRY_BACKOFF_MS = 10_000L
        const val ENVIRONMENT_WAIT_MS = 3_000L
        const val LOGIN_START_WAIT_MS = 10_000L
        const val LOGIN_URL_WAIT_MS = 20_000L
        const val PROXY_SECRET_LENGTH = 24
        private const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        fun phaseOf(summary: TailnetStatusSummary): TailnetPhase = when {
            summary.running -> TailnetPhase.RUNNING
            summary.needsLogin -> TailnetPhase.NEEDS_LOGIN
            !summary.error.isNullOrBlank() -> TailnetPhase.FAILED
            else -> TailnetPhase.STARTING
        }

        /** tailnet 节点名：`infinity-` 加规范化的设备型号，符合 DNS 标签规则（小写字母、数字与连字符，至多 63 字符）。 */
        fun hostnameFor(model: String?): String {
            val slug = model.orEmpty().lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            val name = if (slug.isEmpty()) "infinity" else "infinity-$slug"
            return name.take(63).trimEnd('-')
        }
    }
}

/** 系统网络是否已有 tailnet 地址的网卡（官方 Tailscale App 等 VPN 会分配 100.64.0.0/10 或 fd7a:115c:a1e0::/48 地址）。 */
internal fun hasSystemTailnetInterface(): Boolean = runCatching {
    java.net.NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty().any { networkInterface ->
        networkInterface.isUp && networkInterface.inetAddresses.asSequence().any { TailnetHosts.isTailnetAddress(it.address) }
    }
}.getOrDefault(false)
