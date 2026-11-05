package com.promenar.infinity.data.library.ask

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultChunkLocatorTest {
    @Test fun `精确命中返回原文范围`() {
        val body = "开头 资料正文 结尾"
        assertThat(body.substring(DefaultChunkLocator.locate(body, "资料正文", emptyList())!!)).isEqualTo("资料正文")
    }

    @Test fun `空白归一化后范围仍覆盖原文`() {
        val body = "开头\n甲  乙\n\t丙 结尾"
        val range = DefaultChunkLocator.locate(body, "甲 乙 丙", emptyList())!!
        assertThat(body.substring(range)).isEqualTo("甲  乙\n\t丙")
    }

    @Test fun `没有分块时返回正文中最早的关键词`() {
        val body = "alpha 与 BETA"
        assertThat(body.substring(DefaultChunkLocator.locate(body, "无", listOf("beta", "alpha"))!!)).isEqualTo("alpha")
    }

    @Test fun `全部找不到返回空`() {
        assertThat(DefaultChunkLocator.locate("正文", "缺失", listOf("无"))).isNull()
    }
}
