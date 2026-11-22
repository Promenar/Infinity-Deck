package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.mediagen.CliArgMode
import com.promenar.infinity.domain.mediagen.ComfyMediaCategory
import com.promenar.infinity.domain.mediagen.ComfyUploadTarget
import com.promenar.infinity.domain.mediagen.ComfyWorkflow
import com.promenar.infinity.domain.mediagen.ChainNodeValidity
import com.promenar.infinity.domain.mediagen.FieldMutability
import com.promenar.infinity.domain.mediagen.FieldValueType
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.MediaCapabilities
import com.promenar.infinity.domain.mediagen.StatefulBranchPolicy
import com.promenar.infinity.domain.mediagen.StatefulChainSpec
import com.promenar.infinity.domain.mediagen.SubmitterInvocationProtocol
import com.promenar.infinity.domain.mediagen.SubmitterSourceKind
import com.promenar.infinity.domain.mediagen.WorkflowExecutionMode
import com.promenar.infinity.domain.mediagen.WorkflowFieldSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ComfyWorkflowExecutionEngineTest {
    private fun systemPythonAvailable(): Boolean = runCatching {
        ProcessBuilder("python3", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    private fun testLaunch(): SubmitterPythonLaunch = SubmitterPythonLaunch(
        commandPrefix = listOf("python3"),
        environment = mapOf(
            "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
            "LANG" to "C.UTF-8",
            "PYTHONUTF8" to "1",
        ),
    )

    @Test fun rawJsonHandlesMultiStaticTargetsUnusedPruningDynamicSlotsFailFastAndDiagnostics() = runBlocking {
        val temp = Files.createTempDirectory("engine-raw").toFile()
        try {
            val f = ComfyFixture()
            f.repository.saveHost(f.host)
            val store = FileComfySubmitterFileStore(File(temp, "sub"))
            val runner = ComfySubmitterRunner(store, File(temp, "run"), ::testLaunch)
            val engine = ComfyWorkflowExecutionEngine(
                repository = f.repository,
                chainStore = InMemoryStatefulChainStore(),
                credentials = ComfyCredentials(f.secrets),
                http = f.http,
                proxyClient = OkHttpClient(),
                runner = runner,
                resolver = f.media,
                store = f.media,
                scratchRoot = File(temp, "scratch"),
            )

            // 1. 静态 3 上传节点传入 2 张图：2 张上传并填入，第 3 张移除连线并裁剪孤立 LoadImage 节点 "12"
            val qwenGraph = Json.parseToJsonElement(
                """{
                  "10": {"class_type":"LoadImage","inputs":{"image":"p1.png"}},
                  "11": {"class_type":"LoadImage","inputs":{"image":"p2.png"}},
                  "12": {"class_type":"LoadImage","inputs":{"image":"p3.png"}},
                  "2": {"class_type":"QwenEdit","inputs":{"prompt":"","image1":["10",0],"image2":["11",0],"image3":["12",0]}},
                  "9": {"class_type":"SaveImage","inputs":{"images":["2",0]}}
                }""",
            ).jsonObject
            val wfMulti = ComfyWorkflow(
                workflowId = "wf_multi_static",
                hostId = f.host.id,
                name = "MultiStatic",
                rawWorkflowJson = qwenGraph,
                outputNodeIds = listOf("9"),
                capabilities = MediaCapabilities(modes = setOf(GenerationMode.REFERENCE), maxImages = 3),
                fields = listOf(
                    WorkflowFieldSpec("prompt", "提示词", valueType = FieldValueType.TEXT, nodeId = "2", inputKey = "prompt"),
                    WorkflowFieldSpec(
                        key = "images",
                        label = "参考图",
                        valueType = FieldValueType.FILE_LIST,
                        nodeId = "2",
                        uploadTargets = listOf(
                            ComfyUploadTarget("10", "image"),
                            ComfyUploadTarget("11", "image"),
                            ComfyUploadTarget("12", "image"),
                        ),
                    ),
                ),
            )
            f.repository.saveWorkflow(wfMulti, runPreflight = false)
            f.http.history = Json.parseToJsonElement(
                """{"prompt-1":{"status":{"completed":true,"status_str":"success"},"outputs":{"9":{"images":[{"filename":"out.png","subfolder":"","type":"output"}]}}}}""",
            ).jsonObject

            val res1 = engine.execute(
                ComfyWorkflowExecutionRequest(
                    sessionId = "s1",
                    workflowId = "wf_multi_static",
                    outputDir = "out",
                    contentInputs = mapOf(
                        "prompt" to JsonPrimitive("hello"),
                        "images" to JsonArray(listOf(JsonPrimitive("a.png"), JsonPrimitive("b.png"))),
                    ),
                ),
            )
            assertEquals("prompt-1", res1.promptId)
            assertEquals(2, f.http.uploads.size)
            val submitted1 = f.http.calls.last { it.first == "/prompt" }.second!!["prompt"]!!.jsonObject
            assertTrue("10" in submitted1 && "11" in submitted1)
            assertFalse("未使用的孤立 LoadImage 节点 12 必须裁剪", "12" in submitted1)
            assertFalse("image3" in submitted1["2"]!!.jsonObject["inputs"]!!.jsonObject)

            // 2. 动态前缀 dynamicInputPrefix=ref_images.ref_image_ 按数量创建 LoadImage 并连线
            val h3Graph = Json.parseToJsonElement(
                """{
                  "2": {"class_type":"H3Ref","inputs":{"prompt":""}},
                  "9": {"class_type":"SaveImage","inputs":{"images":["2",0]}}
                }""",
            ).jsonObject
            val wfDyn = ComfyWorkflow(
                workflowId = "wf_dyn",
                hostId = f.host.id,
                name = "Dyn",
                rawWorkflowJson = h3Graph,
                outputNodeIds = listOf("9"),
                capabilities = MediaCapabilities(modes = setOf(GenerationMode.REFERENCE), maxImages = 9),
                fields = listOf(
                    WorkflowFieldSpec("prompt", "提示词", valueType = FieldValueType.TEXT, nodeId = "2", inputKey = "prompt"),
                    WorkflowFieldSpec(
                        key = "ref_images",
                        label = "参考图",
                        valueType = FieldValueType.FILE_LIST,
                        nodeId = "2",
                        dynamicInputPrefix = "ref_images.ref_image_",
                        dynamicStartIndex = 0,
                    ),
                ),
            )
            f.repository.saveWorkflow(wfDyn, runPreflight = false)
            f.http.history = Json.parseToJsonElement(
                """{"prompt-2":{"status":{"completed":true,"status_str":"success"},"outputs":{"9":{"images":[{"filename":"out2.png","subfolder":"","type":"output"}]}}}}""",
            ).jsonObject
            f.http.uploads.clear()
            engine.execute(
                ComfyWorkflowExecutionRequest(
                    sessionId = "s1",
                    workflowId = "wf_dyn",
                    outputDir = "out",
                    contentInputs = mapOf(
                        "prompt" to JsonPrimitive("dyn"),
                        "ref_images" to JsonArray(listOf(JsonPrimitive("1.png"), JsonPrimitive("2.png"), JsonPrimitive("3.png"))),
                    ),
                ),
            )
            assertEquals(3, f.http.uploads.size)
            val submitted2 = f.http.calls.last { it.first == "/prompt" }.second!!["prompt"]!!.jsonObject
            val node2Inputs = submitted2["2"]!!.jsonObject["inputs"]!!.jsonObject
            assertTrue("ref_images.ref_image_0" in node2Inputs)
            assertTrue("ref_images.ref_image_1" in node2Inputs)
            assertTrue("ref_images.ref_image_2" in node2Inputs)

            // 3. Fail-Fast：传入 2 张图到单目标 FILE_LIST 禁止静默丢弃
            val wfBad = wfDyn.copy(
                workflowId = "wf_fail_fast",
                fields = listOf(
                    WorkflowFieldSpec("prompt", "提示词", valueType = FieldValueType.TEXT, nodeId = "2", inputKey = "prompt"),
                    WorkflowFieldSpec("ref_images", "参考图", valueType = FieldValueType.FILE_LIST, uploadNodeId = "10", uploadInputKey = "image"),
                ),
            )
            f.repository.saveWorkflow(wfBad, runPreflight = false)
            val ffErr = try {
                engine.execute(
                    ComfyWorkflowExecutionRequest(
                        sessionId = "s1",
                        workflowId = "wf_fail_fast",
                        contentInputs = mapOf(
                            "prompt" to JsonPrimitive("fail"),
                            "ref_images" to JsonArray(listOf(JsonPrimitive("1.png"), JsonPrimitive("2.png"))),
                        ),
                    ),
                )
                null
            } catch (e: ComfyFailure) {
                e
            }
            assertTrue(ffErr?.message?.contains("禁止静默丢弃") == true)

            // 4. 结构化 History /execution_error 诊断直达 Engine
            f.http.history = Json.parseToJsonElement(
                """{"prompt-3":{"status":{"completed":false,"status_str":"error","messages":[["execution_error",{"node_id":"2","node_type":"H3Ref","exception_type":"ValueError","exception_message":"dim mismatch"}]]}}}""",
            ).jsonObject
            val diagErr = try {
                engine.execute(
                    ComfyWorkflowExecutionRequest(
                        sessionId = "s1",
                        workflowId = "wf_dyn",
                        contentInputs = mapOf("prompt" to JsonPrimitive("err")),
                    ),
                )
                null
            } catch (e: ComfyFailure) {
                e
            }
            assertTrue(diagErr?.message, diagErr?.message?.contains("节点 #2 (H3Ref)") == true)
            assertTrue(diagErr?.message, diagErr?.message?.contains("dim mismatch") == true)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun statefulChainAutoManagesFirstSegmentChainResolutionAndHonestBranchPolicy() = runBlocking {
        assumeTrue(systemPythonAvailable())
        val temp = Files.createTempDirectory("engine-chain").toFile()
        try {
            val f = ComfyFixture()
            val store = FileComfySubmitterFileStore(File(temp, "sub"))
            val repo = ComfyRepository(f.storage, ComfyCredentials(f.secrets), f.http, submitterFiles = store)
            repo.saveHost(f.host)
            val chainStore = InMemoryStatefulChainStore()
            val runner = ComfySubmitterRunner(store, File(temp, "run"), ::testLaunch)
            val engine = ComfyWorkflowExecutionEngine(
                repository = repo,
                chainStore = chainStore,
                credentials = ComfyCredentials(f.secrets),
                http = f.http,
                proxyClient = OkHttpClient(),
                runner = runner,
                resolver = f.media,
                store = f.media,
                scratchRoot = File(temp, "scratch"),
            )

            // A. 单目录 CLI 脚本（不支持物理分支隔离）：
            //    首段自动获得 --latent-dir & --clip-index 1；
            //    无歧义续接自动解析唯一 chain_id；
            //    分叉时诚实降级为 IN_PLACE_OVERWRITE 并使被覆写主链节点失效！
            val cliCode = """
                import argparse, json
                from pathlib import Path

                p = argparse.ArgumentParser()
                p.add_argument("--host", required=True)
                p.add_argument("--output-dir", required=True)
                p.add_argument("--receipt", required=True)
                p.add_argument("--stage", required=True)
                p.add_argument("--latent-dir", required=True)
                p.add_argument("--clip-index", type=int, required=True)
                p.add_argument("--previous-workflow", default=None)
                p.add_argument("--prompt", default="")
                args = p.parse_args()
                out = Path(args.output_dir) / f"seg_{args.clip_index}.mp4"
                out.write_bytes(b"MP4")
                Path(args.receipt).write_text(json.dumps({
                    "prompt_id": f"pid-{args.clip_index}",
                    "workflow": {"1": {"class_type": "SaveLatent", "inputs": {"clip_index": args.clip_index, "dir": args.latent_dir}}},
                    "continuation_state": {"latent_dir": args.latent_dir, "clip_index": args.clip_index},
                }), encoding="utf-8")
            """.trimIndent()
            repo.saveSubmitter(
                hostId = f.host.id,
                scriptId = "cli_chain_py",
                fileName = "submit_cli.py",
                displayName = "cli_chain",
                description = "",
                sourceKind = SubmitterSourceKind.DESKTOP_IMPORT,
                protocol = SubmitterInvocationProtocol.CLI_ARGS,
                sourceText = cliCode,
                supportFiles = emptyMap(),
            )
            val wfCli = ComfyWorkflow(
                workflowId = "wf_cli_chain",
                hostId = f.host.id,
                name = "CLI Chain",
                mediaCategory = ComfyMediaCategory.VIDEO,
                submitterScriptId = "cli_chain_py",
                executionMode = WorkflowExecutionMode.STATEFUL_CHAIN,
                chainSpec = StatefulChainSpec(segmentIndexFlag = "--clip-index"),
                fields = listOf(WorkflowFieldSpec("prompt", "提示词", cliFlag = "--prompt", cliMode = CliArgMode.VALUE)),
                capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)),
            )
            repo.saveWorkflow(wfCli, runPreflight = false)

            // 1. 首段无须传 latent_dir：宿主自动生成 chain_id + comfy_chain/.../main
            val seg1 = engine.execute(
                ComfyWorkflowExecutionRequest(
                    sessionId = "sessA",
                    workflowId = "wf_cli_chain",
                    stage = "first",
                    outputDir = "out",
                    contentInputs = mapOf("prompt" to JsonPrimitive("seg1")),
                ),
            )
            val chainId = seg1.chainRecord!!.chainId
            assertEquals("seg_01_v1", seg1.appendedNode!!.nodeId)
            val seg1LatentDir = seg1.appendedNode!!.continuationStateJson["latent_dir"]!!.jsonPrimitive.content
            assertEquals("comfy_chain/wf_cli_chain/$chainId/main", seg1LatentDir)
            assertEquals("$seg1LatentDir::clip_1", seg1.appendedNode!!.serverLatentSlotKey)

            // 2. 续接段可省略 chain_id（单活跃链自动解析）
            val seg2Main = engine.execute(
                ComfyWorkflowExecutionRequest(
                    sessionId = "sessA",
                    workflowId = "wf_cli_chain",
                    stage = "next",
                    outputDir = "out",
                    contentInputs = mapOf("prompt" to JsonPrimitive("seg2")),
                ),
            )
            assertEquals(chainId, seg2Main.chainRecord!!.chainId)
            assertEquals("seg_02_v1", seg2Main.appendedNode!!.nodeId)
            assertEquals("$seg1LatentDir::clip_2", seg2Main.appendedNode!!.serverLatentSlotKey)

            // 3. 从 seg_01_v1 分叉：单目录 CLI 脚本诚实降级为 IN_PLACE_OVERWRITE 并使 seg_02_v1 失效！
            val seg2Fork = engine.execute(
                ComfyWorkflowExecutionRequest(
                    sessionId = "sessA",
                    workflowId = "wf_cli_chain",
                    stage = "next",
                    chainId = chainId,
                    parentNodeId = "seg_01_v1",
                    branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
                    outputDir = "out",
                    contentInputs = mapOf("prompt" to JsonPrimitive("fork")),
                ),
            )
            assertEquals(StatefulBranchPolicy.IN_PLACE_OVERWRITE, seg2Fork.appendedNode!!.branchPolicy)
            assertEquals(listOf("seg_02_v1"), seg2Fork.invalidatedNodeIds)
            assertEquals(ChainNodeValidity.OVERWRITTEN_BY_SIBLING, seg2Fork.chainRecord!!.node("seg_02_v1")!!.stateValidity)
            assertEquals(ChainNodeValidity.ACTIVE, seg2Fork.chainRecord!!.node("seg_02_v2")!!.stateValidity)

            // B. 支持真隔离的 PAYLOAD_JSON：分叉写入独立 write_latent_dir，双分支均保持 ACTIVE
            val payloadCode = """
                import argparse, json
                from pathlib import Path

                p = argparse.ArgumentParser()
                p.add_argument("--payload", required=True)
                args = p.parse_args()
                payload = json.loads(Path(args.payload).read_text(encoding="utf-8"))
                if payload.get("dry_run"):
                    print(json.dumps({"dry_run": "ok"}))
                    raise SystemExit(0)
                out = Path(payload["output_dir"]) / f"clip_{payload['segment_index']}.mp4"
                out.write_bytes(b"MP4")
                Path(payload["result_path"]).write_text(json.dumps({
                    "prompt_id": "p-iso",
                    "continuation_state": {
                        "latent_dir": payload["write_latent_dir"],
                        "read_dir": payload.get("read_latent_dir"),
                        "clip_index": payload["segment_index"],
                    },
                }), encoding="utf-8")
            """.trimIndent()
            repo.saveSubmitter(
                hostId = f.host.id,
                scriptId = "iso_payload_py",
                fileName = "submit_iso_payload.py",
                displayName = "iso_payload",
                description = "",
                sourceKind = SubmitterSourceKind.AGENT_AUTHORED,
                protocol = SubmitterInvocationProtocol.PAYLOAD_JSON,
                sourceText = payloadCode,
                supportFiles = emptyMap(),
            )
            val wfIso = ComfyWorkflow(
                workflowId = "wf_payload_iso",
                hostId = f.host.id,
                name = "PayloadIso",
                mediaCategory = ComfyMediaCategory.VIDEO,
                submitterScriptId = "iso_payload_py",
                executionMode = WorkflowExecutionMode.STATEFUL_CHAIN,
                chainSpec = StatefulChainSpec(),
                fields = listOf(WorkflowFieldSpec("prompt", "提示词")),
                capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)),
            )
            repo.saveWorkflow(wfIso, runPreflight = false)

            val p1 = engine.execute(ComfyWorkflowExecutionRequest("sessB", "wf_payload_iso", stage = "first", outputDir = "out"))
            val pChainId = p1.chainRecord!!.chainId
            val p2a = engine.execute(ComfyWorkflowExecutionRequest("sessB", "wf_payload_iso", stage = "next", outputDir = "out"))
            val p2b = engine.execute(
                ComfyWorkflowExecutionRequest("sessB", "wf_payload_iso", stage = "next", parentNodeId = "seg_01_v1", outputDir = "out"),
            )
            assertEquals(pChainId, p2a.chainRecord!!.chainId)
            assertTrue(p2b.invalidatedNodeIds.isEmpty())
            assertEquals(ChainNodeValidity.ACTIVE, p2b.chainRecord!!.node("seg_02_v1")!!.stateValidity)
            assertEquals(ChainNodeValidity.ACTIVE, p2b.chainRecord!!.node("seg_02_v2")!!.stateValidity)
            val readDir2b = p2b.appendedNode!!.continuationStateJson["read_dir"]!!.jsonPrimitive.content
            val writeDir2b = p2b.appendedNode!!.continuationStateJson["latent_dir"]!!.jsonPrimitive.content
            assertTrue(readDir2b.endsWith("/main"))
            assertNotEquals(readDir2b, writeDir2b)
        } finally {
            temp.deleteRecursively()
        }
    }
}
