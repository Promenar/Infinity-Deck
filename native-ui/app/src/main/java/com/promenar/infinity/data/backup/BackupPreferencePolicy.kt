package com.promenar.infinity.data.backup

internal object BackupPreferencePolicy {
    const val VOICE_NAMESPACE = "recording_voiceprints"
    const val VOICE_ENTRY = "voiceprints.json"

    private val deniedFragments = listOf(
        "api_key", "apikey", "authorization", "cookie", "token", "password", "passwd", "secret",
        "private_key", "privatekey", "service_account", "serviceaccount", "vertex_json", "vertexjson",
        "credential", "webdav_pass", "webdavpass", "automatic_backup_password", "automaticbackuppassword",
        "tavily_key", "tavilykey", "embedding_key", "embeddingkey",
    )

    private val exactAllowed = mapOf(
        "recording_hotwords" to setOf("library"),
        VOICE_NAMESPACE to setOf("library"),
        "provider" to setOf(
            "protocol_id", "protocol_id_name", "base_url", "model", "provider_name",
            "embedding_base_url", "embedding_model",
        ),
        "settings" to setOf(
            "comfy_configuration_v1",
            "language", "theme_mode", "theme_color_source", "haptic_enabled", "haptic_status_enabled", "loop_limit", "user_name", "user_avatar", "profile_show_brand",
            "task_token_limit", "tool_loop_repeat_limit", "stream_retry_limit",
            "extra_providers_count", "extra_providers_ids", "all_models", "enabled_models", "all_models_order",
            "preset_summary_model", "preset_image_model", "preset_embedding_model", "preset_rerank_model",
            "preset_skills_migrated_v3",
            "media_channel_order_image", "media_channel_order_video", "media_channel_order_audio",
            "enabled_skills",
            "known_builtin_skills",
            "agent_skills_enabled",
            "skill_store_sources", "skill_store_records", "skill_store_auto_check",
            "long_term_memory_enabled",
            "pet_enabled", "pet_default_id", "pet_model_map", "pet_lines_enabled", "pet_lines_model", "pet_video_enabled",
            "pet_fling_gesture_enabled", "pet_motion_sensor_enabled", "pet_haptics_enabled", "pet_perch_kind", "pet_blow_enabled",
            "suppressed_provider_models",
            "default_model", "default_temperature", "default_top_p", "default_max_tokens",
            "model_timeout_llm_seconds", "model_timeout_image_seconds",
            "model_timeout_embedding_seconds", "model_timeout_rerank_seconds",
        ),
        "search" to setOf(
            "web_search_enabled", "search_engine", "searxng_url", "search_depth", "result_count",
            "include_domains", "exclude_domains",
        ),
        "rag" to setOf(
            "doc_chunk_size", "chunk_overlap", "memory_chunk_size", "summary_template", "current_preset",
            "memory_limit", "memory_threshold", "doc_limit", "doc_threshold", "enable_rerank",
            "rerank_top_k", "rerank_final_k", "rerank_max_per_call", "enable_query_rewrite",
            "query_rewrite_strategy", "query_rewrite_count", "query_rewrite_model", "enable_hybrid_search",
            "hybrid_alpha", "hybrid_bm25_boost", "enable_memory", "enable_docs", "enable_kg", "kg_model",
            "kg_prompt", "kg_free_mode", "kg_extraction_timeout",
            "kg_retrieval_hops", "kg_max_seeds", "kg_max_nodes", "kg_max_edges", "kg_context_max_tokens",
            "kg_jit_fallback", "kg_session_extraction", "memory_state_extraction", "kg_jit_min_seeds", "kg_max_calls", "kg_max_output_tokens",
            "kg_request_max_output_tokens", "kg_request_max_output_tokens_default_v2", "kg_budget_defaults_v3", "kg_max_duration_seconds", "kg_large_document_confirm_calls",
            "kg_window_chars", "kg_concurrency", "kg_gleaning_passes",
            "cost_strategy", "show_retrieval_progress",
            "show_retrieval_details", "track_retrieval_metrics", "embed_dimension",
            "max_embed_tokens_per_call", "embedding_base_url", "embedding_model", "rerank_base_url",
        ),
        "ui" to setOf(
            "has_shown_welcome", "language", "theme_mode", "theme_color_source", "haptic_enabled",
            "loop_limit", "user_name",
        ),
        "backup" to setOf("webdav_enabled", "webdav_url", "webdav_user", "last_backup_time"),
    )

    private val dynamicAllowed = mapOf(
        "settings" to listOf(
            Regex("extra_provider_[0-9]+_(id|name|protocol|type|base_url|model|enabled)"),
            Regex(
                "model_info_.+_(name|type|context|provider|provider_id|remote_model_id|maxoutput|cutoff|caps|" +
                    "input|family|canonical_id|chat_endpoint|auto_fingerprint|user_edited_fields|" +
                    "metadata_sources|metadata_diagnostics|provider_owned_by|source_provider_id|" +
                    "provider_display_name|provider_workload|provider_context|provider_input|provider_output|" +
                    "provider_capabilities|media_generation)"
            ),
        ),
        "provider" to listOf(
            Regex("provider_.+_(id|name|protocol|type|base_url|model|enabled)"),
        ),
    )

