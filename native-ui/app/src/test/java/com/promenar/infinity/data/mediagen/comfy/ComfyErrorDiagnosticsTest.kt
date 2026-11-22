package com.promenar.infinity.data.mediagen.comfy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComfyErrorDiagnosticsTest {
    @Test fun headTailBufferPreservesBothEndsWhenMiddleOverflows() {
        val buf = HeadTailBuffer(headLimit = 20, tailLimit = 24)
        buf.append("HEADER_CMD_123456789")
        buf.append("M".repeat(200))
        buf.append("TAIL_EXCEPTION_END_0001")
        val combined = buf.combined()
        assertTrue(combined, combined.startsWith("HEADER_CMD_123456789"))
        assertTrue(combined, combined.contains("已省略"))
        assertTrue(combined, combined.endsWith("TAIL_EXCEPTION_END_0001"))
    }

    @Test fun subprocessFailureExtractsComfyNodeErrorThroughMassiveTensorFloodAndRedactsTokens() {
        val buf = HeadTailBuffer(headLimit = 1024, tailLimit = 4096)
        buf.appendLine("[submit] cmd --host http://u:p12345@127.0.0.1:4444/p/tok")
        val tensorFlood = buildString {
            append("'current_inputs': {'images': tensor([[[")
            repeat(800) { append("0.1234, -0.5678, ") }
            append("]]])}")
        }
        buf.appendLine(
            "ComfyUI execution failed: {'node_id': '4', 'node_type': 'FlashVSRNodeAdv', " +
                "'exception_type': 'TypeError', 'exception_message': \"'NoneType' object is not callable\", " +
                "$tensorFlood}",
        )
        val summary = ComfyErrorDiagnostics.summarizeSubprocessFailure(
            exitCode = 1,
            stderrHead = buf.headText(),
            stderrTail = buf.tailText(),
            stdoutHead = "",
            stdoutTail = "",
        )
        assertTrue(summary, summary.contains("FlashVSRNodeAdv"))
        assertTrue(summary, summary.contains("TypeError"))
        assertTrue(summary, summary.contains("'NoneType' object is not callable"))
        assertFalse(summary, summary.contains("p12345"))
        assertFalse(summary, summary.contains("0.1234, -0.5678, 0.1234"))
    }

    @Test fun promptRejectionSummarizesNodeErrorsAndRedactsCredentials() {
        val response = Json.parseToJsonElement(
            """{
              "error": {"type": "prompt_outputs_failed_validation", "message": "Prompt outputs failed validation", "details": "Bearer leak_token_99"},
              "node_errors": {
                "12": {"class_type": "LoadImage", "errors": [{"message": "Invalid image file", "details": "bad.png"}]}
              }
            }""",
        ).jsonObject
        val summary = ComfyErrorDiagnostics.summarizePromptRejection(response)!!
        assertTrue(summary, summary.contains("Prompt outputs failed validation"))
        assertTrue(summary, summary.contains("节点 #12 (LoadImage)"))
        assertTrue(summary, summary.contains("Invalid image file"))
        assertFalse(summary, summary.contains("leak_token_99"))
    }
}
