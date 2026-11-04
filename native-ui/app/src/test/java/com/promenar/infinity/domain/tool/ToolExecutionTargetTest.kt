package com.promenar.infinity.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class ToolExecutionTargetTest {
    private val sshIdentities = mapOf("termux" to SshExecutionIdentity("host-1", "identity-1"))
    @Test
    fun `Termux审批固定主机且绑定原始完整命令`() {
        val termux = tool("termux_exec", risk = ToolRisk.PROCESS)
        assertThat(ToolExecutionTargets.extract(termux, """{"command":"printf 'a\\nb'","workdir":"~/src"}"""))
            .isEqualTo("""["process","termux","printf 'a\\nb'"]""")
        assertThat(ToolExecutionTargets.extract(termux, """{"host":"remote","command":"id"}"""))
            .isEqualTo("""["process","termux","id"]""")
        listOf("{}", """{"command":" "}""", """{"command":12}""").forEach {
            assertThat(ToolExecutionTargets.extract(termux, it)).isNull()
        }
        assertThat(ToolExecutionTargets.extract(tool("other_process", risk = ToolRisk.PROCESS), """{"command":"id"}""")).isNull()
    }

    @Test
    fun `SSH路径授权保留远端路径语义且PTY发送绑定解析的主机和原始输入`() {
        assertThat(extract("sftp_write", """{"host":"termux","path":"./A/../data","content":"value"}"""))
            .isEqualTo("""["remote_path","termux","./A/../data",{"sshHostId":"host-1","sshIdentity":"identity-1"}]""")
        val send = tool("ssh_session_send", risk = ToolRisk.PROCESS)
        assertThat(ToolExecutionTargets.extract(send, """{"session_id":"pty-1","input":"ls\n"}""", mapOf("ssh_session:pty-1" to "termux"), sshIdentities))
            .isEqualTo("""["process","termux","ls\n",{"sshHostId":"host-1","sshIdentity":"identity-1"}]""")
        assertThat(ToolExecutionTargets.extract(send, """{"session_id":"unknown","input":"ls"}""")).isNull()
        assertThat(ToolExecutionTargets.extract(tool("ssh_session_open", risk = ToolRisk.PROCESS), """{"host":"termux"}""", sshIdentities = sshIdentities))
            .isEqualTo("""["process","termux","[PTY]",{"sshHostId":"host-1","sshIdentity":"identity-1"}]""")
        assertThat(ToolExecutionTargets.display("""["remote_path","termux","/home/u/a"]"""))
            .isEqualTo("termux · /home/u/a")
    }

    @Test
    fun `文件工具使用规范路径且不会合并大小写不同目标`() {
        listOf("read_file", "write_file", "patch_file", "diff_file").forEach { name ->
            assertThat(extract(name, """{"path":" docs\\\\a.md// "}"""))
                .isEqualTo("""["path","/docs/a.md"]""")
        }
        assertThat(extract("delete_file", """{"path":" docs\\\\a.md// "}"""))
            .isEqualTo("""["path","/docs/a.md",{"oneShot":true}]""")
        assertThat(extract("write_file", """{"path":"/Docs/a.md"}"""))
            .isNotEqualTo(extract("write_file", """{"path":"/docs/a.md"}"""))
    }

    @Test
    fun `uuid 优先按已解析路径定位且未知 uuid 不能降级到 path`() {
        val args = """{"uuid":"entry-1","path":"/unrelated.md"}"""
        assertThat(ToolExecutionTargets.extract(tool("write_file"), args, mapOf("entry-1" to "/docs/current.md")))
            .isEqualTo("""["path","/docs/current.md"]""")
        assertThat(extract("write_file", args)).isNull()
        assertThat(extract("write_file", """{"uuid":"entry-1"}""")).isNull()
    }

    @Test
    fun `创建目标包含父目录与文件名且默认父目录是根`() {
        assertThat(extract("create_file", """{"name":" a.md ","parentPath":"//docs/"}"""))
            .isEqualTo("""["path","/docs/a.md"]""")
        assertThat(extract("create_directory", """{"name":"docs"}"""))
            .isEqualTo("""["path","/docs"]""")
        assertThat(ToolExecutionTargets.extract(
            tool("create_file"),
            """{"name":"a.md","parentUuid":"folder","parentPath":"/wrong"}""",
            mapOf("folder" to "/resolved"),
        )).isEqualTo("""["path","/resolved/a.md"]""")
        assertThat(extract("create_file", """{"name":"a.md","parentUuid":"missing"}""")).isNull()
    }

    @Test
    fun `移动目标同时绑定来源路径和完整目的路径`() {
        assertThat(extract("move_file", """{"path":"/docs/a.md","newParentPath":"/archive","newName":"b.md"}"""))
            .isEqualTo("""["move","/docs/a.md","/archive/b.md"]""")
        assertThat(extract("move_file", """{"path":"/docs/a.md","newParentPath":"/archive"}"""))
            .isEqualTo("""["move","/docs/a.md","/archive/a.md"]""")
        assertThat(extract("move_file", """{"path":"/docs/a.md","newName":"b.md"}"""))
            .isEqualTo("""["move","/docs/a.md","/docs/b.md"]""")
        assertThat(ToolExecutionTargets.extract(
            tool("move_file"),
            """{"uuid":"file","newParentUuid":"folder"}""",
            mapOf("file" to "/docs/a.md", "folder" to "/archive"),
        )).isEqualTo("""["move","/docs/a.md","/archive/a.md"]""")
    }

    @Test
    fun `列表与搜索明确绑定工作区根或指定目录`() {
        assertThat(extract("list_files", "{}" )).isEqualTo("""["path","/"]""")
        assertThat(extract("list_files", """{"path":"/docs"}"""))
            .isEqualTo("""["path","/docs"]""")
        assertThat(ToolExecutionTargets.extract(tool("list_files"), """{"parentUuid":"folder"}""", mapOf("folder" to "/docs")))
            .isEqualTo("""["path","/docs"]""")
        assertThat(extract("search_files", """{"query":"report"}"""))
            .isEqualTo("""["path","/"]""")
    }

    @Test
    fun `非法与保留路径及不完整移动不能产生授权目标`() {
        listOf("/docs/../a", "/./a", "/.infinity-hidden/a", "/.recycle_bin/a", "C:\\temp\\a", "\\\\host\\a").forEach { path ->
            assertThat(extract("write_file", args("path" to path))).isNull()
        }
        listOf(
            """{"name":"../a"}""",
            """{"name":".infinity-private"}""",
        ).forEach { raw -> assertThat(extract("create_file", raw)).isNull() }
        // 非字符串父目录与执行层一样按缺省处理，即在工作区根下创建。
        assertThat(extract("create_file", """{"name":"a","parentPath":3}""")).isEqualTo("""["path","/a"]""")
        assertThat(extract("move_file", """{"path":"/a"}""")).isNull()
        assertThat(extract("move_file", """{"path":"/a","newParentUuid":"unknown"}""")).isNull()
        assertThat(extract("move_file", """{"path":"/","newName":"a"}""")).isNull()
    }

    @Test
    fun `web fetch 绑定规范主机且拒绝非 http 地址与无主机地址`() {
        assertThat(extract("web_fetch", """{"url":"https://EXAMPLE.com:8443/a?q=b#c"}"""))
            .isEqualTo("""["host","example.com"]""")
        assertThat(extract("web_fetch", """{"url":"example.com/a"}"""))
            .isEqualTo("""["host","example.com"]""")
        listOf("file:///tmp/a", "https:///a", "javascript:alert(1)", "https://", "https://exa mple.com").forEach { url ->
            assertThat(extract("web_fetch", args("url" to url))).isNull()
        }
    }

    @Test
    fun `MCP 绑定真实服务器与远端工具名而非供应商别名`() {
        val first = tool("mcp_alias", runtimeId = "mcp:server-a:remote:tool", serverId = "server-a")
        assertThat(ToolExecutionTargets.extract(first, "{}"))
            .isEqualTo("""["mcp","server-a","remote:tool"]""")
        val withColon = tool("same_alias", runtimeId = "mcp:server-a:remote:tool", serverId = "server-a:remote")
        assertThat(ToolExecutionTargets.extract(withColon, "{}"))
            .isEqualTo("""["mcp","server-a:remote","tool"]""")
        assertThat(ToolExecutionTargets.extract(first, "{}"))
            .isNotEqualTo(ToolExecutionTargets.extract(withColon, "{}"))
        assertThat(ToolExecutionTargets.extract(first.copy(runtimeToolId = "mcp:other:remote"), "{}")).isNull()
        assertThat(ToolExecutionTargets.extract(first.copy(runtimeToolId = "mcp:server-a:"), "{}")).isNull()
    }

    @Test
    fun `exec js 目标是工具自身而 process 必须绑定主机和完整命令`() {
        assertThat(extract("exec_js", """{"code":"result=1"}"""))
            .isEqualTo("""["tool","exec_js"]""")
        val process = tool("run_command", risk = ToolRisk.PROCESS)
        assertThat(ToolExecutionTargets.extract(process, """{"host":" EXAMPLE.com ","command":"echo a:b"}"""))
            .isEqualTo("""["process","example.com","echo a:b"]""")
        assertThat(ToolExecutionTargets.extract(process, """{"host":"example.com","command":" echo a:b"}"""))
            .isNotEqualTo(ToolExecutionTargets.extract(process, """{"host":"example.com","command":"echo a:b"}"""))
        assertThat(ToolExecutionTargets.extract(process, """{"host":"example.com"}""")).isNull()
        assertThat(ToolExecutionTargets.extract(process, """{"command":"echo a"}""")).isNull()
        assertThat(ToolExecutionTargets.extract(process, """{"host":4,"command":"echo a"}""")).isNull()
    }

    @Test
    fun `未知工具与无效 JSON 参数不产生通用授权键`() {
        assertThat(extract("unknown", """{"path":"/a","host":"example.com"}""")).isNull()
        listOf("not json", "[]", "null", "42", "{\"path\":1}", "{\"path\":null}", "{}").forEach { raw ->
            assertThat(extract("write_file", raw)).isNull()
        }
        assertThat(extract("exec_js", "[]")).isNull()
        assertThat(extract("exec_js", "{}")).isNull()
    }

    @Test
    fun `超长目标保留完整授权内容而显示安全截断`() {
        val prefix = "/" + "a".repeat(1000)
        val first = extract("write_file", args("path" to "${prefix}x"))!!
        val second = extract("write_file", args("path" to "${prefix}y"))!!
        assertThat(first).contains("${prefix}x")
        assertThat(first).isNotEqualTo(second)
        assertThat(ToolExecutionTargets.display(first)).hasLength(240)
        assertThat(ToolExecutionTargets.display(first)).endsWith("…")
    }

    @Test
    fun `显示把换行与制表符变为可见符号、剥离其它控制和双向字符且不会截断代理对`() {
        val target = extract("write_file", args("path" to "/a\n\u0007\u202E\u2066b"))!!
        assertThat(ToolExecutionTargets.display(target)).isEqualTo("/a↵b")
        assertThat(ToolExecutionTargets.display("""["process","python","import csv\r\nwith x:\n\tpass\r"]"""))
            .isEqualTo("python · import csv↵with x:↵⇥pass↵")
        assertThat(ToolExecutionTargets.display("😀😀😀", maxChars = 3)).isEqualTo("😀…")
        assertThat(ToolExecutionTargets.display("abc", maxChars = 1)).isEqualTo("…")
        assertThat(ToolExecutionTargets.display("abc", maxChars = 0)).isEmpty()
        assertThat(ToolExecutionTargets.display("""["move","/a","/b"]"""))
            .isEqualTo("/a → /b")
    }

    private fun extract(name: String, arguments: String): String? =
        ToolExecutionTargets.extract(tool(name), arguments, sshIdentities = sshIdentities)

    private fun tool(
        name: String,
        runtimeId: String = name,
        serverId: String? = null,
        risk: ToolRisk = ToolRisk.UNKNOWN,
    ) = ProtocolTool(
        function = ProtocolToolFunction(name = name, description = "测试工具", parameters = "{}"),
        runtimeToolId = runtimeId,
        mcpServerId = serverId,
        sourceId = if (serverId == null) "builtin" else "mcp",
        risk = risk,
    )

    private fun args(vararg fields: Pair<String, String>): String =
        JsonObject(fields.associate { (key, value) -> key to JsonPrimitive(value) }).toString()
}
