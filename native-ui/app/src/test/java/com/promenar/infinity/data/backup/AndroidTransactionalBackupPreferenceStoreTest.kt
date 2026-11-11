package com.promenar.infinity.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AndroidTransactionalBackupPreferenceStoreTest {
    @Test
    fun `旧备份物理别名导入到当前偏好命名空间`() = runBlocking {
        val adapter = store()
        val before = adapter.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val old = BackupPreferenceSnapshot(listOf(entry("nexara_settings", "user_name", PreferenceValueType.STRING, "迁移用户")), setOf("default"))
        adapter.prepare("tx-rename-legacy", before, old)
        adapter.commitPrepared("tx-rename-legacy")
        assertThat(context.getSharedPreferences("infinity_settings", 0).getString("user_name", null)).isEqualTo("迁移用户")
        assertThat(adapter.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES).entries.single().namespace).isEqualTo("settings")
        val duplicate = old.copy(entries = old.entries + entry("settings", "user_name", PreferenceValueType.STRING, "重复"))
        assertFails { adapter.preflightRestore("tx-rename-duplicate", before, duplicate) }
    }

    private lateinit var context: Context
    private lateinit var ledgerFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ledgerFile = File(context.noBackupFilesDir, "preference-test-${System.nanoTime()}.json")
        physicalNamespaces.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }

    @After
    fun tearDown() {
        physicalNamespaces.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        ledgerFile.delete()
        File(ledgerFile.path + ".bak").delete()
        File(ledgerFile.path + ".new").delete()
    }

    @Test
    fun `技能商店来源记录与开关备份恢复且本机检查时间不变`() = runBlocking {
        val settings = context.getSharedPreferences("infinity_settings", 0)
        settings.edit().putString("skill_store_sources", "[\"https://example.com/index\"]")
            .putString("skill_store_records", "[{\"name\":\"demo\"}]")
            .putBoolean("skill_store_auto_check", false).putLong("skill_store_last_checked", 123L).commit()
        val adapter = store()
        val saved = adapter.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        assertThat(saved.entries.map { it.key }).containsAtLeast("skill_store_sources", "skill_store_records", "skill_store_auto_check")
        assertThat(saved.entries.map { it.key }).doesNotContain("skill_store_last_checked")
        settings.edit().putString("skill_store_sources", "[]").putString("skill_store_records", "[]")
            .putBoolean("skill_store_auto_check", true).putLong("skill_store_last_checked", 456L).commit()
        val before = adapter.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        adapter.prepare("tx-skill-store", before, saved)
        adapter.commitPrepared("tx-skill-store")
        assertThat(settings.getString("skill_store_sources", null)).isEqualTo("[\"https://example.com/index\"]")
        assertThat(settings.getString("skill_store_records", null)).isEqualTo("[{\"name\":\"demo\"}]")
        assertThat(settings.getBoolean("skill_store_auto_check", true)).isFalse()
        assertThat(settings.getLong("skill_store_last_checked", 0)).isEqualTo(456L)
    }

    @Test
    fun `提供商状态不导出且备份恢复不会写入或覆盖状态文件`() = runBlocking {
        val status = context.getSharedPreferences("infinity_provider_status", 0)
        val settings = context.getSharedPreferences("infinity_settings", 0)
        try {
            status.edit().putString("statuses", "local-status-before-backup").commit()
            settings.edit().putInt("loop_limit", 9).commit()
            val snapshot = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
            assertThat(snapshot.entries.none { it.namespace.contains("status") || it.key == "statuses" }).isTrue()

            status.edit().putString("statuses", "local-status-after-backup").commit()
            settings.edit().putInt("loop_limit", 5).commit()
            val before = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
            store().prepare("tx-provider-status", before, snapshot)
            store().commitPrepared("tx-provider-status")

            assertThat(settings.getInt("loop_limit", -1)).isEqualTo(9)
            assertThat(status.getString("statuses", null)).isEqualTo("local-status-after-backup")
        } finally {
            status.edit().clear().commit()
        }
    }

    @Test
    fun `KG高级设置按原始类型备份恢复且不迁移本机用量`() = runBlocking<Unit> {
        val prefs = context.getSharedPreferences("rag_settings", 0)
        val numbers = mapOf(
            "kg_retrieval_hops" to 2, "kg_max_seeds" to 8, "kg_max_nodes" to 48,
            "kg_max_edges" to 16, "kg_context_max_tokens" to 1024, "kg_jit_min_seeds" to 3,
            "kg_max_calls" to 12, "kg_max_output_tokens" to 16384,
            "kg_request_max_output_tokens" to 1024, "kg_max_duration_seconds" to 90,
            "kg_large_document_confirm_calls" to 8,
        )
        prefs.edit().apply {
            numbers.forEach { (key, value) -> putInt(key, value) }
            putBoolean("kg_jit_fallback", false)
        }.commit()
        val usage = context.getSharedPreferences("kg_usage", 0)
        usage.edit().putString("kg_usage_ledger_v1", "local-usage-fixture").commit()
        try {
            val snapshot = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
            assertThat(snapshot.entries.filter { it.namespace == "rag" }.map { it.key })
                .containsAtLeastElementsIn(numbers.keys + "kg_jit_fallback")
            assertThat(snapshot.entries.none { it.namespace == "kg_usage" }).isTrue()
            prefs.edit().clear().commit()
            val before = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
            store().prepare("tx-kg-settings", before, snapshot)
            store().commitPrepared("tx-kg-settings")
            numbers.forEach { (key, value) -> assertThat(prefs.getInt(key, -1)).isEqualTo(value) }
            assertThat(prefs.contains("kg_jit_fallback")).isTrue()
            assertThat(prefs.getBoolean("kg_jit_fallback", true)).isFalse()
            assertThat(usage.getString("kg_usage_ledger_v1", null)).isEqualTo("local-usage-fixture")
        } finally {
            usage.edit().clear().commit()
        }
    }

    @Test
    fun `备份恢复设置时不导出或覆盖本机回滚摘要费用`() = runBlocking {
        val prefs = context.getSharedPreferences("infinity_settings", 0)
        prefs.edit().putInt("loop_limit", 9)
            .putString("rolled_back_summary_usage_v1", "local-summary-usage").commit()
        val snapshot = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        assertThat(snapshot.entries.none { it.key == "rolled_back_summary_usage_v1" }).isTrue()
        prefs.edit().putInt("loop_limit", 5).commit()
        val before = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)

        store().prepare("tx-summary-usage", before, snapshot)
        store().commitPrepared("tx-summary-usage")

        assertThat(prefs.getInt("loop_limit", -1)).isEqualTo(9)
        assertThat(prefs.getString("rolled_back_summary_usage_v1", null)).isEqualTo("local-summary-usage")
    }

    @Test
    fun `不限轮次与任务预算可备份恢复且不迁移自动执行同意`() = runBlocking {
        val prefs = context.getSharedPreferences("infinity_settings", 0)
        prefs.edit().putInt("loop_limit", 0).putInt("task_token_limit", 20_000)
            .putInt("tool_context_ratio_percent", 70).putInt("tool_context_keep_rounds", 3)
            .putString("preset_summary_model", "quick-model")
            .putInt("tool_loop_repeat_limit", 3).putBoolean("unlimited_auto_confirmed", true).commit()
        val snapshot = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        assertThat(snapshot.entries.first { it.key == "loop_limit" }.value).isEqualTo("0")
        assertThat(snapshot.entries.first { it.key == "task_token_limit" }.value).isEqualTo("20000")
        assertThat(snapshot.entries.map { it.key })
            .containsNoneOf("tool_context_ratio_percent", "tool_context_keep_rounds")
        assertThat(snapshot.entries.first { it.key == "preset_summary_model" }.value).isEqualTo("quick-model")
        assertThat(snapshot.entries.none { it.key == "unlimited_auto_confirmed" }).isTrue()
        prefs.edit().clear().commit()
        val before = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        store().prepare("tx-p0-runtime", before, snapshot)
        store().commitPrepared("tx-p0-runtime")
        assertThat(prefs.getInt("loop_limit", -1)).isEqualTo(0)
        assertThat(prefs.getInt("task_token_limit", -1)).isEqualTo(20_000)
        assertThat(prefs.getString("preset_summary_model", null)).isEqualTo("quick-model")
        assertThat(prefs.contains("tool_context_ratio_percent")).isFalse()
        assertThat(prefs.contains("tool_context_keep_rounds")).isFalse()
        assertThat(prefs.getBoolean("unlimited_auto_confirmed", false)).isFalse()
    }

    @Test
    fun `含工具上下文旧偏好的备份可恢复且忽略旧键`() = runBlocking {
        val adapter = store()
        val before = BackupPreferenceSnapshot(emptyList(), setOf("default"))
        val legacyBackup = BackupPreferenceSnapshot(
            entries = listOf(
                entry("settings", "tool_context_ratio_percent", PreferenceValueType.INT, "70"),
                entry("settings", "tool_context_keep_rounds", PreferenceValueType.INT, "3"),
                entry("settings", "preset_summary_model", PreferenceValueType.STRING, "quick-model"),
                entry("settings", "language", PreferenceValueType.STRING, "zh"),
            ),
            providerIds = setOf("default"),
        )

        adapter.preflightRestore("tx-legacy-tool-context", before, legacyBackup)
        adapter.prepare("tx-legacy-tool-context", before, legacyBackup)
        store().commitPrepared("tx-legacy-tool-context")

        val prefs = context.getSharedPreferences("infinity_settings", 0)
        assertThat(prefs.contains("tool_context_ratio_percent")).isFalse()
        assertThat(prefs.contains("tool_context_keep_rounds")).isFalse()
        assertThat(prefs.getString("preset_summary_model", null)).isEqualTo("quick-model")
        assertThat(prefs.getString("language", null)).isEqualTo("zh")
    }

    @Test
    fun `snapshot preserves all SharedPreferences value types across six namespaces and filters denied keys`(): Unit = runBlocking {
        context.getSharedPreferences("infinity_provider", 0).edit()
            .putString("model", "m1")
            .putString("api_key", "must-not-leak")
            .commit()
        context.getSharedPreferences("infinity_settings", 0).edit()
            .putBoolean("haptic_enabled", true)
            .putInt("loop_limit", 9)
            .putFloat("default_temperature", 0.4f)
            .putStringSet("enabled_skills", setOf("z", "a"))
            .putStringSet("suppressed_provider_models", setOf("provider-one"))
            .putString("user_avatar", "/stale/device/path/avatar.img")
            .putString("model_info_sample_family", "MiniMax M3")
            .putString("model_info_sample_canonical_id", "minimax/minimax-m3")
            .putString("model_info_sample_chat_endpoint", "SUPPORTED")
            .putString("model_info_sample_auto_fingerprint", "catalog:v2")
            .putStringSet("model_info_sample_user_edited_fields", setOf("name", "familyName"))
            .putInt("model_info_sample_input", 120000)
            .putStringSet("model_info_sample_metadata_sources", setOf("displayName=PROVIDER", "capabilities.REASONING=PROVIDER"))
            .putStringSet("model_info_sample_metadata_diagnostics", setOf("provider_notice"))
            .putString("model_info_sample_provider_owned_by", "NEWAPI")
            .putString("model_info_sample_source_provider_id", "openrouter")
            .putString("model_info_sample_provider_display_name", "Remote Model")
            .putString("model_info_sample_provider_workload", "GENERATIVE_TEXT")
            .putString("model_info_sample_provider_context", "128000")
            .putString("model_info_sample_provider_input", "120000")
            .putString("model_info_sample_provider_output", "8000")
            .putStringSet("model_info_sample_provider_capabilities", setOf("REASONING=UNSUPPORTED", "TOOL_CALLING=SUPPORTED"))
            .putInt("extra_providers_count", 3)
            .putString("extra_providers_ids", "provider-zero,provider-one")
            .putString("extra_provider_0_id", "provider-zero")
            .commit()
        context.getSharedPreferences("infinity_search", 0).edit().putInt("result_count", 7).commit()
        context.getSharedPreferences("rag_settings", 0).edit()
            .putFloat("hybrid_alpha", 0.65f)
            .putInt("jit_max_chunks", 128)
            .putBoolean("kg_domain_auto", true)
            .putBoolean("enable_incremental_hash", true)
            .putBoolean("enable_local_preprocess", true)
            .commit()
        context.getSharedPreferences("infinity_prefs", 0).edit().putBoolean("has_shown_welcome", true).commit()
        context.getSharedPreferences("infinity_backup_settings", 0).edit()
            .putLong("last_backup_time", 42L)
            .putBoolean("auto_backup", true)
            .putString("webdav_pass", "must-not-leak")
            .commit()

        val snapshot = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)

        assertThat(snapshot.providerIds).containsExactly("default", "provider-zero", "provider-one", "extra_2")
        assertThat(snapshot.entries.associateBy { it.namespace to it.key })
            .containsAtLeastEntriesIn(mapOf(
                ("provider" to "model") to entry("provider", "model", PreferenceValueType.STRING, "m1"),
                ("settings" to "haptic_enabled") to entry("settings", "haptic_enabled", PreferenceValueType.BOOLEAN, "true"),
                ("settings" to "loop_limit") to entry("settings", "loop_limit", PreferenceValueType.INT, "9"),
                ("settings" to "default_temperature") to entry("settings", "default_temperature", PreferenceValueType.FLOAT, "0.4"),
                ("settings" to "enabled_skills") to entry("settings", "enabled_skills", PreferenceValueType.STRING_SET, "[\"a\",\"z\"]"),
                ("settings" to "user_avatar") to entry("settings", "user_avatar", PreferenceValueType.STRING, "/stale/device/path/avatar.img"),
                ("settings" to "suppressed_provider_models") to entry(
                    "settings",
                    "suppressed_provider_models",
                    PreferenceValueType.STRING_SET,
                    "[\"provider-one\"]",
                ),
                ("settings" to "model_info_sample_family") to entry("settings", "model_info_sample_family", PreferenceValueType.STRING, "MiniMax M3"),
                ("settings" to "model_info_sample_canonical_id") to entry("settings", "model_info_sample_canonical_id", PreferenceValueType.STRING, "minimax/minimax-m3"),
                ("settings" to "model_info_sample_chat_endpoint") to entry("settings", "model_info_sample_chat_endpoint", PreferenceValueType.STRING, "SUPPORTED"),
                ("settings" to "model_info_sample_auto_fingerprint") to entry("settings", "model_info_sample_auto_fingerprint", PreferenceValueType.STRING, "catalog:v2"),
                ("settings" to "model_info_sample_user_edited_fields") to entry("settings", "model_info_sample_user_edited_fields", PreferenceValueType.STRING_SET, "[\"familyName\",\"name\"]"),
                ("settings" to "model_info_sample_input") to entry("settings", "model_info_sample_input", PreferenceValueType.INT, "120000"),
                ("settings" to "model_info_sample_metadata_sources") to entry("settings", "model_info_sample_metadata_sources", PreferenceValueType.STRING_SET, "[\"capabilities.REASONING=PROVIDER\",\"displayName=PROVIDER\"]"),
                ("settings" to "model_info_sample_metadata_diagnostics") to entry("settings", "model_info_sample_metadata_diagnostics", PreferenceValueType.STRING_SET, "[\"provider_notice\"]"),
                ("settings" to "model_info_sample_provider_owned_by") to entry("settings", "model_info_sample_provider_owned_by", PreferenceValueType.STRING, "NEWAPI"),
                ("settings" to "model_info_sample_source_provider_id") to entry("settings", "model_info_sample_source_provider_id", PreferenceValueType.STRING, "openrouter"),
                ("settings" to "model_info_sample_provider_display_name") to entry("settings", "model_info_sample_provider_display_name", PreferenceValueType.STRING, "Remote Model"),
                ("settings" to "model_info_sample_provider_workload") to entry("settings", "model_info_sample_provider_workload", PreferenceValueType.STRING, "GENERATIVE_TEXT"),
                ("settings" to "model_info_sample_provider_context") to entry("settings", "model_info_sample_provider_context", PreferenceValueType.STRING, "128000"),
                ("settings" to "model_info_sample_provider_input") to entry("settings", "model_info_sample_provider_input", PreferenceValueType.STRING, "120000"),
                ("settings" to "model_info_sample_provider_output") to entry("settings", "model_info_sample_provider_output", PreferenceValueType.STRING, "8000"),
                ("settings" to "model_info_sample_provider_capabilities") to entry("settings", "model_info_sample_provider_capabilities", PreferenceValueType.STRING_SET, "[\"REASONING=UNSUPPORTED\",\"TOOL_CALLING=SUPPORTED\"]"),
                ("search" to "result_count") to entry("search", "result_count", PreferenceValueType.INT, "7"),
                ("rag" to "hybrid_alpha") to entry("rag", "hybrid_alpha", PreferenceValueType.FLOAT, "0.65"),
                ("ui" to "has_shown_welcome") to entry("ui", "has_shown_welcome", PreferenceValueType.BOOLEAN, "true"),
                ("backup" to "last_backup_time") to entry("backup", "last_backup_time", PreferenceValueType.LONG, "42"),
            ))
        assertThat(snapshot.entries.map { it.key }).doesNotContain("api_key")
        assertThat(snapshot.entries.map { it.key }).doesNotContain("webdav_pass")
        assertThat(snapshot.entries.map { it.key }).doesNotContain("auto_backup")
        assertThat(snapshot.entries.map { it.key }).contains("user_avatar")
        assertThat(snapshot.entries.map { it.key }).containsNoneOf(
            "jit_max_chunks",
            "kg_domain_auto",
            "enable_incremental_hash",
            "enable_local_preprocess",
        )
    }

    @Test
    fun `provider富元数据owner scope和三态字段可备份并完整回读`(): Unit = runBlocking {
        val settings = context.getSharedPreferences("infinity_settings", 0)
        settings.edit()
            .putInt("model_info_sample_input", 120000)
            .putStringSet("model_info_sample_metadata_sources", setOf("displayName=PROVIDER"))
            .putStringSet("model_info_sample_metadata_diagnostics", setOf("ambiguous_exact_match"))
            .putString("model_info_sample_provider_owned_by", "NEWAPI")
            .putString("model_info_sample_source_provider_id", "openrouter")
            .putString("model_info_sample_provider_display_name", "Remote Model")
            .putString("model_info_sample_provider_workload", "GENERATIVE_TEXT")
            .putString("model_info_sample_provider_context", "128000")
            .putString("model_info_sample_provider_input", "120000")
            .putString("model_info_sample_provider_output", "8000")
            .putStringSet(
                "model_info_sample_provider_capabilities",
                setOf("REASONING=UNSUPPORTED", "TOOL_CALLING=SUPPORTED"),
            )
            .commit()
        val originalStore = store()
        val desired = originalStore.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)

        settings.edit().clear().commit()
        val empty = store().snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val restoring = store()
        restoring.prepare("tx-provider-metadata", empty, desired)
        restoring.commitPrepared("tx-provider-metadata")

        assertThat(settings.getInt("model_info_sample_input", 0)).isEqualTo(120000)
        assertThat(settings.getStringSet("model_info_sample_metadata_sources", emptySet()))
            .containsExactly("displayName=PROVIDER")
        assertThat(settings.getStringSet("model_info_sample_metadata_diagnostics", emptySet()))
            .containsExactly("ambiguous_exact_match")
        assertThat(settings.getString("model_info_sample_provider_owned_by", null)).isEqualTo("NEWAPI")
        assertThat(settings.getString("model_info_sample_source_provider_id", null)).isEqualTo("openrouter")
        assertThat(settings.getString("model_info_sample_provider_display_name", null)).isEqualTo("Remote Model")
        assertThat(settings.getString("model_info_sample_provider_context", null)).isEqualTo("128000")
        assertThat(settings.getString("model_info_sample_provider_input", null)).isEqualTo("120000")
        assertThat(settings.getString("model_info_sample_provider_output", null)).isEqualTo("8000")
        assertThat(settings.getStringSet("model_info_sample_provider_capabilities", emptySet()))
            .containsExactly("REASONING=UNSUPPORTED", "TOOL_CALLING=SUPPORTED")
    }

    @Test
    fun `restore discards retired legacy keys while preserving fail closed unknown keys`(): Unit = runBlocking {
        val adapter = store()
        val before = BackupPreferenceSnapshot(emptyList(), setOf("default"))
        val legacyAfter = BackupPreferenceSnapshot(
            entries = listOf(
                entry("backup", "auto_backup", PreferenceValueType.BOOLEAN, "true"),
                entry("rag", "jit_max_chunks", PreferenceValueType.INT, "128"),
                entry("rag", "kg_domain_auto", PreferenceValueType.BOOLEAN, "true"),
                entry("rag", "enable_incremental_hash", PreferenceValueType.BOOLEAN, "true"),
                entry("rag", "enable_local_preprocess", PreferenceValueType.BOOLEAN, "true"),
            ),
            providerIds = setOf("default"),
        )

        adapter.prepare("tx-legacy-auto", before, legacyAfter)
        adapter.commitPrepared("tx-legacy-auto")

        val backupPrefs = context.getSharedPreferences("infinity_backup_settings", 0)
        assertThat(backupPrefs.contains("auto_backup")).isFalse()
        val ragPrefs = context.getSharedPreferences("rag_settings", 0)
        assertThat(ragPrefs.all.keys).containsNoneOf(
            "jit_max_chunks",
            "kg_domain_auto",
            "enable_incremental_hash",
            "enable_local_preprocess",
        )

        val unknownAfter = BackupPreferenceSnapshot(
            entries = listOf(entry("backup", "future_unknown", PreferenceValueType.STRING, "x")),
            providerIds = setOf("default"),
        )
        assertFails { adapter.prepare("tx-unknown", before, unknownAfter) }
    }

    @Test
    fun `commit removes absent allowed keys preserves denied keys and is idempotent after recreation`(): Unit = runBlocking {
        val settings = context.getSharedPreferences("infinity_settings", 0)
        settings.edit()
            .putString("language", "en")
            .putInt("loop_limit", 3)
            .putString("api_key", "legacy-secret")
            .putString("unregistered_internal", "keep")
            .commit()
        val first = store()
        val before = first.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val after = BackupPreferenceSnapshot(
            entries = listOf(
                entry("settings", "language", PreferenceValueType.STRING, "zh"),
                entry("settings", "haptic_enabled", PreferenceValueType.BOOLEAN, "true"),
                entry("search", "result_count", PreferenceValueType.INT, "11"),
                entry("rag", "memory_threshold", PreferenceValueType.FLOAT, "0.7"),
                entry("ui", "has_shown_welcome", PreferenceValueType.BOOLEAN, "true"),
                entry("backup", "last_backup_time", PreferenceValueType.LONG, "99"),
            ),
            providerIds = setOf("default"),
        )

        first.prepare("tx-commit", before, after)
        store().commitPrepared("tx-commit")
        store().commitPrepared("tx-commit")

        assertThat(settings.getString("language", null)).isEqualTo("zh")
        assertThat(settings.getBoolean("haptic_enabled", false)).isTrue()
        assertThat(settings.contains("loop_limit")).isFalse()
        assertThat(settings.getString("api_key", null)).isEqualTo("legacy-secret")
        assertThat(settings.getString("unregistered_internal", null)).isEqualTo("keep")
        assertThat(context.getSharedPreferences("infinity_search", 0).getInt("result_count", 0)).isEqualTo(11)
        assertThat(context.getSharedPreferences("rag_settings", 0).getFloat("memory_threshold", 0f)).isEqualTo(0.7f)
        assertThat(context.getSharedPreferences("infinity_prefs", 0).getBoolean("has_shown_welcome", false)).isTrue()
        assertThat(context.getSharedPreferences("infinity_backup_settings", 0).getLong("last_backup_time", 0)).isEqualTo(99)
    }

    @Test
    fun `partial commit can be retried after recreation`(): Unit = runBlocking {
        seedBeforeState()
        val initial = store()
        val before = initial.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val after = changedSnapshot()
        initial.prepare("tx-partial-commit", before, after)
        val failing = store { namespace, direction ->
            if (namespace == "settings" && direction == PreferenceApplyDirection.AFTER) {
                throw SimulatedPreferenceProcessDeath()
            }
        }

        assertThrows<SimulatedPreferenceProcessDeath> { failing.commitPrepared("tx-partial-commit") }
        store().commitPrepared("tx-partial-commit")

        assertThat(context.getSharedPreferences("infinity_settings", 0).getString("language", null)).isEqualTo("zh")
        assertThat(context.getSharedPreferences("infinity_search", 0).getInt("result_count", 0)).isEqualTo(10)
    }

    @Test
    fun `partial commit can be rolled back after recreation`(): Unit = runBlocking {
        seedBeforeState()
        val initial = store()
        val before = initial.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        initial.prepare("tx-partial-rollback", before, changedSnapshot())
        val failing = store { namespace, direction ->
            if (namespace == "settings" && direction == PreferenceApplyDirection.AFTER) {
                throw SimulatedPreferenceProcessDeath()
            }
        }
        assertThrows<SimulatedPreferenceProcessDeath> { failing.commitPrepared("tx-partial-rollback") }

        store().rollbackPrepared("tx-partial-rollback")

        assertThat(context.getSharedPreferences("infinity_settings", 0).getString("language", null)).isEqualTo("en")
        assertThat(context.getSharedPreferences("infinity_search", 0).getInt("result_count", 0)).isEqualTo(5)
    }

    @Test
    fun `finalized receipt survives recreation and distinguishes completed direction from missing state`(): Unit = runBlocking {
        seedBeforeState()
        val first = store()
        val before = first.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        first.prepare("tx-final", before, changedSnapshot())
        first.commitPrepared("tx-final")
        first.finalizePrepared("tx-final")

        store().commitPrepared("tx-final")
        store().finalizePrepared("tx-final")
        assertFails { store().rollbackPrepared("tx-final") }
        store().rollbackPrepared("never-prepared")
        store().finalizePrepared("never-prepared")
        assertFails { store().commitPrepared("never-prepared") }
    }

    @Test
    fun `preflight validates typed values and provider ids before creating ledger`(): Unit = runBlocking {
        val adapter = store()
        val before = BackupPreferenceSnapshot(emptyList(), setOf("default"))
        val malformed = BackupPreferenceSnapshot(
            entries = listOf(entry("settings", "loop_limit", PreferenceValueType.STRING, "not-an-int")),
            providerIds = setOf("default"),
        )

        assertFails { adapter.preflightRestore("tx-malformed", before, malformed) }

        assertThat(ledgerFile.exists()).isFalse()
    }

    @Test
    fun `preflight rejects ledger capacity before writing ledger`(): Unit = runBlocking {
        val snapshot = BackupPreferenceSnapshot(emptyList(), setOf("default"))

        assertFails { store(maxLedgerBytes = 32).preflightRestore("tx-capacity", snapshot, snapshot) }

        assertThat(ledgerFile.exists()).isFalse()
    }

    @Test
    fun `prepare is idempotent only for identical canonical snapshots and validates provider ids`(): Unit = runBlocking {
        seedBeforeState()
        val adapter = store()
        val before = adapter.snapshot(BackupPackageLimits.MAX_IN_MEMORY_BYTES)
        val after = changedSnapshot()

        adapter.prepare("tx-prepare", before, after)
        store().prepare("tx-prepare", before, after)
        assertFails { store().prepare("tx-prepare", before, after.copy(entries = emptyList())) }
        assertFails { store().prepare("tx-bad-provider", before, after.copy(providerIds = setOf("forged"))) }
    }

    private fun store(
        maxLedgerBytes: Long = 34L * 1024 * 1024,
        hook: (String, PreferenceApplyDirection) -> Unit = { _, _ -> },
    ) = AndroidTransactionalBackupPreferenceStore(context, ledgerFile, hook, maxLedgerBytes)

    private fun seedBeforeState() {
        context.getSharedPreferences("infinity_settings", 0).edit().putString("language", "en").commit()
        context.getSharedPreferences("infinity_search", 0).edit().putInt("result_count", 5).commit()
    }

    private fun changedSnapshot() = BackupPreferenceSnapshot(
        entries = listOf(
            entry("settings", "language", PreferenceValueType.STRING, "zh"),
            entry("search", "result_count", PreferenceValueType.INT, "10"),
        ),
        providerIds = setOf("default"),
    )

    private fun entry(namespace: String, key: String, type: PreferenceValueType, value: String) =
        BackupPreferenceEntry(namespace, key, value, type)

    private suspend inline fun <reified T : Throwable> assertThrows(noinline block: suspend () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (error: Throwable) {
            thrown = error
        }
        assertThat(thrown).isInstanceOf(T::class.java)
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertThat(failed).isTrue()
    }

    private class SimulatedPreferenceProcessDeath : Error()

    private companion object {
        val physicalNamespaces = listOf(
            "infinity_provider",
            "infinity_settings",
            "infinity_search",
            "rag_settings",
            "infinity_prefs",
            "infinity_backup_settings",
        )
    }
}