    // 权限询问、本地执行能力与一次性提示属于设备状态，不能随备份迁移到能力或授权不同的新设备。
    private val exactDenied = mapOf(
        "provider" to setOf("last_local_model"),
        "recording_defaults" to setOf("mode", "fallback"),
        "local_model_revision" to setOf("name", "sha256"),
        // 连接探测结果与模型同步时间仅描述当前设备，不随备份迁移。
        "provider_status" to setOf("statuses"),
        "settings" to setOf(
            "local_models_enabled", "local_auto_load", "last_local_model", "unlimited_auto_confirmed",
            "termux_integration_enabled", "ssh_agent_host_management", "ssh_terminal_idle_disconnect", "script_approve_writes",
            "skill_store_last_checked",
            "rolled_back_summary_usage_v1", "embedding_setup_hint_shown",
            "pet_dismissed", "pet_dismiss_hint_count", "pet_perch_x_fraction", "pet_perch_y_dp", "pet_mood_value", "pet_mood_at_millis",
            // 资料页长期记忆“新”标记的上次查看时间与侧栏固定文件夹（引用本机位置 id）只描述当前设备。
            "library_memories_last_seen", "file_manager_pins", "legacy_recycle_cleanup_complete",
        ),
        "generation_notification_permission" to setOf("post_notifications_asked"),
        // restore_tx_id 是向导发起恢复的本机标记，只在下次启动结算引导时读取。
        "onboarding" to setOf(
            "step", "language", "provider_id", "model_id", "agent_id", "session_id", "restore_tx_id",
        ),
        "catalog_update_schedule" to setOf("checkedat"),
        "kg_usage" to setOf("kg_usage_ledger_v1"),
        "pet_usage" to setOf("kg_usage_ledger_v1"),
        // 会话台词记忆只保留在本机。
        "infinity_pet_memory" to setOf("state"),
        // 本机每日会话图谱抽取统计，换设备后重新计数。
        "session_graph_stats" to setOf("day", "turns", "calls"),
        // 检视器边缘条的拖放位置随本机屏幕尺寸而定，不随备份迁移。
        "infinity_ui_state" to setOf("chat_inspector_strip_lift"),
    )

    // 只为读取旧备份时识别并丢弃；不得继续导出或写回设备。
    private val exactRetired = mapOf(
        "backup" to setOf("auto_backup"),
        "settings" to setOf("tool_context_ratio_percent", "tool_context_keep_rounds", "script_lan_hosts", "mcp_allow_loopback_http"),
        // Tailscale 设备白名单已删除；该键原在本机独立的 infinity_tailnet 文件中，启动时清除。
        "infinity_tailnet" to setOf("tailnet_allowed_peers"),
        "rag" to setOf(
            "jit_max_chunks",
            "kg_domain_auto",
            "enable_incremental_hash",
            "enable_local_preprocess",
        ),
    )

    fun isAllowed(namespace: String, key: String): Boolean {
        val normalizedNamespace = normalizeNamespace(namespace)
        val normalizedKey = normalize(key)
        if (sessionHeaderType(namespace, key) != null) return true
        if (normalizedNamespace == "settings" && Regex("model_info_.+_media_generation").matches(normalizedKey)) return true
        if (isDenied(normalizedKey)) return false
        return normalizedKey in exactAllowed[normalizedNamespace].orEmpty() ||
            dynamicAllowed[normalizedNamespace].orEmpty().any { it.matches(normalizedKey) }
    }

    /** 稳定 ID 可以包含标点或 token 等字样，仅按完整键形状识别这三类配置。 */
    fun sessionHeaderType(namespace: String, key: String): PreferenceValueType? {
        if (normalizeNamespace(namespace) != "settings") return null
        return when {
            Regex("session_header_provider_.+_enabled").matches(key) -> PreferenceValueType.BOOLEAN
            Regex("session_header_provider_.+_name").matches(key) -> PreferenceValueType.STRING
            Regex("session_header_model_.+").matches(key) -> PreferenceValueType.STRING
            else -> null
        }
    }

    fun isKnown(namespace: String, key: String): Boolean {
        val normalizedNamespace = normalizeNamespace(namespace)
        val normalizedKey = normalize(key)
        return isAllowed(namespace, key) ||
            normalizedKey in exactDenied[normalizedNamespace].orEmpty() ||
            normalizedKey in exactRetired[normalizedNamespace].orEmpty() ||
            isDenied(key)
    }

    fun isRetired(namespace: String, key: String): Boolean {
        val normalizedNamespace = normalizeNamespace(namespace)
        val normalizedKey = normalize(key)
        return normalizedKey in exactRetired[normalizedNamespace].orEmpty()
    }

    fun isDenied(key: String): Boolean {
        val normalized = normalize(key)
        // 数字预算逐键列举，不能把认证令牌的通用拒绝规则放宽。
        if (normalized in setOf("task_token_limit", "kg_context_max_tokens", "kg_max_output_tokens", "kg_request_max_output_tokens",
            "kg_request_max_output_tokens_default_v2")) return false
        return deniedFragments.any(normalized::contains)
    }

    fun normalizeNamespace(value: String): String = when (normalize(value)) {
        "nexara_provider", "infinity_provider", "provider", "model" -> "provider"
        "nexara_provider_status", "infinity_provider_status", "provider_status" -> "provider_status"
        "nexara_settings", "infinity_settings", "settings" -> "settings"
        "rag_settings", "rag" -> "rag"
        "nexara_search", "infinity_search", "search" -> "search"
        "nexara_prefs", "infinity_prefs", "ui" -> "ui"
        "nexara_backup_settings", "infinity_backup_settings", "backup" -> "backup"
        "nexara_onboarding", "infinity_onboarding", "onboarding" -> "onboarding"
        else -> normalize(value)
    }

    private fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "_")
}
