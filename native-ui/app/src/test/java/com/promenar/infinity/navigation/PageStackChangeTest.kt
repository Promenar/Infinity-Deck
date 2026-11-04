package com.promenar.infinity.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PageStackChangeTest {
    @Test
    fun `层级加深为前进、变浅为返回，手势中为跟手返回`() {
        assertThat(pageStackChange(emptyList<String>(), listOf("files"), predictive = false)).isEqualTo(PageChange.PUSH)
        assertThat(pageStackChange(listOf("overview"), listOf("overview", "doc"), predictive = false)).isEqualTo(PageChange.PUSH)
        assertThat(pageStackChange(listOf("overview", "doc"), listOf("overview"), predictive = false)).isEqualTo(PageChange.POP)
        assertThat(pageStackChange(listOf("files"), emptyList<String>(), predictive = true)).isEqualTo(PageChange.PREDICTIVE_POP)
    }

    @Test
    fun `根页面变化为同级叠化，不论层级深浅`() {
        assertThat(pageStackChange(listOf("overview"), listOf("memory"), predictive = false)).isEqualTo(PageChange.SAME_LEVEL)
        assertThat(pageStackChange(listOf("overview", "doc", "x"), listOf("memory"), predictive = false)).isEqualTo(PageChange.SAME_LEVEL)
    }

    @Test
    fun `页面标识区分层级深度`() {
        assertThat(pageStackId(listOf("a", "doc"))).isNotEqualTo(pageStackId(listOf("doc")))
        assertThat(pageStackId(emptyList<String>())).isEqualTo("0:null")
    }
}
