package com.promenar.infinity.domain.script

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ScriptNetworkPolicyTest {
    private val public = InetAddress.getByName("93.184.216.34")
    private val local = InetAddress.getByName("192.168.1.4")

    @Test fun `公网局域网和本机允许且保留解析地址`() = runTest {
        for (address in listOf(public, local, InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1"))) {
            assertThat(ScriptNetworkPolicy.validate("http://host.test/a") { arrayOf(address) }.addresses).containsExactly(address)
        }
        assertThat(ScriptNetworkPolicy.validate("https://host.test") { arrayOf(public, local) }.addresses).containsExactly(public, local)
    }

    @Test fun `无效协议凭据未指定地址与组播仍拒绝`() = runTest {
        for (url in listOf("file:///etc/passwd", "https://u:p@host.test")) {
            try { ScriptNetworkPolicy.validate(url) { arrayOf(public) }; error("应拒绝") }
            catch (e: ScriptFailure) { assertThat(e.code).isEqualTo("NETWORK_DENIED") }
        }
        for (address in listOf("0.0.0.0", "::", "224.0.0.1", "ff02::1")) {
            try { ScriptNetworkPolicy.validate("http://host.test") { arrayOf(InetAddress.getByName(address)) }; error("应拒绝") }
            catch (e: ScriptFailure) { assertThat(e.message).contains("ADDRESS_DENIED") }
        }
    }

    @Test fun `请求大小次数和累计响应预算逐次扣减`() {
        val budget = ScriptNetworkBudget(maxRequests = 2, bodyBytes = 10, totalBytes = 15)
        budget.request(10)
        budget.response(8)
        budget.request(1)
        assertThrows(ScriptFailure::class.java) { budget.request(0) }
        assertThrows(ScriptFailure::class.java) { budget.response(8) }
        assertThrows(ScriptFailure::class.java) { ScriptNetworkBudget(bodyBytes = 10).request(11) }
        assertThrows(ScriptFailure::class.java) { ScriptNetworkBudget(bodyBytes = 10).response(11) }
    }
}
