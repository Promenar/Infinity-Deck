package com.promenar.infinity.data.model

import android.app.Application
import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.model.catalog.MetadataSource
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.data.model.catalog.ModelMetadataRecord
import com.promenar.infinity.data.model.catalog.ModelMetadataResolver
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.io.File

class ModelSpecsTest {
    @org.junit.Test
    fun `未核实模型后缀不能继承家族缓存价`() {
        for (model in listOf("gpt-5.4-mini", "gpt-5.5-pro", "gemini-2.5-flash-lite")) {
            org.junit.Assert.assertNull(model, findModelPricing(model)!!.cacheReadPerMillion)
        }
        org.junit.Assert.assertEquals(0.10, findModelPricing("provider::gpt-4.1-mini-2025-04-14")!!.cacheReadPerMillion!!, 0.000001)
    }

    @org.junit.Test
    fun `缓存价格采用具体模型且未核实档位保持未知`() {
        org.junit.Assert.assertEquals(0.10, findModelPricing("gpt-4.1-mini")!!.cacheReadPerMillion!!, 0.000001)
        org.junit.Assert.assertEquals(0.025, findModelPricing("gemini-3.1-flash-lite")!!.cacheReadPerMillion!!, 0.000001)
        org.junit.Assert.assertEquals(6.0, findModelPricing("claude-sonnet-4-6")!!.cacheWriteLongTtlPerMillion!!, 0.000001)
        org.junit.Assert.assertNull(findModelPricing("qwen-flash")!!.cacheReadPerMillion)
        org.junit.Assert.assertNull(findModelPricing("gpt-5-pro")!!.cacheReadPerMillion)
    }

    @org.junit.Test
    fun `价格按具体模型匹配而非宽泛家族`() {
        org.junit.Assert.assertEquals(0.40, findModelPricing("gpt-4.1-mini")!!.inputPerMillion, 0.000001)
        org.junit.Assert.assertEquals(10.0, findModelPricing("o3-pro")!!.inputPerMillion, 0.000001)
    }

    @Test
    fun `实际目录资产保持可加载且额度非负`() {
        val catalog = com.promenar.infinity.data.model.catalog.BundledModelCatalog.fromJson(bundledCatalogFile().readText())
        assertThat(catalog.records).isNotEmpty()
        catalog.records.forEach { record ->
            assertThat(record.canonicalModelId).isNotEmpty()
            assertThat(record.displayName).isNotEmpty()
            listOfNotNull(record.contextTokens, record.inputTokens, record.outputTokens).forEach {
                assertThat(it).isAtLeast(0)
            }
        }
    }

    @Test
    fun `实际请求ID中的双冒号不能作为持久化前缀拆解`() = withFrozenCatalog {
        assertThat(findRemoteModelSpec("tenant::qwen-flash")).isNull()
        assertThat(findRemoteModelSpec("models/qwen-flash")?.note).isEqualTo("Qwen Flash")
    }
    @Test
    fun `旧 findModelSpec 只适配精确目录记录`() = withFrozenCatalog {
        val spec = findModelSpec("alibaba/qwen-flash")

        assertThat(spec).isNotNull()
        assertThat(spec!!.note).isEqualTo("Qwen Flash")
        assertThat(spec.type).isEqualTo(ModelType.REASONING)
        assertThat(spec.contextLength).isEqualTo(1_000_000)
    }

    @Test
    fun `旧 findModelSpec 不再按子串或声明顺序猜测身份`() = withFrozenCatalog {
        assertThat(findModelSpec("custom-qwen-flash-proxy")).isNull()
        assertThat(findModelSpec("future-gemini-1.5-pro-wrapper")).isNull()
    }

