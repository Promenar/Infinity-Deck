package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.tool.ToolArgumentApprovalRules
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.ComfyRunWorkflowSkill
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ComfyFixedOverrideApprovalTest {
    private suspend fun setupWorkflow(f: ComfyFixture): ComfyWorkflow {
        f.repository.saveHost(f.host)
        f.http.info = Json.parseToJsonElement(
            """{
                "TestNode":{"input":{"required":{"prompt":["STRING"],"steps":["INT"],"cfg":["FLOAT"]}}},
                "SaveImage":{"input":{"required":{}},"output_node":true,"output":["IMAGE"]}
            }""",
        ).jsonObject
        val rawGraph = Json.parseToJsonElement(
            """{
                "1":{"class_type":"TestNode","inputs":{"prompt":"","steps":40,"cfg":1.0}},
                "2":{"class_type":"SaveImage","inputs":{}}
            }""",
        ).jsonObject
        val workflow = ComfyWorkflow(
            workflowId = "wf_qwen",
            hostId = f.host.id,
            name = "Qwen Image 2.1",
            mediaCategory = ComfyMediaCategory.IMAGE,
            rawWorkflowJson = rawGraph,
            outputNodeIds = listOf("2"),
            fields = listOf(
                WorkflowFieldSpec(
                    key = "prompt",
                    label = "提示词",
                    mutability = FieldMutability.CONTENT,
                    valueType = FieldValueType.TEXT,
                    required = true,
                    nodeId = "1",
                    inputKey = "prompt",
                ),
                WorkflowFieldSpec(
                    key = "steps",
                    label = "采样步数",
                    mutability = FieldMutability.FIXED,
                    valueType = FieldValueType.INT,
                    defaultValue = JsonPrimitive(40),
                    min = 1.0,
                    max = 100.0,
                    nodeId = "1",
                    inputKey = "steps",
                ),
                WorkflowFieldSpec(
                    key = "cfg",
                    label = "引导系数",
                    mutability = FieldMutability.FIXED,
                    valueType = FieldValueType.FLOAT,
                    defaultValue = JsonPrimitive(1.0),
                    nodeId = "1",
                    inputKey = "cfg",
                ),
            ),
            capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)),
        )
        f.repository.saveWorkflow(workflow, runPreflight = true)
        return workflow
    }

    @Test
    fun contentOnlyAndEquivalentValuesReturnNullWhileDeviationBuildsDeterministicTarget() = runTest {
        val f = ComfyFixture()
        setupWorkflow(f)

        assertNull(
            ComfyFixedOverrideApprovalPlanner.analyze(
                f.repository,
                buildJsonObject {
                    put("workflow_id", "wf_qwen")
                    putJsonObject("content_inputs") { put("prompt", "画一只猫") }
                },
            ),
        )
        assertNull(
            ComfyFixedOverrideApprovalPlanner.analyze(
                f.repository,
                buildJsonObject {
                    put("workflow_id", "wf_qwen")
                    putJsonObject("fixed_overrides") {
                        put("steps", 40.0)
                        put("cfg", 1)
                    }
                },
            ),
        )

        val argsA = buildJsonObject {
            put("workflow_id", "wf_qwen")
            putJsonObject("fixed_overrides") {
                put("cfg", 1.5)
                put("steps", 60)
            }
            put("reason", "测试更高步数与 CFG 对细节锐度的影响")
        }
        val argsB = buildJsonObject {
            put("workflow_id", "wf_qwen")
            putJsonObject("fixed_overrides") {
                put("steps", 60)
                put("cfg", 1.5)
            }
        }
        val pA = ComfyFixedOverrideApprovalPlanner.analyze(f.repository, argsA)!!
        val pB = ComfyFixedOverrideApprovalPlanner.analyze(f.repository, argsB)!!
        assertEquals(pA.authorizationTarget, pB.authorizationTarget)
        assertTrue(ComfyFixedOverrideApprovalPlanner.isOverrideTarget(pA.authorizationTarget))
        assertTrue(ToolArgumentApprovalRules.requiresNonGrantableApproval("comfy_run_workflow", pA.authorizationTarget))
        assertEquals(2, pA.diffs.size)
        assertEquals("60", pA.diffs.first { it.key == "steps" }.newValue)
    }

    @Test
    fun runSkillRejectsUnboundOverrideAndKeepsBaselineUnchangedAfterApprovedRun() = runTest {
        val f = ComfyFixture()
        setupWorkflow(f)
        val scratch = Files.createTempDirectory("comfy-override-exec").toFile()
        try {
            val runner = ComfySubmitterRunner(InMemoryComfySubmitterFileStore(), scratch, launchProvider = { null })
            val chainStore = InMemoryStatefulChainStore()
            val engine = ComfyWorkflowExecutionEngine(
                repository = f.repository,
                chainStore = chainStore,
                credentials = ComfyCredentials(f.secrets),
                http = f.http,
                proxyClient = OkHttpClient(),
                runner = runner,
                resolver = f.media,
                store = f.media,
                scratchRoot = scratch,
            )
            f.http.onPrompt = {
                f.http.history = Json.parseToJsonElement(
                    """{"prompt-1":{"status":{"completed":true},"outputs":{"2":{"images":[{"filename":"out.png","subfolder":"","type":"output"}]}}}}""",
                ).jsonObject
            }

            val runSkill = ComfyRunWorkflowSkill(f.repository, engine, jobs = null)
            val overrideArgs = buildJsonObject {
                put("workflow_id", "wf_qwen")
                putJsonObject("content_inputs") { put("prompt", "雨夜巷口") }
                putJsonObject("fixed_overrides") { put("steps", 60) }
                put("output_dir", "outputs/qwen")
                put("reason", "临时实验高步数")
            }

            val unboundContext = object : SkillExecutionContext {
                override val sessionId = "session-1"
                override val agentId = "agent-1"
                override val workspacePath: String? = null
                override val workspaceRootUuid = "ws"
                override val isApprovalBoundExecution = false
            }
            val rejected = runSkill.execute(overrideArgs, unboundContext)
            assertEquals("error", rejected.status)
            assertTrue(rejected.content.contains("一次性 Diff 审批"))

            val preflight = ComfyFixedOverrideApprovalPlanner.analyze(f.repository, overrideArgs)!!
            val approvedContext = object : SkillExecutionContext {
                override val sessionId = "session-1"
                override val agentId = "agent-1"
                override val workspacePath: String? = null
                override val workspaceRootUuid = "ws"
                override val isApprovalBoundExecution = true
                override val approvedExecutionTarget = preflight.authorizationTarget
            }
            val succeeded = runSkill.execute(overrideArgs, approvedContext)
            assertNotEquals(succeeded.content, "error", succeeded.status)
            val respJson = Json.parseToJsonElement(succeeded.content).jsonObject
            assertEquals("60", respJson["applied_fixed_overrides"]!!.jsonArray.single().jsonObject["applied"]!!.jsonPrimitive.content)

            // 核验提交到 ComfyUI 的工作流实际采用了 60 步，而保存的工作流基线仍为 40 步。
            val submittedPrompt = f.http.calls.last { it.first == "/prompt" }.second!!["prompt"]!!.jsonObject
            assertEquals(60, submittedPrompt["1"]!!.jsonObject["inputs"]!!.jsonObject["steps"]!!.jsonPrimitive.int)
            val savedBaseline = f.repository.workflowState("wf_qwen").workflow.field("steps")?.defaultValue?.jsonPrimitive?.int
            assertEquals(40, savedBaseline)
        } finally {
            scratch.deleteRecursively()
        }
    }
}
