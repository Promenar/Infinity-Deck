package com.promenar.infinity.data.proxy

import android.content.SharedPreferences
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal class ProxyNodeConfig(val summary: ProxyNodeSummary, val config: JsonObject) {
    val id: String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalConfig(config.filterKeys { it != "name" }).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun item() = ProxyNodeItem(id, summary.name, summary.type, summary.server, summary.port)
}

/** 配置指纹不受分享名与对象字段顺序影响，数组保留顺序。 */
private fun canonicalConfig(value: Map<String, JsonElement>): JsonObject =
    JsonObject(value.toSortedMap().mapValues { canonicalValue(it.value) })

private fun canonicalValue(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> canonicalConfig(value)
    is JsonArray -> JsonArray(value.map(::canonicalValue))
    else -> value
}

@Serializable
private data class ProxyNodeSelection(
    val activeNode: String? = null,
    val nodes: List<ProxyNodeItem> = emptyList(),
)

@Serializable
private data class ProxyMetadata(
    val enabled: Boolean = false,
    val mode: ProxyMode = ProxyMode.SINGLE,
    val managed: ProxyNodeSelection = ProxyNodeSelection(),
    val advanced: ProxyNodeSelection? = null,
    val subscriptions: List<ProxySubscription> = emptyList(),
    val secretSlot: Int? = null,
) {
    val active: ProxyNodeSelection get() = advanced ?: managed
    fun settings() = BuiltInProxySettings(enabled, mode, active.activeNode, active.nodes, subscriptions, advanced != null)
}

@Serializable
private class ProxySecrets(
    val nodes: Map<String, JsonObject> = emptyMap(),
    val subscriptionUrls: Map<String, String> = emptyMap(),
    val advancedYaml: String? = null,
) {
    fun withNodes(nodes: Map<String, JsonObject>) = ProxySecrets(nodes, subscriptionUrls, advancedYaml)
}