    @Test
    fun `公共目录关键精确型号保持可达`() = withFrozenCatalog {
        val expectations = listOf(
            CatalogExpectation("gpt-4o", 128_000, 16_384, ModelType.CHAT, vision = true, structuredOutput = true),
            CatalogExpectation("deepseek-r1", 128_000, 32_768, ModelType.REASONING, reasoning = true),
            CatalogExpectation("deepseek-v4-pro", 1_000_000, 384_000, ModelType.REASONING, reasoning = true, structuredOutput = true),
            CatalogExpectation("o3-pro", 200_000, 100_000, ModelType.REASONING, reasoning = true, vision = true, structuredOutput = true),
            CatalogExpectation("qwen-flash", 1_000_000, 32_768, ModelType.REASONING, reasoning = true),
            CatalogExpectation("MiniMax-M3", 512_000, 128_000, ModelType.REASONING, reasoning = true, vision = true, video = true),
        )

        expectations.forEach { expected ->
            val spec = findModelSpec(expected.modelId)
            assertWithMessage(expected.modelId).that(spec).isNotNull()
            assertWithMessage("${expected.modelId} context").that(spec!!.contextLength).isEqualTo(expected.contextLength)
            assertWithMessage("${expected.modelId} output").that(spec.maxOutputTokens).isEqualTo(expected.outputTokens)
            assertWithMessage("${expected.modelId} type").that(spec.type).isEqualTo(expected.type)
            assertWithMessage("${expected.modelId} reasoning").that(spec.capabilities?.reasoning).isEqualTo(expected.reasoning)
            assertWithMessage("${expected.modelId} vision").that(spec.capabilities?.vision).isEqualTo(expected.vision)
            assertWithMessage("${expected.modelId} video").that(spec.capabilities?.videoUnderstanding).isEqualTo(expected.video)
            assertWithMessage("${expected.modelId} structured").that(spec.capabilities?.structuredOutput).isEqualTo(expected.structuredOutput)
        }
    }

    @Test
    fun `冻结的旧明确型号精确兼容矩阵可达`() = withFrozenCatalog {
        val expectations = listOf(
            LegacyExpectation("o1-preview", 128_000, ModelType.REASONING, reasoning = true),
            LegacyExpectation("gemini-1.5-pro", 2_000_000, ModelType.REASONING, reasoning = true, vision = true),
            LegacyExpectation("gemini-2.0-flash-thinking", 1_000_000, ModelType.REASONING, reasoning = true),
            LegacyExpectation("deepseek-v3", 64_000, ModelType.CHAT),
            LegacyExpectation("qwen2.5-72b", 131_072, ModelType.CHAT),
            LegacyExpectation("llama-3.1-405b", 128_000, ModelType.CHAT),
            LegacyExpectation("qwen-long", 10_000_000, ModelType.CHAT),
            LegacyExpectation("grok-4.1", 2_000_000, ModelType.REASONING, reasoning = true, vision = true, maxOutputTokens = 65_536),
            LegacyExpectation("gemma-4-31b", 256_000, ModelType.REASONING, reasoning = true, vision = true, maxOutputTokens = 8_192),
            LegacyExpectation("bge-reranker-v2-m3", 4_096, ModelType.RERANK),
            LegacyExpectation("gemini-3.1-pro", 2_000_000, ModelType.REASONING, reasoning = true, vision = true, maxOutputTokens = 65_536),
            LegacyExpectation("gemini-3-flash", 1_000_000, ModelType.REASONING, reasoning = true, vision = true, maxOutputTokens = 65_536),
            LegacyExpectation("glm-4v", 128_000, ModelType.CHAT, vision = true),
        )

        expectations.forEach { expected ->
            val spec = findModelSpec(expected.modelId)
            assertWithMessage(expected.modelId).that(spec).isNotNull()
            assertWithMessage("${expected.modelId} context").that(spec!!.contextLength).isEqualTo(expected.contextLength)
            assertWithMessage("${expected.modelId} type").that(spec.type).isEqualTo(expected.type)
            assertWithMessage("${expected.modelId} reasoning").that(spec.capabilities?.reasoning).isEqualTo(expected.reasoning)
            assertWithMessage("${expected.modelId} vision").that(spec.capabilities?.vision).isEqualTo(expected.vision)
            assertWithMessage("${expected.modelId} output").that(spec.maxOutputTokens).isEqualTo(expected.maxOutputTokens)
        }
    }

    @Test
    fun `专用 workload 适配为 embedding 和 rerank`() = withFrozenCatalog {
        val textEmbedding = findModelSpec("google/gemini-embedding-001")
        val visualEmbedding = findModelSpec("nvidia/llama-nemotron-embed-vl-1b-v2")
        val visualRerank = findModelSpec("nvidia/llama-nemotron-rerank-vl-1b-v2")
        val legacyRerank = findModelSpec("bge-reranker-v2-m3")

        assertThat(textEmbedding?.type).isEqualTo(ModelType.EMBEDDING)
        assertThat(textEmbedding?.capabilities?.embedding).isTrue()
        assertThat(visualEmbedding?.type).isEqualTo(ModelType.EMBEDDING)
        assertThat(visualEmbedding?.capabilities?.vision).isTrue()
        assertThat(visualRerank?.type).isEqualTo(ModelType.RERANK)
        assertThat(visualRerank?.capabilities?.rerank).isTrue()
        assertThat(legacyRerank?.type).isEqualTo(ModelType.RERANK)
        assertThat(legacyRerank?.capabilities?.rerank).isTrue()
        assertThat(legacyRerank?.icon).isEqualTo("rerank")
    }

