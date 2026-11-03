package com.promenar.infinity.domain.network

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AgentNetworkPolicyTest {
    private fun addresses(vararg values: String) = values.map(InetAddress::getByName).toTypedArray()

    @Test fun `每类地址与混合结果按非公网优先归类`() = runTest {
        val cases = mapOf(
            listOf("93.184.216.34", "2606:4700:4700::1111") to NetworkTargetClass.PUBLIC,
            listOf("192.168.1.2") to NetworkTargetClass.LAN,
            listOf("fd00::1") to NetworkTargetClass.LAN,
            listOf("fe80::1") to NetworkTargetClass.LAN,
            listOf("127.0.0.1") to NetworkTargetClass.LOOPBACK,
            listOf("::1") to NetworkTargetClass.LOOPBACK,
            listOf("93.184.216.34", "10.0.0.1") to NetworkTargetClass.LAN,
            listOf("93.184.216.34", "100.64.1.2") to NetworkTargetClass.TAILNET,
            listOf("100.64.1.2", "127.0.0.1") to NetworkTargetClass.LOOPBACK,
        )
        for ((values, expected) in cases) {
            val policy = AgentNetworkPolicy(resolver = { addresses(*values.toTypedArray()) })
            assertThat(policy.classify("host.test").target).isEqualTo(expected)
        }
    }

    @Test fun `公网局域网与本机允许HTTP和HTTPS`() = runTest {
        for (address in listOf("93.184.216.34", "192.168.1.71", "10.0.0.2", "127.0.0.1", "::1", "fd00::1")) {
            val policy = AgentNetworkPolicy(resolver = { addresses(address) })
            for (scheme in listOf("http", "https")) {
                assertThat(policy.validate("$scheme://host.test:8080/").addresses).containsExactlyElementsIn(addresses(address).toList())
            }
        }
    }

    @Test fun `tailnet字面量MagicDNS与已知短名不调用系统DNS`() = runTest {
        val policy = AgentNetworkPolicy(isTailnetHost = { it == "main" }, resolver = { error("不应查询 DNS") })
        for (host in listOf("100.64.0.0", "100.127.255.255", "[fd7a:115c:a1e0::1]", "main.tail.ts.net", "MAIN")) {
            assertThat(policy.classify(host).target).isEqualTo(NetworkTargetClass.TAILNET)
        }
    }

    @Test fun `任一未指定或组播地址均拒绝`() = runTest {
        for (address in listOf("0.0.0.0", "::", "224.0.0.1", "ff02::1")) {
            val policy = AgentNetworkPolicy(resolver = { addresses("93.184.216.34", address) })
            try { policy.validate("https://host.test"); error("应拒绝") }
            catch (failure: NetworkAccessDenied) { assertThat(failure.code).isEqualTo("ADDRESS_DENIED") }
        }
    }

    @Test fun `tailnet仅在运行时允许HTTP和HTTPS`() = runTest {
        for (scheme in listOf("http", "https")) {
            val online = AgentNetworkPolicy(tailnetRunning = { true })
            assertThat(online.validate("$scheme://100.64.0.1").target).isEqualTo(NetworkTargetClass.TAILNET)
            val offline = AgentNetworkPolicy(tailnetRunning = { false })
            val failure = assertThrows(NetworkAccessDenied::class.java) {
                kotlinx.coroutines.runBlocking { offline.validate("$scheme://100.64.0.1") }
            }
            assertThat(failure.code).isEqualTo("TAILNET_OFFLINE")
        }
    }

    @Test fun `总表声明顺序与每个单元格符合契约`() {
        val allowed = NetworkAccessState.ALLOWED
        val tailnet = NetworkAccessState.TAILNET_REQUIRED
        val configured = NetworkAccessState.CONFIGURED_ONLY
        val unrestricted = NetworkAccessState.UNRESTRICTED
        val expected = listOf(
            listOf(allowed, allowed, allowed, tailnet), listOf(allowed, allowed, allowed, tailnet),
            listOf(allowed, allowed, allowed, tailnet), List(4) { configured },
            listOf(unrestricted, unrestricted, unrestricted, tailnet), List(4) { unrestricted }, List(4) { configured },
        )
        assertThat(AgentNetworkPolicy.rows.map { it.tool }).containsExactlyElementsIn(AgentNetworkTool.entries).inOrder()
        AgentNetworkPolicy.rows.zip(expected).forEach { (row, states) ->
            assertThat(row.cells.map { it.target }).containsExactlyElementsIn(NetworkTargetClass.entries).inOrder()
            assertThat(row.cells.map { it.state }).containsExactlyElementsIn(states).inOrder()
        }
    }
}
