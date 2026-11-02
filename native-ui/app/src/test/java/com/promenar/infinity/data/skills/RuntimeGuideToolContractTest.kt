package com.promenar.infinity.data.skills

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.tool.ToolExecutionPolicy
import com.promenar.infinity.startup.startupDiagnosticTools
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.skills.*
import io.mockk.mockk
import io.mockk.mockkClass
import java.io.File
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.domain.tool.ToolExecutionTargets

/** 从应用注册入口构造真实工具定义；所有依赖均为假对象，不执行工具或访问网络。 */
class RuntimeGuideToolContractTest {
    private val source = runtimeGuideMainDirectory().resolve("java/com/promenar/infinity")
    private val guide = runtimeGuideMainDirectory().resolve("assets/builtin_skills/infinity-workbench/SKILL.md")

    private fun tools(): List<SkillDefinition> {
        val app = source.resolve("InfinityApplication.kt").readText()
        val registrations = app.substringAfter("val presetSkillRegistry:").substringBefore("val userSkillRegistry:")
        val constructors = Regex("register\\((?:mounted\\()?([\\w.]*[A-Z]\\w*)\\s*[({]")
            .findAll(registrations).map { it.groupValues[1].substringAfterLast('.') }.toList()
        assertThat(constructors.size).isEqualTo(Regex("\\bregister\\(").findAll(registrations).count())
        val static = constructors.filterNot { it == "SessionSubagentSkill" }.map { name ->
            val file = source.walkTopDown().first { it.extension == "kt" && Regex("\\bclass $name\\b").containsMatchIn(it.readText()) }
            val pkg = file.readLines().first { it.startsWith("package ") }.removePrefix("package ")
            val type = Class.forName("$pkg.$name")
            val constructor = type.constructors.filterNot { it.isSynthetic }.minBy { it.parameterCount }
            val args = constructor.parameterTypes.map { parameter ->
                when (parameter) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    String::class.java -> "test"
                    else -> mockkClass(parameter.kotlin, relaxed = true)
                }
            }.toTypedArray()
            constructor.newInstance(*args) as SkillDefinition
        }
        // 工厂自行枚举动态工具名；注册入口增加工厂时必须给本测试补上构造方法。
        val factories = Regex("(?m)^ {12}(?:[\\w]+\\.)*([a-z]\\w*Tools)\\(").findAll(registrations).map { it.groupValues[1] }.toSet()
        assertThat(factories).containsExactly("startupDiagnosticTools", "longTermMemoryTools", "sshTools", "sshHostManagementTools", "browserTools", "backgroundJobTools")
        assertThat(Regex("forEach\\(::register\\)").findAll(registrations).count()).isEqualTo(factories.size)
        val registry = app.substringAfter("private fun createSkillRegistry(").substringBefore("private fun knowledgeConfigForTools")
            .substringAfter("listOf(").substringBefore("\n            )")
        val members = Regex("(?m)^\\s*([\\w.]+)(?:\\(|,)").findAll(registry).map { it.groupValues[1].substringAfterLast('.') }.toList()
        assertThat(members).containsExactly("presetSkillRegistry", "scheduleSkillRegistry", "AgentSkillToolRegistry", "userSkillRegistry", "mcpSkillRegistry")
        val operations = Regex("listOf\\(([^)]*)\\)\\.forEach \\{ operation ->").find(registrations)!!.groupValues[1]
        val sessionTools = Regex("\"([^\"]+)\"").findAll(operations).map { SessionSubagentSkill(it.groupValues[1], emptyList()) }.toList()
        return static + sessionTools + startupDiagnosticTools(mockk()) + longTermMemoryTools(mockk(), mockk()) +
            sshTools(mockk(relaxed = true), null) + sshHostManagementTools(mockk(), mockk(), mockk()) { true } +
            browserTools(mockk(), mockk()) + backgroundJobTools(mockk()) +
            scheduleSkillRegistry(mockk()).getAllSkills() + AgentSkillToolRegistry(mockk()).getAllSkills()
    }

    @Test fun `指南覆盖全部模型内置工具并与四种运行配置的审批判定一致`() {
        val tools = tools().distinctBy { it.name }
        assertThat(tools.size).isAtLeast(60)
        val rows = guide.readLines().filter { it.startsWith("| `") }.associate { line ->
            val cells = line.split('|').map(String::trim)
            cells[1].trim('`') to cells.drop(2).take(5)
        }
        // 配置读取显式列出；其它工具按审批级别与参数条件核对。
        assertThat(tools.map { it.name }).containsAtLeastElementsIn(rows.keys)
        rows.forEach { (name, cells) ->
            if (name != "agents_read") assertWithMessage("$name 四种配置均为自由且无参数条件，不应列入表格").that(cells.take(4).all { it == "自由" } && cells[4] == "无").isFalse()
        }
        val targets = mapOf(
            "普通" to null,
            "技能目录" to "[\"path\",\"/skills/demo/SKILL.md\"]",
            "Linux根目录" to "[\"path\",\"/etc/demo\",{\"linuxRootfs\":true}]",
            "跨会话" to "session-approval:other",
        )
        for (tool in tools) {
            val row = rows[tool.name] ?: listOf("自由", "自由", "自由", "自由", "无")
            val normal = row.take(4).map { if (it == "逐次审批") "批" else "免" }
            assertThat(row.take(4).all { it in setOf("自由", "逐次审批") }).isTrue()
            val expectedByScenario = targets.keys.associateWith { normal }.toMutableMap()
            if (row[4] != "无") row[4].split(';').forEach { part ->
                val (scenario, results) = part.trim().split('=')
                assertThat(targets).containsKey(scenario)
                expectedByScenario[scenario] = results.split(',').map(String::trim)
            }
            println("TOOL_EVIDENCE|${tool.name}|${tool.risk}|${tool.javaClass.name}")
            for ((scenario, target) in targets) {
                if (tool.name == "sftp_download" && scenario == "技能目录") {
                    assertThat(expectedByScenario.getValue(scenario)).containsExactly("拒", "拒", "拒", "拒")
                    continue
                }
                val actual = listOf(false, true).flatMap { linux -> ExecutionMode.entries.map { mode ->
                    if (ToolExecutionPolicy.requiresApproval(mode, tool.name, tool.risk, resolvedTarget = target, usesLinux = linux)) "批" else "免"
                } }
                assertWithMessage("${tool.name} / $scenario").that(actual).containsExactlyElementsIn(expectedByScenario.getValue(scenario)).inOrder()
            }

        }
    }

    @Test fun `配置工具审批级别和限时授权与指南一致`() {
        val tools = tools().filter { it.name in setOf("agents_read", "agents_write") }
        assertThat(tools.map { it.name }).containsExactly("agents_read", "agents_write")
        for (mode in ExecutionMode.entries) for (tool in tools) {
            assertThat(ToolExecutionPolicy.requiresApproval(mode, tool.name, tool.risk)).isEqualTo(tool.name == "agents_write")
            assertThat(ToolExecutionPolicy.grantCategory(mode, tool.name, tool.risk)).isNull()
        }
    }

    @Test fun `SFTP下载技能目录走真实目标解析和执行拒绝而不是审批放行`() = runTest {
        val runtime = mockk<com.promenar.infinity.data.ssh.SshRuntime>(relaxed = true)
        val tool = sshTools(runtime, null).single { it.name == "sftp_download" }
        val context = object : SkillExecutionContext {
            override val sessionId = "s"
            override val agentId = "a"
            override val workspaceRootUuid = "root"
            override val workspacePath = "/"
        }
        for (path in listOf("/skills/demo/SKILL.md", "/.agents/skills/demo/SKILL.md", "${BuiltinSkillCatalog.VIRTUAL_DIR}/SKILL.md")) {
            val args = buildJsonObject {
                put("host", "demo"); put("local_path", path); put("remote_path", "/tmp/source.md")
            }
            assertThat(ToolExecutionTargets.extract(tool.toProtocolTool(), args.toString())).isNull()
            val result = tool.execute(args, context)
            assertThat(result.status).isEqualTo("error")
            if (!BuiltinSkillCatalog.isVirtualPath(path)) assertThat(result.content).contains("PROTECTED_LOCAL_PATH")
            else assertThat(result.content).contains("INVALID_LOCAL_PATH")
        }
        io.mockk.coVerify(exactly = 0) { runtime.hostSnapshot(any()) }
    }

}
