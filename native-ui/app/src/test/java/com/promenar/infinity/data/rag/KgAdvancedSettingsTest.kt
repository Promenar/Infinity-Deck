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
    @Test fun `默认输出上限调整但已保存值保持不变`() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kg-output-default-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgRequestMaxOutputTokens).isEqualTo(4096)
        prefs.edit().putInt("kg_request_max_output_tokens", 2048).commit()
        assertThat(KgAdvancedSettings.load(RagConfiguration(), prefs).kgRequestMaxOutputTokens).isEqualTo(2048)
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
