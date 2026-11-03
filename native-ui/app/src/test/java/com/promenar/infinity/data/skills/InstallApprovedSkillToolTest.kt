package com.promenar.infinity.data.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.tool.ToolExecutionPolicy
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstallApprovedSkillToolTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(approved: Boolean) = object : SkillExecutionContext {
        override val sessionId = "session-test"
        override val agentId = "agent-test"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root-test"
        override val isApprovalBoundExecution = approved
    }

    @Test fun `安装工具所有模式均逐次审批且未绑定审批不能写入`() = runTest {
        val root = temporary.newFolder("user")
        val catalog = FileSkillCatalog(root, InMemoryPreferences(), temporary.newFolder("state"),
            Dispatchers.IO, System::currentTimeMillis, SkillCatalogFiles(JvmBoundRealPathFileSystem()))
        val tool = InstallApprovedSkillTool(catalog)
        ExecutionMode.entries.forEach { mode ->
            assertThat(ToolExecutionPolicy.requiresApproval(mode, tool.name, tool.risk)).isTrue()
            assertThat(ToolExecutionPolicy.grantCategory(mode, tool.name, tool.risk)).isNull()
        }
        val payload = Json.parseToJsonElement("""{"files":{"SKILL.md":{"text":"---\nname: approved-tool\ndescription: 测试\n---\n正文"},"scripts/run.py":{"base64":"cHJpbnQoMSkK"}}}""") as JsonObject
        assertThat(tool.execute(payload, context(false)).status).isEqualTo("error")
        assertThat(root.listFiles()).isEmpty()
        assertThat(tool.execute(payload, context(true)).status).isEqualTo("success")
        assertThat(catalog.scan(null).user.single().status).isEqualTo(SkillStatus.ENABLED)
        assertThat(catalog.scan(null).user.single().modifiedSinceConfirmed).isFalse()
        assertThat(root.resolve("approved-tool/scripts/run.py").readText()).isEqualTo("print(1)\n")
    }
}
