package com.promenar.infinity.background.indexing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IndexForegroundStartGateTest {
    @Test
    fun `只在可见且有活动文档时启动`() {
        val gate = IndexForegroundStartGate()
        assertThat(gate.observe(hasDocuments = true, visible = false)).isFalse()
        assertThat(gate.observe(hasDocuments = true, visible = true)).isTrue()
        assertThat(gate.observe(hasDocuments = true, visible = true)).isFalse()
    }

    @Test
    fun `启动失败后进度变化不会重复启动回到前台可重试一次`() {
        val gate = IndexForegroundStartGate()
        assertThat(gate.observe(true, true)).isTrue()
        gate.startRejected()
        assertThat(gate.observe(true, true)).isFalse()
        assertThat(gate.observe(true, false)).isFalse()
        assertThat(gate.observe(true, true)).isTrue()
    }

    @Test
    fun `服务停止期间新批次可在销毁回调后启动`() {
        val gate = IndexForegroundStartGate()
        assertThat(gate.observe(true, true)).isTrue()
        assertThat(gate.observe(false, true)).isFalse()
        assertThat(gate.observe(true, true)).isFalse()
        assertThat(gate.serviceStopped(queueWasEmpty = true, hasDocuments = true, visible = true)).isTrue()
    }

    @Test
    fun `控制器错过空队列状态时仍能在销毁后启动新批次`() {
        val gate = IndexForegroundStartGate()
        assertThat(gate.observe(true, true)).isTrue()
        assertThat(gate.serviceStopped(queueWasEmpty = true, hasDocuments = true, visible = true)).isTrue()
    }

    @Test
    fun `超时或提升失败后本轮不重试`() {
        val gate = IndexForegroundStartGate()
        assertThat(gate.observe(true, true)).isTrue()
        gate.suppressRestart()
        assertThat(gate.serviceStopped(queueWasEmpty = false, hasDocuments = true, visible = true)).isFalse()
        assertThat(gate.observe(true, true)).isFalse()
    }
}
