package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.memory.LongTermMemoryKind
import com.promenar.infinity.data.memory.LongTermMemorySource
import com.promenar.infinity.data.memory.LongTermMemoryWrite
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.tool.ToolExecutionPolicy
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.ui.chat.manager.RecordingMemoryAccess
import com.promenar.infinity.ui.chat.manager.RecordingMemoryRepository
import com.promenar.infinity.ui.chat.manager.memoryFixture
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class LongTermMemorySkillsTest {
    @Test fun `工具读取和遗忘按会话归属过滤`() = runTest {
        val repository = RecordingMemoryRepository(listOf(
            memoryFixture(id = "shared", content = "共享"),
            memoryFixture(id = "private", content = "隔离").copy(ownerSessionId = "current-session")))
        val access = RecordingMemoryAccess().apply { ownerSessionId = "current-session" }
        assertError(tool("memory_read", repository, access).execute(args("""{"id":"shared"}"""), context), "MEMORY_NOT_FOUND")
        assertError(tool("memory_forget", repository, access).execute(args("""{"id":"shared"}"""), context), "MEMORY_NOT_FOUND")
        assertThat(tool("memory_read", repository, access).execute(args("""{"id":"private"}"""), context).body()["content"]!!.jsonPrimitive.content).isEqualTo("隔离")
    }
    @Test fun `领域错误分别告诉模型补正文改id删旧记忆或移除凭据`() = runTest {
        val expected = mapOf(
            com.promenar.infinity.data.memory.LongTermMemoryFailure.INVALID_TITLE to "INVALID_MEMORY_TITLE",
            com.promenar.infinity.data.memory.LongTermMemoryFailure.INVALID_CONTENT to "INVALID_MEMORY_CONTENT",
            com.promenar.infinity.data.memory.LongTermMemoryFailure.CREDENTIALS to "MEMORY_CREDENTIALS_REJECTED",
            com.promenar.infinity.data.memory.LongTermMemoryFailure.NOT_FOUND to "MEMORY_NOT_FOUND",
            com.promenar.infinity.data.memory.LongTermMemoryFailure.LIMIT_REACHED to "MEMORY_LIMIT_REACHED",
        )
        for ((reason, code) in expected) {
            val repository = RecordingMemoryRepository().apply { failure = com.promenar.infinity.data.memory.LongTermMemoryException(reason) }
            val result = tool("memory_save", repository).execute(saveArgs, context)
            assertError(result, code)
            assertThat(result.body()["suggestion"]!!.jsonPrimitive.content).isNotEmpty()
        }
    }

    private val context = object : SkillExecutionContext {
        override val sessionId = "current-session"
        override val agentId = "current-agent"
        override val assistantMessageId = "current-message"
        override val workspacePath = null
        override val workspaceRootUuid = "workspace"
    }
    private val saveArgs = args("""{"kind":"user","title":" 偏好 ","content":" 使用简体中文 "}""")

    @Test fun `保存返回创建结果并只使用真实执行上下文记录来源`() = runTest {
        val repository = RecordingMemoryRepository()
        val result = tool("memory_save", repository).execute(saveArgs, context)
        assertThat(result.status).isEqualTo("success")
        assertThat(result.body()["id"]!!.jsonPrimitive.content).isEqualTo("memory-id")
        assertThat(result.body()["action"]!!.jsonPrimitive.content).isEqualTo("created")
        assertThat(result.body()["title"]!!.jsonPrimitive.content).isEqualTo("偏好")
        assertThat(result.body()["previous"]).isNull()
        // 界面据写入后的类型与正文判断记忆是否仍是这次写入的结果，才提供撤销。
        assertThat(result.body()["kind"]!!.jsonPrimitive.content).isEqualTo("user")
        assertThat(result.body()["content"]!!.jsonPrimitive.content).isEqualTo("使用简体中文")
        assertThat(repository.savedKind).isEqualTo(LongTermMemoryKind.USER)
        assertThat(repository.savedTitle).isEqualTo("偏好")
        assertThat(repository.savedContent).isEqualTo("使用简体中文")
        assertThat(repository.savedSource).isEqualTo(LongTermMemorySource("current-agent", "current-session", "current-message"))
    }

    @Test fun `覆盖结果包含旧正文供撤销并保留显式id`() = runTest {
        val previous = memoryFixture(kind = LongTermMemoryKind.FEEDBACK, title = "协作", content = "旧要求")
        val repository = RecordingMemoryRepository().apply {
            writeResult = LongTermMemoryWrite(previous.copy(content = "新要求"), previous)
        }
        val result = tool("memory_save", repository).execute(args("""{"kind":"feedback","title":"协作","content":"新要求","id":"memory-id"}"""), context)
        assertThat(result.body()["action"]!!.jsonPrimitive.content).isEqualTo("updated")
        val old = result.body()["previous"]!!.jsonObject
        assertThat(old["kind"]!!.jsonPrimitive.content).isEqualTo("feedback")
        assertThat(old["title"]!!.jsonPrimitive.content).isEqualTo("协作")
        assertThat(old["content"]!!.jsonPrimitive.content).isEqualTo("旧要求")
        assertThat(repository.savedId).isEqualTo("memory-id")
    }

    @Test fun `遗忘返回被删条目的恢复信息且读取返回完整正文与来源`() = runTest {
        val repository = RecordingMemoryRepository(listOf(memoryFixture(content = "完整内容")))
        val read = tool("memory_read", repository).execute(args("""{"id":"memory-id"}"""), context)
        assertThat(read.status).isEqualTo("success")
        assertThat(read.body()["content"]!!.jsonPrimitive.content).isEqualTo("完整内容")
        assertThat(read.body()["source_session_id"]!!.jsonPrimitive.content).isEqualTo("session")
        assertThat(read.body()["created_at"]!!.jsonPrimitive.content).isEqualTo("1")
        val removed = tool("memory_forget", repository).execute(args("""{"id":"memory-id"}"""), context)
        assertThat(removed.status).isEqualTo("success")
        assertThat(removed.body()["id"]!!.jsonPrimitive.content).isEqualTo("memory-id")
        assertThat(removed.body()["title"]!!.jsonPrimitive.content).isEqualTo("偏好")
        assertThat(removed.body()["kind"]!!.jsonPrimitive.content).isEqualTo("user")
        assertThat(removed.body()["content"]!!.jsonPrimitive.content).isEqualTo("完整内容")
        // 撤销删除按原来源与时间写回。
        assertThat(removed.body()["source_session_id"]!!.jsonPrimitive.content).isEqualTo("session")
        assertThat(removed.body()["created_at"]!!.jsonPrimitive.content).isEqualTo("1")
        assertThat(removed.body()).containsKey("updated_at")
        assertError(tool("memory_read", repository).execute(args("""{"id":"memory-id"}"""), context), "MEMORY_NOT_FOUND")
        assertError(tool("memory_forget", repository).execute(args("""{"id":"memory-id"}"""), context), "MEMORY_NOT_FOUND")
    }

    @Test fun `保存拒绝未知kind与非法参数且不把参数当作来源`() = runTest {
        val repository = RecordingMemoryRepository()
        val save = tool("memory_save", repository)
        val malformed = listOf(
            "{}", """{"kind":"unknown","title":"标题","content":"正文"}""",
            """{"kind":true,"title":"标题","content":"正文"}""",
            """{"kind":"user","title":3,"content":"正文"}""",
            """{"kind":"user","title":" ","content":"正文"}""",
            """{"kind":"user","title":"标题","content":[]}""",
            """{"kind":"user","title":"标题","content":" "}""",
            """{"kind":"user","title":"标题","content":"正文","id":null}""",
            """{"kind":"user","title":"标题","content":"正文","id":" "}""",
            """{"kind":"user","title":"标题","content":"正文","source_session_id":"forged"}""",
            """{"kind":"user","title":"${"长".repeat(81)}","content":"正文"}""",
            """{"kind":"user","title":"标题","content":"${"长".repeat(2001)}"}""",
        )
        malformed.forEach { assertError(save.execute(args(it), context), "INVALID_MEMORY_ARGUMENTS") }
        assertThat(repository.writes).isEqualTo(0)
        for (name in listOf("memory_read", "memory_forget")) {
            for (raw in listOf("{}", """{"id":7}""", """{"id":" "}""", """{"id":"x","extra":1}""")) {
                assertError(tool(name, repository).execute(args(raw), context), "INVALID_MEMORY_ARGUMENTS")
            }
        }
        assertThat(repository.reads).isEqualTo(0)
    }

    @Test fun `工具每次执行重新校验隐私与开关并在关闭时不访问仓库`() = runTest {
        val access = RecordingMemoryAccess()
        val repository = RecordingMemoryRepository(listOf(memoryFixture()))
        val tools = longTermMemoryTools(repository, access)
        access.enabled = false
        for (skill in tools) {
            assertError(skill.execute(if (skill.name == "memory_save") saveArgs else args("""{"id":"memory-id"}"""), context), "MEMORY_UNAVAILABLE")
        }
        assertThat(repository.reads).isEqualTo(0)
        assertThat(repository.writes).isEqualTo(0)
        assertThat(access.checkedIds).containsExactly("current-session", "current-session", "current-session")
    }

    @Test fun `仓库与策略异常只返回固定安全错误不泄露异常正文`() = runTest {
        val secret = "sensitive-exception-payload"
        for (failure in listOf(IllegalArgumentException(secret), IllegalStateException(secret))) {
            val repository = RecordingMemoryRepository().apply { this.failure = failure }
            for (name in listOf("memory_save", "memory_read", "memory_forget")) {
                val result = tool(name, repository).execute(if (name == "memory_save") saveArgs else args("""{"id":"memory-id"}"""), context)
                assertError(result, "MEMORY_OPERATION_FAILED")
                assertThat(result.content).doesNotContain(secret)
            }
            val access = RecordingMemoryAccess().apply { this.failure = failure }
            val result = tool("memory_save", RecordingMemoryRepository(), access).execute(saveArgs, context)
            assertError(result, "MEMORY_OPERATION_FAILED")
            assertThat(result.content).doesNotContain(secret)
        }
    }

    @Test fun `取消会从访问策略与所有仓库操作向上传播`() = runTest {
        for (name in listOf("memory_save", "memory_read", "memory_forget")) {
            val repository = RecordingMemoryRepository().apply { failure = CancellationException("cancel") }
            val access = RecordingMemoryAccess()
            assertCancellation { tool(name, repository, access).execute(if (name == "memory_save") saveArgs else args("""{"id":"memory-id"}"""), context) }
        }
        val access = RecordingMemoryAccess().apply { failure = CancellationException("cancel") }
        assertCancellation { tool("memory_save", RecordingMemoryRepository(), access).execute(saveArgs, context) }
    }

    @Test fun `schema符合实际参数且所有执行模式均免审批`() {
        val tools = longTermMemoryTools(RecordingMemoryRepository(), RecordingMemoryAccess())
        val validator = ToolSchemaValidator()
        assertThat(tools.map { it.name }).containsExactly("memory_save", "memory_forget", "memory_read")
        for (skill in tools) {
            assertThat(skill.risk).isEqualTo(if (skill.name == "memory_read") ToolRisk.SAFE_READ else ToolRisk.SESSION_STATE)
            assertThat(validator.validateDefinition(skill.parametersSchema)).isInstanceOf(ToolSchemaValidation.Valid::class.java)
            val arguments = if (skill.name == "memory_save") saveArgs else args("""{"id":"memory-id"}""")
            assertThat(validator.validate(skill.parametersSchema, arguments)).isInstanceOf(ToolSchemaValidation.Valid::class.java)
            for (mode in ExecutionMode.entries) {
                assertThat(ToolExecutionPolicy.requiresApproval(mode, skill.name, skill.risk, arguments.toString())).isFalse()
            }
        }
    }

    private fun tool(name: String, repository: RecordingMemoryRepository, access: RecordingMemoryAccess = RecordingMemoryAccess()): SkillDefinition =
        longTermMemoryTools(repository, access).single { it.name == name }
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun ToolResult.body(): JsonObject = args(content)
    private fun assertError(result: ToolResult, code: String) {
        assertThat(result.status).isEqualTo("error")
        assertThat(result.body()["error"]!!.jsonPrimitive.content).isEqualTo(code)
        assertThat(result.body()["message"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat(result.body()["suggestion"]!!.jsonPrimitive.content).isNotEmpty()
    }
    private suspend fun assertCancellation(block: suspend () -> Unit) {
        var caught: Throwable? = null
        try { block() } catch (failure: Throwable) { caught = failure }
        assertThat(caught).isInstanceOf(CancellationException::class.java)
    }
}
