package com.promenar.infinity.data.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionLaunchInboxTest {
    @Test fun `消息只能取走一次，预设标题标记保留`() {
        val inbox = SessionLaunchInbox()
        inbox.offer("s1", "配置 ComfyUI")
        assertThat(inbox.pending.value).containsExactly("s1", "配置 ComfyUI")
        assertThat(inbox.take("s1")).isEqualTo("配置 ComfyUI")
        assertThat(inbox.take("s1")).isNull()
        assertThat(inbox.pending.value).isEmpty()
        assertThat(inbox.keepsTitle("s1")).isTrue()
        assertThat(inbox.keepsTitle("s2")).isFalse()
    }
}
