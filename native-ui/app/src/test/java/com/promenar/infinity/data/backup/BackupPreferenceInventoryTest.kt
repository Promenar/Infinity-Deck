package com.promenar.infinity.data.backup

import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class BackupPreferenceInventoryTest {
    @Test
    fun `废弃网络限制键仅作为旧包识别项且不参与导出`() {
        listOf("settings" to "script_lan_hosts", "settings" to "mcp_allow_loopback_http", "infinity_tailnet" to "tailnet_allowed_peers").forEach { (namespace, key) ->
            assertThat(BackupPreferencePolicy.isRetired(namespace, key)).isTrue()
            assertThat(BackupPreferencePolicy.isAllowed(namespace, key)).isFalse()
        }
    }

    @Test
    fun `提供商连接与同步状态显式登记为本机状态且不参与备份`() {
        assertThat(BackupPreferencePolicy.isKnown("infinity_provider_status", "statuses")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("infinity_provider_status", "statuses")).isFalse()
        assertThat(BackupPreferencePolicy.isAllowed("provider", "statuses")).isFalse()
        assertThat(BackupPreferencePolicy.isAllowed("settings", "statuses")).isFalse()
    }

    @Test
    fun `资料页记忆查看时间与固定文件夹登记为本机状态且不参与备份`() {
        for (key in listOf("library_memories_last_seen", "file_manager_pins", "legacy_recycle_cleanup_complete")) {
            assertWithMessage(key).that(BackupPreferencePolicy.isKnown("infinity_settings", key)).isTrue()
            assertWithMessage(key).that(BackupPreferencePolicy.isAllowed("infinity_settings", key)).isFalse()
        }
    }

    @Test
    fun `检视器边缘条位置登记为本机状态且不参与备份`() {
        assertThat(BackupPreferencePolicy.isKnown("infinity_ui_state", "chat_inspector_strip_lift")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("infinity_ui_state", "chat_inspector_strip_lift")).isFalse()
    }

    @Test
    fun `长期记忆开关作为用户偏好参与备份且不放宽凭据限制`() {
        assertThat(BackupPreferencePolicy.isKnown("infinity_settings", "long_term_memory_enabled")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("infinity_settings", "long_term_memory_enabled")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("infinity_settings", "long_term_memory_api_key")).isFalse()
    }

    @Test
    fun `宠物总开关作为用户偏好参与备份`() {
        for (key in listOf("pet_enabled", "pet_default_id", "pet_model_map", "pet_lines_enabled", "pet_lines_model", "pet_video_enabled")) {
            assertThat(BackupPreferencePolicy.isKnown("infinity_settings", key)).isTrue()
            assertThat(BackupPreferencePolicy.isAllowed("infinity_settings", key)).isTrue()
        }
    }

    @Test
    fun `工具上下文旧偏好只读识别且快速模型偏好继续参与备份`() {
        for (key in listOf("tool_context_ratio_percent", "tool_context_keep_rounds")) {
            assertWithMessage(key).that(BackupPreferencePolicy.isKnown("infinity_settings", key)).isTrue()
            assertWithMessage(key).that(BackupPreferencePolicy.isRetired("infinity_settings", key)).isTrue()
            assertWithMessage(key).that(BackupPreferencePolicy.isAllowed("infinity_settings", key)).isFalse()
        }
        assertThat(BackupPreferencePolicy.isAllowed("infinity_settings", "preset_summary_model")).isTrue()
    }

    @Test
    fun `脚本局域网和写入授权策略不得随备份迁移`() {
        for (key in listOf("script_lan_hosts", "script_approve_writes")) {
            assertThat(BackupPreferencePolicy.isKnown("infinity_settings", key)).isTrue()
            assertThat(BackupPreferencePolicy.isAllowed("infinity_settings", key)).isFalse()
        }
    }

    @Test
    fun `all literal production preference keys are explicitly allowed or denied`() {
        val sourceRoot = Path.of("app/src/main/java/com/promenar/infinity")
        val files = Files.walk(sourceRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }
        val preferenceDeclaration = Regex(
            "(?:val|var)\\s+(\\w+)[^=]*=?[\\s\\S]{0,160}?getSharedPreferences\\(\\s*\"([^\"]+)\""
        )
        val keyCall = Regex(
            "(\\w+)(?:\\s*\\.edit\\(\\))?\\s*\\.(?:(?:get|put)(?:String|StringSet|Boolean|Int|Long|Float)|contains|remove)" +
                "\\(\\s*\"([^\"]+)\""
        )
        val injectedNamespaces = mapOf(
            "data/local/ProviderStatusStore.kt#preferences" to "infinity_provider_status",
            "data/local/SessionHeaderPreferences.kt#preferences" to "infinity_settings",
            "data/memory/LongTermMemoryAccess.kt#settings" to "infinity_settings",
            "data/remote/mcp/McpConnectionSettings.kt#preferences" to "infinity_settings",
            "data/termux/TermuxRuntime.kt#settings" to "infinity_settings",
            "domain/usecase/AgentConfigResolver.kt#globalPrefs" to "infinity_settings",
            "domain/usecase/RagConfigPersistence.kt#prefs" to "rag_settings",
            "data/rag/KgAdvancedSettings.kt#preferences" to "rag_settings",
            "data/rag/KgUsageStore.kt#preferences" to "kg_usage",
            "data/rag/SessionGraphExtraction.kt#preferences" to "session_graph_stats",
            "data/rag/EmbeddingSetupHint.kt#prefs" to "infinity_settings",
            "data/repository/RolledBackSummaryUsageStore.kt#preferences" to "infinity_settings",
            "data/pet/PetPreferenceStore.kt#preferences" to "infinity_settings",
            "ui/settings/BackupViewModel.kt#prefs" to "infinity_backup_settings",
            "ui/settings/SettingsViewModel.kt#prefs" to "infinity_settings",
            "ui/theme/ThemePreferenceStore.kt#preferences" to "infinity_settings",
            "ui/developer/DeveloperMode.kt#prefs" to "infinity_settings",
            "data/script/ScriptSettings.kt#preferences" to "infinity_settings",
            "data/network/AgentNetworkSettings.kt#preferences" to "infinity_settings",
            "ui/settings/LocalModelsViewModel.kt#prefs" to "infinity_settings",
            "ui/settings/SshSettingsScreen.kt#settingsPrefs" to "infinity_settings",
            "data/ssh/SshUserPrompts.kt#settings" to "infinity_settings",
            "ui/settings/SearchConfigViewModel.kt#prefs" to "infinity_search",
            "ui/rag/RagViewModel.kt#prefs" to "rag_settings",
            "ui/rag/RagViewModel.kt#settingsPrefs" to "infinity_settings",
            "ui/chat/ChatScreen.kt#notificationPermissionPrefs" to
                "generation_notification_permission",
            "ui/chat/ChatRoute.kt#permissionPrefs" to
                "generation_notification_permission",
            "ui/chat/ChatViewModel.kt#prefs" to "infinity_settings",
            "data/generation/DefaultChatGenerationRuntime.kt#settings" to "infinity_settings",
            "data/generation/PreparedPromptBudgetGate.kt#settings" to "infinity_settings",
            "data/generation/ChatGenerationContentStrategy.kt#settings" to "infinity_settings",
            "data/skills/AgentSkillStore.kt#settings" to "infinity_settings",
            "onboarding/OnboardingStateStore.kt#preferences" to "infinity_onboarding",
            "MainActivity.kt#prefs" to "infinity_prefs",
            "util/LocaleHelper.kt#prefs" to "infinity_settings",
            "ui/chat/manager/WebSearchContextProvider.kt#prefs" to "infinity_search",
            "ui/chat/manager/skills/WebSearchSkill.kt#prefs" to "infinity_search",
            "InfinityApplication.kt#prefs" to "infinity_provider",
            "data/manager/ProviderManager.kt#providerPrefs" to "infinity_provider",
            "data/manager/ProviderManager.kt#settingsPrefs" to "infinity_settings",
            "data/manager/ProviderManager.kt#searchPrefs" to "infinity_search",
            "data/manager/ProviderManager.kt#backupPrefs" to "infinity_backup_settings",
            "data/remote/ModelTimeoutPreferenceStore.kt#prefs" to "infinity_settings",
            "data/model/catalog/ModelCatalogUpdater.kt#prefs" to "catalog_update_schedule",
            // 内置 Tailscale 设置：独立偏好文件，不在备份命名空间内（键为常量，见 TailnetSettingsStoreTest）。
            "data/tailnet/TailnetSettings.kt#preferences" to "infinity_tailnet",
            // 已建索引的分块基线：随本机向量索引走，独立偏好文件，不在备份命名空间内（键为常量）。
            "ui/rag/KnowledgeBaseSettingsLogic.kt#prefs" to "rag_index_baseline",
            // 检视器边缘条位置：本机界面状态，独立偏好文件，不参与备份。
            "ui/chat/ChatInspector.kt#prefs" to "infinity_ui_state",
            // 资料页知识源与固定文件夹由 LibraryKnowledgeModule / DefaultFileManagerServices 注入 infinity_settings。
            "data/library/DefaultLibraryKnowledgeSource.kt#preferences" to "infinity_settings",
            "data/files/DefaultPinnedFolderStore.kt#preferences" to "infinity_settings",
            // 外部文档查看器“存入存储空间”上次用过的文件夹：本机界面状态，独立偏好文件，不参与备份。
            "ui/document/external/AppExternalDocumentTargets.kt#preferences" to "external_document_viewer",
        )
        val editorChain = Regex("(\\w+)\\s*\\.edit\\(\\)([\\s\\S]{0,2000}?)\\.apply\\(\\)")
        val editorPut = Regex("\\.put(?:String|StringSet|Boolean|Int|Long|Float)\\(\\s*\"([^\"]+)\"")
        val editorScope = Regex("(\\w+)\\s*\\.edit\\(\\)\\s*\\.apply\\s*\\{([\\s\\S]{0,2000}?)\\}")
        val scopePut = Regex("(?:^|[;\\s])put(?:String|StringSet|Boolean|Int|Long|Float)\\(\\s*\"([^\"]+)\"")
        val directCall = Regex(
            "getSharedPreferences\\(\\s*\"([^\"]+)\"[^)]*\\)\\s*(?:\\.edit\\(\\))?\\s*\\." +
                "(?:(?:get|put)(?:String|StringSet|Boolean|Int|Long|Float)|contains|remove)\\(\\s*\"([^\"]+)\""
        )

        files.forEach { file ->
            val text = Files.readAllBytes(file).toString(Charsets.UTF_8)
            val receiverNamespaces = preferenceDeclaration.findAll(text).associate {
                it.groupValues[1] to it.groupValues[2]
            }
            val typedPreferenceReceivers = Regex("(\\w+)\\s*:\\s*(?:android\\.content\\.)?SharedPreferences\\b")
                .findAll(text).map { it.groupValues[1] }.toSet()
            keyCall.findAll(text).forEach { match ->
                val receiver = match.groupValues[1]
                val key = match.groupValues[2]
                val representatives = listOf("extra_provider_0", "model_info_sample").map { prefix ->
                    key.replace("\${prefix}", prefix)
                        .replace("\${id}", "sample")
                        .replace("\${modelId}", "sample")
                }
                val relative = sourceRoot.relativize(file).toString()
                val declaredNamespace = receiverNamespaces[receiver] ?: injectedNamespaces["$relative#$receiver"]
                if (declaredNamespace == null) {
                    if (receiver.contains("pref", ignoreCase = true) || receiver in typedPreferenceReceivers) {
                        assertWithMessage("疑似 SharedPreferences receiver 未解析，必须显式登记: $file -> $receiver")
                            .fail()
                    }
                    return@forEach
                }
                val namespaces = setOf(declaredNamespace)
                val known = representatives.any { representative ->
                    namespaces.any { BackupPreferencePolicy.isKnown(it, representative) }
                }
                assertWithMessage("未知或 namespace 不匹配的生产偏好 key: $file -> $receiver.$key")
                    .that(known).isTrue()
            }
            editorChain.findAll(text).forEach { chain ->
                val receiver = chain.groupValues[1]
                val relative = sourceRoot.relativize(file).toString()
                val namespace = receiverNamespaces[receiver] ?: injectedNamespaces["$relative#$receiver"]
                assertWithMessage("edit/apply receiver 未解析，必须显式登记: $file -> $receiver")
                    .that(namespace).isNotNull()
                editorPut.findAll(chain.groupValues[2]).forEach { put ->
                    val key = put.groupValues[1]
                    val representatives = listOf("extra_provider_0", "model_info_sample").map { prefix ->
                        key.replace("\${prefix}", prefix)
                            .replace("\${id}", "sample")
                            .replace("\${modelId}", "sample")
                    }
                    assertWithMessage("未知 edit/apply 偏好 key: $file -> $receiver.${put.groupValues[1]}")
                        .that(representatives.any { BackupPreferencePolicy.isKnown(namespace!!, it) }).isTrue()
                }
            }
            editorScope.findAll(text).forEach { scope ->
                val receiver = scope.groupValues[1]
                val relative = sourceRoot.relativize(file).toString()
                val namespace = receiverNamespaces[receiver] ?: injectedNamespaces["$relative#$receiver"]
                assertWithMessage("edit().apply scope receiver 未解析，必须显式登记: $file -> $receiver")
                    .that(namespace).isNotNull()
                scopePut.findAll(scope.groupValues[2]).forEach { put ->
                    assertWithMessage("未知 edit().apply scope 偏好 key: $file -> $receiver.${put.groupValues[1]}")
                        .that(BackupPreferencePolicy.isKnown(namespace!!, put.groupValues[1])).isTrue()
                }
            }
            directCall.findAll(text).forEach { call ->
                assertWithMessage("未知直接 SharedPreferences 偏好 key: $file -> ${call.groupValues[1]}.${call.groupValues[2]}")
                    .that(BackupPreferencePolicy.isKnown(call.groupValues[1], call.groupValues[2])).isTrue()
            }
        }
    }

    @Test
    fun `inventory patterns cover multiline edit apply and injected receivers fail closed`() {
        val declaration = Regex(
            "(?:val|var)\\s+(\\w+)[^=]*=?[\\s\\S]{0,160}?getSharedPreferences\\(\\s*\"([^\"]+)\""
        )
        val calls = Regex(
            "(\\w+)(?:\\s*\\.edit\\(\\))?\\s*\\.(?:(?:get|put)(?:String|StringSet|Boolean|Int|Long|Float)|contains|remove)" +
                "\\(\\s*\"([^\"]+)\""
        )
        val editorChain = Regex("(\\w+)\\s*\\.edit\\(\\)([\\s\\S]{0,2000}?)\\.apply\\(\\)")
        val editorPut = Regex("\\.put(?:String|StringSet|Boolean|Int|Long|Float)\\(\\s*\"([^\"]+)\"")
        val editorScope = Regex("(\\w+)\\s*\\.edit\\(\\)\\s*\\.apply\\s*\\{([\\s\\S]{0,2000}?)\\}")
        val scopePut = Regex("(?:^|[;\\s])put(?:String|StringSet|Boolean|Int|Long|Float)\\(\\s*\"([^\"]+)\"")
        val directCall = Regex(
            "getSharedPreferences\\(\\s*\"([^\"]+)\"[^)]*\\)\\s*(?:\\.edit\\(\\))?\\s*\\." +
                "(?:(?:get|put)(?:String|StringSet|Boolean|Int|Long|Float)|contains|remove)\\(\\s*\"([^\"]+)\""
        )
        val fixture = """
            val prefs = context.getSharedPreferences("infinity_settings", 0)
            prefs.edit()
                .putString("language", "zh")
                .putBoolean("haptic_enabled", true)
                .apply()
            injectedPrefs.getString("default_model", null)
            prefs.edit().apply {
                putString("theme_mode", "dark")
            }
            context.getSharedPreferences("infinity_settings", 0).getBoolean("haptic_enabled", true)
        """.trimIndent()

        assertWithMessage("应解析声明 receiver").that(declaration.find(fixture)?.groupValues?.get(1)).isEqualTo("prefs")
        val found = buildSet {
            calls.findAll(fixture).forEach { add(it.groupValues[1] to it.groupValues[2]) }
            editorChain.findAll(fixture).forEach { chain ->
                editorPut.findAll(chain.groupValues[2]).forEach { add(chain.groupValues[1] to it.groupValues[1]) }
            }
            editorScope.findAll(fixture).forEach { scope ->
                scopePut.findAll(scope.groupValues[2]).forEach { add(scope.groupValues[1] to it.groupValues[1]) }
            }
        }
        assertWithMessage("应覆盖跨行 edit/apply 链").that(found)
            .containsAtLeast(
                "prefs" to "language", "prefs" to "haptic_enabled", "injectedPrefs" to "default_model",
                "prefs" to "theme_mode",
            )
        assertWithMessage("应覆盖直接 getSharedPreferences 调用")
            .that(directCall.find(fixture)?.groupValues?.drop(1))
            .isEqualTo(listOf("infinity_settings", "haptic_enabled"))
        assertWithMessage("注入 receiver 必须由显式 namespace hint 解析")
            .that(mapOf<String, String>()["fixture#injectedPrefs"]).isNull()
    }

    @Test
    fun `deny policy wins for every credential spelling`() {
        listOf(
            "provider_api_key", "apiKey", "tavily-api-key", "embedding_key", "webdavPass",
            "automaticBackupPassword", "Authorization", "Cookie", "access_token", "privateKey",
            "vertexServiceAccountJson", "client_secret",
        ).forEach { key ->
            assertWithMessage(key).that(BackupPreferencePolicy.isAllowed("settings", key)).isFalse()
        }
    }

    @Test fun `KG数字预算可备份且本机用量与认证令牌不可迁移`() {
        listOf("kg_retrieval_hops", "kg_max_seeds", "kg_max_nodes", "kg_max_edges", "kg_context_max_tokens",
            "kg_jit_fallback", "kg_session_extraction", "memory_state_extraction", "kg_jit_min_seeds", "kg_max_calls", "kg_max_output_tokens", "kg_request_max_output_tokens",
            "kg_max_duration_seconds", "kg_large_document_confirm_calls", "kg_window_chars", "kg_concurrency", "kg_gleaning_passes",
            "kg_request_max_output_tokens_default_v2", "kg_budget_defaults_v3").forEach { key ->
            assertWithMessage(key).that(BackupPreferencePolicy.isAllowed("rag", key)).isTrue()
        }
        listOf("kg_token", "kg_api_key", "kg_access_token").forEach { key ->
            assertThat(BackupPreferencePolicy.isAllowed("rag", key)).isFalse()
        }
        assertThat(BackupPreferencePolicy.isKnown("kg_usage", "kg_usage_ledger_v1")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("kg_usage", "kg_usage_ledger_v1")).isFalse()
        listOf("day", "turns", "calls").forEach { key ->
            assertThat(BackupPreferencePolicy.isKnown("session_graph_stats", key)).isTrue()
            assertThat(BackupPreferencePolicy.isAllowed("session_graph_stats", key)).isFalse()
        }
    }

    @Test
    fun `本机回滚摘要用量已登记且禁止跨设备迁移`() {
        assertThat(BackupPreferencePolicy.isKnown("infinity_settings", "rolled_back_summary_usage_v1")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("infinity_settings", "rolled_back_summary_usage_v1")).isFalse()
    }

    @Test
    fun `嵌入模型配置引导的已提示状态已登记且禁止跨设备迁移`() {
        assertThat(BackupPreferencePolicy.isKnown("infinity_settings", "embedding_setup_hint_shown")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("infinity_settings", "embedding_setup_hint_shown")).isFalse()
    }

    @Test
    fun `Termux与本机MCP启用授权属于当前设备且不得跨备份恢复`() {
        listOf("termux_integration_enabled", "mcp_allow_loopback_http").forEach { key ->
            assertWithMessage("设备授权开关应被显式识别: $key")
                .that(BackupPreferencePolicy.isKnown("infinity_settings", key)).isTrue()
            assertWithMessage("设备授权开关不得跨备份恢复: $key")
                .that(BackupPreferencePolicy.isAllowed("infinity_settings", key)).isFalse()
        }
    }

    @Test
    fun `退役自动备份键只用于识别旧包且不得继续导出`() {
        assertThat(BackupPreferencePolicy.isKnown("backup", "auto_backup")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("backup", "auto_backup")).isFalse()
    }

    @Test
    fun `四个无runtime消费者的旧RAG字段只读识别但禁止再次导出`() {
        val retiredKeys = listOf(
            "jit_max_chunks",
            "kg_domain_auto",
            "enable_incremental_hash",
            "enable_local_preprocess",
        )

        retiredKeys.forEach { key ->
            assertWithMessage("$key 应被识别以便旧备份可恢复")
                .that(BackupPreferencePolicy.isKnown("rag", key)).isTrue()
            assertWithMessage("$key 不得进入新备份")
                .that(BackupPreferencePolicy.isAllowed("rag", key)).isFalse()
            assertWithMessage("$key 应按退休字段丢弃")
                .that(BackupPreferencePolicy.isRetired("rag", key)).isTrue()
        }
    }

    @Test
    fun `通知权限询问状态属于设备本地状态且不得进入备份`() {
        assertWithMessage("设备权限状态应登记为已知")
            .that(
                BackupPreferencePolicy.isKnown(
                    "generation_notification_permission",
                    "post_notifications_asked",
                ),
            ).isTrue()
        assertWithMessage("设备权限状态不得随备份迁移")
            .that(
                BackupPreferencePolicy.isAllowed(
                    "generation_notification_permission",
                    "post_notifications_asked",
                ),
            ).isFalse()
    }

    @Test
    fun `目录刷新节流状态属于设备本地状态且不得进入备份`() {
        assertThat(BackupPreferencePolicy.isKnown("catalog_update_schedule", "checkedAt")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("catalog_update_schedule", "checkedAt")).isFalse()
    }

    @Test
    fun `本地推理运行时偏好属于设备能力且不得进入备份`() {
        listOf(
            "local_models_enabled",
            "local_auto_load",
            "last_local_model",
        ).forEach { key ->
            assertWithMessage("本地运行时偏好应登记为已知: $key")
                .that(BackupPreferencePolicy.isKnown("settings", key)).isTrue()
            assertWithMessage("本地运行时偏好不得跨设备恢复: $key")
                .that(BackupPreferencePolicy.isAllowed("settings", key)).isFalse()
        }
        assertWithMessage("旧 provider namespace 中的本地模型路径也不得备份")
            .that(BackupPreferencePolicy.isKnown("provider", "last_local_model")).isTrue()
        assertWithMessage("旧 provider namespace 中的本地模型路径不得恢复")
            .that(BackupPreferencePolicy.isAllowed("provider", "last_local_model")).isFalse()
    }

    @Test
    fun `UI 白名单应覆盖主题偏好键`() {
        listOf("ui", "infinity_settings").forEach { namespace ->
            assertThat(BackupPreferencePolicy.isKnown(namespace, "theme_mode")).isTrue()
            assertThat(BackupPreferencePolicy.isAllowed(namespace, "theme_mode")).isTrue()
            assertThat(BackupPreferencePolicy.isKnown(namespace, "theme_color_source")).isTrue()
            assertThat(BackupPreferencePolicy.isAllowed(namespace, "theme_color_source")).isTrue()
        }
    }

    @Test
    fun `模型抑制状态和头像引用纳入备份策略`() {
        assertThat(BackupPreferencePolicy.isAllowed("settings", "suppressed_provider_models")).isTrue()
        assertThat(BackupPreferencePolicy.isKnown("settings", "user_avatar")).isTrue()
        assertThat(BackupPreferencePolicy.isAllowed("settings", "user_avatar")).isTrue()
        assertThat(BackupPreferencePolicy.isRetired("settings", "user_avatar")).isFalse()
        assertThat(BackupPreferencePolicy.isAllowed("settings", "profile_show_brand")).isTrue()
    }
}
