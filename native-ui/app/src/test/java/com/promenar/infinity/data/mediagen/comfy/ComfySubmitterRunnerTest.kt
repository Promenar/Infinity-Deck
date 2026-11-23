package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.linux.LinuxProcessLaunch
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ComfySubmitterRunnerTest {
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

    @Test fun runsPayloadJsonWithSupportModuleProgressAndNoCredentialLeak() = runBlocking {
        assumeTrue(systemPythonAvailable())
        val temp = Files.createTempDirectory("runner-payload").toFile()
        try {
            val fileStore = FileComfySubmitterFileStore(File(temp, "submitters"))
            val helperCode = """def build_tag(val): return f"helper-{val}" """
            val entryCode = """
                import argparse, json, os, sys
                from pathlib import Path
                from helper import build_tag

                def main():
                    p = argparse.ArgumentParser()
                    p.add_argument("--payload", required=True)
                    args = p.parse_args()
                    payload = json.loads(Path(args.payload).read_text(encoding="utf-8"))
                    if payload.get("dry_run"):
                        print(json.dumps({"dry_run": "ok", "tag": build_tag("dry")}))
                        return
                    assert "secret-should-not-exist" not in json.dumps(dict(os.environ))
                    assert payload["host_proxy_url"] == "http://127.0.0.1:4444/p/nonce"
                    print(json.dumps({"type": "progress", "percent": 50, "message": "sampling"}), flush=True)
                    out_dir = Path(payload["output_dir"])
                    out_dir.mkdir(parents=True, exist_ok=True)
                    video_file = out_dir / "clip_save_video.mp4"
                    video_file.write_bytes(b"FAKE_MP4_DATA")
                    Path(payload["result_path"]).write_text(json.dumps({
                        "prompt_id": "pid-77",
                        "outputs": [{"path": str(video_file), "kind": "VIDEO"}],
                        "continuation_state": {"latent_dir": "shot_01", "clip_index": 2},
                        "summary": build_tag(payload["inputs"]["prompt"]),
                    }), encoding="utf-8")

                if __name__ == "__main__":
                    main()
            """.trimIndent()

            val sha = fileStore.saveFiles(
                hostId = "main",
                scriptId = "custom_py",
                fileName = "submit_custom.py",
                sourceText = entryCode,
                supportFiles = mapOf("helper.py" to helperCode.toByteArray()),
            )
            val submitter = ComfySubmitterScript(
                scriptId = "custom_py",
                hostId = "main",
                fileName = "submit_custom.py",
                sourceKind = SubmitterSourceKind.AGENT_AUTHORED,
                protocol = SubmitterInvocationProtocol.PAYLOAD_JSON,
                sha256 = sha,
                supportFiles = listOf("helper.py"),
            )
            val runner = ComfySubmitterRunner(fileStore, File(temp, "scratch"), ::testLaunch)

            val dry = runner.dryRunScript(submitter)
            assertTrue(dry.success)

            val progressEvents = mutableListOf<Pair<Float?, String>>()
            val res = runner.execute(
                SubmitterRunRequest(
                    submitter = submitter,
                    workflow = null,
                    proxyBaseUrl = "http://127.0.0.1:4444/p/nonce",
                    resolvedInputs = mapOf("prompt" to JsonPrimitive("rain")),
                    stage = "next",
                    onProgress = { pct, msg -> progressEvents += pct to msg },
                ),
            )
            assertTrue(res.success)
            assertEquals("pid-77", res.promptId)
            assertEquals("helper-rain", res.summary)
            assertEquals(1, res.outputs.size)
            assertEquals(MediaKind.VIDEO, res.outputs.single().kind)
            assertEquals("video/mp4", res.outputs.single().mimeType)
            assertEquals("shot_01::clip_2", res.serverLatentSlotKey)
            assertEquals(1, progressEvents.size)
            assertEquals(0.5f, progressEvents.single().first)
            ComfySubmitterRunner.cleanupRunDirectory(res.continuationState)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun runsCliArgsProtocolForDesktopStyleSubmitters() = runBlocking {
        assumeTrue(systemPythonAvailable())
        val temp = Files.createTempDirectory("runner-cli").toFile()
        try {
            val fileStore = FileComfySubmitterFileStore(File(temp, "submitters"))
            val stagedImg = File(temp, "ref_src.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val cliCode = """
                import argparse, json
                from pathlib import Path

                p = argparse.ArgumentParser()
                p.add_argument("--host", required=True)
                p.add_argument("--output-dir", required=True)
                p.add_argument("--receipt", required=True)
                p.add_argument("--prompt-file", required=True)
                p.add_argument("--image", action="append", default=[])
                p.add_argument("--steps", type=int, default=40)
                p.add_argument("--free-after", action="store_true")
                args = p.parse_args()
                prompt = Path(args.prompt_file).read_text(encoding="utf-8")
                assert args.host == "http://127.0.0.1:5555/p/tok"
                assert len(args.image) == 1 and Path(args.image[0]).is_file()
                assert args.steps == 36
                assert args.free_after is True
                out = Path(args.output_dir) / "qwen_out.png"
                out.write_bytes(b"PNG_OUT")
                Path(args.receipt).write_text(json.dumps({"prompt_id": "q-1", "prompt": prompt}), encoding="utf-8")
            """.trimIndent()

            val sha = fileStore.saveFiles("main", "qwen_cli", "submit_qwen_test.py", cliCode)
            val submitter = ComfySubmitterScript(
                scriptId = "qwen_cli",
                hostId = "main",
                fileName = "submit_qwen_test.py",
                sourceKind = SubmitterSourceKind.DESKTOP_IMPORT,
                protocol = SubmitterInvocationProtocol.CLI_ARGS,
                sha256 = sha,
            )
            val workflow = ComfyWorkflow(
                workflowId = "qwen_wf",
                hostId = "main",
                name = "Qwen CLI",
                submitterScriptId = "qwen_cli",
                fields = listOf(
                    WorkflowFieldSpec("prompt", "提示词", valueType = FieldValueType.TEXT, cliFlag = "--prompt-file", cliMode = CliArgMode.TEXT_FILE),
                    WorkflowFieldSpec("images", "参考图", valueType = FieldValueType.FILE_LIST, cliFlag = "--image", cliMode = CliArgMode.REPEAT),
                    WorkflowFieldSpec("steps", "步数", mutability = FieldMutability.FIXED, valueType = FieldValueType.INT, defaultValue = JsonPrimitive(40), cliFlag = "--steps", cliMode = CliArgMode.VALUE),
                    WorkflowFieldSpec("free_after", "释放", mutability = FieldMutability.FIXED, valueType = FieldValueType.BOOL, defaultValue = JsonPrimitive(true), cliFlag = "--free-after", cliMode = CliArgMode.BOOL_FLAG),
                ),
            )
            val runner = ComfySubmitterRunner(fileStore, File(temp, "scratch"), ::testLaunch)
            val dry = runner.dryRunScript(submitter, workflow)
            assertTrue(dry.success)

            val res = runner.execute(
                SubmitterRunRequest(
                    submitter = submitter,
                    workflow = workflow,
                    proxyBaseUrl = "http://127.0.0.1:5555/p/tok",
                    resolvedInputs = mapOf(
                        "prompt" to JsonPrimitive("测试提示词"),
                        "images" to JsonArray(listOf(JsonPrimitive("media/ref.png"))),
                        "steps" to JsonPrimitive(36),
                        "free_after" to JsonPrimitive(true),
                    ),
                    stagedFilesByPath = mapOf("media/ref.png" to stagedImg),
                ),
            )
            assertTrue(res.success)
            assertEquals("q-1", res.promptId)
            assertEquals(1, res.outputs.size)
            assertEquals(MediaKind.IMAGE, res.outputs.single().kind)
            ComfySubmitterRunner.cleanupRunDirectory(res.continuationState)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun isolatesSlotKeysAcrossBranchesSanitizesEphemeralLatentDirAndHonorsBranchIdFlag() = runBlocking {
        assumeTrue(systemPythonAvailable())
        val temp = Files.createTempDirectory("runner-chain-iso").toFile()
        try {
            val fileStore = FileComfySubmitterFileStore(File(temp, "submitters"))
            val scratch = File(temp, "scratch")
            val payloadCode = """
                import argparse, json
                from pathlib import Path

                def main():
                    p = argparse.ArgumentParser()
                    p.add_argument("--payload", required=True)
                    args = p.parse_args()
                    payload = json.loads(Path(args.payload).read_text(encoding="utf-8"))
                    if payload.get("dry_run"):
                        print(json.dumps({"dry_run": "ok"}))
                        return
                    assert payload["branch_policy"] in ("ISOLATED_DIR", "IN_PLACE_OVERWRITE")
                    assert payload["segment_index"] == 2
                    assert payload["parent_node_id"] == "seg_01_v1"
                    out_dir = Path(payload["output_dir"])
                    out_dir.mkdir(parents=True, exist_ok=True)
                    out_img = out_dir / "frame.png"
                    out_img.write_bytes(b"PNG")
                    Path(payload["result_path"]).write_text(json.dumps({
                        "prompt_id": "pid-chain",
                        "outputs": [{"path": str(out_img), "kind": "IMAGE"}],
                        "continuation_state": {
                            "latent_dir": str(out_dir),
                        },
                    }), encoding="utf-8")

                if __name__ == "__main__":
                    main()
            """.trimIndent()
            val payloadSha = fileStore.saveFiles("main", "chain_iso", "submit_iso.py", payloadCode)
            val payloadSub = ComfySubmitterScript(
                scriptId = "chain_iso",
                hostId = "main",
                fileName = "submit_iso.py",
                sourceKind = SubmitterSourceKind.AGENT_AUTHORED,
                protocol = SubmitterInvocationProtocol.PAYLOAD_JSON,
                sha256 = payloadSha,
            )
            val wfIso = ComfyWorkflow(
                workflowId = "chain_wf",
                hostId = "main",
                name = "Chain",
                submitterScriptId = "chain_iso",
                executionMode = WorkflowExecutionMode.STATEFUL_CHAIN,
                chainSpec = StatefulChainSpec(),
            )
            val runner = ComfySubmitterRunner(fileStore, scratch, ::testLaunch)
            val b1 = runner.execute(
                SubmitterRunRequest(
                    submitter = payloadSub,
                    workflow = wfIso,
                    proxyBaseUrl = "http://127.0.0.1:4444/p/t",
                    stage = "next",
                    branchId = "b__01_v1_aaaa",
                    branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
                    parentNodeId = "seg_01_v1",
                    segmentIndex = 2,
                ),
            )
            val b2 = runner.execute(
                SubmitterRunRequest(
                    submitter = payloadSub,
                    workflow = wfIso,
                    proxyBaseUrl = "http://127.0.0.1:4444/p/t",
                    stage = "next",
                    branchId = "b__01_v1_bbbb",
                    branchPolicy = StatefulBranchPolicy.ISOLATED_DIR,
                    parentNodeId = "seg_01_v1",
                    segmentIndex = 2,
                ),
            )
            val ld1 = (b1.continuationState["latent_dir"] as JsonPrimitive).content
            val ld2 = (b2.continuationState["latent_dir"] as JsonPrimitive).content
            assertFalse("ephemeral scratch dir must be sanitized: $ld1", ld1.contains("scratch"))
            assertFalse("ephemeral scratch dir must be sanitized: $ld2", ld2.contains("scratch"))
            assertNotEquals(b1.serverLatentSlotKey, b2.serverLatentSlotKey)
            assertEquals("comfy_chain/chain_wf/b__01_v1_aaaa::clip_2", b1.serverLatentSlotKey)
            assertEquals("comfy_chain/chain_wf/b__01_v1_bbbb::clip_2", b2.serverLatentSlotKey)
            ComfySubmitterRunner.cleanupRunDirectory(b1.continuationState)
            ComfySubmitterRunner.cleanupRunDirectory(b2.continuationState)

            // IN_PLACE_OVERWRITE shares slot key under the same inherited latent_dir.
            val ov1 = runner.execute(
                SubmitterRunRequest(
                    submitter = payloadSub,
                    workflow = wfIso,
                    proxyBaseUrl = "http://127.0.0.1:4444/p/t",
                    stage = "next",
                    branchId = "b__01_v1_aaaa",
                    branchPolicy = StatefulBranchPolicy.IN_PLACE_OVERWRITE,
                    parentNodeId = "seg_01_v1",
                    segmentIndex = 2,
                    inheritedState = buildJsonObject { put("latent_dir", "shared_shot") },
                ),
            )
            val ov2 = runner.execute(
                SubmitterRunRequest(
                    submitter = payloadSub,
                    workflow = wfIso,
                    proxyBaseUrl = "http://127.0.0.1:4444/p/t",
                    stage = "next",
                    branchId = "b__01_v1_bbbb",
                    branchPolicy = StatefulBranchPolicy.IN_PLACE_OVERWRITE,
                    parentNodeId = "seg_01_v1",
                    segmentIndex = 2,
                    inheritedState = buildJsonObject { put("latent_dir", "shared_shot") },
                ),
            )
            assertEquals("shared_shot::clip_2", ov1.serverLatentSlotKey)
            assertEquals(ov1.serverLatentSlotKey, ov2.serverLatentSlotKey)
            ComfySubmitterRunner.cleanupRunDirectory(ov1.continuationState)
            ComfySubmitterRunner.cleanupRunDirectory(ov2.continuationState)

            // CLI_ARGS passes branchIdFlag only when non-blank.
            val cliCode = """
                import argparse
                from pathlib import Path

                p = argparse.ArgumentParser()
                p.add_argument("--host", required=True)
                p.add_argument("--output-dir", required=True)
                p.add_argument("--stage")
                p.add_argument("--latent-dir")
                p.add_argument("--branch-id", default=None)
                args = p.parse_args()
                out = Path(args.output_dir) / "cli.png"
                out.write_bytes(args.branch_id.encode() if args.branch_id else b"NO_BRANCH")
            """.trimIndent()
            val cliSha = fileStore.saveFiles("main", "cli_branch", "submit_cb.py", cliCode)
            val cliSub = ComfySubmitterScript(
                scriptId = "cli_branch",
                hostId = "main",
                fileName = "submit_cb.py",
                sourceKind = SubmitterSourceKind.DESKTOP_IMPORT,
                protocol = SubmitterInvocationProtocol.CLI_ARGS,
                sha256 = cliSha,
            )
            val wfWithFlag = ComfyWorkflow(
                workflowId = "cli_wf",
                hostId = "main",
                name = "CLI Chain",
                submitterScriptId = "cli_branch",
                executionMode = WorkflowExecutionMode.STATEFUL_CHAIN,
                receiptFlag = null,
                chainSpec = StatefulChainSpec(branchIdFlag = "--branch-id"),
            )
            val cliRes = runner.execute(
                SubmitterRunRequest(
                    submitter = cliSub,
                    workflow = wfWithFlag,
                    proxyBaseUrl = "http://127.0.0.1:4444/p/t",
                    stage = "next",
                    branchId = "b__01_v1_xyz",
                    segmentIndex = 2,
                    inheritedState = buildJsonObject { put("latent_dir", "shot_cli") },
                ),
            )
            assertEquals("b__01_v1_xyz", cliRes.outputs.single().file.readText())
            ComfySubmitterRunner.cleanupRunDirectory(cliRes.continuationState)

            // 验证首段自动注入 --latent-dir 与 segmentIndexFlag (--clip-index)，以及双读写分离标志
            val dualCode = """
                import argparse, json
                from pathlib import Path

                p = argparse.ArgumentParser()
                p.add_argument("--host", required=True)
                p.add_argument("--output-dir", required=True)
                p.add_argument("--stage")
                p.add_argument("--clip-index", type=int)
                p.add_argument("--latent-dir", default=None)
                p.add_argument("--read-latent-dir", default=None)
                p.add_argument("--write-latent-dir", default=None)
                args = p.parse_args()
                out = Path(args.output_dir) / "dual.png"
                out.write_text(json.dumps({
                    "stage": args.stage,
                    "clip_index": args.clip_index,
                    "latent_dir": args.latent_dir,
                    "read_latent_dir": args.read_latent_dir,
                    "write_latent_dir": args.write_latent_dir,
                }), encoding="utf-8")
            """.trimIndent()
            val dualSha = fileStore.saveFiles("main", "cli_dual", "submit_dual.py", dualCode)
            val dualSub = ComfySubmitterScript(
                scriptId = "cli_dual",
                hostId = "main",
                fileName = "submit_dual.py",
                sourceKind = SubmitterSourceKind.DESKTOP_IMPORT,
                protocol = SubmitterInvocationProtocol.CLI_ARGS,
                sha256 = dualSha,
            )
            val wfSingleDirFirst = ComfyWorkflow(
                workflowId = "wf_single_first",
                hostId = "main",
                name = "SingleDir",
                submitterScriptId = "cli_dual",
                executionMode = WorkflowExecutionMode.STATEFUL_CHAIN,
                receiptFlag = null,
                chainSpec = StatefulChainSpec(segmentIndexFlag = "--clip-index"),
            )
            val firstRes = runner.execute(
                SubmitterRunRequest(
                    submitter = dualSub,
                    workflow = wfSingleDirFirst,
                    proxyBaseUrl = "http://127.0.0.1:4444/p/t",
                    stage = "first",
                    segmentIndex = 1,
                    readLatentDir = "comfy_chain/wf_single_first/c1/main",
                    writeLatentDir = "comfy_chain/wf_single_first/c1/main",
                ),
            )
            val firstJson = Json.parseToJsonElement(firstRes.outputs.single().file.readText()).jsonObject
            assertEquals("first", firstJson["stage"]!!.jsonPrimitive.content)
            assertEquals(1, firstJson["clip_index"]!!.jsonPrimitive.int)
            assertEquals("comfy_chain/wf_single_first/c1/main", firstJson["latent_dir"]!!.jsonPrimitive.content)
            ComfySubmitterRunner.cleanupRunDirectory(firstRes.continuationState)

            val wfDualDir = wfSingleDirFirst.copy(
                workflowId = "wf_dual",
                chainSpec = StatefulChainSpec(
                    segmentIndexFlag = "--clip-index",
                    readLatentDirFlag = "--read-latent-dir",
                    writeLatentDirFlag = "--write-latent-dir",
                ),
            )
            val dualRes = runner.execute(
                SubmitterRunRequest(
                    submitter = dualSub,
                    workflow = wfDualDir,
                    proxyBaseUrl = "http://127.0.0.1:4444/p/t",
                    stage = "next",
                    segmentIndex = 2,
                    readLatentDir = "comfy_chain/wf_dual/c1/main",
                    writeLatentDir = "comfy_chain/wf_dual/c1/b_01",
                ),
            )
            val dualJson = Json.parseToJsonElement(dualRes.outputs.single().file.readText()).jsonObject
            assertTrue(dualJson["latent_dir"] is JsonNull)
            assertEquals("comfy_chain/wf_dual/c1/main", dualJson["read_latent_dir"]!!.jsonPrimitive.content)
            assertEquals("comfy_chain/wf_dual/c1/b_01", dualJson["write_latent_dir"]!!.jsonPrimitive.content)
            ComfySubmitterRunner.cleanupRunDirectory(dualRes.continuationState)

            // 验证超过 32KB 的巨型 stderr（含 current_inputs 张量输出）仍保留尾部真正节点异常
            val floodCode = """
                import sys
                sys.stderr.write("HEADER_BOOT\n")
                sys.stderr.write("'current_inputs': {'frames': tensor([[" + ", ".join(["0.111"] * 12000) + "]])}\n")
                sys.stderr.write("{'node_id': '4', 'node_type': 'FlashVSRNodeAdv', 'exception_type': 'TypeError', 'exception_message': \"'NoneType' object is not callable\"}\n")
                sys.exit(1)
            """.trimIndent()
            val floodSha = fileStore.saveFiles("main", "cli_flood", "submit_flood.py", floodCode)
            val floodSub = ComfySubmitterScript(
                scriptId = "cli_flood",
                hostId = "main",
                fileName = "submit_flood.py",
                protocol = SubmitterInvocationProtocol.CLI_ARGS,
                sha256 = floodSha,
            )
            val floodWf = ComfyWorkflow(workflowId = "wf_flood", hostId = "main", name = "Flood", submitterScriptId = "cli_flood")
            val failEx = try {
                runner.execute(
                    SubmitterRunRequest(
                        submitter = floodSub,
                        workflow = floodWf,
                        proxyBaseUrl = "http://127.0.0.1:4444/p/t",
                    ),
                )
                null
            } catch (e: ComfyFailure) {
                e
            }
            assertNotNull(failEx)
            assertTrue(failEx!!.message, failEx.message!!.contains("FlashVSRNodeAdv"))
            assertTrue(failEx.message, failEx.message!!.contains("TypeError"))
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test fun supportsTemplatesBoolFalseHostMetaMultilineJsonPromptRegexModesSidecarsAndLinuxBridge() = runBlocking {
        assumeTrue(systemPythonAvailable())
        val temp = Files.createTempDirectory("runner-universal").toFile()
        try {
            val fileStore = FileComfySubmitterFileStore(File(temp, "submitters"))
            val stagedRoot = File(temp, "staged").apply { mkdirs() }
            val refA = File(stagedRoot, "a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val guideB = File(stagedRoot, "mid.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }

            val scriptCode = """
                import argparse, json
                from pathlib import Path

                p = argparse.ArgumentParser()
                p.add_argument("--host", default=None)
                p.add_argument("--output-dir", default=None)
                p.add_argument("--load-file", action="append", default=[])
                p.add_argument("--guide", action="append", default=[])
                p.add_argument("--set", action="append", default=[])
                p.add_argument("--free-after", action=argparse.BooleanOptionalAction, default=True)
                p.add_argument("--comfyui-version", default="")
                p.add_argument("--emit-workflow", type=Path, default=None)
                p.add_argument("--preflight-only", action="store_true")
                p.add_argument("--previous-workflow", type=Path, default=None)
                args = p.parse_args()

                if args.emit_workflow:
                    graph = {
                        "1": {"class_type": "Demo", "inputs": {
                            "loads": args.load_file,
                            "guides": args.guide,
                            "sets": args.set,
                            "free_after": args.free_after,
                            "ver": args.comfyui_version,
                        }}
                    }
                    args.emit_workflow.write_text(json.dumps(graph), encoding="utf-8")
                    print(json.dumps({"workflow": str(args.emit_workflow)}))
                    raise SystemExit(0)

                if args.preflight_only:
                    print(json.dumps({"preflight": {"ok": True, "host": args.host, "ver": args.comfyui_version}}, indent=2))
                    raise SystemExit(0)

                if args.previous_workflow:
                    sidecar = args.previous_workflow.with_suffix(".segment.json")
                    assert sidecar.is_file(), f"missing sidecar: {sidecar}"
                    assert json.loads(sidecar.read_text(encoding="utf-8"))["proof"] == "ok-seg1"

                out_dir = Path(args.output_dir)
                out_dir.mkdir(parents=True, exist_ok=True)
                glb = out_dir / "mesh.glb"
                glb.write_bytes(b"glTF_BYTES")
                wf_file = out_dir / "saved_wf.json"
                wf_file.write_text(json.dumps({"1": {"class_type": "Demo", "inputs": {}}}), encoding="utf-8")
                wf_file.with_suffix(".segment.json").write_text(json.dumps({"proof": "ok-seg1"}), encoding="utf-8")
                print("log line before json")
                print("prompt_id=zimg-99887766")
                print(json.dumps({
                    "status": "success",
                    "downloaded": [str(glb)],
                    "reconstruction": {"workflow": str(wf_file)},
                    "extra": {"loads": args.load_file, "guides": args.guide, "sets": args.set, "free": args.free_after, "ver": args.comfyui_version},
                }, indent=2))
            """.trimIndent()
            val sha = fileStore.saveFiles("main", "univ_cli", "submit_univ.py", scriptCode)
            val sub = ComfySubmitterScript(
                scriptId = "univ_cli",
                hostId = "main",
                fileName = "submit_univ.py",
                protocol = SubmitterInvocationProtocol.CLI_ARGS,
                sha256 = sha,
            )
            val wf = ComfyWorkflow(
                workflowId = "wf_univ",
                hostId = "main",
                name = "Universal",
                mediaCategory = ComfyMediaCategory.MODEL_3D,
                submitterScriptId = "univ_cli",
                executionMode = WorkflowExecutionMode.STATEFUL_CHAIN,
                chainSpec = StatefulChainSpec(
                    stageFlag = "",
                    latentDirFlag = "",
                    sidecarSuffixes = listOf(".segment.json"),
                ),
                receiptFlag = null,
                emitWorkflowFlag = "--emit-workflow",
                preflightOnlyFlag = "--preflight-only",
                cliBaseArgs = listOf("--comfyui-version", "{host.comfyui_version}"),
                fields = listOf(
                    WorkflowFieldSpec(
                        key = "load_files", label = "文件绑定", valueType = FieldValueType.FILE_LIST,
                        cliFlag = "--load-file", cliMode = CliArgMode.TEMPLATE, cliValueTemplate = "{node}={file}",
                    ),
                    WorkflowFieldSpec(
                        key = "guides", label = "时间锚点", valueType = FieldValueType.TIMED_FILES,
                        cliFlag = "--guide", cliMode = CliArgMode.TEMPLATE, cliValueTemplate = "{frame_idx}:{file}",
                    ),
                    WorkflowFieldSpec(
                        key = "scalars", label = "标量覆写", valueType = FieldValueType.JSON,
                        cliFlag = "--set", cliMode = CliArgMode.TEMPLATE, cliValueTemplate = "{node}.{input}={json}",
                    ),
                    WorkflowFieldSpec(
                        key = "free_after", label = "释放显存", valueType = FieldValueType.BOOL,
                        cliFlag = "--free-after", cliFalseFlag = "--no-free-after", cliMode = CliArgMode.BOOL_FLAG,
                    ),
                ),
            )
            val runner = ComfySubmitterRunner(fileStore, File(temp, "scratch"), ::testLaunch)
            val inputs = mapOf(
                "load_files" to Json.parseToJsonElement("""[{"node":"1","file":"/workspace/a.png"}]"""),
                "guides" to Json.parseToJsonElement("""[{"frame_idx":60,"path":"/workspace/mid.png"}]"""),
                "scalars" to Json.parseToJsonElement("""[{"node":"10","input":"prompt","value":"hello world"}]"""),
                "free_after" to JsonPrimitive(false),
            )
            val stagedMap = mapOf("/workspace/a.png" to refA, "/workspace/mid.png" to guideB)
            val hostMeta = mapOf("comfyui_version" to "0.41.3", "id" to "main", "name" to "MAIN")

            // 1. EMIT_WORKFLOW 模式：不提交 GPU，直接返回结构化图
            val emitted = runner.execute(
                SubmitterRunRequest(
                    submitter = sub,
                    workflow = wf,
                    proxyBaseUrl = null,
                    runMode = ComfyWorkflowRunMode.EMIT_WORKFLOW,
                    resolvedInputs = inputs,
                    stagedFilesByPath = stagedMap,
                    stagedRootDir = stagedRoot,
                    hostMetadata = hostMeta,
                ),
            )
            assertTrue(emitted.success)
            val demoInputs = emitted.emittedWorkflowJson!!["1"]!!.jsonObject["inputs"]!!.jsonObject
            assertEquals(listOf("1=${refA.path}"), demoInputs["loads"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("60:${guideB.path}"), demoInputs["guides"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("""10.prompt="hello world""""), demoInputs["sets"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertFalse(demoInputs["free_after"]!!.jsonPrimitive.boolean)
            assertEquals("0.41.3", demoInputs["ver"]!!.jsonPrimitive.content)
            ComfySubmitterRunner.cleanupRunDirectory(emitted.continuationState)

            // 2. PREFLIGHT_ONLY 模式：解析多行缩进 JSON
            val preflight = runner.execute(
                SubmitterRunRequest(
                    submitter = sub,
                    workflow = wf,
                    proxyBaseUrl = "http://127.0.0.1:4321/p/n",
                    runMode = ComfyWorkflowRunMode.PREFLIGHT_ONLY,
                    resolvedInputs = inputs,
                    stagedFilesByPath = stagedMap,
                    stagedRootDir = stagedRoot,
                    hostMetadata = hostMeta,
                ),
            )
            assertEquals("0.41.3", preflight.preflightReport!!["preflight"]!!.jsonObject["ver"]!!.jsonPrimitive.content)

            // 3. EXECUTE 模式：验证多行 JSON + 纯文本正则提取 prompt_id + .glb MediaKind.FILE + .segment.json sidecar 保存
            val exec1 = runner.execute(
                SubmitterRunRequest(
                    submitter = sub,
                    workflow = wf,
                    proxyBaseUrl = "http://127.0.0.1:4321/p/n",
                    resolvedInputs = inputs,
                    stagedFilesByPath = stagedMap,
                    stagedRootDir = stagedRoot,
                    hostMetadata = hostMeta,
                    stage = "first",
                    segmentIndex = 1,
                ),
            )
            assertEquals("zimg-99887766", exec1.promptId)
            assertEquals(MediaKind.FILE, exec1.outputs.single().kind)
            assertEquals("model/gltf-binary", exec1.outputs.single().mimeType)
            val sidecarsObj = exec1.continuationState["__sidecar_files"]!!.jsonObject
            assertTrue(sidecarsObj[".segment.json"]!!.jsonPrimitive.content.contains("ok-seg1"))
            val prevWfFile = File(temp, "prev_wf.json").apply { writeText("""{"1":{"class_type":"Demo","inputs":{}}}""") }
            ComfySubmitterRunner.cleanupRunDirectory(exec1.continuationState)

            // 4. 续段还原 sidecar
            val exec2 = runner.execute(
                SubmitterRunRequest(
                    submitter = sub,
                    workflow = wf,
                    proxyBaseUrl = "http://127.0.0.1:4321/p/n",
                    resolvedInputs = inputs,
                    stagedFilesByPath = stagedMap,
                    stagedRootDir = stagedRoot,
                    hostMetadata = hostMeta,
                    stage = "next",
                    segmentIndex = 2,
                    previousWorkflowFile = prevWfFile,
                    previousSidecars = mapOf(".segment.json" to sidecarsObj[".segment.json"]!!.jsonPrimitive.content),
                ),
            )
            assertTrue(exec2.success)
            ComfySubmitterRunner.cleanupRunDirectory(exec2.continuationState)

            // 5. 平台与工具前置门禁（如 macOS 专属流程 / 缺失 ffmpeg）提前拒绝
            val macOnlyWf = wf.copy(workflowId = "wf_mac", requiredPlatforms = listOf("macos"))
            val macErr = runCatching {
                runner.execute(SubmitterRunRequest(submitter = sub, workflow = macOnlyWf, proxyBaseUrl = null))
            }.exceptionOrNull()
            assertTrue(macErr is ComfyFailure && macErr.message!!.contains("macos"))

            val missingToolWf = wf.copy(workflowId = "wf_tool", requiredTools = listOf("nonexistent_tool_xyz_0715"))
            val toolErr = runCatching {
                runner.execute(SubmitterRunRequest(submitter = sub, workflow = missingToolWf, proxyBaseUrl = null))
            }.exceptionOrNull()
            assertTrue(toolErr is ComfyFailure && toolErr.message!!.contains("nonexistent_tool_xyz_0715"))

            // 6. 双执行环境：模拟 Linux 桥接（验证 /mnt/comfy_run 与 /mnt/comfy_stage 双向换算，并自动选中 LINUX 运行时）
            var capturedGuestArgv: List<String> = emptyList()
            var capturedGuestEnv: Map<String, String> = emptyMap()
            val fakeLinuxBridge = object : ComfyLinuxSubmitterBridge {
                override fun isReady(): Boolean = true
                override suspend fun shouldUseLinux(sessionId: String?): Boolean = sessionId == "linux_sess"
                override suspend fun prepareLaunch(
                    runDir: File,
                    guestRunRoot: String,
                    stagedRoot: File?,
                    guestStagedRoot: String?,
                    argv: List<String>,
                    workingDirectoryGuest: String,
                    environment: Map<String, String>,
                ): LinuxProcessLaunch {
                    capturedGuestArgv = argv
                    capturedGuestEnv = environment
                    // 将 guest 路径翻回宿主机路径在 JVM 宿主 python3 执行，完整验证真实双向路径换算
                    fun translateBack(s: String): String {
                        var r = s.replace(guestRunRoot, runDir.canonicalPath)
                        if (stagedRoot != null && guestStagedRoot != null) {
                            r = r.replace(guestStagedRoot, stagedRoot.canonicalPath)
                        }
                        return r
                    }
                    return LinuxProcessLaunch(
                        executable = "python3",
                        arguments = argv.drop(1).map(::translateBack),
                        environment = environment.mapValues { (_, v) -> translateBack(v) } + mapOf(
                            "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
                        ),
                        hostWorkingDirectory = File(translateBack(workingDirectoryGuest)),
                    )
                }
                override suspend fun checkTools(tools: List<String>): Map<String, Boolean> =
                    tools.associateWith { it == "ffmpeg" }
            }
            val dualRunner = ComfySubmitterRunner(fileStore, File(temp, "scratch2"), ::testLaunch, linuxBridge = fakeLinuxBridge)
            assertEquals(ComfySubmitterRuntime.LINUX, dualRunner.resolveRuntime("linux_sess"))
            assertEquals(ComfySubmitterRuntime.BUILTIN_PYTHON, dualRunner.resolveRuntime("builtin_sess"))

            val linuxWf = wf.copy(workflowId = "wf_linux", requiredTools = listOf("ffmpeg"))
            val linuxRes = dualRunner.execute(
                SubmitterRunRequest(
                    submitter = sub,
                    workflow = linuxWf,
                    proxyBaseUrl = "http://127.0.0.1:4321/p/n",
                    sessionId = "linux_sess",
                    runMode = ComfyWorkflowRunMode.EMIT_WORKFLOW,
                    resolvedInputs = inputs,
                    stagedFilesByPath = stagedMap,
                    stagedRootDir = stagedRoot,
                    hostMetadata = hostMeta,
                ),
            )
            assertEquals(ComfySubmitterRuntime.LINUX, linuxRes.runtime)
            assertTrue(capturedGuestArgv.any { it.startsWith("${ComfySubmitterRunner.GUEST_RUN_ROOT}/script/") })
            assertTrue(capturedGuestArgv.any { it.contains("1=${ComfySubmitterRunner.GUEST_STAGED_ROOT}/a.png") })
            assertEquals("127.0.0.1,localhost", capturedGuestEnv["NO_PROXY"])
            ComfySubmitterRunner.cleanupRunDirectory(linuxRes.continuationState)
        } finally {
            temp.deleteRecursively()
        }
    }
}
