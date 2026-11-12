package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.jobs.BackgroundJobManager
import com.promenar.infinity.data.schedule.DefaultScheduledTaskController
import com.promenar.infinity.data.script.ScriptToolRunner
import com.promenar.infinity.data.ssh.SshRuntime
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import io.mockk.mockk
import java.io.File
import org.junit.Test

/**
 * 内置工具定义在真实聊天中会先经过 [ToolSchemaValidator.validateDefinition] 预检，
 * 使用不受支持的关键字（如 minimum/maximum）会让工具整体以 INVALID_DEFINITION 失败。
 */
class BuiltInToolSchemaDefinitionTest {
    private val validator = ToolSchemaValidator()

    @Test
    fun `源码中全部字面量工具schema均通过定义预检`() {
        // Gradle 单元测试的工作目录可能是模块或仓库根，按候选路径定位主源码。
        val sourceRoot = listOf("src/main/java", "app/src/main/java", "native-ui/app/src/main/java")
            .map(::File).firstOrNull { it.resolve("com/promenar/infinity").isDirectory }
        assertWithMessage("未找到 app 主源码目录：${File("").absolutePath}").that(sourceRoot).isNotNull()
        sourceRoot!!
        // 既扫 `parametersSchema = """…"""`，也扫作为构造参数等方式传入、以根对象类型开头的 schema 字面量。
        val literals = listOf(
            Regex("parametersSchema\\s*=\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL),
            Regex("\"\"\"(\\{\"type\":\"object\".*?)\"\"\"", RegexOption.DOT_MATCHES_ALL),
        )
        val schemas = sourceRoot.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            literals.flatMap { literal -> literal.findAll(text).map { it.groupValues[1] } }.distinct()
                .map { "${file.name}: $it" to it }
        }.filterNot { (_, schema) -> "\$" in schema }.toList()

        // 防止正则失效后空跑通过。
        assertWithMessage("扫描到的字面量 schema 数量").that(schemas.size).isAtLeast(20)
        schemas.forEach { (location, schema) -> assertValid(location, schema) }
    }

    @Test
    fun `模板拼接与脚本工具schema均通过定义预检`() {
        val definitions = sshTools(mockk<SshRuntime>(relaxed = true)) + listOf(
            ExecJsSkill(mockk<ScriptToolRunner>()),
            RunScriptSkill(mockk<ScriptToolRunner>()),
            TermuxExecSkill({ error("不应派发") }, { true }),
            ProductionCutSkill({ error("不应读库") }, { error("不应派发") }),
        ) + scheduleSkillRegistry(mockk<DefaultScheduledTaskController>(relaxed = true)).getAllSkills() +
            backgroundJobTools(mockk<BackgroundJobManager>(relaxed = true))
        definitions.forEach { assertValid(it.id, it.parametersSchema) }
    }

    private fun assertValid(location: String, schema: String) {
        val result = validator.validateDefinition(schema)
        assertWithMessage("$location -> $result").that(result).isInstanceOf(ToolSchemaValidation.Valid::class.java)
    }
}
