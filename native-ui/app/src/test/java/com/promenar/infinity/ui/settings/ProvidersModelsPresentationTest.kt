package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.model.catalog.MetadataSource
import org.junit.Test
import java.util.Locale

class ProvidersModelsPresentationTest {
    private fun model(
        id: String,
        providerId: String? = "p1",
        enabled: Boolean = true,
        userEditedFields: Set<String> = emptySet(),
        sources: Map<String, MetadataSource> = emptyMap(),
    ) = ModelInfo(
        name = id,
        id = id,
        description = "",
        enabled = enabled,
        providerId = providerId,
        userEditedFields = userEditedFields,
        metadataSourceByField = sources,
    )

    private fun provider(id: String, enabled: Boolean) = ProviderListItem(
        id = id,
        name = id,
        baseUrl = "https://api.example.com",
        model = "",
        protocolType = ProtocolType.OpenAI_ChatCompletions,
        hasApiKey = true,
        hasVertexCredentials = false,
        enabled = enabled,
    )

    @Test
    fun `模型数按 providerId 分组统计已启用与全部`() {
        val counts = providerModelCounts(
            listOf(
                model("a", "p1", enabled = true),
                model("b", "p1", enabled = false),
                model("c", "p1", enabled = true),
                model("d", "p2", enabled = false),
            ),
        )
        assertThat(counts["p1"]).isEqualTo(ProviderModelCount(enabled = 2, total = 3))
        assertThat(counts["p2"]).isEqualTo(ProviderModelCount(enabled = 0, total = 1))
        assertThat(counts).doesNotContainKey("p3")
    }

    @Test
    fun `列表概况把不受支持的提供商计入总数但不计入已启用`() {
        val providers = listOf(true, false, true).mapIndexed { index, enabled -> provider("p$index", enabled) }
        assertThat(providerListSummary(providers, unsupportedCount = 2))
            .isEqualTo(ProviderListSummary(total = 5, enabled = 2))
        assertThat(providerListSummary(emptyList(), unsupportedCount = 0))
            .isEqualTo(ProviderListSummary(total = 0, enabled = 0))
    }

    @Test
    fun `主机名去掉协议路径与凭据并保留非默认端口`() {
        assertThat(providerHostLabel("https://api.openai.com/v1")).isEqualTo("api.openai.com")
        assertThat(providerHostLabel("https://user:secret@gpu-box.tail3f9c.ts.net:8443/v1"))
            .isEqualTo("gpu-box.tail3f9c.ts.net:8443")
        assertThat(providerHostLabel("api.deepseek.com/v1")).isEqualTo("api.deepseek.com")
        assertThat(providerHostLabel("  ")).isEmpty()
    }

    @Test
    fun `相对时间分档覆盖刚刚分钟小时天与日期`() {
        val now = 1_000_000_000_000L
        val minute = 60_000L
        assertThat(relativeTimeBucket(now - 59_000L, now)).isEqualTo(RelativeTimeBucket.JustNow)
        assertThat(relativeTimeBucket(now + 5 * minute, now)).isEqualTo(RelativeTimeBucket.JustNow)
        assertThat(relativeTimeBucket(now - 5 * minute, now)).isEqualTo(RelativeTimeBucket.Minutes(5))
        assertThat(relativeTimeBucket(now - 59 * minute, now)).isEqualTo(RelativeTimeBucket.Minutes(59))
        assertThat(relativeTimeBucket(now - 60 * minute, now)).isEqualTo(RelativeTimeBucket.Hours(1))
        assertThat(relativeTimeBucket(now - 24 * 60 * minute, now)).isEqualTo(RelativeTimeBucket.Days(1))
        assertThat(relativeTimeBucket(now - 6 * 24 * 60 * minute, now)).isEqualTo(RelativeTimeBucket.Days(6))
        val weekAgo = now - 7 * 24 * 60 * minute
        assertThat(relativeTimeBucket(weekAgo, now)).isEqualTo(RelativeTimeBucket.Date(weekAgo))
    }

    @Test
    fun `token 简写区分二进制与十进制整数`() {
        assertThat(formatTokenShort(131_072)).isEqualTo("128K")
        assertThat(formatTokenShort(1_048_576)).isEqualTo("1M")
        assertThat(formatTokenShort(200_000)).isEqualTo("200K")
        assertThat(formatTokenShort(128_000)).isEqualTo("128K")
        assertThat(formatTokenShort(1_500)).isEqualTo("1.5K")
        assertThat(formatTokenShort(999)).isEqualTo("999")
        assertThat(formatTokenShort(0)).isEmpty()
        assertThat(formatTokenShort(-1)).isEmpty()
        assertThat(formatTokenExact(128_000, Locale.US)).isEqualTo("128,000")
    }

    @Test
    fun `规格来源用户改过优先于解析来源且兜底不标来源`() {
        val m = model(
            "m",
            userEditedFields = setOf("contextLength"),
            sources = mapOf(
                "contextTokens" to MetadataSource.MODELS_DEV,
                "inputTokens" to MetadataSource.PROVIDER,
                "outputTokens" to MetadataSource.FAMILY,
                "knowledgeCutoff" to MetadataSource.FALLBACK,
            ),
        )
        assertThat(modelSpecSource(m, ModelSpecField.Context)).isEqualTo(ModelSpecSource.Manual)
        assertThat(modelSpecSource(m, ModelSpecField.Input)).isEqualTo(ModelSpecSource.Provider)
        assertThat(modelSpecSource(m, ModelSpecField.Output)).isEqualTo(ModelSpecSource.Inferred)
        assertThat(modelSpecSource(m, ModelSpecField.KnowledgeCutoff)).isNull()
        val catalog = model("c", sources = mapOf("outputTokens" to MetadataSource.LITELLM))
        assertThat(modelSpecSource(catalog, ModelSpecField.Output)).isEqualTo(ModelSpecSource.Catalog)
        assertThat(modelSpecSource(catalog, ModelSpecField.Context)).isNull()
    }

    @Test
    fun `切换类型只替换基础能力并保留附加能力`() {
        assertThat(capabilitiesForTypeChange(setOf("chat", "reasoning", "vision", "tools"), "image"))
            .containsExactly("image", "vision", "tools")
        assertThat(capabilitiesForTypeChange(setOf("embedding"), "reasoning"))
            .containsExactly("chat", "reasoning")
        assertThat(capabilitiesForTypeChange(setOf("tools"), "unknown-type"))
            .containsExactly("chat", "tools")
    }

    @Test
    fun `同步后编辑中的模型消失时退出编辑`() {
        val models = listOf(model("a"), model("b"))
        assertThat(reconcileEditingModelId("a", models)).isEqualTo("a")
        assertThat(reconcileEditingModelId("gone", models)).isNull()
        assertThat(reconcileEditingModelId(null, models)).isNull()
    }
}
