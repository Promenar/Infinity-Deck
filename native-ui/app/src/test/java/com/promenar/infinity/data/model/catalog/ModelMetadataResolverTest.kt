package com.promenar.infinity.data.model.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelMetadataResolverTest {
    private val resolver = ModelMetadataResolver()

    @Test
    fun `领域枚举保持完整冻结合同`() {
        assertThat(ModelWorkload.values().toSet()).containsExactly(
            ModelWorkload.GENERATIVE_TEXT,
            ModelWorkload.EMBEDDING,
            ModelWorkload.RERANK,
            ModelWorkload.IMAGE_GENERATION,
            ModelWorkload.AUDIO,
            ModelWorkload.VIDEO,
            ModelWorkload.UNKNOWN,
        )
        assertThat(ModelCapability.values().toSet()).containsExactly(
            ModelCapability.REASONING,
            ModelCapability.CHAT_ENDPOINT,
            ModelCapability.VISION_INPUT,
            ModelCapability.AUDIO_INPUT,
            ModelCapability.AUDIO_OUTPUT,
            ModelCapability.VIDEO_INPUT,
            ModelCapability.TOOL_CALLING,
            ModelCapability.STRUCTURED_OUTPUT,
            ModelCapability.PROMPT_CACHING,
            ModelCapability.COMPUTER_USE,
            ModelCapability.WEB_ACCESS,
        )
        assertThat(MetadataSource.values().toSet()).containsExactly(
            MetadataSource.USER,
            MetadataSource.PROVIDER,
            MetadataSource.INFINITY_OVERRIDE,
            MetadataSource.MODELS_DEV,
            MetadataSource.LITELLM,
            MetadataSource.OPENROUTER,
            MetadataSource.FAMILY,
            MetadataSource.FALLBACK,
        )
        assertThat(SupportState.values().toList()).containsExactly(
            SupportState.SUPPORTED,
            SupportState.UNSUPPORTED,
            SupportState.UNKNOWN,
        ).inOrder()
    }

    @Test
    fun `用户名称高于提供商且提供商逐字段覆盖精确目录`() {
        val providerResolved = resolver.resolve(
            remoteModelId = "deepseek-v4-flash",
            providerId = "default",
            providerMetadata = metadata(
                displayName = "Provider DeepSeek",
                workload = ModelWorkload.EMBEDDING,
                capabilities = mapOf(ModelCapability.REASONING to SupportState.UNSUPPORTED),
                contextTokens = 16_384,
            ),
        )
        val userResolved = resolver.resolve(
            remoteModelId = "deepseek-v4-flash",
            providerId = "default",
            providerMetadata = metadata(
                displayName = "Provider DeepSeek",
                workload = ModelWorkload.EMBEDDING,
                capabilities = mapOf(ModelCapability.REASONING to SupportState.UNSUPPORTED),
                contextTokens = 16_384,
            ),
            userOverride = override(displayName = "我的模型"),
        )

        assertThat(providerResolved.displayName).isEqualTo("Provider DeepSeek")
        assertThat(providerResolved.sourceByField["displayName"]).isEqualTo(MetadataSource.PROVIDER)
        assertThat(userResolved.displayName).isEqualTo("我的模型")
        assertThat(userResolved.sourceByField["displayName"]).isEqualTo(MetadataSource.USER)
        assertThat(userResolved.workload).isEqualTo(ModelWorkload.EMBEDDING)
        assertThat(userResolved.sourceByField["workload"]).isEqualTo(MetadataSource.PROVIDER)
        assertThat(userResolved.capabilities[ModelCapability.REASONING])
            .isEqualTo(SupportState.UNSUPPORTED)
        assertThat(userResolved.sourceByField["capabilities.REASONING"])
            .isEqualTo(MetadataSource.PROVIDER)
        assertThat(userResolved.contextTokens).isEqualTo(16_384)
        assertThat(userResolved.sourceByField["contextTokens"])
            .isEqualTo(MetadataSource.PROVIDER)
        assertThat(userResolved.familyName).isEqualTo("DeepSeek V4")
        assertThat(userResolved.sourceByField["familyName"])
            .isEqualTo(MetadataSource.INFINITY_OVERRIDE)
    }

    @Test
    fun `家族匹配只补 familyName 不覆盖精确字段`() {
        val resolved = resolver.resolve("vendor/deepseek-new-variant", "default")

        assertThat(resolved.displayName).isEqualTo("vendor/deepseek-new-variant")
        assertThat(resolved.familyName).isEqualTo("DeepSeek")
        assertThat(resolved.workload).isEqualTo(ModelWorkload.UNKNOWN)
        assertThat(resolved.contextTokens).isNull()
    }

    @Test
    fun `deepseeker 不得误判为 deepseek 家族`() {
        val resolved = resolver.resolve("deepseeker-x")

        assertThat(resolved.familyName).isNull()
        assertThat(resolved.sourceByField["familyName"]).isEqualTo(MetadataSource.FALLBACK)
    }

    @Test
    fun `精确模型 ID 不得被系列泛称覆盖`() {
        val resolved = resolver.resolve("deepseek-v4-flash")

        assertThat(resolved.displayName).isEqualTo("DeepSeek V4 Flash")
        assertThat(resolved.canonicalModelId).isEqualTo("deepseek/deepseek-v4-flash")
        assertThat(resolved.sourceByField["canonicalModelId"])
            .isEqualTo(MetadataSource.INFINITY_OVERRIDE)
        assertThat(resolved.familyName).isEqualTo("DeepSeek V4")
        assertThat(resolved.capabilities[ModelCapability.CHAT_ENDPOINT])
            .isEqualTo(SupportState.UNKNOWN)
        assertThat(resolved.sourceByField["capabilities.CHAT_ENDPOINT"])
            .isEqualTo(MetadataSource.FALLBACK)
        assertThat(resolved.inputTokens).isNull()
        assertThat(resolved.sourceByField["inputTokens"])
            .isEqualTo(MetadataSource.FALLBACK)
        assertThat(resolved.knowledgeCutoff).isNull()
        assertThat(resolved.sourceByField["knowledgeCutoff"])
            .isEqualTo(MetadataSource.FALLBACK)
    }

    @Test
    fun `未知模型保留原始远端 ID 与 unknown 类型`() {
        val resolved = resolver.resolve("vendor-new-reasoning-x")

        assertThat(resolved.displayName).isEqualTo("vendor-new-reasoning-x")
        assertThat(resolved.canonicalModelId).isNull()
        assertThat(resolved.sourceByField["canonicalModelId"])
            .isEqualTo(MetadataSource.FALLBACK)
        assertThat(resolved.workload).isEqualTo(ModelWorkload.UNKNOWN)
        assertThat(resolved.capabilities[ModelCapability.REASONING])
            .isEqualTo(SupportState.UNKNOWN)
        assertThat(resolved.capabilities.keys).containsExactlyElementsIn(ModelCapability.values().toSet())
        assertThat(resolved.sourceByField["displayName"]).isEqualTo(MetadataSource.FALLBACK)
        assertThat(resolved.sourceByField["capabilities.REASONING"])
            .isEqualTo(MetadataSource.FALLBACK)
    }

    @Test
    fun `未知模型逐字段合并保持提供商未被用户覆盖的字段`() {
        val resolved = resolver.resolve(
            remoteModelId = "model-x",
            providerMetadata = metadata(
                displayName = "Provider X",
                workload = ModelWorkload.GENERATIVE_TEXT,
                capabilities = mapOf(ModelCapability.REASONING to SupportState.SUPPORTED),
                contextTokens = 16_384,
                outputTokens = 2_048,
            ),
            userOverride = override(
                displayName = "User X",
                outputTokens = 4_096,
            ),
        )

        assertThat(resolved.displayName).isEqualTo("User X")
        assertThat(resolved.workload).isEqualTo(ModelWorkload.GENERATIVE_TEXT)
        assertThat(resolved.capabilities[ModelCapability.REASONING]).isEqualTo(SupportState.SUPPORTED)
        assertThat(resolved.contextTokens).isEqualTo(16_384)
        assertThat(resolved.outputTokens).isEqualTo(4_096)
        assertThat(resolved.sourceByField["workload"]).isEqualTo(MetadataSource.PROVIDER)
        assertThat(resolved.sourceByField["outputTokens"]).isEqualTo(MetadataSource.USER)
    }

    @Test
    fun `双冒号稳定ID不再作为远端ID拆解而models前缀仍仅用于查找`() {
        val resolved = resolver.resolve("default::models/MINIMAX-M2.7-HIGHSPEED")

        assertThat(resolved.canonicalModelId).isNull()
        assertThat(resolved.remoteModelId).isEqualTo("default::models/MINIMAX-M2.7-HIGHSPEED")
        assertThat(resolver.resolve("models/MINIMAX-M2.7-HIGHSPEED").canonicalModelId)
            .isEqualTo("minimax/MiniMax-M2.7-highspeed")
        assertThat(normalizeRemoteModelId("models/MiniMax-M2.7-Highspeed"))
            .isEqualTo("minimax-m2.7-highspeed")
    }

    @Test
    fun `全部精确覆盖保留冻结的名称与家族`() {
        val expected = listOf(
            OverrideExpectation(
                remoteId = "deepseek-v4-flash",
                canonicalId = "deepseek/deepseek-v4-flash",
                displayName = "DeepSeek V4 Flash",
                familyName = "DeepSeek V4",
                reasoning = SupportState.SUPPORTED,
            ),
            OverrideExpectation(
                remoteId = "minimax-m3",
                canonicalId = "minimax/MiniMax-M3",
                displayName = "MiniMax M3",
                familyName = "MiniMax M3",
                reasoning = SupportState.SUPPORTED,
            ),
            OverrideExpectation(
                remoteId = "minimax-m2.7-highspeed",
                canonicalId = "minimax/MiniMax-M2.7-highspeed",
                displayName = "MiniMax M2.7 Highspeed",
                familyName = "MiniMax M2.7",
                reasoning = SupportState.UNKNOWN,
            ),
            OverrideExpectation(
                remoteId = "sensenova-6.7-flash-lite",
                canonicalId = "sensenova/sensenova-6.7-flash-lite",
                displayName = "SenseNova 6.7 Flash Lite",
                familyName = "SenseNova 6.7",
                reasoning = SupportState.UNKNOWN,
            ),
        )

        expected.forEach { expectation ->
            val resolved = resolver.resolve(expectation.remoteId)
            assertThat(resolved.canonicalModelId).isEqualTo(expectation.canonicalId)
            assertThat(resolved.displayName).isEqualTo(expectation.displayName)
            assertThat(resolved.familyName).isEqualTo(expectation.familyName)
            assertThat(resolved.capabilities[ModelCapability.REASONING])
                .isEqualTo(expectation.reasoning)
            assertThat(resolved.sourceByField["canonicalModelId"])
                .isEqualTo(MetadataSource.INFINITY_OVERRIDE)
            assertThat(resolved.sourceByField["capabilities.REASONING"])
                .isEqualTo(MetadataSource.INFINITY_OVERRIDE)
        }
    }

    @Test
    fun `INFINITY exact 跨层优先级不依赖输入顺序`() {
        val infinity = record(
            canonicalId = "infinity/shared",
            displayName = "Infinity Deck Winner",
            workload = ModelWorkload.GENERATIVE_TEXT,
            source = MetadataSource.INFINITY_OVERRIDE,
        )
        val modelsDev = record(
            canonicalId = "models-dev/shared",
            displayName = "Models Dev",
            workload = ModelWorkload.EMBEDDING,
            source = MetadataSource.MODELS_DEV,
        )

        listOf(
            listOf(infinity, modelsDev),
            listOf(modelsDev, infinity),
        ).forEach { records ->
            val resolved = ModelMetadataResolver(records).resolve("shared-model")
            assertThat(resolved.displayName).isEqualTo("Infinity Deck Winner")
            assertThat(resolved.workload).isEqualTo(ModelWorkload.GENERATIVE_TEXT)
            assertThat(resolved.sourceByField["displayName"])
                .isEqualTo(MetadataSource.INFINITY_OVERRIDE)
            assertThat(resolved.diagnostics).isEmpty()
        }
    }

    @Test
    fun `集合构造保留 INFINITY 短 alias 并压过 MODELS_DEV`() {
        val modelsDev = record(
            canonicalId = "models-dev/deepseek-v4-flash",
            displayName = "Models Dev DeepSeek",
            workload = ModelWorkload.EMBEDDING,
            source = MetadataSource.MODELS_DEV,
            exactAliases = setOf("deepseek-v4-flash"),
        )
        val combined = INFINITY_EXACT_MODEL_OVERRIDES.values + modelsDev

        val resolved = ModelMetadataResolver(combined).resolve("deepseek-v4-flash")

        assertThat(resolved.displayName).isEqualTo("DeepSeek V4 Flash")
        assertThat(resolved.canonicalModelId).isEqualTo("deepseek/deepseek-v4-flash")
        assertThat(resolved.sourceByField["displayName"])
            .isEqualTo(MetadataSource.INFINITY_OVERRIDE)
        assertThat(resolved.diagnostics).isEmpty()
    }

    @Test
    fun `精确覆盖保留来源与生成工作负载`() {
        val resolved = resolver.resolve("deepseek-v4-flash")

        assertThat(resolved.workload).isEqualTo(ModelWorkload.GENERATIVE_TEXT)
        assertThat(resolved.sourceByField["displayName"])
            .isEqualTo(MetadataSource.INFINITY_OVERRIDE)
        assertThat(resolved.sourceByField["workload"])
            .isEqualTo(MetadataSource.INFINITY_OVERRIDE)
    }

    @Test
    fun `同 canonical 的冲突精确记录返回 fallback unknown 与诊断`() {
        val ambiguousResolver = ModelMetadataResolver(
            listOf(
                record(
                    canonicalId = "shared-canonical",
                    displayName = "First",
                    workload = ModelWorkload.GENERATIVE_TEXT,
                ),
                record(
                    canonicalId = "shared-canonical",
                    displayName = "Second",
                    workload = ModelWorkload.EMBEDDING,
                ),
            ),
        )

        val resolved = ambiguousResolver.resolve("shared-model")

        assertThat(resolved.displayName).isEqualTo("shared-model")
        assertThat(resolved.sourceByField["displayName"]).isEqualTo(MetadataSource.FALLBACK)
        assertThat(resolved.workload).isEqualTo(ModelWorkload.UNKNOWN)
        assertThat(resolved.sourceByField["workload"]).isEqualTo(MetadataSource.FALLBACK)
        assertThat(resolved.diagnostics).contains("ambiguous_exact_match")
    }

    @Test
    fun `构造时复制可变 alias 与 capability 集合`() {
        val aliases = mutableSetOf("mutable-alias")
        val capabilities = mutableMapOf(ModelCapability.REASONING to SupportState.SUPPORTED)
        val mutableRecord = record(
            canonicalId = "mutable/model",
            displayName = "Mutable Model",
            workload = ModelWorkload.GENERATIVE_TEXT,
            exactAliases = aliases,
            capabilities = capabilities,
        )
        val mutableResolver = ModelMetadataResolver(listOf(mutableRecord))

        aliases.clear()
        aliases += "late-alias"
        capabilities[ModelCapability.REASONING] = SupportState.UNSUPPORTED
        capabilities[ModelCapability.TOOL_CALLING] = SupportState.SUPPORTED

        val resolved = mutableResolver.resolve("mutable-alias")
        val lateAlias = mutableResolver.resolve("late-alias")

        assertThat(resolved.displayName).isEqualTo("Mutable Model")
        assertThat(resolved.capabilities[ModelCapability.REASONING])
            .isEqualTo(SupportState.SUPPORTED)
        assertThat(resolved.capabilities[ModelCapability.TOOL_CALLING])
            .isEqualTo(SupportState.UNKNOWN)
        assertThat(lateAlias.canonicalModelId).isNull()
    }

    @Test
    fun `作用域记录仅在明确sourceProviderId一致时参与且不冒充canonical`() {
        val scoped = record(
            canonicalId = "offering/model-x",
            displayName = "Scoped X",
            workload = ModelWorkload.GENERATIVE_TEXT,
            source = MetadataSource.OPENROUTER,
        ).copy(providerScope = "openrouter")
        val scopedResolver = ModelMetadataResolver(listOf(scoped))

        assertThat(scopedResolver.resolve("shared-model").canonicalModelId).isNull()
        assertThat(scopedResolver.resolve("shared-model", sourceProviderId = "other").offeringId).isNull()
        val resolved = scopedResolver.resolve("shared-model", sourceProviderId = "openrouter")
        assertThat(resolved.canonicalModelId).isNull()
        assertThat(resolved.offeringId).isEqualTo("offering/model-x")
        assertThat(resolved.displayName).isEqualTo("Scoped X")
    }

    @Test
    fun `同provider不同source按优先级逐字段补全且不跨provider`() {
        val modelsDev = record(
            canonicalId = "provider/model-x",
            displayName = "Models Dev X",
            workload = ModelWorkload.GENERATIVE_TEXT,
            source = MetadataSource.MODELS_DEV,
        ).copy(providerScope = "openai", contextTokens = 128_000)
        val liteLlm = record(
            canonicalId = "provider/model-x",
            displayName = "LiteLLM X",
            workload = ModelWorkload.UNKNOWN,
            source = MetadataSource.LITELLM,
        ).copy(providerScope = "openai", outputTokens = 8_000)
        val scopedResolver = ModelMetadataResolver(listOf(liteLlm, modelsDev))

        val resolved = scopedResolver.resolve("shared-model", sourceProviderId = "openai")
        assertThat(resolved.displayName).isEqualTo("Models Dev X")
        assertThat(resolved.contextTokens).isEqualTo(128_000)
        assertThat(resolved.outputTokens).isEqualTo(8_000)
        assertThat(resolved.canonicalModelId).isNull()
        assertThat(resolved.offeringId).isEqualTo("provider/model-x")
        assertThat(scopedResolver.resolve("shared-model", sourceProviderId = "other").offeringId).isNull()
    }

    @Test
    fun `同源global与scope分层不冲突且scoped false覆盖global true`() {
        val global = record(
            canonicalId = "base/model-x",
            displayName = "Global X",
            workload = ModelWorkload.GENERATIVE_TEXT,
            source = MetadataSource.MODELS_DEV,
            capabilities = mapOf(ModelCapability.TOOL_CALLING to SupportState.SUPPORTED),
        ).copy(contextTokens = 128_000)
        val scoped = record(
            canonicalId = "base/model-x",
            displayName = "Scoped X",
            workload = ModelWorkload.GENERATIVE_TEXT,
            source = MetadataSource.MODELS_DEV,
            capabilities = mapOf(ModelCapability.TOOL_CALLING to SupportState.UNSUPPORTED),
        ).copy(providerScope = "openai", outputTokens = 8_000)
        val scopedResolver = ModelMetadataResolver(listOf(global, scoped))

        val resolved = scopedResolver.resolve("shared-model", sourceProviderId = "openai")
        assertThat(resolved.diagnostics).isEmpty()
        assertThat(resolved.canonicalModelId).isEqualTo("base/model-x")
        assertThat(resolved.offeringId).isEqualTo("base/model-x")
        assertThat(resolved.displayName).isEqualTo("Scoped X")
        assertThat(resolved.contextTokens).isEqualTo(128_000)
        assertThat(resolved.outputTokens).isEqualTo(8_000)
        assertThat(resolved.capabilities[ModelCapability.TOOL_CALLING])
            .isEqualTo(SupportState.UNSUPPORTED)
    }

    @Test
    fun `scoped offering不从其它厂商同名global补额度或canonical`() {
        val otherVendor = record(
            canonicalId = "other-vendor/model-x",
            displayName = "Other Vendor X",
            workload = ModelWorkload.GENERATIVE_TEXT,
            source = MetadataSource.INFINITY_OVERRIDE,
        ).copy(contextTokens = 999_999)
        val scoped = record(
            canonicalId = "provider-a/model-x",
            displayName = "Provider A X",
            workload = ModelWorkload.GENERATIVE_TEXT,
            source = MetadataSource.OPENROUTER,
        ).copy(providerScope = "openrouter")
        val scopedResolver = ModelMetadataResolver(listOf(otherVendor, scoped))

        val resolved = scopedResolver.resolve("shared-model", sourceProviderId = "openrouter")
        assertThat(resolved.canonicalModelId).isNull()
        assertThat(resolved.offeringId).isEqualTo("provider-a/model-x")
        assertThat(resolved.displayName).isEqualTo("Provider A X")
        assertThat(resolved.contextTokens).isNull()
    }

    @Test
    fun `wire id含双冒号与大小写逐字符保留`() {
        val exact = record(
            canonicalId = "Vendor/Model-X",
            displayName = "Model X",
            workload = ModelWorkload.GENERATIVE_TEXT,
            exactAliases = setOf("Tenant::Model-X"),
        )
        val resolved = ModelMetadataResolver(listOf(exact)).resolve("Tenant::Model-X")

        assertThat(resolved.remoteModelId).isEqualTo("Tenant::Model-X")
        assertThat(resolved.canonicalModelId).isEqualTo("Vendor/Model-X")
    }

    @Test
    fun `路由前缀仅在ownedBy证据一致时生成查找候选`() {
        val exact = record(
            canonicalId = "vendor/model-x",
            displayName = "Model X",
            workload = ModelWorkload.GENERATIVE_TEXT,
            exactAliases = setOf("model-x"),
        )
        val prefixResolver = ModelMetadataResolver(listOf(exact))

        assertThat(prefixResolver.resolve("newapi/model-x").canonicalModelId).isNull()
        assertThat(prefixResolver.resolve("newapi/model-x", ownedBy = "other").canonicalModelId).isNull()
        val resolved = prefixResolver.resolve("newapi/model-x", ownedBy = "NEWAPI")
        assertThat(resolved.remoteModelId).isEqualTo("newapi/model-x")
        assertThat(resolved.canonicalModelId).isEqualTo("vendor/model-x")
    }

    @Test
    fun `当前五个网关ID只按明确证据精确匹配且未知版本不编造`() {
        val cases = listOf(
            Triple("newapi/deepseek-v4-flash", "NEWAPI", "deepseek/deepseek-v4-flash"),
            Triple("newapi/gemini-3.8-flash", "NEWAPI", null),
            Triple("newapi/sensenova-6.8-flash-lite", "NEWAPI", null),
            Triple("openai-chatgpt/gpt-5.6-luna", "OpenAI ChatGPT", null),
            Triple("newapi/MiniMax-M3", "NEWAPI", "minimax/MiniMax-M3"),
        )

        cases.forEach { (wireId, ownedBy, expectedCanonical) ->
            val resolved = resolver.resolve(wireId, ownedBy = ownedBy)
            assertThat(resolved.remoteModelId).isEqualTo(wireId)
            assertThat(resolved.canonicalModelId).isEqualTo(expectedCanonical)
        }
    }

    private fun metadata(
        displayName: String? = null,
        workload: ModelWorkload? = null,
        capabilities: Map<ModelCapability, SupportState> = emptyMap(),
        contextTokens: Int? = null,
        outputTokens: Int? = null,
    ) = ModelMetadataOverride(
        displayName = displayName,
        workload = workload,
        capabilities = capabilities,
        contextTokens = contextTokens,
        outputTokens = outputTokens,
    )

    private fun override(
        displayName: String? = null,
        outputTokens: Int? = null,
    ) = ModelMetadataOverride(displayName = displayName, outputTokens = outputTokens)

    private fun record(
        canonicalId: String,
        displayName: String,
        workload: ModelWorkload,
        source: MetadataSource = MetadataSource.INFINITY_OVERRIDE,
        exactAliases: Set<String> = setOf("shared-model"),
        capabilities: Map<ModelCapability, SupportState> = emptyMap(),
    ) = ModelMetadataRecord(
        canonicalModelId = canonicalId,
        exactAliases = exactAliases,
        displayName = displayName,
        familyName = null,
        workload = workload,
        capabilities = capabilities,
        contextTokens = null,
        inputTokens = null,
        outputTokens = null,
        knowledgeCutoff = null,
        source = source,
    )

    private data class OverrideExpectation(
        val remoteId: String,
        val canonicalId: String,
        val displayName: String,
        val familyName: String,
        val reasoning: SupportState,
    )
}
