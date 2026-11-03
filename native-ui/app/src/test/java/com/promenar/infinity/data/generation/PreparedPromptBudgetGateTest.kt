package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.document.ContextBudgetBlockReason
import com.promenar.infinity.data.document.ContextBudgetDecision
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.model.catalog.MetadataSource
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.data.model.catalog.ModelMetadataRecord
import com.promenar.infinity.data.model.catalog.ModelMetadataResolver
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.remote.protocol.ProtocolToolCall
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class PreparedPromptBudgetGateTest {
    @Test fun `图片传输编码不作为文本token且URL与base64预留一致`() {
        val encoded = "A".repeat(128_000)
        val embedded = prompt("解释图片").copy(messages = listOf(ProtocolMessage(role = "user", content = "解释图片",
            imageUrls = listOf(com.promenar.infinity.data.remote.protocol.ImageInput(mimeType = "image/jpeg", base64 = encoded,
                url = "data:image/jpeg;base64,$encoded")))))
        val remote = embedded.copy(messages = listOf(embedded.messages.single().copy(imageUrls = listOf(
            com.promenar.infinity.data.remote.protocol.ImageInput(mimeType = "image/jpeg", url = "https://example.invalid/image.jpg")))))
        val inlineDecision = PreparedPromptBudgetGate.evaluate(session(), embedded, settings(128_000), false)
        val remoteDecision = PreparedPromptBudgetGate.evaluate(session(), remote, settings(128_000), false)
        assertThat(inlineDecision).isInstanceOf(ContextBudgetDecision.Allowed::class.java)
        assertThat(inlineDecision).isEqualTo(remoteDecision)
    }

    @Test
    fun `无完整文档也计量全部提示词来源`() {
        val decision = PreparedPromptBudgetGate.evaluate(
            session(),
            prompt("普通消息"),
            settings(128_000),
            false,
        )

        assertThat(decision).isInstanceOf(ContextBudgetDecision.Allowed::class.java)
    }

    @Test fun `无完整文档时超量图谱与搜索上下文也阻止发送`() {
        val decision = PreparedPromptBudgetGate.evaluate(
            session(), prompt("图谱和搜索资料".repeat(4000)), settings(8_000), false,
        )
        assertThat(decision).isInstanceOf(ContextBudgetDecision.Blocked::class.java)
    }

    @Test
    fun `最终Prompt超过容量时在Provider前阻止`() {
        val documentPrompt = buildString {
            append("<<<INFINITY_DOCUMENT_BEGIN hash>>>\n")
            append("x".repeat(40_000))
            append("\n<<<INFINITY_DOCUMENT_END hash>>>")
        }
        val decision = PreparedPromptBudgetGate.evaluate(
            session(),
            prompt(documentPrompt),
            settings(8_000),
            true,
        )

        assertThat(decision).isInstanceOf(ContextBudgetDecision.Blocked::class.java)
        assertThat((decision as ContextBudgetDecision.Blocked).reason)
            .isEqualTo(ContextBudgetBlockReason.ExceededCapacity)
        assertThat(decision.requiredTokens).isGreaterThan(decision.availableTokens)
    }

    @Test
    fun `完整文档容量未知时必须阻止而不是假定128K`() {
        val decision = PreparedPromptBudgetGate.evaluate(
            session(modelId = "unknown-model"),
            prompt("正文", modelId = "unknown-model"),
            settings(0),
            true,
        )

        assertThat((decision as ContextBudgetDecision.Blocked).reason)
            .isEqualTo(ContextBudgetBlockReason.UnknownCapacity)
    }

    @Test
    fun `完整文档中文与统一本地估算使用相同口径`() {
        val decision = PreparedPromptBudgetGate.evaluate(
            session(),
            prompt("中".repeat(2_000), maxTokens = 1_024),
            settings(7_500),
            true,
        )

        assertThat(decision).isInstanceOf(ContextBudgetDecision.Allowed::class.java)
        assertThat((decision as ContextBudgetDecision.Allowed).requiredTokens)
            .isEqualTo(ApproximateTokenEstimator.estimate(prompt("中".repeat(2_000), maxTokens = 1_024)).toLong())
    }

    @Test fun `服务端校准数用于发送前门禁且完整文档开关不改变总数`() {
        val p = prompt("短输入", maxTokens = 1024)
        val s = session().let { current -> current.copy(stats = com.promenar.infinity.data.model.SessionStats(
            contextUsageBaseline = PromptUsageCalibration.capture(current, p, 10000))) }
        val ordinary = PreparedPromptBudgetGate.evaluate(s, p, settings(8000), false)
        val document = PreparedPromptBudgetGate.evaluate(s, p, settings(8000), true)
        assertThat(ordinary).isEqualTo(document)
        assertThat((ordinary as ContextBudgetDecision.Blocked).requiredTokens).isEqualTo(10000)
    }

    @Test
    fun `用户容量覆盖按稳定模型ID读取而目录仍按最终远端ID解析`() {
        val prefs = mockk<SharedPreferences> {
            every { getInt(any(), any()) } returns 0
            every { getInt("model_info_provider-x::actual-model_context", any()) } returns 128_000
        }
        val decision = PreparedPromptBudgetGate.evaluate(
            session(modelId = "session-alias"),
            prompt("正文", modelId = "actual-model"),
            prefs,
            true,
            stableModelId = "provider-x::actual-model",
        )

        assertThat(decision).isInstanceOf(ContextBudgetDecision.Allowed::class.java)
    }

    // 复现：262K 窗口、目录最大输出 131072 的模型，在约 34K 实际上下文的多轮工具对话中被误判“需要 127352，可用 125830”。
    @Test fun `未显式限制输出时长工具对话不因最大输出预留和字节计量被误拒`() = withLargeOutputModel {
        val rounds = (1..30).flatMap { round ->
            listOf(
                ProtocolMessage(
                    role = "assistant",
                    content = "",
                    reasoning = "我需要先确认加法结果，再检查第${round}步的输出是否一致。".repeat(12),
                    toolCalls = listOf(ProtocolToolCall(id = "call_$round", name = "run_command",
                        arguments = """{"command":"python3 -c 'print(1+$round)'","timeout":30}""")),
                ),
                ProtocolMessage(role = "tool", toolCallId = "call_$round",
                    content = "命令执行完成，标准输出如下：${round + 1}\n退出码 0。".repeat(20)),
            )
        }
        val longSession = PromptRequest(
            messages = listOf(
                ProtocolMessage(role = "system", content = "你是一个严谨的助手，回答前先核对工具结果。".repeat(450)),
                ProtocolMessage(role = "user", content = "1+1 等于几？请用命令验证。"),
            ) + rounds,
            model = LARGE_OUTPUT_MODEL,
        )
        val approximate = ApproximateTokenEstimator.estimate(longSession)
        assertThat(approximate).isLessThan(40_000)

        val window = PreparedPromptBudgetGate.resolveWindow(
            session(modelId = LARGE_OUTPUT_MODEL, maxTokens = null), longSession, settings(262_144),
        )
        assertThat(window.outputReserveTokens).isAtMost(32_768)

        val decision = PreparedPromptBudgetGate.evaluate(
            session(modelId = LARGE_OUTPUT_MODEL, maxTokens = null), longSession, settings(262_144), false,
        )
        assertThat(decision).isInstanceOf(ContextBudgetDecision.Allowed::class.java)
        assertThat((decision as ContextBudgetDecision.Allowed).requiredTokens).isEqualTo(approximate.toLong())
        assertThat(decision.availableTokens).isEqualTo(window.inputBudgetTokens)
    }

    @Test fun `显式输出上限仍按设置完整预留`() = withLargeOutputModel {
        val explicit = prompt("问".repeat(60_000), modelId = LARGE_OUTPUT_MODEL, maxTokens = 200_000)
        val decision = PreparedPromptBudgetGate.evaluate(
            session(modelId = LARGE_OUTPUT_MODEL, maxTokens = null), explicit, settings(262_144), false,
        )
        assertThat(decision).isInstanceOf(ContextBudgetDecision.Blocked::class.java)
        assertThat((decision as ContextBudgetDecision.Blocked).availableTokens).isEqualTo(262_144L - 200_000 - 5_242)
    }

    @Test fun `自动压缩预算不高于发送门禁可用量`() {
        val window = PromptWindow(modelContextTokens = 131_072, outputReserveTokens = 32_768)
        // 压缩阈值设为 100% 时，压缩目标也必须留出与门禁相同的安全余量，否则会出现“不压缩却被拒”的空档。
        assertThat(window.inputBudgetTokens).isEqualTo(131_072L - 32_768 - 2_621)
        assertThat(PromptWindow(modelContextTokens = null, outputReserveTokens = 4_096).inputBudgetTokens).isNull()
    }

    private fun <T> withLargeOutputModel(block: () -> T): T = ModelCatalogRuntime.withTestResolver(
        ModelMetadataResolver(listOf(ModelMetadataRecord(
            canonicalModelId = "test/$LARGE_OUTPUT_MODEL",
            exactAliases = setOf(LARGE_OUTPUT_MODEL),
            displayName = "Large Output Model",
            familyName = null,
            workload = ModelWorkload.GENERATIVE_TEXT,
            capabilities = mapOf(ModelCapability.REASONING to SupportState.SUPPORTED),
            contextTokens = 1_000_000,
            inputTokens = null,
            outputTokens = 131_072,
            knowledgeCutoff = null,
            source = MetadataSource.INFINITY_OVERRIDE,
        ))),
        block,
    )

    private fun session(modelId: String = "test-model", maxTokens: Int? = 1_024) = Session(
        id = "session",
        agentId = "agent",
        modelId = modelId,
        inferenceParams = InferenceParams(maxTokens = maxTokens),
    )

    private fun prompt(
        content: String,
        modelId: String = "test-model",
        maxTokens: Int? = null,
    ) = PromptRequest(
        messages = listOf(ProtocolMessage(role = "user", content = content)),
        model = modelId,
        maxTokens = maxTokens,
    )

    private fun settings(context: Int): SharedPreferences = mockk {
        every { getInt(any(), any()) } returns context
    }

    private companion object {
        const val LARGE_OUTPUT_MODEL = "large-output-model"
    }
}
