package com.promenar.infinity.data.model.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class PublishedModelCatalogTest {
    @Test
    fun `schema3解析三态额度作用域与未知字段`() {
        val catalog = PublishedModelCatalog.fromJson(catalogJson("""
            {
              "canonicalModelId":"vendor/model-x",
              "displayName":"Model X",
              "source":"OPENROUTER",
              "providerScope":"openrouter",
              "exactAliases":["model-x"],
              "workload":"GENERATIVE_TEXT",
              "reasoning":false,
              "tool_call":true,
              "contextTokens":128000,
              "inputTokens":120000,
              "outputTokens":8000,
              "modalities":{"input":["text","image"],"output":["text"]},
              "futureField":{"kept":"ignored"}
            }
        """))

        val record = catalog.records.single()
        assertThat(record.providerScope).isEqualTo("openrouter")
        assertThat(record.capabilities[ModelCapability.REASONING]).isEqualTo(SupportState.UNSUPPORTED)
        assertThat(record.capabilities[ModelCapability.TOOL_CALLING]).isEqualTo(SupportState.SUPPORTED)
        assertThat(record.contextTokens).isEqualTo(128000)
        assertThat(record.inputTokens).isEqualTo(120000)
        assertThat(record.outputTokens).isEqualTo(8000)
        assertThat(record.capabilities[ModelCapability.VISION_INPUT]).isEqualTo(SupportState.SUPPORTED)
    }

    @Test
    fun `完全重复source scope raw identity拒绝整个目录`() {
        listOf(
            """{"canonicalModelId":"same","displayName":"A","source":"MODELS_DEV"},
                {"canonicalModelId":"same","displayName":"B","source":"MODELS_DEV"}""",
            """{"canonicalModelId":"same","displayName":"A","source":"MODELS_DEV","providerScope":"vendor"},
                {"canonicalModelId":"same","displayName":"B","source":"MODELS_DEV","providerScope":"vendor"}""",
        ).forEach { records ->
            assertThrows(IllegalArgumentException::class.java) {
                PublishedModelCatalog.fromJson(catalogJson(records))
            }
        }
    }

    @Test
    fun `大小写不同wire identity保留为独立记录且查找时报告歧义`() {
        val catalog = PublishedModelCatalog.fromJson(catalogJson("""
            {"canonicalModelId":"together_ai/BAAI/bge-base-en-v1.5","displayName":"Upper","source":"LITELLM","providerScope":"together_ai"},
            {"canonicalModelId":"together_ai/baai/bge-base-en-v1.5","displayName":"Lower","source":"LITELLM","providerScope":"together_ai"}
        """))

        assertThat(catalog.records).hasSize(2)
        val resolved = ModelMetadataResolver(catalog.records).resolve(
            "together_ai/BAAI/bge-base-en-v1.5",
            sourceProviderId = "together_ai",
        )
        assertThat(resolved.canonicalModelId).isNull()
        assertThat(resolved.offeringId).isNull()
        assertThat(resolved.diagnostics).contains("ambiguous_exact_match")
    }

    @Test
    fun `已知字段错误类型负值溢出和空ID均拒绝`() {
        listOf(
            """{"canonicalModelId":"","displayName":"A","source":"MODELS_DEV"}""",
            """{"canonicalModelId":"a","displayName":"A","source":"MODELS_DEV","reasoning":"true"}""",
            """{"canonicalModelId":"a","displayName":"A","source":"MODELS_DEV","contextTokens":-1}""",
            """{"canonicalModelId":"a","displayName":"A","source":"MODELS_DEV","outputTokens":2147483648}""",
        ).forEach { record ->
            assertThrows(IllegalArgumentException::class.java) {
                PublishedModelCatalog.fromJson(catalogJson(record))
            }
        }
    }

    @Test
    fun `同scope同offering可由不同source分别描述`() {
        val catalog = PublishedModelCatalog.fromJson(catalogJson("""
            {"canonicalModelId":"vendor/model","displayName":"Models Dev","source":"MODELS_DEV","providerScope":"openai"},
            {"canonicalModelId":"vendor/model","displayName":"LiteLLM","source":"LITELLM","providerScope":"openai"}
        """))

        assertThat(catalog.records).hasSize(2)
    }

    @Test
    fun `输入集合在解析后不可由外部修改`() {
        val records = PublishedModelCatalog.fromJson(catalogJson(
            """{"canonicalModelId":"a","displayName":"A","source":"MODELS_DEV","exactAliases":["alias"]}""",
        )).records

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (records as MutableList<ModelMetadataRecord>).clear()
        }
        assertThat(records.single().exactAliases).containsExactly("alias")
    }

    @Test
    fun `云端重排记录与本地覆盖合并时仍可解析精确身份`() {
        val catalog = PublishedModelCatalog.fromJson(catalogJson("""
            {
              "canonicalModelId":"BAAI/bge-reranker-v2-m3",
              "displayName":"BGE Reranker V2 M3",
              "source":"INFINITY_OVERRIDE",
              "exactAliases":["bge-reranker-v2-m3","Pro/BAAI/bge-reranker-v2-m3"],
              "family":"BGE",
              "workload":"RERANK",
              "contextTokens":8192
            }
        """))

        assertThat(catalog.records.single().capabilities[ModelCapability.CHAT_ENDPOINT])
            .isEqualTo(SupportState.UNSUPPORTED)
        val resolver = ModelMetadataResolver(catalog.records + INFINITY_EXACT_MODEL_OVERRIDES.values)
        val result = resolver.resolve("Free/BAAI/bge-reranker-v2-m3")
        assertThat(result.workload).isEqualTo(ModelWorkload.RERANK)
        assertThat(result.canonicalModelId).isEqualTo("BAAI/bge-reranker-v2-m3")
        assertThat(result.diagnostics).isEmpty()
    }

    private fun catalogJson(records: String) = """
        {
          "schemaVersion":3,
          "generatedAt":"2026-09-22T00:00:00Z",
          "sources":[],
          "records":[$records]
        }
    """.trimIndent()
}
