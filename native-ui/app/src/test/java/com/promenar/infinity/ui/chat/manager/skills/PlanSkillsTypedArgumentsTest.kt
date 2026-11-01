package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.TaskState
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.domain.repository.ITaskRepository
import com.promenar.infinity.domain.repository.PlanPatchOp
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class PlanSkillsTypedArgumentsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "session-1"
        override val agentId = "agent-1"
        override val workspacePath = null
        override val workspaceRootUuid = "root-1"
    }

    @Test
    fun `update plan 直接消费嵌套 JsonObject`() = runTest {
        val repo = RecordingTaskRepository()
        val args = Json.parseToJsonElement(
            """{
              "operations":[{
                "action":"add_step",
                "parentId":"parent",
                "payload":{"title":"子步骤","sortOrder":"2","note":"保留"}
              }]
            }""".trimIndent(),
        ).jsonObject

        val result = UpdatePlanSkill(repo).execute(args, context)

        assertThat(result.status).isEqualTo("success")
        assertThat(repo.updated).containsExactly(
            PlanPatchOp(
                action = "add_step",
                parentId = "parent",
                payload = mapOf(
                    "title" to "子步骤",
                    "sortOrder" to "2",
                    "note" to "保留",
                ),
            ),
        )
    }

    @Test
    fun `计划更新结果返回完整新状态供冻结上下文中的模型读取`() = runTest {
        val state = TaskState(id = "plan", title = "新目标", status = "active", steps = listOf(
            TaskStep("leaf", title = "新步骤", status = "done", description = "新说明", note = "新备注"),
        ))
        val repo = io.mockk.mockk<ITaskRepository>()
        io.mockk.coEvery { repo.updatePlan(any(), any()) } returns state
        io.mockk.every { repo.countLeafProgress(any()) } returns (1 to 1)
        val result = UpdatePlanSkill(repo).execute(Json.parseToJsonElement(
            """{"operations":[{"action":"set_status","stepId":"leaf","payload":{"status":"done"}}]}""",
        ).jsonObject, context)
        val plan = Json.parseToJsonElement(result.content).jsonObject["currentPlan"]!!.jsonObject
        assertThat(plan["steps"]).isEqualTo(kotlinx.serialization.json.Json.encodeToJsonElement(
            kotlinx.serialization.builtins.ListSerializer(TaskStep.serializer()), state.steps,
        ))
    }

    @Test
    fun `initialize plan 直接消费递归 tree 数组并保持整数`() = runTest {
        val repo = RecordingTaskRepository()
        val args = Json.parseToJsonElement(
            """{
              "goal":"发布",
              "tree":[{
                "id":"root",
                "title":"根步骤",
                "sortOrder":1,
                "children":[{"id":"leaf","title":"叶步骤","sortOrder":3,"children":[]}]
              }]
            }""".trimIndent(),
        ).jsonObject

        val result = InitializePlanSkill(repo).execute(args, context)

        assertThat(result.status).isEqualTo("success")
        assertThat(repo.initializedGoal).isEqualTo("发布")
        assertThat(repo.initializedTree.single().sortOrder).isEqualTo(1)
        assertThat(repo.initializedTree.single().children.single().sortOrder).isEqualTo(3)
    }

    @Test
    fun `update plan 接受数字形式的payload值`() = runTest {
        val repo = RecordingTaskRepository()
        val args = Json.parseToJsonElement(
            """{"operations":[{"action":"add_step","payload":{"title":"新步骤","sortOrder":2}}]}""",
        ).jsonObject

        val result = UpdatePlanSkill(repo).execute(args, context)

        assertThat(result.status).isEqualTo("success")
        assertThat(repo.updated.single().payload).containsEntry("sortOrder", "2")
    }

    @Test
    fun `update plan 把仓库错误原因作为结构化错误回传`() = runTest {
        val repo = RecordingTaskRepository(updateFailure = IllegalArgumentException("步骤 \"9\" 不存在。"))
        val args = Json.parseToJsonElement(
            """{"operations":[{"action":"set_status","stepId":"9","payload":{"status":"done"}}]}""",
        ).jsonObject

        val result = UpdatePlanSkill(repo).execute(args, context)

        assertThat(result.status).isEqualTo("error")
        val body = Json.parseToJsonElement(result.content).jsonObject
        assertThat(body["error"]?.jsonPrimitive?.content).isEqualTo("INVALID_OPERATION")
        assertThat(body["message"]?.jsonPrimitive?.content).isEqualTo("步骤 \"9\" 不存在。")
    }

    @Test
    fun `initialize plan 目标含引号时结果仍是合法JSON`() = runTest {
        val repo = RecordingTaskRepository()
        val args = Json.parseToJsonElement(
            """{"goal":"发布 \"v2\"\n版本","tree":[{"id":"1","title":"打包"}]}""",
        ).jsonObject

        val result = InitializePlanSkill(repo).execute(args, context)

        val body = Json.parseToJsonElement(result.content).jsonObject
        assertThat(body["goal"]?.jsonPrimitive?.content).isEqualTo("发布 \"v2\"\n版本")
    }

    private class RecordingTaskRepository(
        private val updateFailure: Exception? = null,
    ) : ITaskRepository {
        var updated: List<PlanPatchOp> = emptyList()
        var initializedGoal: String? = null
        var initializedTree: List<TaskStep> = emptyList()

        override fun observeActiveTree(sessionId: String): Flow<List<TaskStep>> = emptyFlow()
        override suspend fun initializePlan(
            sessionId: String,
            goal: String,
            tree: List<TaskStep>,
        ): TaskState {
            initializedGoal = goal
            initializedTree = tree
            return TaskState(id = "plan", title = goal, status = "active", steps = tree)
        }

        override suspend fun updatePlan(sessionId: String, operations: List<PlanPatchOp>): TaskState {
            updateFailure?.let { throw it }
            updated = operations
            return TaskState(id = "plan", status = "active")
        }

        override suspend fun getPlan(sessionId: String): TaskState? = null
        override suspend fun dropPlan(sessionId: String, reason: String) = Unit
        override fun deriveParentStatus(children: List<TaskStep>): String = "pending"
        override fun countLeafProgress(steps: List<TaskStep>): Pair<Int, Int> = 0 to 0
    }
}
