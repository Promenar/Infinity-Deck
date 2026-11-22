package com.promenar.infinity.data.mediagen.comfy

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
            assertEquals(InputKind.VIDEO, res.outputs.single().kind)
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
            assertEquals(InputKind.IMAGE, res.outputs.single().kind)
            ComfySubmitterRunner.cleanupRunDirectory(res.continuationState)
        } finally {
            temp.deleteRecursively()
        }
    }
}
