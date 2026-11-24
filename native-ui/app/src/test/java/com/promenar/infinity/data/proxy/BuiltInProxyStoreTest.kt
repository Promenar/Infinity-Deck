package com.promenar.infinity.data.proxy

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.tailnet.MemoryPreferences
import com.promenar.infinity.data.tailnet.MemorySecretStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class BuiltInProxyStoreTest {
    private val preferences = MemoryPreferences()
    private val secrets = MemorySecretStore()
    private val store = BuiltInProxyStore(preferences, secrets)

    @Test
    fun `同一节点跨来源合并且订阅删除保留手动节点`() {
        val first = store.addSubscription("一", "https://example.org/first?token=fixture")
        val second = store.addSubscription("二", "https://example.org/second")
        store.replaceSubscriptionNodes(first.id, listOf(node("甲", "fixture-one")))
        store.replaceSubscriptionNodes(second.id, listOf(node("乙", "fixture-one"), node("甲", "fixture-two")))
        store.addImportedNodes(listOf(node("手动", "fixture-one")))
        assertThat(store.settings.value.nodes.map { it.name }).containsExactly("甲", "甲").inOrder()
        assertThat(store.settings.value.nodes.first().subscriptionIds).containsExactly(first.id, second.id)
        val firstNode = store.settings.value.nodes.first().id
        store.setNodeEnabled(firstNode, false)
        store.moveNode(firstNode, 1)
        store.setMode(ProxyMode.FALLBACK)
        store.setEnabled(true)
        val restored = BuiltInProxyStore(preferences, secrets)
        assertThat(restored.settings.value.nodes.last().enabled).isFalse()
        assertThat(restored.settings.value.mode).isEqualTo(ProxyMode.FALLBACK)
        assertThat(restored.settings.value.enabled).isTrue()
        assertThat(preferences.all.values.joinToString()).doesNotContain("fixture-one")
        assertThat(preferences.all.values.joinToString()).doesNotContain("example.org/first")
        restored.removeSubscription(first.id)
        restored.removeSubscription(second.id)
        assertThat(restored.settings.value.nodes.single().id).isEqualTo(firstNode)
    }

    @Test
    fun `高级配置覆盖且回同步可以保留已有手动节点`() {
        store.addImportedNodes(listOf(node("普通", "one")))
        store.setAdvancedYaml("proxies: fixture-data", listOf(node("高级", "two")))
        assertThat(store.settings.value.nodes.single().name).isEqualTo("高级")
        assertThat(store.readAdvancedYaml()).isEqualTo("proxies: fixture-data")
        assertThat(preferences.all.values.joinToString()).doesNotContain("fixture-data")
        store.syncAdvancedYaml()
        assertThat(store.settings.value.hasAdvancedYaml).isFalse()
        assertThat(store.settings.value.nodes.map { it.name }).containsExactly("普通", "高级")
    }

    private fun node(name: String, password: String) = ProxyNodeConfig(
        ProxyNodeSummary(name, "http", "example.org", 443),
        buildJsonObject {
            put("name", name)
            put("type", "http")
            put("server", "example.org")
            put("port", 443)
            put("password", password)
        },
    )
}