/** 明文偏好保存设置与摘要。敏感内容先写备用加密槽，再一次提交指向新槽的偏好快照。 */
class BuiltInProxyStore(
    private val preferences: SharedPreferences,
    secretStore: () -> SecretStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(
        preferences: SharedPreferences,
        secrets: SecretStore,
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(preferences, { secrets }, nowMillis)

    private val secrets: SecretStore by lazy(secretStore)
    private val initial = runCatching {
        preferences.getString(KEY_METADATA, null)?.let { proxyJson.decodeFromString<ProxyMetadata>(it) } ?: ProxyMetadata()
    }
    internal val settingsReadable: Boolean get() = initial.isSuccess
    private var metadata = initial.getOrDefault(ProxyMetadata())
    private val mutable = MutableStateFlow(metadata.settings())
    val settings: StateFlow<BuiltInProxySettings> = mutable.asStateFlow()

    @Synchronized
    fun setEnabled(enabled: Boolean) = save(metadata.copy(enabled = enabled))

    @Synchronized
    fun setMode(mode: ProxyMode) = save(metadata.copy(mode = mode))

    @Synchronized
    fun selectNode(id: String) = editSelection { current ->
        require(current.nodes.any { it.id == id && it.enabled }) { "请选择已启用的节点" }
        current.copy(activeNode = id)
    }

    @Synchronized
    fun setNodeEnabled(id: String, enabled: Boolean) = editSelection { current ->
        current.withNodes(current.nodes.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    @Synchronized
    fun moveNode(id: String, offset: Int) = editSelection { current ->
        val from = current.nodes.indexOfFirst { it.id == id }
        if (from < 0) return@editSelection current
        val to = (from.toLong() + offset).coerceIn(0, current.nodes.lastIndex.toLong()).toInt()
        val nodes = current.nodes.toMutableList()
        nodes.add(to, nodes.removeAt(from))
        current.withNodes(nodes)
    }

    @Synchronized
    fun removeNode(id: String) {
        val next = metadata.editSelection { it.withNodes(it.nodes.filterNot { node -> node.id == id }) }
        if (next == metadata) return
        val stored = readSecrets()
        val updated = if (next.advanced != null) {
            ProxySecrets(stored.nodes, stored.subscriptionUrls, yamlFor(next.advanced.nodes, stored.nodes, useIds = false))
        } else stored
        save(next.withSubscriptionCounts(), updated)
    }

    @Synchronized
    internal fun addImportedNodes(configs: List<ProxyNodeConfig>): List<ProxyNodeItem> {
        val stored = readSecrets()
        val nodes = merge(metadata.managed.nodes, configs.map { it.item().copy(manuallyImported = true) })
        save(metadata.copy(managed = metadata.managed.withNodes(nodes)), stored.withNodes(mergeConfigs(stored, configs)))
        val ids = configs.mapTo(hashSetOf()) { it.id }
        return nodes.filter { it.id in ids }
    }

    @Synchronized
    fun addSubscription(name: String, url: String): ProxySubscription {
        val safeName = name.trim()
        require(safeName.isNotEmpty() && safeName.length <= 128) { "订阅名称无效" }
        val normalized = BuiltInProxySubscriptionFetcher.subscriptionUrl(url).toString()
        val stored = readSecrets()
        require(normalized !in stored.subscriptionUrls.values) { "此订阅已存在" }
        val item = ProxySubscription(UUID.randomUUID().toString(), safeName)
        save(
            metadata.copy(subscriptions = metadata.subscriptions + item),
            ProxySecrets(stored.nodes, stored.subscriptionUrls + (item.id to normalized), stored.advancedYaml),
        )
        return item
    }

    @Synchronized
    fun readSubscriptionUrl(id: String): String? = readSecrets().subscriptionUrls[id]

    @Synchronized
    internal fun replaceSubscriptionNodes(
        id: String,
        configs: List<ProxyNodeConfig>,
        expectedUrl: String? = null,
    ): ProxySubscription {
        val stored = readSecrets()
        check(id in stored.subscriptionUrls && (expectedUrl == null || stored.subscriptionUrls[id] == expectedUrl)) {
            "订阅已变更"
        }
        val incoming = configs.map { it.item().copy(subscriptionIds = setOf(id)) }
        val refreshed = incoming.mapTo(hashSetOf()) { it.id }
        val kept = metadata.managed.nodes.mapNotNull { node ->
            if (id !in node.subscriptionIds || node.id in refreshed) node
            else node.copy(subscriptionIds = node.subscriptionIds - id).takeIf(::hasSource)
        }
        val nodes = merge(kept, incoming)
        val next = metadata.copy(
            managed = metadata.managed.withNodes(nodes),
            subscriptions = metadata.subscriptions.map {
                if (it.id == id) it.copy(lastRefreshedAtMillis = nowMillis()) else it
            },
        ).withSubscriptionCounts()
        save(next, stored.withNodes(mergeConfigs(stored, configs)))
        return next.subscriptions.first { it.id == id }
    }

    @Synchronized
    fun removeSubscription(id: String) {
        if (metadata.subscriptions.none { it.id == id }) return
        val stored = readSecrets()
        val nodes = metadata.managed.nodes.mapNotNull { node ->
            node.copy(subscriptionIds = node.subscriptionIds - id).takeIf(::hasSource)
        }
        save(
            metadata.copy(
                managed = metadata.managed.withNodes(nodes),
                subscriptions = metadata.subscriptions.filterNot { it.id == id },
            ),
            ProxySecrets(stored.nodes, stored.subscriptionUrls - id, stored.advancedYaml),
        )
    }

    @Synchronized
    fun readAdvancedYaml(): String? = readSecrets().advancedYaml

    @Synchronized
    internal fun setAdvancedYaml(yaml: String?, configs: List<ProxyNodeConfig>) {
        val stored = readSecrets()
        val current = metadata.advanced ?: ProxyNodeSelection()
        val old = current.nodes.associateBy { it.id }
        val incoming = configs.map { it.item().copy(manuallyImported = true) }
        val nodes = incoming.distinctBy { it.id }.map { node -> node.copy(enabled = old[node.id]?.enabled ?: true) }
        save(
            metadata.copy(advanced = yaml?.let { current.withNodes(nodes) }),
            ProxySecrets(mergeConfigs(stored, configs), stored.subscriptionUrls, yaml),
        )
    }

    @Synchronized
    fun syncAdvancedYaml() {
        val advanced = metadata.advanced ?: return
        val stored = readSecrets()
        val nodes = merge(metadata.managed.nodes, advanced.nodes)
        save(
            metadata.copy(managed = metadata.managed.withNodes(nodes), advanced = null),
            ProxySecrets(stored.nodes, stored.subscriptionUrls),
        )
    }

    @Synchronized
    internal fun runtimeYaml(): String = yamlFor(metadata.active.nodes, readSecrets().nodes, useIds = true)

    private fun editSelection(edit: (ProxyNodeSelection) -> ProxyNodeSelection) = save(metadata.editSelection(edit))

    private fun save(next: ProxyMetadata, sensitive: ProxySecrets? = null) {
        if (!settingsReadable) throw IOException("内置代理设置读取失败")
        if (sensitive == null && next == metadata) return
        require(next.managed.nodes.size <= MAX_NODES && (next.advanced?.nodes?.size ?: 0) <= MAX_NODES) {
            "代理节点数量超过上限"
        }
        val old = metadata
        val nextSlot = sensitive?.let {
            val ids = (next.managed.nodes + next.advanced?.nodes.orEmpty()).mapTo(hashSetOf()) { node -> node.id }
            val filtered = ProxySecrets(it.nodes.filterKeys(ids::contains), it.subscriptionUrls, it.advancedYaml)
            val slot = if (old.secretSlot == 0) 1 else 0
            val bytes = proxyJson.encodeToString(ProxySecrets.serializer(), filtered).toByteArray(Charsets.UTF_8)
            try {
                secrets.put(SecretCatalog.builtInProxyStorage(slot), bytes)
            } catch (_: Exception) {
                throw IOException("内置代理加密配置写入失败")
            } finally {
                bytes.fill(0)
            }
            slot
        } ?: old.secretSlot
        val ready = next.copy(secretSlot = nextSlot)
        if (!preferences.edit().putString(KEY_METADATA, proxyJson.encodeToString(ProxyMetadata.serializer(), ready)).commit()) {
            // commit 返回失败时 Android 内存视图也可能已更新，恢复旧指针。
            preferences.edit().putString(KEY_METADATA, proxyJson.encodeToString(ProxyMetadata.serializer(), old)).commit()
            throw IOException("内置代理设置保存失败")
        }
        metadata = ready
        mutable.value = ready.settings()
        if (nextSlot != old.secretSlot && old.secretSlot != null) {
            runCatching { secrets.remove(SecretCatalog.builtInProxyStorage(old.secretSlot)) }
        }
    }

    private fun readSecrets(): ProxySecrets {
        val slot = metadata.secretSlot ?: return ProxySecrets()
        val bytes = try {
            secrets.get(SecretCatalog.builtInProxyStorage(slot))
        } catch (_: Exception) {
            null
        } ?: throw IOException("内置代理加密配置读取失败")
        return try {
            proxyJson.decodeFromString<ProxySecrets>(bytes.decodeToString())
        } catch (_: Exception) {
            throw IOException("内置代理加密配置格式无效")
        } finally {
            bytes.fill(0)
        }
    }

    private fun yamlFor(nodes: List<ProxyNodeItem>, configs: Map<String, JsonObject>, useIds: Boolean): String =
        buildJsonObject {
            put("proxies", buildJsonArray {
                nodes.forEach { node ->
                    val config = configs[node.id] ?: throw IOException("内置代理节点配置缺失")
                    // JSON 对象同样可由 Go 的 YAML 解码器读取，保留嵌套字段与原始标量类型。
                    add(JsonObject(config + ("name" to JsonPrimitive(if (useIds) node.id else node.name))))
                }
            })
        }.toString()

    private fun mergeConfigs(stored: ProxySecrets, configs: List<ProxyNodeConfig>): Map<String, JsonObject> =
        stored.nodes + configs.associate { it.id to it.config }

    private fun merge(old: List<ProxyNodeItem>, incoming: List<ProxyNodeItem>): List<ProxyNodeItem> {
        val result = old.associateByTo(linkedMapOf()) { it.id }
        incoming.forEach { node ->
            val previous = result[node.id]
            result[node.id] = previous?.copy(
                subscriptionIds = previous.subscriptionIds + node.subscriptionIds,
                manuallyImported = previous.manuallyImported || node.manuallyImported,
            ) ?: node
        }
        return result.values.toList()
    }

    private fun hasSource(node: ProxyNodeItem): Boolean = node.manuallyImported || node.subscriptionIds.isNotEmpty()

    private fun ProxyMetadata.withSubscriptionCounts() = copy(subscriptions = subscriptions.map { subscription ->
        subscription.copy(nodeCount = managed.nodes.count { subscription.id in it.subscriptionIds })
    })

    private fun ProxyMetadata.editSelection(edit: (ProxyNodeSelection) -> ProxyNodeSelection): ProxyMetadata =
        if (advanced != null) copy(advanced = edit(advanced)) else copy(managed = edit(managed))

    private fun ProxyNodeSelection.withNodes(items: List<ProxyNodeItem>) = copy(
        activeNode = activeNode?.takeIf { id -> items.any { it.id == id && it.enabled } }
            ?: items.firstOrNull { it.enabled }?.id,
        nodes = items,
    )

    companion object {
        const val PREFERENCES_NAME = "infinity_proxy"
        internal const val KEY_METADATA = "settings_v1"
        private const val MAX_NODES = 512
    }
}
