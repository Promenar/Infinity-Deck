package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResourceExplorerHostPolicyTest {
    @Test
    fun `导入只在当前会话的专用位置且空闲时提供`() {
        assertThat(canImportIntoSessionLocation("alpha", "session:alpha", false)).isTrue()
        assertThat(canImportIntoSessionLocation("alpha", "session:alpha", true)).isFalse()
        assertThat(canImportIntoSessionLocation("alpha", "session:other", false)).isFalse()
        assertThat(canImportIntoSessionLocation("alpha", "workspace:alpha", false)).isFalse()
        assertThat(canImportIntoSessionLocation("alpha", "mount:alpha", false)).isFalse()
        assertThat(canImportIntoSessionLocation("alpha", null, false)).isFalse()
    }
}
