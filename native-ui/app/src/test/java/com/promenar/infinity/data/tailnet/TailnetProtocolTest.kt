package com.promenar.infinity.data.tailnet

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Test

class TailnetProtocolTest {
    private val secretKey = "tskey-auth-kSECRETVALUE-0123456789"
    private val options = TailnetStartOptions(
        stateDir = "/data/user/0/app/no_backup/tailnet",
        hostname = "infinity-pixel",
        controlUrl = "https://hs.example.org",
        authKey = secretKey,
        ephemeral = true,
        proxyUser = "U".repeat(24),
        proxyPassword = "P".repeat(24),
    )

    @Test
    fun `start 请求按 Go 端字段编码`() {
        val line = TailnetCodec.encode(TailnetRequest(id = 7, op = TailnetOps.START, start = options))
        assertThat(line).doesNotContain("\n")
        val json = TailnetCodec.json.parseToJsonElement(line).jsonObject
        assertThat(json["id"]!!.jsonPrimitive.int).isEqualTo(7)
        assertThat(json["op"]!!.jsonPrimitive.content).isEqualTo("start")
        val start = json["start"]!!.jsonObject
        assertThat(start.keys).containsAtLeast("stateDir", "hostname", "controlUrl", "authKey", "ephemeral", "proxyUser", "proxyPassword")
        assertThat(start["authKey"]!!.jsonPrimitive.content).isEqualTo(secretKey)
    }

    @Test
    fun `可能进入日志的 toString 不含 auth key、代理口令与登录链接`() {
        val request = TailnetRequest(id = 1, op = TailnetOps.START, start = options)
        for (text in listOf(options.toString(), request.toString())) {
            assertThat(text).doesNotContain("SECRETVALUE")
            assertThat(text).doesNotContain("P".repeat(24))
            assertThat(text).doesNotContain("U".repeat(24))
        }
        val summary = TailnetStatusSummary(backendState = "NeedsLogin", authUrl = "https://login.tailscale.com/a/abcdef")
        assertThat(summary.toString()).doesNotContain("abcdef")
        val endpoint = TailnetProxyEndpoint(40000, "U".repeat(24), "P".repeat(24))
        assertThat(endpoint.toString()).isEqualTo("TailnetProxyEndpoint(127.0.0.1:40000)")
    }

    @Test
    fun `解码应答、状态事件与无法识别的行`() {
        val ok = TailnetCodec.decode("""{"id":3,"ok":true,"result":{"proxyPort":41234}}""") as TailnetMessage.Response
        assertThat(ok.id).isEqualTo(3)
        assertThat(ok.ok).isTrue()
        assertThat(ok.result!!.jsonObject["proxyPort"]!!.jsonPrimitive.int).isEqualTo(41234)

        val failed = TailnetCodec.decode("""{"id":4,"ok":false,"error":"not_started","message":"尚未启动"}""") as TailnetMessage.Response
        assertThat(failed.ok).isFalse()
        assertThat(failed.error).isEqualTo(TailnetErrors.NOT_STARTED)

        val event = TailnetCodec.decode(
            """{"event":"state","state":{"backendState":"Running","selfName":"phone","selfIps":["100.64.0.2"],"online":true,"magicDnsSuffix":"tail1.ts.net","future":1}}""",
        ) as TailnetMessage.State
        assertThat(event.state.running).isTrue()
        assertThat(event.state.selfIps).containsExactly("100.64.0.2")
        assertThat(TailnetManager.phaseOf(event.state)).isEqualTo(TailnetPhase.RUNNING)

        for (line in listOf("", "not json", "[]", """{"event":"other"}""", """{"id":"x","ok":true}""", """{"event":"state","state":"bad"}""")) {
            assertThat(TailnetCodec.decode(line)).isEqualTo(TailnetMessage.Unknown)
        }
    }

    @Test
    fun `阶段由后端状态推出`() {
        assertThat(TailnetManager.phaseOf(TailnetStatusSummary(backendState = "NeedsLogin"))).isEqualTo(TailnetPhase.NEEDS_LOGIN)
        assertThat(TailnetManager.phaseOf(TailnetStatusSummary(backendState = "NeedsMachineAuth"))).isEqualTo(TailnetPhase.NEEDS_LOGIN)
        assertThat(TailnetManager.phaseOf(TailnetStatusSummary(backendState = "Starting"))).isEqualTo(TailnetPhase.STARTING)
        assertThat(TailnetManager.phaseOf(TailnetStatusSummary(backendState = "NoState", error = "start_failed: x"))).isEqualTo(TailnetPhase.FAILED)
    }

    @Test
    fun `节点名符合 DNS 标签规则`() {
        assertThat(TailnetManager.hostnameFor("Pixel 9 Pro")).isEqualTo("infinity-pixel-9-pro")
        assertThat(TailnetManager.hostnameFor("sdk_gphone64_arm64")).isEqualTo("infinity-sdk-gphone64-arm64")
        assertThat(TailnetManager.hostnameFor("")).isEqualTo("infinity")
        assertThat(TailnetManager.hostnameFor(null)).isEqualTo("infinity")
        assertThat(TailnetManager.hostnameFor("一加 Ace")).isEqualTo("infinity-ace")
        val long = TailnetManager.hostnameFor("x".repeat(100))
        assertThat(long.length).isAtMost(63)
        assertThat(long).matches("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
        assertThat(TailnetManager.hostnameFor("a".repeat(55) + " b")).matches("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    }
}
