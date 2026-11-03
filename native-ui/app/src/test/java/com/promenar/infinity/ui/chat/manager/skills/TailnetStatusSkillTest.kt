package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.tailnet.TailnetFailure
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.data.tailnet.TailnetStatusSummary
import com.promenar.infinity.domain.tool.ToolRisk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class TailnetStatusSkillTest {
    private val running = TailnetState(
        phase = TailnetPhase.RUNNING,
        summary = TailnetStatusSummary(
            backendState = "Running",
            authUrl = "https://login.tailscale.com/a/stale-secret",
            selfName = "phone",
            selfDnsName = "phone.tail1.ts.net",
            selfIps = listOf("100.64.0.2", "fd7a:115c:a1e0::2"),
            online = true,
            magicDnsSuffix = "tail1.ts.net",
            tailnetName = "user@example.com",
        ),
        peers = listOf(
            TailnetPeer("nas", "nas.tail1.ts.net", listOf("100.64.0.5"), "linux", online = true),
            TailnetPeer("laptop", "laptop.tail1.ts.net", listOf("100.64.0.6"), "macOS", online = false),
        ),
    )

    @Test
    fun `在线时输出本机与节点信息，不含登录链接`() {
        val output = TailnetStatusSkill.render(running)
        assertThat(output["state"]!!.jsonPrimitive.content).isEqualTo("running")
        val self = output["self"]!!.jsonObject
        assertThat(self["name"]!!.jsonPrimitive.content).isEqualTo("phone")
        assertThat(self["ips"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("100.64.0.2", "fd7a:115c:a1e0::2")
        assertThat(self["online"]!!.jsonPrimitive.boolean).isTrue()
        val peers = output["peers"]!!.jsonArray.map { it.jsonObject }
        assertThat(peers.map { it["name"]!!.jsonPrimitive.content }).containsExactly("nas", "laptop").inOrder()
        assertThat(peers[1].containsKey("allowed")).isFalse()
        assertThat(peers[1]["os"]!!.jsonPrimitive.content).isEqualTo("macOS")
        assertThat(output.toString()).doesNotContain("stale-secret")
        assertThat(output.toString()).doesNotContain("authUrl")
        assertThat(output.toString()).doesNotContain("proxy")
    }

    @Test
    fun `未启用、未登录与失败时给出指引`() {
        val disabled = TailnetStatusSkill.render(TailnetState(TailnetPhase.DISABLED))
        assertThat(disabled["state"]!!.jsonPrimitive.content).isEqualTo("disabled")
        assertThat(disabled["guidance"]!!.jsonPrimitive.content).contains("设置 > Tailscale")
        assertThat(disabled).doesNotContainKey("peers")

        val needsLogin = TailnetStatusSkill.render(
            TailnetState(TailnetPhase.NEEDS_LOGIN, TailnetStatusSummary(backendState = "NeedsLogin", authUrl = "https://login.tailscale.com/a/secret-login")),
        )
        assertThat(needsLogin["guidance"]!!.jsonPrimitive.content).contains("登录")
        assertThat(needsLogin.toString()).doesNotContain("secret-login")
        assertThat(needsLogin).doesNotContainKey("self")

        val exited = TailnetStatusSkill.render(TailnetState(TailnetPhase.FAILED, failure = TailnetFailure.EXITED))
        assertThat(exited["guidance"]!!.jsonPrimitive.content).contains("自动重启")
        val unavailable = TailnetStatusSkill.render(TailnetState(TailnetPhase.UNAVAILABLE))
        assertThat(unavailable["guidance"]!!.jsonPrimitive.content).contains("未包含")
    }

    @Test
    fun `节点过多时截断并标记`() {
        val many = running.copy(peers = (1..250).map { TailnetPeer("n$it", ips = listOf("100.64.1.$it")) })
        val output = TailnetStatusSkill.render(many)
        assertThat(output["peers"]!!.jsonArray).hasSize(TailnetStatusSkill.MAX_PEERS)
        assertThat(output["peers_truncated"]!!.jsonPrimitive.boolean).isTrue()
    }

    @Test
    fun `工具只读且无参数`() {
        val schema = Json.parseToJsonElement(TailnetStatusSkill(FakeTailnetController()).parametersSchema).jsonObject
        assertThat(schema["properties"]!!.jsonObject).isEmpty()
        assertThat(TailnetStatusSkill(FakeTailnetController()).risk).isEqualTo(ToolRisk.SAFE_READ)
    }

    private class FakeTailnetController : com.promenar.infinity.data.tailnet.TailnetController {
        override val available = true
        override val state = kotlinx.coroutines.flow.MutableStateFlow(TailnetState(TailnetPhase.DISABLED))
        override val settings = kotlinx.coroutines.flow.MutableStateFlow(com.promenar.infinity.data.tailnet.TailnetSettings())
        override fun start() = Unit
        override fun retry() = Unit
        override suspend fun refresh() = state.value
        override suspend fun login(): String? = null
        override suspend fun logout() = false
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun setEphemeral(ephemeral: Boolean) = Unit
        override suspend fun setControlUrl(raw: String) = false
        override suspend fun setAuthKey(raw: CharArray) = false
        override suspend fun clearAuthKey() = Unit
        override suspend fun revealAuthKey(): CharArray? = null
    }
}
