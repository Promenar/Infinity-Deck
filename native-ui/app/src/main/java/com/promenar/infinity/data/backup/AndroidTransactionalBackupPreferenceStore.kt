package com.promenar.infinity.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.promenar.infinity.recording.RecordingHotwordPolicy
import com.promenar.infinity.recording.RecordingHotwordStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
internal enum class PreferenceApplyDirection { BEFORE, AFTER }

/**
 * SharedPreferences 的进程重启安全 prepared adapter。
 *
 * 每个命名空间由一次同步 commit 原子替换；跨命名空间中断后可依据持久化 before/after 重放。
 * finalized 只留下方向与摘要收据，使外层 restore journal 删除前再次中断仍可判定幂等结果。
 */
internal class AndroidTransactionalBackupPreferenceStore(
    context: Context,
    ledgerFile: File = File(
        requireNotNull(ContextCompat.getNoBackupFilesDir(context)) { "noBackupFilesDir 不可用" },
        "backup-preference-transactions-v1.json",
    ),
    private val namespaceAppliedHook: (String, PreferenceApplyDirection) -> Unit = { _, _ -> },
    private val maxLedgerBytes: Long = MAX_LEDGER_BYTES,
) : TransactionalBackupPreferenceStore {
    private val appContext = context.applicationContext
    private val atomicLedger = AtomicFile(ledgerFile)
    private val hotwordFile = File(appContext.filesDir, RecordingHotwordStore.FILE_NAME)
    private val hotwordStore = RecordingHotwordStore(hotwordFile)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    override suspend fun snapshot(maxTotalBytes: Long): BackupPreferenceSnapshot = processMutex.withLock {
        val entries = buildList {
            namespaceSpecs.forEach { spec ->
                val prefs = appContext.getSharedPreferences(spec.physicalName, Context.MODE_PRIVATE)
                prefs.all.toSortedMap().forEach { (key, rawValue) ->
                    if (!BackupPreferencePolicy.isAllowed(spec.logicalName, key)) return@forEach
                    add(encodeEntry(spec.logicalName, key, rawValue))
                }
            }
        }
        val withHotwords = if (!hotwordFile.exists()) entries else entries + BackupPreferenceEntry(
            "recording_hotwords", "library", RecordingHotwordPolicy.encode(hotwordStore.read()), PreferenceValueType.STRING,
        )
        validateSnapshot(canonicalSnapshot(withHotwords, deriveProviderIds(withHotwords))).also {
            if (json.encodeToString(it).toByteArray().size.toLong() > maxTotalBytes) {
                throw BackupValidationException("偏好快照超过内存预算")
            }
        }
    }

    override suspend fun prepare(
        txId: String,
        before: BackupPreferenceSnapshot,
        after: BackupPreferenceSnapshot,
    ) = processMutex.withLock {
        requireTxId(txId)
        val canonicalBefore = validateSnapshot(before)
        val canonicalAfter = validateSnapshot(after)
        val ledger = readLedger()
        val existing = ledger.records[txId]
        val candidate = PreferenceTransactionRecord.prepared(txId, canonicalBefore, canonicalAfter)
        when {
            existing == null -> writeLedger(candidateLedger(ledger, txId, candidate))
            existing == candidate -> Unit
            else -> throw BackupValidationException("相同 txId 的偏好 prepared 状态不一致")
        }
    }

    override suspend fun preflightRestore(
        txId: String,
        before: BackupPreferenceSnapshot,
        after: BackupPreferenceSnapshot,
    ) = processMutex.withLock {
        requireTxId(txId)
        val candidate = PreferenceTransactionRecord.prepared(
            txId = txId,
            before = validateSnapshot(before),
            after = validateSnapshot(after),
        )
        val ledger = readLedger()
        val existing = ledger.records[txId]
        when {
            existing == null -> validateLedgerCapacity(candidateLedger(ledger, txId, candidate))
            existing == candidate -> Unit
            else -> throw BackupValidationException("相同 txId 的偏好 prepared 状态不一致")
        }
    }

    override suspend fun commitPrepared(txId: String) = applyPrepared(txId, PreferenceApplyDirection.AFTER)

    override suspend fun rollbackPrepared(txId: String) = applyPrepared(txId, PreferenceApplyDirection.BEFORE)

    override suspend fun finalizePrepared(txId: String) = processMutex.withLock {
        requireTxId(txId)
        val ledger = readLedger()
        val record = ledger.records[txId] ?: return@withLock
        if (record.phase == PreferenceTransactionPhase.FINALIZED) return@withLock
        val direction = record.direction ?: throw BackupValidationException("偏好 prepared 尚未应用，不能 finalize")
        val receiptWithoutDigest = PreferenceTransactionRecord(
            txId = record.txId,
            phase = PreferenceTransactionPhase.FINALIZED,
            direction = direction,
            before = null,
            after = null,
            payloadDigest = "",
        )
        val receipt = receiptWithoutDigest.copy(payloadDigest = receiptWithoutDigest.calculateDigest(json))
        writeLedger(ledger.copy(records = ledger.records + (txId to receipt)))
    }

    private suspend fun applyPrepared(txId: String, direction: PreferenceApplyDirection) = processMutex.withLock {
        requireTxId(txId)
        val ledger = readLedger()
        val record = ledger.records[txId]
        if (record == null) {
            if (direction == PreferenceApplyDirection.BEFORE) return@withLock
            throw BackupValidationException("偏好 prepared 状态不存在")
        }
        if (record.phase == PreferenceTransactionPhase.FINALIZED) {
            if (record.direction != direction) throw BackupValidationException("偏好 finalized 方向冲突")
            return@withLock
        }
        val target = when (direction) {
            PreferenceApplyDirection.BEFORE -> record.before
            PreferenceApplyDirection.AFTER -> record.after
        } ?: throw BackupValidationException("偏好 prepared payload 缺失")
        applySnapshot(target, direction)
        val appliedWithoutDigest = record.copy(
            phase = PreferenceTransactionPhase.APPLIED,
            direction = direction,
            payloadDigest = "",
        )
        val applied = appliedWithoutDigest.copy(payloadDigest = appliedWithoutDigest.calculateDigest(json))
        writeLedger(ledger.copy(records = ledger.records + (txId to applied)))
    }

    private fun applySnapshot(snapshot: BackupPreferenceSnapshot, direction: PreferenceApplyDirection) {
        val byNamespace = snapshot.entries.groupBy { it.namespace }
        namespaceSpecs.forEach { spec ->
            val prefs = appContext.getSharedPreferences(spec.physicalName, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.all.keys.filter {
                BackupPreferencePolicy.isAllowed(spec.logicalName, it) ||
                    BackupPreferencePolicy.isRetired(spec.logicalName, it)
            }
                .forEach(editor::remove)
            byNamespace[spec.logicalName].orEmpty().forEach { entry -> editor.put(entry) }
            if (!editor.commit()) throw BackupValidationException("偏好命名空间写入失败: ${spec.logicalName}")
            namespaceAppliedHook(spec.logicalName, direction)
        }
        val hotwords = byNamespace["recording_hotwords"].orEmpty().singleOrNull()
        if (hotwords == null) hotwordStore.delete() else hotwordStore.write(RecordingHotwordPolicy.decode(hotwords.value))
        namespaceAppliedHook("recording_hotwords", direction)
    }

    private fun SharedPreferences.Editor.put(entry: BackupPreferenceEntry) {
        try {
            when (entry.type) {
                PreferenceValueType.STRING -> putString(entry.key, entry.value)
                PreferenceValueType.STRING_SET -> putStringSet(
                    entry.key,
                    json.decodeFromString<List<String>>(entry.value).toSet(),
                )
                PreferenceValueType.BOOLEAN -> putBoolean(entry.key, entry.value.toBooleanStrict())
                PreferenceValueType.INT -> putInt(entry.key, entry.value.toInt())
                PreferenceValueType.LONG -> putLong(entry.key, entry.value.toLong())
                PreferenceValueType.FLOAT -> putFloat(entry.key, entry.value.toFloat())
            }
        } catch (error: Exception) {
            throw BackupValidationException("偏好值类型无效: ${entry.namespace}.${entry.key}", error)
        }
    }

    private fun encodeEntry(namespace: String, key: String, rawValue: Any?): BackupPreferenceEntry = when (rawValue) {
        is String -> BackupPreferenceEntry(namespace, key, rawValue, PreferenceValueType.STRING)
        is Boolean -> BackupPreferenceEntry(namespace, key, rawValue.toString(), PreferenceValueType.BOOLEAN)
        is Int -> BackupPreferenceEntry(namespace, key, rawValue.toString(), PreferenceValueType.INT)
        is Long -> BackupPreferenceEntry(namespace, key, rawValue.toString(), PreferenceValueType.LONG)
        is Float -> BackupPreferenceEntry(namespace, key, rawValue.toString(), PreferenceValueType.FLOAT)
        is Set<*> -> {
            val values = rawValue.map {
                it as? String ?: throw BackupValidationException("SharedPreferences StringSet 含非字符串元素")
            }.sorted()
            BackupPreferenceEntry(namespace, key, json.encodeToString(values), PreferenceValueType.STRING_SET)
        }
        else -> throw BackupValidationException("不支持的 SharedPreferences 类型: $namespace.$key")
    }

    private fun validateSnapshot(snapshot: BackupPreferenceSnapshot): BackupPreferenceSnapshot {
        val canonical = canonicalSnapshot(snapshot.entries, snapshot.providerIds)
        val seen = mutableSetOf<Pair<String, String>>()
        canonical.entries.forEach { entry ->
            if (entry.namespace !in logicalNamespaces) throw BackupValidationException("偏好 namespace 不受支持")
            if (!BackupPreferencePolicy.isKnown(entry.namespace, entry.key)) {
                throw BackupValidationException("偏好键不在白名单")
            }
            if (!seen.add(entry.namespace to entry.key)) throw BackupValidationException("偏好键重复")
            if (!BackupPreferencePolicy.isRetired(entry.namespace, entry.key)) {
                validateEntryType(entry)
            }
        }
        val retained = canonical.entries.filterNot {
            BackupPreferencePolicy.isRetired(it.namespace, it.key)
        }
        if (retained.any { !BackupPreferencePolicy.isAllowed(it.namespace, it.key) }) {
            throw BackupValidationException("偏好键不在白名单")
        }
        if (canonical.providerIds != deriveProviderIds(retained)) {
            throw BackupValidationException("Provider ID 清单与偏好内容不一致")
        }
        return canonicalSnapshot(retained, canonical.providerIds)
    }

    private fun validateEntryType(entry: BackupPreferenceEntry) {
        val expected = expectedType(entry.namespace, entry.key)
        if (entry.namespace == "recording_hotwords") {
            try { RecordingHotwordPolicy.decode(entry.value) } catch (error: Exception) {
                throw BackupValidationException("热词库备份内容无效", error)
            }
        }
        if (entry.type != expected) throw BackupValidationException("偏好值类型与策略不一致: ${entry.namespace}.${entry.key}")
        if (entry.namespace == "settings" && entry.key.startsWith("session_header_model_") &&
            com.promenar.infinity.data.model.SessionHeaderMode.entries.none { it.name == entry.value }) {
            throw BackupValidationException("会话请求头模型模式无效")
        }
        when (entry.type) {
            PreferenceValueType.STRING -> Unit
            PreferenceValueType.STRING_SET -> {
                val values = runCatching { json.decodeFromString<List<String>>(entry.value) }
                    .getOrElse { throw BackupValidationException("StringSet 编码无效", it) }
                if (values != values.distinct().sorted()) throw BackupValidationException("StringSet 必须规范排序且无重复")
            }
            PreferenceValueType.BOOLEAN -> runCatching { entry.value.toBooleanStrict() }
                .getOrElse { throw BackupValidationException("Boolean 编码无效", it) }
            PreferenceValueType.INT -> runCatching { entry.value.toInt() }
                .getOrElse { throw BackupValidationException("Int 编码无效", it) }
            PreferenceValueType.LONG -> runCatching { entry.value.toLong() }
                .getOrElse { throw BackupValidationException("Long 编码无效", it) }
            PreferenceValueType.FLOAT -> {
                val value = runCatching { entry.value.toFloat() }
                    .getOrElse { throw BackupValidationException("Float 编码无效", it) }
                if (!value.isFinite()) throw BackupValidationException("Float 必须为有限值")
            }
        }
    }

    private fun expectedType(namespace: String, key: String): PreferenceValueType {
        BackupPreferencePolicy.sessionHeaderType(namespace, key)?.let { return it }
        if (namespace == "settings" && key.startsWith("model_info_")) {
            return when {
                key.endsWith("_caps") || key.endsWith("_user_edited_fields") ||
                    key.endsWith("_metadata_sources") || key.endsWith("_metadata_diagnostics") ||
                    key.endsWith("_provider_capabilities") ->
                    PreferenceValueType.STRING_SET
                key.endsWith("_provider_context") || key.endsWith("_provider_input") ||
                    key.endsWith("_provider_output") -> PreferenceValueType.STRING
                key.endsWith("_context") || key.endsWith("_maxoutput") || key.endsWith("_input") ->
                    PreferenceValueType.INT
                else -> PreferenceValueType.STRING
            }
        }
        if (namespace == "settings" && key.startsWith("extra_provider_") && key.endsWith("_enabled")) {
            return PreferenceValueType.BOOLEAN
        }
        if (namespace == "provider" && key.startsWith("provider_") && key.endsWith("_enabled")) {
            return PreferenceValueType.BOOLEAN
        }
        return preferenceTypes[namespace to key] ?: PreferenceValueType.STRING
    }

    private fun deriveProviderIds(entries: List<BackupPreferenceEntry>): Set<String> {
        val settings = entries.filter { it.namespace == "settings" }.associateBy { it.key }
        val count = settings["extra_providers_count"]?.value?.toIntOrNull() ?: 0
        if (count !in 0..MAX_EXTRA_PROVIDERS) throw BackupValidationException("额外 Provider 数量无效")
        val legacyIds = settings["extra_providers_ids"]?.value.orEmpty().split(',')
        if (legacyIds.drop(count).any { it.isNotBlank() }) {
            throw BackupValidationException("Provider ID 清单超过声明数量")
        }
        return buildSet {
            add("default")
            repeat(count) { index ->
                val explicit = settings["extra_provider_${index}_id"]?.value?.trim().orEmpty()
                val legacy = legacyIds.getOrNull(index)?.trim().orEmpty()
                val id = explicit.ifEmpty { legacy.ifEmpty { "extra_$index" } }
                if (explicit.isNotEmpty() && legacy.isNotEmpty() && explicit != legacy) {
                    throw BackupValidationException("Provider ID 新旧索引不一致")
                }
                if (id.length > 200 || id.any(Char::isISOControl)) throw BackupValidationException("Provider ID 无效")
                if (!add(id)) throw BackupValidationException("Provider ID 重复")
            }
        }.toSortedSet()
    }

    private fun canonicalSnapshot(
        entries: List<BackupPreferenceEntry>,
        providerIds: Set<String>,
    ) = BackupPreferenceSnapshot(
        entries = entries.map { it.copy(namespace = BackupPreferencePolicy.normalizeNamespace(it.namespace)) }.sortedWith(compareBy(BackupPreferenceEntry::namespace, BackupPreferenceEntry::key)),
        providerIds = providerIds.toSortedSet(),
    )

    private fun readLedger(): PreferenceTransactionLedger {
        if (!atomicLedger.baseFile.exists()) return PreferenceTransactionLedger()
        if (atomicLedger.baseFile.length() > maxLedgerBytes) throw BackupValidationException("偏好事务状态过大")
        return try {
            atomicLedger.openRead().use {
                json.decodeFromString<PreferenceTransactionLedger>(it.readBytes().toString(Charsets.UTF_8))
            }
        } catch (error: Exception) {
            throw BackupValidationException("偏好事务状态损坏", error)
        }.also { ledger ->
            if (ledger.version != LEDGER_VERSION) throw BackupValidationException("偏好事务状态版本不兼容")
            ledger.records.forEach { (txId, record) ->
                val digestValid = record.payloadDigest == record.calculateDigest(json)
                if (txId != record.txId || !digestValid) {
                    throw BackupValidationException("偏好事务状态摘要无效")
                }
            }
        }
    }

    private fun writeLedger(ledger: PreferenceTransactionLedger) {
        val bytes = json.encodeToString(ledger).toByteArray()
        if (bytes.size.toLong() > maxLedgerBytes) throw BackupValidationException("偏好事务状态超过安全限制")
        val output = atomicLedger.startWrite()
        try {
            output.write(bytes)
            atomicLedger.finishWrite(output)
        } catch (error: Throwable) {
            atomicLedger.failWrite(output)
            throw error
        }
    }

    private fun candidateLedger(
        ledger: PreferenceTransactionLedger,
        txId: String,
        candidate: PreferenceTransactionRecord,
    ) = ledger.copy(records = ledger.records + (txId to candidate))

    private fun validateLedgerCapacity(ledger: PreferenceTransactionLedger) {
        if (json.encodeToString(ledger).toByteArray().size.toLong() > maxLedgerBytes) {
            throw BackupValidationException("偏好事务状态超过安全限制")
        }
    }

    private fun requireTxId(txId: String) {
        if (!TX_ID.matches(txId)) throw BackupValidationException("偏好事务 txId 无效")
    }

    @Serializable
    private data class PreferenceTransactionLedger(
        val version: Int = LEDGER_VERSION,
        val records: Map<String, PreferenceTransactionRecord> = emptyMap(),
    )

    @Serializable
    private data class PreferenceTransactionRecord(
        val txId: String,
        val phase: PreferenceTransactionPhase,
        val direction: PreferenceApplyDirection? = null,
        val before: BackupPreferenceSnapshot? = null,
        val after: BackupPreferenceSnapshot? = null,
        val payloadDigest: String,
    ) {
        fun calculateDigest(json: Json): String {
            val payload = "$txId|$phase|$direction|${before?.let(json::encodeToString)}|${after?.let(json::encodeToString)}"
            return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        companion object {
            fun prepared(
                txId: String,
                before: BackupPreferenceSnapshot,
                after: BackupPreferenceSnapshot,
            ): PreferenceTransactionRecord {
                val emptyDigest = PreferenceTransactionRecord(
                    txId = txId,
                    phase = PreferenceTransactionPhase.PREPARED,
                    before = before,
                    after = after,
                    payloadDigest = "",
                )
                return emptyDigest.copy(payloadDigest = emptyDigest.calculateDigest(defaultJson))
            }
        }
    }

    @Serializable
    private enum class PreferenceTransactionPhase { PREPARED, APPLIED, FINALIZED }

    private data class NamespaceSpec(val logicalName: String, val physicalName: String)

    private companion object {
        const val LEDGER_VERSION = 1
        const val MAX_EXTRA_PROVIDERS = 1_000
        const val MAX_LEDGER_BYTES = 34L * 1024 * 1024
        val TX_ID = Regex("[A-Za-z0-9._-]{1,200}")
        val processMutex = Mutex()
        val defaultJson = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
        val namespaceSpecs = listOf(
            NamespaceSpec("provider", "infinity_provider"),
            NamespaceSpec("settings", "infinity_settings"),
            NamespaceSpec("search", "infinity_search"),
            NamespaceSpec("rag", "rag_settings"),
            NamespaceSpec("ui", "infinity_prefs"),
            NamespaceSpec("backup", "infinity_backup_settings"),
        )
        val logicalNamespaces = namespaceSpecs.mapTo(mutableSetOf()) { it.logicalName } + "recording_hotwords"
        val preferenceTypes = buildMap<Pair<String, String>, PreferenceValueType> {
            fun typed(namespace: String, type: PreferenceValueType, vararg keys: String) {
                keys.forEach { put(namespace to it, type) }
            }
            typed("settings", PreferenceValueType.BOOLEAN,
                "skill_store_auto_check", "haptic_enabled", "haptic_status_enabled", "local_models_enabled", "local_auto_load", "preset_skills_migrated_v3")
            typed("settings", PreferenceValueType.INT, "loop_limit", "extra_providers_count", "default_max_tokens")
            typed("settings", PreferenceValueType.INT,
                "task_token_limit", "tool_loop_repeat_limit", "stream_retry_limit")
            typed("settings", PreferenceValueType.FLOAT, "default_temperature", "default_top_p")
            typed(
                "settings",
                PreferenceValueType.STRING_SET,
                "all_models",
                "enabled_models",
                "enabled_skills",
                "known_builtin_skills",
                "agent_skills_enabled",
                "suppressed_provider_models",
            )
            typed("search", PreferenceValueType.BOOLEAN, "web_search_enabled")
            typed("search", PreferenceValueType.INT, "result_count")
            typed("rag", PreferenceValueType.INT,
                "doc_chunk_size", "chunk_overlap", "memory_chunk_size", "memory_limit", "doc_limit",
                "rerank_top_k", "rerank_final_k", "rerank_max_per_call", "query_rewrite_count",
                "kg_extraction_timeout", "jit_max_chunks", "embed_dimension", "max_embed_tokens_per_call",
                "kg_retrieval_hops", "kg_max_seeds", "kg_max_nodes", "kg_max_edges", "kg_context_max_tokens",
                "kg_jit_min_seeds", "kg_max_calls", "kg_max_output_tokens", "kg_request_max_output_tokens",
                "kg_max_duration_seconds", "kg_large_document_confirm_calls", "kg_window_chars", "kg_concurrency", "kg_gleaning_passes")
            typed("rag", PreferenceValueType.FLOAT,
                "memory_threshold", "doc_threshold", "hybrid_alpha", "hybrid_bm25_boost")
            typed("rag", PreferenceValueType.BOOLEAN,
                "enable_rerank", "enable_query_rewrite", "enable_hybrid_search", "enable_memory", "enable_docs",
                "enable_kg", "kg_free_mode", "kg_domain_auto", "enable_incremental_hash", "enable_local_preprocess", "kg_jit_fallback",
                "kg_session_extraction", "memory_state_extraction", "kg_request_max_output_tokens_default_v2", "kg_budget_defaults_v3",
                "show_retrieval_progress", "show_retrieval_details", "track_retrieval_metrics")
            typed("ui", PreferenceValueType.BOOLEAN, "has_shown_welcome", "haptic_enabled")
            typed("ui", PreferenceValueType.INT, "loop_limit")
            typed("backup", PreferenceValueType.BOOLEAN, "webdav_enabled", "auto_backup")
            typed("backup", PreferenceValueType.LONG, "last_backup_time")
        }
    }
}
