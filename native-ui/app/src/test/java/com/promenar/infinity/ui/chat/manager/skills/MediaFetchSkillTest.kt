package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.media.download.WorkspaceMediaDownloader
import com.promenar.infinity.domain.media.MediaFetchUrlPolicy
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import com.promenar.infinity.data.tailnet.DisabledTailnetRouting
import com.promenar.infinity.data.tailnet.TailnetRouting
import java.net.InetAddress
import com.promenar.infinity.domain.media.MediaOrigin
import com.promenar.infinity.domain.tool.ToolArgumentApprovalRules
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalMountOperationBindings
import com.promenar.infinity.domain.media.*
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.coEvery
import io.mockk.mockk
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Test
import java.net.Proxy
import java.net.ProxySelector
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MediaFetchSkillTest {
    private val store = RecordingMediaStore()
    private val requests = mutableListOf<String>()
    private var bodyClosed = false
    private fun downloader(
        connected: Boolean = true,
        proxies: List<Proxy> = listOf(Proxy.NO_PROXY),
        responder: (Request) -> Response = { response(it, mediaTestPng) },
    ): WorkspaceMediaDownloader = WorkspaceMediaDownloader(
        OkHttpClient.Builder().proxySelector(object : ProxySelector() {
            override fun select(uri: URI?) = proxies
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }).addInterceptor { chain ->
            requests += chain.request().url.toString()
            responder(chain.request())
        }.build(),
        MediaFetchUrlPolicy { it == "main" },
        { connected },
        AgentNetworkPolicy(isTailnetHost = { it == "main" }, tailnetRunning = { connected }, resolver = {
            arrayOf(InetAddress.getByName(if (it.endsWith(".test")) "93.184.216.34" else it))
        }),
        object : TailnetRouting by DisabledTailnetRouting {
            override fun agentProxy() = proxies.singleOrNull()
        },
    )
    private fun response(request: Request, bytes: ByteArray = mediaTestPng, mime: String = "image/png", code: Int = 200, location: String? = null, length: Long = bytes.size.toLong()): Response {
        val body = object : ResponseBody() {
            private val buffer = Buffer().write(bytes)
            override fun contentType(): MediaType? = null
            override fun contentLength() = length
            override fun source(): BufferedSource = buffer
            override fun close() { bodyClosed = true; super.close() }
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Content-Type", mime).body(body).apply {
                if (location != null) header("Location", location)
                if (length >= 0) header("Content-Length", length.toString())
            }.build()
    }
    private suspend fun execute(client: WorkspaceMediaDownloader = downloader(), path: String? = null, limit: Long = 100L * 1024 * 1024, url: String = "https://example.test/image") =
        MediaFetchSkill(store, client, limit).execute(buildJsonObject { put("url", url); path?.let { put("path", it) } }, mediaTestContext)

    @Test fun `局域网与本机支持HTTP和HTTPS下载`() = runTest {
        for (host in listOf("192.168.1.2", "127.0.0.1", "[::1]")) {
            for (scheme in listOf("http", "https")) {
                assertThat(execute(url = "$scheme://$host/a.png").status).isEqualTo("success")
            }
        }
        assertThat(requests).hasSize(6)
    }

    @Test fun `重定向到局域网HTTP允许并逐跳校验`() = runTest {
        val result = execute(downloader { if (requests.size == 1) response(it, code = 302, location = "http://192.168.1.2/a.png") else response(it) })
        assertThat(result.status).isEqualTo("success")
        assertThat(requests).hasSize(2)
    }

    @Test fun `下载以流写入指定工作区路径并返回媒体引用与元数据`() = runTest {
        val result = execute(path = "/media/custom.png")
        assertThat(result.status).isEqualTo("success")
        assertThat(result.media!!.single().path).isEqualTo("/media/custom.png")
        assertThat(result.content).contains("3×2")
        assertThat(result.content).contains("![说明](/media/custom.png)")
        assertThat(store.saved.single().session).isEqualTo("media-session")
        assertThat(store.saved.single().origin).isEqualTo(MediaOrigin.DOWNLOAD)
        assertThat(store.saved.single().bytes).isEqualTo(mediaTestPng)
        assertThat(bodyClosed).isTrue()
    }

    @Test fun `每一跳请求都带浏览器式UA以免被站点按爬虫拒绝`() = runTest {
        val agents = mutableListOf<String?>()
        val result = execute(downloader { request ->
            agents += request.header("User-Agent")
            if (request.url.encodedPath == "/image") response(request, code = 302, location = "/final.png") else response(request)
        })
        assertThat(result.status).isEqualTo("success")
        assertThat(agents).hasSize(2)
        agents.forEach { assertThat(it).startsWith("Mozilla/5.0") }
    }

    @Test fun `响应头和文件头都不是媒体时拒绝且不调用存储`() = runTest {
        val result = execute(downloader { response(it, "<html>not media</html>".toByteArray(), "text/html") })
        assertThat(result.status).isEqualTo("error")
        assertThat(store.saved).isEmpty()
        assertThat(bodyClosed).isTrue()
    }

    @Test fun `扩展名不作为媒体证据且文件头可覆盖通用响应头`() = runTest {
        assertThat(execute(downloader { response(it, "not media".toByteArray(), "application/octet-stream") }, url = "https://example.test/a.png").status).isEqualTo("error")
        assertThat(execute(downloader { response(it, mediaTestPng, "application/octet-stream") }).status).isEqualTo("success")
    }

    @Test fun `Content-Length超限在流读取和存储前拒绝`() = runTest {
        val result = execute(downloader { response(it, length = 101) }, limit = 100)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("上限")
        assertThat(store.saved).isEmpty()
        assertThat(bodyClosed).isTrue()
    }

    @Test fun `无长度和伪造长度都不能越过流式上限`() = runTest {
        for (length in listOf(-1L, 1L)) {
            val result = execute(downloader { response(it, mediaTestPng + ByteArray(10000), length = length) }, limit = 5000)
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("上限")
        }
        assertThat(store.saved).isEmpty()
        assertThat(bodyClosed).isTrue()
    }

    @Test fun `逐跳验证重定向并拒绝降级及userinfo`() = runTest {
        val client = downloader { request -> response(request, code = 302, location = "http://example.test/blocked") }
        assertThat(execute(client).status).isEqualTo("error")
        assertThat(requests).hasSize(1)
        requests.clear()
        assertThat(execute(downloader { response(it, code = 302, location = "https://user:password@example.test/a") }).status).isEqualTo("error")
        assertThat(requests).hasSize(1)
    }

    @Test fun `最多接受五跳且相对重定向可用`() = runTest {
        assertThat(execute(downloader { request ->
            val n = request.url.encodedPath.substringAfterLast('/').toIntOrNull() ?: 0
            response(request, code = if (n < 5) 302 else 200, location = "/${n + 1}")
        }).status).isEqualTo("success")
        assertThat(requests).hasSize(6)
        requests.clear()
        assertThat(execute(downloader { response(it, code = 302, location = "/again") }).status).isEqualTo("error")
        assertThat(requests).hasSize(6)
    }

    @Test fun `tailnet离线在发请求前报错且IP同样受控`() = runTest {
        for (host in listOf("main", "main.tail.ts.net", "100.64.1.2", "[fd7a:115c:a1e0::1]")) {
            val result = execute(downloader(connected = false), url = "https://$host/a")
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("Tailscale")
        }
        assertThat(requests).isEmpty()
    }

    @Test fun `tailnet明文使用内置HTTP代理`() = runTest {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 34567))
        for (host in listOf("main", "100.64.1.2", "[fd7a:115c:a1e0::1]")) {
            assertThat(execute(downloader(proxies = listOf(proxy)), url = "http://$host/a").status).isEqualTo("success")
        }
        assertThat(requests).hasSize(3)
    }

    @Test fun `实际保存路径与审批规范路径一致`() = runTest {
        val result = execute(path = " /media//approved.png ")
        assertThat(result.status).isEqualTo("success")
        assertThat(store.saved.single().path).isEqualTo("/media/approved.png")
    }

    @Test fun `非法路径在网络与存储之前拒绝`() = runTest {
        for (path in listOf("/media/../a.png", "https://example.test/a", "C:/a.png", "/media\\a.png")) {
            assertThat(execute(path = path).status).isEqualTo("error")
        }
        assertThat(requests).isEmpty()
        assertThat(store.saved).isEmpty()
    }

    @Test fun `tailnet已连接但选择器回退DIRECT时拒绝发起请求`() = runTest {
        assertThat(execute(url = "https://main/a").status).isEqualTo("error")
        assertThat(requests).isEmpty()
    }

    @Test fun `tailnet只接受固定的单一本机HTTP代理`() = runTest {
        val loopback = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 34567))
        assertThat(execute(downloader(proxies = listOf(loopback)), url = "https://main/a").status).isEqualTo("success")
        for (proxies in listOf(listOf(loopback, Proxy.NO_PROXY), listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("192.0.2.1", 34567))), listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 34567))))) {
            requests.clear()
            assertThat(execute(downloader(proxies = proxies), url = "https://main/a").status).isEqualTo("error")
            assertThat(requests).isEmpty()
        }
    }

    @Test fun `下载期间同别名挂载换源被拒绝且不写入新来源`() = runTest {
        var mountId = "old"
        val mounts = mockk<ExternalWorkspaceAccess>()
        coEvery { mounts.visible(any()) } answers { listOf(ExternalMount(mountId, "assets", "real_path", "/test", "media-session", 1)) }
        coEvery { mounts.workspaceMount(any()) } returns null
        val bound = object : SkillExecutionContext by mediaTestContext {
            override val approvedExecutionTarget = """["path","/mnt/assets/a.png",{"mountIds":{"assets":"old"}}]"""
            override val isApprovalBoundExecution = true
        }
        val client = downloader { request -> mountId = "new"; response(request) }
        val result = MediaFetchSkill(store, client, mounts = mounts, sessionWorkspace = { SessionWorkspace.Private })
            .execute(buildJsonObject { put("url", "https://example.test/a"); put("path", "/mnt/assets/a.png") }, bound)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("发生变化")
        assertThat(store.saved).isEmpty()
    }

    @Test fun `审批挂载缺失或身份过期在联网前拒绝`() = runTest {
        val mounts = mockk<ExternalWorkspaceAccess>()
        coEvery { mounts.visible(any()) } returns listOf(ExternalMount("new", "assets", "real_path", "/test", "media-session", 1))
        coEvery { mounts.workspaceMount(any()) } returns null
        for (target in listOf("""["path","/mnt/assets/a.png"]""", """["path","/mnt/assets/a.png",{"mountIds":{"assets":"old"}}]""")) {
            val bound = object : SkillExecutionContext by mediaTestContext {
                override val approvedExecutionTarget = target
                override val isApprovalBoundExecution = true
            }
            assertThat(MediaFetchSkill(store, downloader(), mounts = mounts, sessionWorkspace = { SessionWorkspace.Private })
                .execute(buildJsonObject { put("url", "https://example.test/a"); put("path", "/mnt/assets/a.png") }, bound).status).isEqualTo("error")
        }
        assertThat(requests).isEmpty()
        assertThat(store.saved).isEmpty()
    }

    @Test fun `默认媒体在审批后新增用户挂载改变逻辑落点时联网前拒绝`() = runTest {
        val primary = ExternalMount("ws_project", "", "workspace", "project", "media-session", 1)
        var visible = emptyList<ExternalMount>()
        val mounts = mockk<ExternalWorkspaceAccess>()
        coEvery { mounts.visible(any()) } answers { visible }
        coEvery { mounts.workspaceMount(any()) } returns primary
        val current = SessionWorkspace.Directory(com.promenar.infinity.data.local.db.entity.WorkspaceEntity("project", "project", "项目", "project", 1), java.io.File("project"))
        val skill = MediaFetchSkill(store, downloader(), mounts = mounts, sessionWorkspace = { current })
        val arguments = """{"url":"https://example.test/a"}"""
        val approved = com.promenar.infinity.data.generation.resolveSessionToolTarget(
            com.promenar.infinity.data.model.Session(id = "media-session", agentId = "agent", title = "媒体", workspaceRootUuid = "root"),
            skill.toProtocolTool(), arguments, null, mounts)!!
        visible = listOf(ExternalMount("assets", "素材", "real_path", "/assets", "media-session", 1))
        val bound = object : SkillExecutionContext by mediaTestContext {
            override val approvedExecutionTarget = approved
            override val isApprovalBoundExecution = true
        }
        val result = skill.execute(Json.parseToJsonElement(arguments).jsonObject, bound)
        assertThat(result.status).isEqualTo("error")
        assertThat(result.content).contains("APPROVAL_CHANGED")
        assertThat(requests).isEmpty()
        assertThat(store.saved).isEmpty()
    }

    @Test fun `默认媒体批准冻结逻辑目标主根和挂载集合且旧tuple不能复用`() = runTest {
        val primary = ExternalMount("ws_project", "", "workspace", "project", "media-session", 1)
        val assets = ExternalMount("assets", "素材", "real_path", "/assets", "media-session", 1)
        var visible = listOf(assets)
        val mounts = mockk<ExternalWorkspaceAccess>()
        coEvery { mounts.visible(any()) } answers { visible }
        coEvery { mounts.workspaceMount(any()) } returns primary
        val current = SessionWorkspace.Directory(com.promenar.infinity.data.local.db.entity.WorkspaceEntity("project", "project", "项目", "project", 1), java.io.File("project"))
        val skill = MediaFetchSkill(store, downloader(), mounts = mounts, sessionWorkspace = { current })
        val arguments = """{"url":"https://example.test/a"}"""
        val approved = com.promenar.infinity.data.generation.resolveSessionToolTarget(
            com.promenar.infinity.data.model.Session(id = "media-session", agentId = "agent", title = "媒体", workspaceRootUuid = "root"),
            skill.toProtocolTool(), arguments, null, mounts)!!
        val frozen = Json.parseToJsonElement(approved).jsonArray.last().jsonObject.getValue("defaultFileDestination").jsonObject
        assertThat(frozen.getValue("destination").jsonPrimitive.content).isEqualTo("SESSION_PRIVATE")
        assertThat(frozen.getValue("workspaceId").jsonPrimitive.content).isEqualTo("project")
        assertThat(frozen.getValue("mountIds").jsonObject.keys).containsAtLeast("", "素材")
        fun bound(target: String) = object : SkillExecutionContext by mediaTestContext {
            override val approvedExecutionTarget = target
            override val isApprovalBoundExecution = true
        }
        assertThat(skill.execute(Json.parseToJsonElement(arguments).jsonObject, bound(approved)).status).isEqualTo("success")
        requests.clear()
        store.saved.clear()
        for (state in listOf(emptyList(), listOf(assets.copy(id = "replacement")))) {
            visible = state
            val result = skill.execute(Json.parseToJsonElement(arguments).jsonObject, bound(approved))
            assertThat(result.status).isEqualTo("error")
            assertThat(result.content).contains("APPROVAL_CHANGED")
        }
        visible = listOf(assets)
        assertThat(skill.execute(Json.parseToJsonElement(arguments).jsonObject, bound("""["path","/media"]""")).status).isEqualTo("error")
        assertThat(requests).isEmpty()
        assertThat(store.saved).isEmpty()
    }

    @Test fun `批准的挂载身份通过协程上下文传到保存`() = runTest {
        val mounts = mockk<ExternalWorkspaceAccess>()
        coEvery { mounts.visible(any()) } returns listOf(ExternalMount("old", "assets", "real_path", "/test", "media-session", 1))
        coEvery { mounts.workspaceMount(any()) } returns null
        var observed: Map<String, String>? = null
        val boundStore = object : WorkspaceMediaStore by store {
            override suspend fun save(sessionId: String, payload: MediaPayload, origin: MediaOrigin, mimeTypeHint: String?, suggestedName: String?, targetPath: String?): MediaRef {
                observed = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
                return store.save(sessionId, payload, origin, mimeTypeHint, suggestedName, targetPath)
            }
        }
        val result = MediaFetchSkill(boundStore, downloader(), mounts = mounts, sessionWorkspace = { SessionWorkspace.Private })
            .execute(buildJsonObject { put("url", "https://example.test/a"); put("path", "/mnt/assets/a.png") }, mediaTestContext)
        assertThat(result.status).isEqualTo("success")
        assertThat(observed).containsEntry("assets", "old")
    }

    @Test fun `响应体阻塞读取时取消关闭响应并结束任务`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val closed = CountDownLatch(1)
        val header = Buffer().write(mediaTestPng + ByteArray(4096 - mediaTestPng.size))
        val source = object : Source {
            override fun timeout() = Timeout.NONE
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (header.size > 0) return header.read(sink, byteCount)
                entered.complete(Unit)
                if (!closed.await(5, TimeUnit.SECONDS)) throw IOException("测试等待超时")
                throw IOException("已关闭")
            }
            override fun close() { closed.countDown() }
        }.buffer()
        val client = downloader { request -> response(request).newBuilder().removeHeader("Content-Length").body(object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = source
        }).build() }
        val task = async { execute(client) }
        entered.await()
        task.cancel()
        task.join()
        assertThat(task.isCancelled).isTrue()
        assertThat(closed.count).isEqualTo(0)
        assertThat(store.saved).isEmpty()
    }

    @Test fun `仅查询和空引用的重定向保留原文件路径`() = runTest {
        for (location in listOf("?token=2", "")) {
            requests.clear()
            val client = downloader { response(it, code = if (requests.size == 1) 302 else 200, location = location) }
            assertThat(execute(client, url = "https://example.test/a/image.png?old=1").status).isEqualTo("success")
            assertThat(requests.last()).isEqualTo("https://example.test/a/image.png" + if (location.isEmpty()) "?old=1" else "?token=2")
        }
    }

    @Test fun `多斜杠引用不能折叠空userinfo后访问新主机`() = runTest {
        for (location in listOf("///@other.test/a", "////@other.test/a")) {
            requests.clear()
            val client = downloader { response(it, code = if (requests.size == 1) 302 else 200, location = location) }
            assertThat(execute(client).status).isEqualTo("error")
            assertThat(requests).hasSize(1)
        }
    }

    @Test fun `写入风险与审批目标按最终路径或默认媒体目录生成`() {
        val skill = MediaFetchSkill(store, downloader())
        assertThat(skill.risk).isEqualTo(ToolRisk.FILE_WRITE)
        val tool = skill.toProtocolTool()
        assertThat(ToolExecutionTargets.extract(tool, """{"url":"https://example.test/a"}""")).isEqualTo("""["path","/media"]""")
        assertThat(ToolExecutionTargets.extract(tool, """{"path":" /mnt/assets/a.png "}""")).isEqualTo("""["path","/mnt/assets/a.png"]""")
        assertThat(ToolArgumentApprovalRules.requiresExplicitApproval("media_fetch", """{"path":"/.agents/skills/demo/a.png"}""")).isTrue()
        val target = ToolExecutionTargets.extract(tool, """{"path":"/skills/a.png"}""")!!
        assertThat(ToolExecutionTargets.canRemember(target)).isFalse()
    }

    @Test fun `默认目录与挂载目标使用各自的审批白名单作用域`() {
        val tool = MediaFetchSkill(store, downloader()).toProtocolTool()
        val privateTarget = ToolExecutionTargets.extract(tool, "{}")!!
        assertThat(com.promenar.infinity.data.repository.ToolAllowlistScopes.resolve(privateTarget, "s1", SessionWorkspace.Private)?.scopeKey).isEqualTo("session:s1")
        val mountedTarget = ToolExecutionTargets.extract(tool, """{"path":"/mnt/assets/a.png"}""")!!
        assertThat(com.promenar.infinity.data.repository.ToolAllowlistScopes.resolve(mountedTarget, "s1", SessionWorkspace.Private, mapOf("assets" to "mount-1"))?.scopeKey).isEqualTo("mount:mount-1")
    }

    @Test fun `注册工具可按id筛选且旧启用集合中的新工具默认启用`() {
        val registry = com.promenar.infinity.ui.chat.manager.registry.DefaultSkillRegistry()
        registry.register(MediaFetchSkill(store, downloader()))
        assertThat(registry.getAllTools(listOf("media_fetch")).single().function.name).isEqualTo("media_fetch")
        val prefs = mockk<android.content.SharedPreferences>()
        io.mockk.every { prefs.getStringSet("enabled_skills", null) } returns emptySet()
        io.mockk.every { prefs.getStringSet("known_builtin_skills", null) } returns com.promenar.infinity.data.generation.BuiltinToolPreferences.LEGACY_KNOWN
        assertThat(com.promenar.infinity.data.generation.BuiltinToolPreferences.isEnabled(prefs, "media_fetch")).isTrue()
        io.mockk.every { prefs.getStringSet("known_builtin_skills", null) } returns setOf("media_fetch")
        assertThat(com.promenar.infinity.data.generation.BuiltinToolPreferences.isEnabled(prefs, "media_fetch")).isFalse()
    }

    @Test fun `取消从下载和工具层传播`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val slowStore = object : com.promenar.infinity.domain.media.WorkspaceMediaStore by store {
            override suspend fun save(sessionId: String, payload: com.promenar.infinity.domain.media.MediaPayload, origin: MediaOrigin, mimeTypeHint: String?, suggestedName: String?, targetPath: String?): com.promenar.infinity.domain.media.MediaRef {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val task = async { MediaFetchSkill(slowStore, downloader()).execute(buildJsonObject { put("url", "https://example.test/a") }, mediaTestContext) }
        entered.await()
        task.cancel()
        val error = try { task.await(); null } catch (e: CancellationException) { e }
        assertThat(error).isNotNull()
        assertThat(store.saved).isEmpty()
        assertThat(bodyClosed).isTrue()
    }
}
