package com.promenar.infinity.data.rag

import android.content.SharedPreferences

/** KG 高级参数在全局检索设置和应用构造器之间使用同一持久来源。 */
object KgAdvancedSettings {
    private const val REQUEST_OUTPUT_KEY = "kg_request_max_output_tokens"
    const val REQUEST_OUTPUT_DEFAULT = 16384
    private const val REQUEST_OUTPUT_MIGRATED_KEY = "kg_request_max_output_tokens_default_v2"
    private const val BUDGET_DEFAULTS_MIGRATED_KEY = "kg_budget_defaults_v3"

    private fun migrateBudgetDefaults(preferences: SharedPreferences) {
        if (preferences.getBoolean(BUDGET_DEFAULTS_MIGRATED_KEY, false)) return
        val oldRequestDefaults = if (preferences.getBoolean(REQUEST_OUTPUT_MIGRATED_KEY, false)) {
            setOf(4096)
        } else {
            setOf(2048, 4096)
        }
        val defaults = mapOf(
            REQUEST_OUTPUT_KEY to (oldRequestDefaults to REQUEST_OUTPUT_DEFAULT),
            "kg_max_output_tokens" to (setOf(32768) to 262144),
            "kg_max_calls" to (setOf(32) to 64),
            "kg_max_duration_seconds" to (setOf(180) to 600),
        )
        val changes = defaults.filter { (key, values) -> preferences.contains(key) && preferences.getInt(key, -1) in values.first }
        val editor = preferences.edit()
        changes.forEach { (key, values) -> editor.putInt(key, values.second) }
        if (changes.isNotEmpty()) editor.putBoolean(REQUEST_OUTPUT_MIGRATED_KEY, true)
        editor.putBoolean(BUDGET_DEFAULTS_MIGRATED_KEY, true).apply()
    }

    fun load(base: RagConfiguration, preferences: SharedPreferences): RagConfiguration = run {
        migrateBudgetDefaults(preferences)
        loadMigrated(base, preferences)
    }

    private fun loadMigrated(base: RagConfiguration, preferences: SharedPreferences): RagConfiguration = base.copy(
        kgRetrievalHops = preferences.getInt("kg_retrieval_hops", 1).coerceIn(1, 2),
        kgMaxSeeds = preferences.getInt("kg_max_seeds", 12).coerceIn(1, 32),
        kgMaxNodes = preferences.getInt("kg_max_nodes", 64).coerceIn(2, 256),
        kgMaxEdges = preferences.getInt("kg_max_edges", 24).coerceIn(1, 32),
        kgContextMaxTokens = preferences.getInt("kg_context_max_tokens", 2048).coerceIn(128, 16384),
        kgJitFallbackEnabled = preferences.getBoolean("kg_jit_fallback", true),
        kgSessionExtractionEnabled = preferences.getBoolean("kg_session_extraction", false),
        memoryStateExtractionEnabled = preferences.getBoolean("memory_state_extraction", false),
        kgJitMinSeeds = preferences.getInt("kg_jit_min_seeds", 2).coerceIn(1, 32),
        kgWindowChars = preferences.getInt("kg_window_chars", 16000).coerceIn(2000, 128000),
        kgConcurrency = preferences.getInt("kg_concurrency", 4).coerceIn(1, 8),
        kgGleaningPasses = preferences.getInt("kg_gleaning_passes", 1).coerceIn(0, 2),
        kgMaxCalls = preferences.getInt("kg_max_calls", 64).coerceIn(1, 1024),
        kgMaxOutputTokens = preferences.getInt("kg_max_output_tokens", 262144).coerceIn(256, 1048576),
        kgRequestMaxOutputTokens = preferences.getInt(REQUEST_OUTPUT_KEY, REQUEST_OUTPUT_DEFAULT).coerceIn(128, 65536),
        kgMaxDurationSeconds = preferences.getInt("kg_max_duration_seconds", 600).coerceIn(5, 3600),
        kgLargeDocumentConfirmCalls = preferences.getInt("kg_large_document_confirm_calls", 16).coerceIn(1, 128),
    )

    fun save(config: RagConfiguration, preferences: SharedPreferences) {
        preferences.edit()
            .putInt("kg_retrieval_hops", config.kgRetrievalHops)
            .putInt("kg_max_seeds", config.kgMaxSeeds)
            .putInt("kg_max_nodes", config.kgMaxNodes)
            .putInt("kg_max_edges", config.kgMaxEdges)
            .putInt("kg_context_max_tokens", config.kgContextMaxTokens)
            .putBoolean("kg_jit_fallback", config.kgJitFallbackEnabled)
            .putBoolean("kg_session_extraction", config.kgSessionExtractionEnabled)
            .putBoolean("memory_state_extraction", config.memoryStateExtractionEnabled)
            .putInt("kg_jit_min_seeds", config.kgJitMinSeeds)
            .putInt("kg_window_chars", config.kgWindowChars.coerceIn(2000, 128000))
            .putInt("kg_concurrency", config.kgConcurrency.coerceIn(1, 8))
            .putInt("kg_gleaning_passes", config.kgGleaningPasses.coerceIn(0, 2))
            .putInt("kg_max_calls", config.kgMaxCalls)
            .putInt("kg_max_output_tokens", config.kgMaxOutputTokens)
            .putInt(REQUEST_OUTPUT_KEY, config.kgRequestMaxOutputTokens)
            .putBoolean(REQUEST_OUTPUT_MIGRATED_KEY, true)
            .putBoolean(BUDGET_DEFAULTS_MIGRATED_KEY, true)
            .putInt("kg_max_duration_seconds", config.kgMaxDurationSeconds)
            .putInt("kg_large_document_confirm_calls", config.kgLargeDocumentConfirmCalls)
            .apply()
    }
}
