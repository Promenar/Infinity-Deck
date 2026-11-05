package com.promenar.infinity.background.indexing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IndexVisibleActivitySetTest {
    @Test
    fun `多窗口中最后一个可见页面停止时立即变为不可见`() {
        val visible = IndexVisibleActivitySet<Any>()
        val first = Any()
        val second = Any()
        assertThat(visible.started(first)).isTrue()
        assertThat(visible.started(second)).isTrue()
        assertThat(visible.stopped(first)).isTrue()
        assertThat(visible.stopped(second)).isFalse()
    }

    @Test
    fun `重复生命周期回调不会留下错误的可见计数`() {
        val visible = IndexVisibleActivitySet<Any>()
        val activity = Any()
        assertThat(visible.started(activity)).isTrue()
        assertThat(visible.started(activity)).isTrue()
        assertThat(visible.stopped(activity)).isFalse()
        assertThat(visible.stopped(activity)).isFalse()
    }
}
