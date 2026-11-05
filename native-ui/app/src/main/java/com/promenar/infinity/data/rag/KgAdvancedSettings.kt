package com.promenar.infinity.data.rag

import android.content.SharedPreferences

/** KG 高级参数在全局检索设置和应用构造器之间使用同一持久来源。 */
object KgAdvancedSettings {
    fun load(base: RagConfiguration, preferences: SharedPreferences): RagConfiguration = base.copy(
        kgRetrievalHops = preferences.getInt("kg_retrieval_hops", 1).coerceIn(1, 2),
        kgMaxSeeds = preferences.getInt("kg_max_seeds", 12).coerceIn(1, 32),
        kgMaxNodes = preferences.getInt("kg_max_nodes", 64).coerceIn(2, 256),
        kgMaxEdges = preferences.getInt("kg_max_edges", 24).coerceIn(1, 32),
        kgContextMaxTokens = preferences.getInt("kg_context_max_tokens", 2048).coerceIn(128, 16384),
        kgJitFallbackEnabled = preferences.getBoolean("kg_jit_fallback", true),
        kgSessionExtractionEnabled = preferences.getBoolean("kg_session_extraction", false),
        memoryStateExtractionEnabled = preferences.getBoolean("memory_state_extraction", false),
        kgJitMinSeeds = preferences.getInt("kg_jit_min_seeds", 2).coerceIn(1, 32),
        kgMaxCalls = preferences.getInt("kg_max_calls", 32).coerceIn(1, 1024),
        kgMaxOutputTokens = preferences.getInt("kg_max_output_tokens", 32768).coerceIn(256, 1048576),
        kgRequestMaxOutputTokens = preferences.getInt("kg_request_max_output_tokens", 4096).coerceIn(128, 16384),
        kgMaxDurationSeconds = preferences.getInt("kg_max_duration_seconds", 180).coerceIn(5, 3600),
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
            .putInt("kg_max_calls", config.kgMaxCalls)
            .putInt("kg_max_output_tokens", config.kgMaxOutputTokens)
            .putInt("kg_request_max_output_tokens", config.kgRequestMaxOutputTokens)
            .putInt("kg_max_duration_seconds", config.kgMaxDurationSeconds)
            .putInt("kg_large_document_confirm_calls", config.kgLargeDocumentConfirmCalls)
            .apply()
    }
}
