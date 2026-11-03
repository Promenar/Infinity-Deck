package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import com.promenar.infinity.data.network.ConnectProxyFixture
import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetRouting
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test

class WebFetchSkillTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s1"
        override val agentId = "a1"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root-1"
    }
    private fun html(request: Request, code: Int = 200, location: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("测试响应")
        .body("<article><p>网页正文内容可以正常读取。</p></article>".toResponseBody())
        .apply { location?.let { header("Location", it) } }.build()

    @Test fun `本机与局域网可直接读取HTTP和HTTPS网页`() = runTest {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { requests++; html(it.request()) }.build()
        val policy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName(if (it.endsWith(".test")) "192.168.1.2" else it)) })
        val skill = WebFetchSkill(client, networkPolicy = policy)
        for (host in listOf("127.0.0.1", "192.168.1.10", "lan.test", "child.lan.test")) {
            for (scheme in listOf("http", "https")) {
                assertThat(skill.execute(skillArgs("url" to "$scheme://$host/page"), context).status).isEqualTo("success")
            }
        }
        assertThat(requests).isEqualTo(8)
        assertThat(client.dispatcher.executorService.isShutdown).isFalse()
    }

    @Test fun `逐跳允许局域网并拒绝组播和未指定地址`() = runTest {
        for (host in listOf("192.168.1.2", "224.0.0.1", "0.0.0.0")) {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor {
                requests++
                if (requests == 1) html(it.request(), 302, "http://$host/next") else html(it.request())
            }.build()
            val policy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName(if (it == "public.test") "93.184.216.34" else it)) })
            val result = WebFetchSkill(client, networkPolicy = policy).execute(skillArgs("url" to "https://public.test/a"), context)
            if (host == "192.168.1.2") {
                assertThat(result.status).isEqualTo("success")
                assertThat(requests).isEqualTo(2)
            } else {
                assertThat(result.content).contains("ADDRESS_DENIED")
                assertThat(requests).isEqualTo(1)
            }
        }
    }

    @Test fun `公网相对重定向后仍可读取且不关闭共享客户端`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor {
            if (it.request().url.encodedPath == "/a") html(it.request(), 302, "/b") else html(it.request())
        }.build()
        val policy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName("93.184.216.34")) })
        assertThat(WebFetchSkill(client, networkPolicy = policy).execute(skillArgs("url" to "https://public.test/a"), context).status).isEqualTo("success")
        assertThat(client.dispatcher.executorService.isShutdown).isFalse()
    }

    @Test fun `站点返回非200时报告HTTP状态而不是策略拦截`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { html(it.request(), 404) }.build()
        val policy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName("93.184.216.34")) })
        val result = WebFetchSkill(client, networkPolicy = policy).execute(skillArgs("url" to "https://public.test/missing"), context)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("HTTP 404")
        assertThat(result.content).doesNotContain("Blocked URL")
    }

    @Test fun `tailnet未运行在请求前返回设置指引`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { error("不应发送请求") }.build()
        val result = WebFetchSkill(client).execute(skillArgs("url" to "https://100.64.0.1/a"), context)
        assertThat(result.content).contains("TAILNET_OFFLINE")
        assertThat(result.content).contains("Tailscale 设置中启用并连接")
    }

    @Test fun `tailnet运行时使用真实CONNECT代理及其认证且无DNS直连`() = runTest {
        ConnectProxyFixture().use { fixture ->
            val policy = AgentNetworkPolicy(fixture.routing::isTailnetHost, tailnetRunning = { true }, resolver = { error("不应解析 tailnet") })
            val result = WebFetchSkill(OkHttpClient(), networkPolicy = policy, tailnetRouting = fixture.routing)
                .execute(skillArgs("url" to "https://main.tail.ts.net/a"), context)
            assertThat(result.content).contains("TAILNET_PROXY_ERROR")
            assertThat(withTimeout(2000) { fixture.connect.await() }).isEqualTo("CONNECT main.tail.ts.net:443 HTTP/1.1")
            assertThat(fixture.authentications.get()).isGreaterThan(0)
        }
    }

    private fun fetchBody(body: String, contentType: String): String = kotlinx.coroutines.runBlocking {
        val client = OkHttpClient.Builder().addInterceptor {
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("测试响应")
                .header("Content-Type", contentType).body(body.toResponseBody()).build()
        }.build()
        val policy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByName("192.168.1.2")) })
        val result = WebFetchSkill(client, networkPolicy = policy).execute(skillArgs("url" to "http://192.168.1.2/a"), context)
        assertThat(result.status).isEqualTo("success")
        result.content
    }

    @Test fun `纯文本按原有换行逐行返回且不拆版本号`() {
        val content = fetchBody("LAN-CHECK-7Q2X 0.17.0-beta\nok\n\n第三行 v1.2", "text/plain; charset=utf-8")
        assertThat(content).contains("[1] LAN-CHECK-7Q2X 0.17.0-beta\n")
        assertThat(content).contains("[2] ok\n")
        assertThat(content).contains("[3] 第三行 v1.2\n")
        assertThat(content).contains("Total Lines: 3")
    }

    @Test fun `短网页按句断行时保留小数网址与短句`() {
        val content = fetchBody(
            "<html><body><div>版本 0.17.0-beta 已发布。详见 https://example.com/a.b/c 页面. Ok. 售价 3.5 元！</div></body></html>",
            "text/html; charset=utf-8",
        )
        assertThat(content).contains("[1] 版本 0.17.0-beta 已发布。\n")
        assertThat(content).contains("[2] 详见 https://example.com/a.b/c 页面.\n")
        assertThat(content).contains("[3] Ok.\n")
        assertThat(content).contains("[4] 售价 3.5 元！\n")
    }

    @Test fun `系统回退本机代理不能冒充内置代理`() = runTest {
        ConnectProxyFixture().use { fixture ->
            val routing = object : TailnetRouting by DisabledTailnetRouting {
                override val proxySelector = ConnectProxyFixture.selector(listOf(fixture.proxy))
            }
            val policy = AgentNetworkPolicy(tailnetRunning = { true })
            val result = WebFetchSkill(OkHttpClient(), networkPolicy = policy, tailnetRouting = routing)
                .execute(skillArgs("url" to "https://100.64.0.1/a"), context)
            assertThat(result.content).contains("TAILNET_OFFLINE")
            assertThat(fixture.connect.isCompleted).isFalse()
        }
    }
}
