package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.skills.InMemoryPreferences
import org.junit.Test

class ChatInspectorPreferenceStoreTest {

    @Test
    fun `按各自会话独立持久化展开状态与根页签并跨实例恢复`() {
        val prefs = InMemoryPreferences()
        val store = ChatInspectorPreferenceStore(prefs)

        store.record("session-a", open = true, root = ChatPage.Git)
        store.record("session-b", open = false, root = ChatPage.Explorer)
        store.record("session-c", open = true, root = ChatPage.Run)

        val reloaded = ChatInspectorPreferenceStore(prefs)
        assertThat(reloaded.initialPagesToken("session-a")).isEqualTo("git")
        assertThat(reloaded.initialPagesToken("session-b")).isEmpty()
        assertThat(reloaded.lastRootToken("session-b")).isEqualTo("explorer")
        assertThat(reloaded.initialPagesToken("session-c")).isEqualTo("run")
        assertThat(reloaded.initialPagesToken("session-unknown")).isEmpty()
    }

    @Test
    fun `收起检视器后保留上次根页签且不再自动展开`() {
        val prefs = InMemoryPreferences()
        val store = ChatInspectorPreferenceStore(prefs)

        store.record("session-a", open = true, root = ChatPage.Explorer)
        store.record("session-a", open = false, root = null)

        val reloaded = ChatInspectorPreferenceStore(prefs)
        assertThat(reloaded.initialPagesToken("session-a")).isEmpty()
        assertThat(reloaded.lastRootToken("session-a")).isEqualTo("explorer")
    }

    @Test
    fun `删除会话时清理对应的检视器持久化状态`() {
        val prefs = InMemoryPreferences()
        val store = ChatInspectorPreferenceStore(prefs)

        store.record("session-a", open = true, root = ChatPage.Run)
        store.record("session-b", open = true, root = ChatPage.Git)
        store.deleteSession("session-a")

        val reloaded = ChatInspectorPreferenceStore(prefs)
        assertThat(reloaded.read("session-a")).isNull()
        assertThat(reloaded.initialPagesToken("session-a")).isEmpty()
        assertThat(reloaded.initialPagesToken("session-b")).isEqualTo("git")
    }

    @Test
    fun `损坏JSON与未知页签安全降级且按容量上限淘汰最旧条目`() {
        val prefs = InMemoryPreferences()
        prefs.edit().putString(ChatInspectorPreferenceStore.KEY_SESSION_STATES, "{broken").apply()
        val recovered = ChatInspectorPreferenceStore(prefs, maxEntries = 2)
        assertThat(recovered.initialPagesToken("session-a")).isEmpty()

        recovered.record("s1", open = true, root = ChatPage.Run)
        recovered.record("s2", open = true, root = ChatPage.Explorer)
        recovered.record("s3", open = true, root = ChatPage.Git)

        val reloaded = ChatInspectorPreferenceStore(prefs, maxEntries = 2)
        assertThat(reloaded.read("s1")).isNull()
        assertThat(reloaded.initialPagesToken("s2")).isEqualTo("explorer")
        assertThat(reloaded.initialPagesToken("s3")).isEqualTo("git")
    }
}