    @Test
    fun `chat endpoint 能力不覆盖 generative text 的 reasoning 类型`() {
        val record = ModelMetadataRecord(
            canonicalModelId = "test/dual-capability-model",
            exactAliases = setOf("dual-capability-model"),
            displayName = "Dual Capability Model",
            familyName = null,
            workload = ModelWorkload.GENERATIVE_TEXT,
            capabilities = mapOf(
                ModelCapability.CHAT_ENDPOINT to SupportState.SUPPORTED,
                ModelCapability.REASONING to SupportState.SUPPORTED,
            ),
            contextTokens = 32_000,
            inputTokens = null,
            outputTokens = 8_000,
            knowledgeCutoff = null,
            source = MetadataSource.INFINITY_OVERRIDE,
        )

        ModelCatalogRuntime.withTestResolver(ModelMetadataResolver(listOf(record))) {
            val spec = findModelSpec("dual-capability-model")

            assertThat(spec?.type).isEqualTo(ModelType.REASONING)
            assertThat(spec?.capabilities?.reasoning).isTrue()
        }
    }

    @Test
    fun `findContextLength 只返回精确适配结果中的正数`() = withFrozenCatalog {
        assertThat(findContextLength("qwen-flash")).isEqualTo(1_000_000)
        assertThat(findContextLength("deepseek-v4-flash")).isEqualTo(1_000_000)
        assertThat(findContextLength("future-provider/model-x")).isNull()
    }

    @Test
    fun `稳定和 API 前缀仍在精确目录边界内规范化`() = withFrozenCatalog {
        val spec = findModelSpec("default::models/QWEN-FLASH")

        assertThat(spec?.note).isEqualTo("Qwen Flash")
    }

    @Test
    fun `旧 ModelSpecs 保留给尚未迁移调用点`() {
        assertThat(MODEL_SPECS).isNotEmpty()
        assertThat(MODEL_SPECS.all { it.contextLength > 0 }).isTrue()
        assertThat(MODEL_SPECS.size).isAtLeast(100)
        assertThat(MODEL_SPECS.size).isAtMost(500)
    }

    @Test
    fun `模型名称中的上下文后缀解析保持兼容`() {
        assertThat(extractContextLengthFromName("model-128k")).isEqualTo(128_000)
        assertThat(extractContextLengthFromName("model-2m")).isEqualTo(2_000_000)
        assertThat(extractContextLengthFromName("qwen-flash")).isNull()
    }

    private fun applicationWithFrozenCatalog(): Application {
        val assets = mockk<AssetManager>()
        every { assets.open("model-catalog/models-dev.normalized.json") } answers {
            requireNotNull(javaClass.getResourceAsStream("/model-catalog/model-specs-compatibility.json"))
        }
        return mockk<Application>().also { application ->
            every { application.assets } returns assets
        }
    }

    private fun bundledCatalogFile(): File = listOf(
        File("app/src/main/assets/model-catalog/models-dev.normalized.json"),
        File("src/main/assets/model-catalog/models-dev.normalized.json"),
        File("native-ui/app/src/main/assets/model-catalog/models-dev.normalized.json"),
    ).firstOrNull(File::isFile)
        ?: error("models-dev.normalized.json fixture is unavailable")

    private fun <T> withFrozenCatalog(block: () -> T): T {
        val catalog = com.promenar.infinity.data.model.catalog.BundledModelCatalog.load(
            applicationWithFrozenCatalog(),
        )
        return ModelCatalogRuntime.withTestResolver(
            replacement = ModelCatalogRuntime.resolverFor(catalog),
            block = block,
        )
    }

    private data class LegacyExpectation(
        val modelId: String,
        val contextLength: Int,
        val type: ModelType,
        val reasoning: Boolean = false,
        val vision: Boolean = false,
        val maxOutputTokens: Int = 0,
    )

    private data class CatalogExpectation(
        val modelId: String,
        val contextLength: Int,
        val outputTokens: Int,
        val type: ModelType,
        val reasoning: Boolean = false,
        val vision: Boolean = false,
        val video: Boolean = false,
        val structuredOutput: Boolean = false,
    )
}
