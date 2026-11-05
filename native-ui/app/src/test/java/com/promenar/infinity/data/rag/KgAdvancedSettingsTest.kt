package com.promenar.infinity.data.rag

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.usecase.RagConfigPersistence
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KgAdvancedSettingsTest {
    @Test fun `v2 标记存在时保留用户选择的 2048 并迁移其他旧默认值`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kg-v3-preserve-v2", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("kg_request_max_output_tokens_default_v2", true)
            .putInt("kg_request_max_output_tokens", 2048).putInt("kg_max_output_tokens", 32768)
            .putInt("kg_max_calls", 32).putInt("kg_max_duration_seconds", 180).commit()

        val migrated = KgAdvancedSettings.load(RagConfiguration(), prefs)

        assertThat(migrated.kgRequestMaxOutputTokens).isEqualTo(2048)
        assertThat(migrated.kgMaxOutputTokens).isEqualTo(262144)
        assertThat(migrated.kgMaxCalls).isEqualTo(64)
        assertThat(migrated.kgMaxDurationSeconds).isEqualTo(600)
        assertThat(prefs.getBoolean("kg_budget_defaults_v3", false)).isTrue()
    }

    @Test fun `v2 标记不存在时旧默认值 2048 迁移`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kg-v3-without-v2", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("kg_request_max_output_tokens", 2048).commit()

        assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgRequestMaxOutputTokens).isEqualTo(16384)
        assertThat(prefs.getBoolean("kg_budget_defaults_v3", false)).isTrue()
    }

    @Test fun `4096 在 v2 标记真假两种情况下均迁移`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kg-v3-4096", Context.MODE_PRIVATE)
        for (v2 in listOf(false, true)) {
            prefs.edit().clear().putBoolean("kg_request_max_output_tokens_default_v2", v2)
                .putInt("kg_request_max_output_tokens", 4096).commit()
            assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgRequestMaxOutputTokens).isEqualTo(16384)
            assertThat(prefs.getBoolean("kg_budget_defaults_v3", false)).isTrue()
        }
    }

    @Test fun `没有旧默认值时也完成 v3 标记并且之后不再迁移`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kg-v3-no-matches", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("kg_request_max_output_tokens", 1024).commit()

        assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgRequestMaxOutputTokens).isEqualTo(1024)
        assertThat(prefs.getBoolean("kg_budget_defaults_v3", false)).isTrue()
        prefs.edit().putInt("kg_request_max_output_tokens", 4096).putInt("kg_max_calls", 32).commit()
        val restored = KgAdvancedSettings.load(RagConfiguration(), prefs)
        assertThat(restored.kgRequestMaxOutputTokens).isEqualTo(4096)
        assertThat(restored.kgMaxCalls).isEqualTo(32)
    }

    @Test fun `用户保存预算后匹配旧默认值也不再迁移`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kg-v3-user-save", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        KgAdvancedSettings.save(RagConfiguration(kgRequestMaxOutputTokens = 2048, kgMaxOutputTokens = 32768,
            kgMaxCalls = 32, kgMaxDurationSeconds = 180), prefs)

        val restored = KgAdvancedSettings.load(RagConfiguration(), prefs)

        assertThat(restored.kgRequestMaxOutputTokens).isEqualTo(2048)
        assertThat(restored.kgMaxOutputTokens).isEqualTo(32768)
        assertThat(restored.kgMaxCalls).isEqualTo(32)
        assertThat(restored.kgMaxDurationSeconds).isEqualTo(180)
        assertThat(prefs.getBoolean("kg_budget_defaults_v3", false)).isTrue()
    }

    @Test fun `v3 只迁移匹配的旧默认值并保留其他设置`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kg-v3-defaults", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("kg_request_max_output_tokens", 4096)
            .putInt("kg_max_output_tokens", 32768).putInt("kg_max_calls", 32)
            .putInt("kg_max_duration_seconds", 181).commit()
        val migrated = KgAdvancedSettings.load(RagConfiguration(), prefs)
        assertThat(migrated.kgRequestMaxOutputTokens).isEqualTo(16384)
        assertThat(migrated.kgMaxOutputTokens).isEqualTo(262144)
        assertThat(migrated.kgMaxCalls).isEqualTo(64)
        assertThat(migrated.kgMaxDurationSeconds).isEqualTo(181)
        prefs.edit().putInt("kg_max_calls", 32).commit()
        assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgMaxCalls).isEqualTo(32)
        KgAdvancedSettings.save(migrated.copy(kgWindowChars = 32000, kgConcurrency = 8, kgGleaningPasses = 2), prefs)
        val restored = RagConfigPersistence(prefs).loadFullConfig()
        assertThat(restored.kgWindowChars).isEqualTo(32000)
        assertThat(restored.kgConcurrency).isEqualTo(8)
        assertThat(restored.kgGleaningPasses).isEqualTo(2)
        assertThat(prefs.getBoolean("kg_budget_defaults_v3", false)).isTrue()
        assertThat(prefs.getBoolean("kg_request_max_output_tokens_default_v2", false)).isTrue()
        prefs.edit().clear().putInt("kg_max_duration_seconds", 180).commit()
        assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgMaxDurationSeconds).isEqualTo(600)
        prefs.edit().clear().commit()
    }

    @Test fun `旧版随保存写入的默认单次输出 2048 一次性升到 16384，其后的用户选择保留`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kg-output-default-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgRequestMaxOutputTokens).isEqualTo(16384)

        // 旧版：保存任一高级参数都会写入当时的默认 2048。
        prefs.edit().clear().putInt("kg_request_max_output_tokens", 2048).commit()
        assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgRequestMaxOutputTokens).isEqualTo(16384)
        assertThat(prefs.getInt("kg_request_max_output_tokens", -1)).isEqualTo(16384)
        assertThat(prefs.getBoolean("kg_budget_defaults_v3", false)).isTrue()

        // 迁移之后用户再选 2048 时照常保留。
        KgAdvancedSettings.save(RagConfiguration(kgRequestMaxOutputTokens = 2048), prefs)
        assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgRequestMaxOutputTokens).isEqualTo(2048)

        // 旧版的其它自定义值不迁移。
        prefs.edit().clear().putInt("kg_request_max_output_tokens", 1024).commit()
        assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgRequestMaxOutputTokens).isEqualTo(1024)
        prefs.edit().clear().commit()
    }

    @Test fun `状态记忆抽取默认关闭且开关经真实持久层双向恢复`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("state-memory-settings-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertThat(RagConfigPersistence(prefs).loadFullConfig().memoryStateExtractionEnabled).isFalse()

        KgAdvancedSettings.save(RagConfiguration(memoryStateExtractionEnabled = true), prefs)
        assertThat(prefs.getBoolean("memory_state_extraction", false)).isTrue()
        assertThat(RagConfigPersistence(prefs).loadFullConfig().memoryStateExtractionEnabled).isTrue()

        KgAdvancedSettings.save(RagConfiguration(memoryStateExtractionEnabled = false), prefs)
        assertThat(RagConfigPersistence(prefs).loadFullConfig().memoryStateExtractionEnabled).isFalse()
        prefs.edit().clear().commit()
    }

    @Test fun `高级设置经真实持久层恢复检索与抽取预算`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kg-advanced-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val configured = RagConfiguration(kgRetrievalHops = 2, kgMaxSeeds = 5, kgMaxNodes = 40, kgMaxEdges = 10,
            kgContextMaxTokens = 1024, kgJitFallbackEnabled = false, kgJitMinSeeds = 4,
            kgMaxCalls = 8, kgMaxOutputTokens = 4096, kgRequestMaxOutputTokens = 512,
            kgMaxDurationSeconds = 30, kgLargeDocumentConfirmCalls = 3)
        KgAdvancedSettings.save(configured, prefs)
        val restored = RagConfigPersistence(prefs).loadFullConfig()
        assertThat(restored.kgRetrievalHops).isEqualTo(2)
        assertThat(restored.kgJitFallbackEnabled).isFalse()
        assertThat(restored.kgContextMaxTokens).isEqualTo(1024)
        assertThat(restored.kgMaxSeeds).isEqualTo(5)
        assertThat(restored.kgMaxNodes).isEqualTo(40)
        assertThat(restored.kgMaxEdges).isEqualTo(10)
        assertThat(restored.kgJitMinSeeds).isEqualTo(4)
        assertThat(KgExtractionBudget.from(restored)).isEqualTo(KgExtractionBudget(8, 4096, 512, 30000, 3))
        prefs.edit().clear().commit()
    }

    @Test fun `异常持久参数恢复到允许区间且默认策略可预测`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kg-advanced-invalid-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("kg_retrieval_hops", 100).putInt("kg_max_calls", -2).commit()
        val restored = KgAdvancedSettings.load(RagConfiguration(), prefs)
        assertThat(restored.kgRetrievalHops).isEqualTo(2)
        assertThat(restored.kgMaxCalls).isEqualTo(1)
        assertThat(restored.kgJitFallbackEnabled).isTrue()
        assertThat(restored.kgContextMaxTokens).isEqualTo(2048)
        prefs.edit().clear().commit()
    }
}
