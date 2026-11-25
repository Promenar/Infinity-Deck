package com.promenar.infinity.ui.chat

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 宽屏（折叠屏内屏、平板）右侧检视器的按会话持久化状态：
 * - 仅在宽屏检视器模式（[com.promenar.infinity.ui.common.LocalInfinityInspectorLayout] 为 true）下读写；
 * - 竖屏/外屏的 93% 上滑抽屉不自动按此展开，关闭竖屏抽屉也不会覆盖内屏常驻偏好；
 * - 记录每个会话的开合态与上次停留的根页签，支持切会话、切主标签页与重启应用后恢复，并在删除会话时清理。
 */
data class SavedChatInspectorState(
    val open: Boolean,
    val rootToken: String? = null,
) {
    val rootPage: ChatPage?
        get() = rootToken?.let { token -> ChatPage.entries.firstOrNull { it.token == token } }
}

class ChatInspectorPreferenceStore(
    private val preferences: SharedPreferences,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: LinkedHashMap<String, StoredInspectorEntry>? = null

    fun read(sessionId: String): SavedChatInspectorState? {
        if (sessionId.isBlank()) return null
        synchronized(lock) {
            val entry = entriesLocked()[sessionId] ?: return null
            return SavedChatInspectorState(open = entry.open, rootToken = entry.root)
        }
    }

    /** 宽屏检视器模式首次进入会话时的初始层级令牌；未记录或上次已收起时返回空串。 */
    fun initialPagesToken(sessionId: String): String {
        val saved = read(sessionId) ?: return ""
        if (!saved.open) return ""
        val root = saved.rootPage ?: ChatPage.Run
        return saveChatPages(openChatPageRoot(root))
    }

    /** 该会话上次打开过的根页签（即使当前已收起，点击右缘条时仍可沿用）。 */
    fun lastRootToken(sessionId: String): String? = read(sessionId)?.rootPage?.token

    /**
     * 记录宽屏检视器的开合与页签变化：
     * - 展开时保存 `open = true` 与当前根页签；
     * - 收起时保存 `open = false`，同时保留收起前的根页签供下次点右缘条复用。
     */
    fun record(sessionId: String, open: Boolean, root: ChatPage?) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            val map = entriesLocked()
            val previous = map.remove(sessionId)
            val resolvedRoot = root?.token ?: previous?.root?.takeIf { token ->
                ChatPage.entries.any { it.token == token }
            }
            val next = StoredInspectorEntry(open = open, root = resolvedRoot)
            map[sessionId] = next
            trimToLimit(map)
            persistLocked(map)
        }
    }

    fun deleteSession(sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            val map = entriesLocked()
            if (map.remove(sessionId) != null) {
                persistLocked(map)
            }
        }
    }

    private fun entriesLocked(): LinkedHashMap<String, StoredInspectorEntry> {
        cache?.let { return it }
        val loaded = LinkedHashMap<String, StoredInspectorEntry>()
        val raw = preferences.getString(KEY_SESSION_STATES, null)
        if (!raw.isNullOrBlank()) {
            val parsed = runCatching { json.decodeFromString<List<StoredSessionRecord>>(raw) }.getOrNull().orEmpty()
            parsed.forEach { item ->
                if (item.sessionId.isNotBlank()) {
                    val safeRoot = item.root?.takeIf { token -> ChatPage.entries.any { it.token == token } }
                    loaded[item.sessionId] = StoredInspectorEntry(open = item.open, root = safeRoot)
                }
            }
            trimToLimit(loaded)
        }
        cache = loaded
        return loaded
    }

    private fun trimToLimit(map: LinkedHashMap<String, StoredInspectorEntry>) {
        val limit = maxEntries.coerceAtLeast(1)
        while (map.size > limit) {
            val oldest = map.entries.iterator().next().key
            map.remove(oldest)
        }
    }

    private fun persistLocked(map: LinkedHashMap<String, StoredInspectorEntry>) {
        val payload = map.entries.map { (id, entry) ->
            StoredSessionRecord(sessionId = id, open = entry.open, root = entry.root)
        }
        preferences.edit().putString(KEY_SESSION_STATES, json.encodeToString(payload)).apply()
    }

    @Serializable
    private data class StoredSessionRecord(
        val sessionId: String,
        val open: Boolean,
        val root: String? = null,
    )

    private data class StoredInspectorEntry(
        val open: Boolean,
        val root: String?,
    )

    companion object {
        const val PREFS_NAME = "infinity_ui_state"
        const val KEY_SESSION_STATES = "chat_inspector_session_states"
        const val DEFAULT_MAX_ENTRIES = 200
    }
}
