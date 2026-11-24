package com.promenar.infinity.data.proxy

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

/** 活动链路切换后重新取当前网络，旧网络的回调不会写入过期 DNS。 */
class AndroidBuiltInProxyDns(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun current(): List<String> = runCatching {
        connectivity?.activeNetwork?.let { connectivity.getLinkProperties(it)?.dnsServers }
            .orEmpty().mapNotNull { it.hostAddress }.distinct()
    }.getOrDefault(emptyList())

    val changes: Flow<List<String>> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(current()) }
            override fun onLost(network: Network) { trySend(current()) }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network == manager.activeNetwork) {
                    trySend(linkProperties.dnsServers.mapNotNull { it.hostAddress }.distinct())
                }
            }
        }
        val registered = runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        trySend(current())
        awaitClose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}

class BuiltInProxyManager(
    private val store: BuiltInProxyStore,
    private val launcher: BuiltInProxyProcessLauncher?,
    private val scope: CoroutineScope,
    private val readDns: () -> List<String> = { emptyList() },
    private val dnsChanges: Flow<List<String>> = emptyFlow(),
    private val setUpstream: suspend (BuiltInProxyEndpoint?) -> Unit = {},
    private val fetchSubscription: (suspend (String) -> String)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BuiltInProxyController, BuiltInProxyRouting {
    override val available: Boolean get() = launcher != null
    override val settings: StateFlow<BuiltInProxySettings> = store.settings

    private val mutableEndpoint = MutableStateFlow<BuiltInProxyEndpoint?>(null)
    override val endpoint: StateFlow<BuiltInProxyEndpoint?> = mutableEndpoint.asStateFlow()
    private val mutableState = MutableStateFlow(stateFor(if (available) BuiltInProxyPhase.STOPPED else BuiltInProxyPhase.UNAVAILABLE))
    override val state: StateFlow<BuiltInProxyState> = mutableState.asStateFlow()

    override val proxySelector = BuiltInProxySelector(this)
    override val proxyAuthenticator = BuiltInProxyAuthenticator(this)
    override val proxyAuthInterceptor = BuiltInPreemptiveProxyAuthInterceptor(this)

    private val lifecycle = Mutex()
    private val oneShot = Mutex()
    private val subscriptionRefresh = Mutex()
    private val random = SecureRandom()
    private val subscriptionFetcher by lazy { BuiltInProxySubscriptionFetcher(this) }
    private var runtime: Runtime? = null
    private var dnsServers = emptyList<String>()
    private var observingDns = false

    override suspend fun start() = update {
        if (!observingDns) {
            observingDns = true
            dnsServers = readDns().distinct()
            scope.launch(dispatcher) { dnsChanges.collect(::updateDns) }
        }
    }

    override suspend fun setEnabled(enabled: Boolean) = update { store.setEnabled(enabled) }
    override suspend fun setMode(mode: ProxyMode) = update { store.setMode(mode) }
    override suspend fun selectNode(id: String) = update { store.selectNode(id) }
    override suspend fun setNodeEnabled(id: String, enabled: Boolean) = update { store.setNodeEnabled(id, enabled) }
    override suspend fun moveNode(id: String, offset: Int) = update { store.moveNode(id, offset) }
    override suspend fun removeNode(id: String) = update { store.removeNode(id) }
    override suspend fun removeSubscription(id: String) = update { store.removeSubscription(id) }
    override suspend fun syncAdvancedYaml() = update { store.syncAdvancedYaml() }

    override suspend fun importNodes(content: String): List<ProxyNodeItem> {
        checkSize(content)
        val reply = inspection { client ->
            client.call(ProxyOps.IMPORT, buildJsonObject { put("content", content) }, ProxyImportReply.serializer())
        }
        return change { store.addImportedNodes(reply.configs()) }
    }

    override suspend fun addSubscription(name: String, url: String): ProxySubscription =
        change { store.addSubscription(name, url) }

    override suspend fun readSubscriptionUrl(id: String): String? = withContext(dispatcher) { store.readSubscriptionUrl(id) }

    override suspend fun refreshSubscription(id: String): ProxySubscription = subscriptionRefresh.withLock {
        val url = readSubscriptionUrl(id) ?: throw IllegalArgumentException("订阅不存在")
        val content = fetchSubscription?.invoke(url) ?: subscriptionFetcher.fetch(url)
        checkSize(content)
        val reply = inspection { client ->
            client.call(ProxyOps.IMPORT, buildJsonObject { put("content", content) }, ProxyImportReply.serializer())
        }
        change { store.replaceSubscriptionNodes(id, reply.configs(), expectedUrl = url) }
    }

    override suspend fun validateYaml(yaml: String): ProxyYamlValidation = validateConfig(yaml).summary()

    override suspend fun readAdvancedYaml(): String? = withContext(dispatcher) { store.readAdvancedYaml() }

    override suspend fun setAdvancedYaml(yaml: String?): ProxyYamlValidation {
        if (yaml.isNullOrBlank()) {
            update { store.setAdvancedYaml(null, emptyList()) }
            return ProxyYamlValidation(valid = true)
        }
        val reply = validateConfig(yaml)
        if (reply.valid) update { store.setAdvancedYaml(yaml, reply.configs()) }
        return reply.summary()
    }

    override suspend fun testLatency(nodeIds: List<String>, url: String?, timeoutMillis: Long): List<ProxyLatencyResult> {
        require(timeoutMillis in 250L..15_000L) { "测速超时须为 250 至 15000 毫秒" }
        val config = withContext(dispatcher) {
            lifecycle.withLock {
                val known = settings.value.nodes.mapTo(hashSetOf()) { it.id }
                val ids = nodeIds.distinct().filter(known::contains)
                if (ids.isEmpty()) return@withLock null
                if (ids.size > MAX_LATENCY_NODES) throw IllegalArgumentException("一次最多测试 $MAX_LATENCY_NODES 个节点")
                ids to store.runtimeYaml()
            }
        } ?: return emptyList()
        return inspection { client ->
            client.call(
                ProxyOps.LATENCY,
                buildJsonObject {
                    put("nodes", config.first.asJson())
                    put("yaml", config.second)
                    url?.let { put("url", it) }
                    put("timeoutMs", timeoutMillis)
                },
                ProxyLatencyReply.serializer(),
                timeoutMillis = timeoutMillis * config.first.size + 2_000,
            ).nodes
        }
    }

    private suspend fun validateConfig(yaml: String): ProxyValidationReply {
        checkSize(yaml)
        return inspection { client ->
            client.call(ProxyOps.VALIDATE, buildJsonObject { put("yaml", yaml) }, ProxyValidationReply.serializer())
        }
    }

    private suspend fun <T> inspection(block: suspend (BuiltInProxyClient) -> T): T = withContext(dispatcher) {
        val client = lifecycle.withLock { runtime?.client?.takeIf { it.alive } }
        if (client != null) return@withContext block(client)
        oneShot.withLock {
            val process = launcher?.launch() ?: throw BuiltInProxyUnavailableException("此安装包未包含内置代理")
            val temporary = BuiltInProxyClient(process)
            try {
                val dns = readDns()
                if (dns.isNotEmpty()) temporary.call(ProxyOps.DNS, buildJsonObject { put("dnsServers", dns.asJson()) })
                block(temporary)
            } finally {
                temporary.close()
            }
        }
    }

    private suspend fun update(mutation: () -> Unit) { change(mutation) }

    private suspend fun <T> change(mutation: () -> T): T = withContext(dispatcher) {
        lifecycle.withLock {
            val result = mutation()
            // 已落盘的设置变更必须同步到运行时，即使界面随后取消这次调用。
            withContext(NonCancellable) { reconcile() }
            result
        }
    }

    private suspend fun reconcile() {
        val options = settings.value
        if (!store.settingsReadable) {
            stopRuntime()
            mutableState.value = stateFor(BuiltInProxyPhase.ERROR, error = "内置代理设置读取失败")
            return
        }
        if (!available || !options.enabled || options.nodes.none { it.enabled }) {
            stopRuntime()
            mutableState.value = stateFor(if (available) BuiltInProxyPhase.STOPPED else BuiltInProxyPhase.UNAVAILABLE)
            return
        }
        try {
            val config = RuntimeConfig(
                options.mode,
                options.activeNode,
                options.nodes.filter { it.enabled }.map { it.id },
                store.runtimeYaml(),
            )
            val existing = runtime?.takeIf { it.client.alive }
            if (existing != null && existing.config.matches(config)) {
                mutableState.value = mutableState.value.copy(nodes = options.nodes)
                return
            }
            if (existing == null) {
                stopRuntime()
                startRuntime(config)
            } else {
                existing.client.call(ProxyOps.RELOAD, config.params(), ProxyStartReply.serializer())
                existing.config = config
                mutableState.value = stateFor(BuiltInProxyPhase.RUNNING, readState(existing.client))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stopRuntime()
            mutableState.value = stateFor(BuiltInProxyPhase.ERROR, error = "内置代理运行失败，请检查启用节点与网络")
        }
    }

    private suspend fun startRuntime(config: RuntimeConfig) {
        mutableState.value = stateFor(BuiltInProxyPhase.STARTING)
        val ticket = Any()
        val user = "nx-" + credential(9)
        val password = credential(24)
        val process = checkNotNull(launcher).launch()
        val client = BuiltInProxyClient(
            process,
            onState = { reported -> scope.launch(dispatcher) { handleState(ticket, reported) } },
            onExit = { scope.launch(dispatcher) { handleExit(ticket) } },
        )
        runtime = Runtime(ticket, client, config)
        val result = client.call(
            ProxyOps.START,
            config.params(buildJsonObject {
                put("proxyUser", user)
                put("proxyPassword", password)
                put("dnsServers", dnsServers.asJson())
            }),
            ProxyStartReply.serializer(),
        )
        val reported = readState(client)
        if (!client.alive || !reported.running) throw BuiltInProxyUnavailableException()
        val live = BuiltInProxyEndpoint(result.proxyPort, user, password)
        mutableEndpoint.value = live
        mutableState.value = stateFor(BuiltInProxyPhase.RUNNING, reported)
        try {
            setUpstream(live)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Tailscale 上游同步失败由其自身生命周期处理。
        }
    }

    private suspend fun readState(client: BuiltInProxyClient): ProxyReplyState =
        client.call(ProxyOps.STATUS, JsonObject(emptyMap()), ProxyReplyState.serializer())

    private suspend fun stopRuntime() {
        val existing = runtime ?: return
        runtime = null
        mutableEndpoint.value = null
        try {
            setUpstream(null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 已取消端点的本地发布，仍须回收子进程。
        } finally {
            existing.client.close()
        }
    }

    private suspend fun handleState(ticket: Any, reported: ProxyReplyState) = lifecycle.withLock {
        if (runtime?.ticket !== ticket || !reported.running || reported.mode != null && reported.mode != settings.value.mode) return@withLock
        if (endpoint.value != null) mutableState.value = stateFor(BuiltInProxyPhase.RUNNING, reported)
    }

    private suspend fun handleExit(ticket: Any) = lifecycle.withLock {
        if (runtime?.ticket !== ticket) return@withLock
        stopRuntime()
        mutableState.value = stateFor(BuiltInProxyPhase.ERROR, error = "内置代理进程已退出")
    }

    private suspend fun updateDns(servers: List<String>) = lifecycle.withLock {
        if (dnsServers == servers) return@withLock
        dnsServers = servers
        val child = runtime?.client ?: return@withLock
        try {
            child.call(ProxyOps.DNS, buildJsonObject { put("dnsServers", servers.asJson()) }, timeoutMillis = 2_000)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            stopRuntime()
            reconcile()
        }
    }

    private fun stateFor(phase: BuiltInProxyPhase, reported: ProxyReplyState? = null, error: String? = null): BuiltInProxyState {
        val options = settings.value
        return BuiltInProxyState(
            phase = phase,
            enabled = options.enabled,
            mode = options.mode,
            activeNode = reported?.activeNode?.takeIf { id -> options.nodes.any { it.id == id && it.enabled } },
            healthyNodeCount = reported?.healthyNodeCount ?: 0,
            fallbackReason = reported?.fallbackReason?.takeIf { it.length <= 128 },
            nodes = options.nodes,
            error = error,
        )
    }

    private fun credential(size: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size).also(random::nextBytes))

    private class Runtime(val ticket: Any, val client: BuiltInProxyClient, var config: RuntimeConfig)

    private class RuntimeConfig(val mode: ProxyMode, val active: String?, val ids: List<String>, val yaml: String) {
        fun matches(other: RuntimeConfig): Boolean = mode == other.mode && active == other.active && ids == other.ids && yaml == other.yaml

        fun params(extra: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
            extra.forEach { (key, value) -> put(key, value) }
            put("mode", proxyJson.encodeToJsonElement(ProxyMode.serializer(), mode))
            active?.let { put("activeNode", it) }
            put("enabledNodes", ids.asJson())
            put("fallbackOrder", ids.asJson())
            put("yaml", yaml)
        }
    }

    private companion object {
        const val MAX_LATENCY_NODES = 32
        fun checkSize(input: String) {
            require(input.length <= BuiltInProxySubscriptionFetcher.MAX_BYTES &&
                input.toByteArray(Charsets.UTF_8).size <= BuiltInProxySubscriptionFetcher.MAX_BYTES) {
                "内置代理配置超过大小限制"
            }
        }
    }
}

private fun List<String>.asJson(): JsonArray = JsonArray(map(::JsonPrimitive))
