package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.skills.BuiltinSkillCatalog
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class RuntimeGuideReadonlyToolsTest {
    @Test fun `两种模式的全部文件变更工具在执行前拒绝内置技能路径`() = runTest {
        for (mode in SessionRunMode.entries) for (name in listOf("write_file", "patch_file", "create_file", "create_directory", "move_file", "delete_file")) {
            var executed = false
            var modeReads = 0
            val original = object : SkillDefinition {
                override val id = name
                override val name = id
                override val description = "测试工具"
                override val parametersSchema = "{}"
                override val mcpServerId: String? = null
                override val risk = ToolRisk.FILE_WRITE
                override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                    executed = true
                    error("不得执行写入")
                }
            }
            val tool = LinuxSessionFileSkill(original, { modeReads++; mode }, mockk(), mockk(), mockk(), mockk())
            for (field in listOf("path", "parentPath", "newParentPath")) {
                val result = tool.execute(buildJsonObject { put(field, "${BuiltinSkillCatalog.VIRTUAL_DIR}/SKILL.md") }, object : SkillExecutionContext {
                    override val sessionId = "s"
                    override val agentId = "a"
                    override val workspacePath = "/"
                    override val workspaceRootUuid = "root"
                })
                assertThat(result.status).isEqualTo("error")
                assertThat(result.content).contains("内置技能只读")
            }
            assertThat(executed).isFalse()
            assertThat(modeReads).isEqualTo(0)
        }
    }
}
