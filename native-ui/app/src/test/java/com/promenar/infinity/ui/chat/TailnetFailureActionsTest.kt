package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.chat.TailnetFailureAction.ENABLE
import com.promenar.infinity.ui.chat.TailnetFailureAction.LOGIN
import com.promenar.infinity.ui.chat.TailnetFailureAction.RETRY
import com.promenar.infinity.ui.chat.TailnetFailureAction.SETTINGS
import com.promenar.infinity.ui.chat.TailnetFailureAction.SWITCH_MODEL
import org.junit.Test

class TailnetFailureActionsTest {
    private fun reachable(actions: TailnetFailureActions) = actions.row + actions.menu

    @Test
    fun 每种失败都能重试与换用公网模型且行内最多两个按钮() {
        TailnetFailureKind.entries.forEach { kind ->
            listOf(true, false).forEach { hasSettings ->
                val actions = tailnetFailureActions(kind, hasSettings)
                assertThat(reachable(actions)).containsAtLeast(RETRY, SWITCH_MODEL)
                assertThat(actions.row.size).isAtMost(2)
                assertThat(reachable(actions)).containsNoDuplicates()
            }
        }
    }

    @Test
    fun 未开启或未登录且有设置入口时修复为主操作_换模型进菜单() {
        assertThat(tailnetFailureActions(TailnetFailureKind.DISABLED, hasSettings = true))
            .isEqualTo(TailnetFailureActions(row = listOf(RETRY, ENABLE), menu = listOf(SWITCH_MODEL)))
        assertThat(tailnetFailureActions(TailnetFailureKind.NEEDS_LOGIN, hasSettings = true))
            .isEqualTo(TailnetFailureActions(row = listOf(RETRY, LOGIN), menu = listOf(SWITCH_MODEL)))
    }

    @Test
    fun 不可达且有设置入口时设置进菜单_重试为主操作() {
        assertThat(tailnetFailureActions(TailnetFailureKind.UNREACHABLE, hasSettings = true))
            .isEqualTo(TailnetFailureActions(row = listOf(SWITCH_MODEL, RETRY), menu = listOf(SETTINGS)))
    }

    @Test
    fun 没有设置入口时不出现开启登录与设置_也不显示菜单() {
        TailnetFailureKind.entries.forEach { kind ->
            val actions = tailnetFailureActions(kind, hasSettings = false)
            assertThat(actions).isEqualTo(TailnetFailureActions(row = listOf(SWITCH_MODEL, RETRY), menu = emptyList()))
        }
    }
}
