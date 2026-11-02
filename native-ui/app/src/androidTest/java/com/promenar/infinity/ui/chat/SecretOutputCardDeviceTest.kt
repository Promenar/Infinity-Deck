package com.promenar.infinity.ui.chat

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.secret.SessionSecretVault
import com.promenar.infinity.domain.secret.SecretSource
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 输出卡在值失效（销毁、同名新值替换）后收成一行记录，不再提供显示、复制与“更多”菜单里的另存、销毁。 */
@RunWith(AndroidJUnit4::class)
class SecretOutputCardDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val vault = SessionSecretVault()

    private fun showCards() {
        rule.setContent {
            InfinityTheme {
                val cards by vault.observeCards("s").collectAsState(emptyList())
                cards.forEach { card ->
                    SecretOutputCardView(
                        card = card,
                        actions = SecretOutputActions(onDestroy = { vault.destroy("s", card.slot.name) }),
                        authenticator = { _, _, _ -> },
                    )
                }
            }
        }
    }

    @Test
    fun 销毁后卡片收成一行() {
        vault.put("s", "DEMO_TOKEN", "演示令牌", SecretSource.UserInput, "synthetic-device-value".toByteArray())
        vault.show("s", "a1", "call-1", "DEMO_TOKEN", "展示演示令牌")
        showCards()
        rule.onNodeWithTag(UiTags.CHAT_SECRET_OUTPUT_REVEAL).assertExists()

        // 另存与销毁收在“更多”菜单里，先打开菜单。
        rule.onNodeWithTag(UiTags.CHAT_SECRET_OUTPUT_MORE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_SECRET_OUTPUT_DESTROY).performClick()
        rule.onNodeWithTag(UiTags.CHAT_SECRET_OUTPUT_DESTROY_CONFIRM).performClick()
        rule.waitForIdle()

        rule.onAllNodesWithTag(UiTags.CHAT_SECRET_OUTPUT_CARD).assertCountEquals(1)
        listOf(
            UiTags.CHAT_SECRET_OUTPUT_REVEAL,
            UiTags.CHAT_SECRET_OUTPUT_COPY,
            UiTags.CHAT_SECRET_OUTPUT_MORE,
            UiTags.CHAT_SECRET_OUTPUT_SAVE_AS,
            UiTags.CHAT_SECRET_OUTPUT_DESTROY,
            UiTags.CHAT_SECRET_OUTPUT_VALUE,
        ).forEach { rule.onAllNodesWithTag(it).assertCountEquals(0) }
        assertThat(vault.revealBytes("s", "DEMO_TOKEN")).isNull()
    }

    @Test
    fun 同名新值只让新卡可用() {
        vault.put("s", "DEMO_TOKEN", "演示令牌", SecretSource.UserInput, "synthetic-first".toByteArray())
        vault.show("s", "a1", "old", "DEMO_TOKEN", "第一次展示")
        showCards()
        rule.onAllNodesWithTag(UiTags.CHAT_SECRET_OUTPUT_REVEAL).assertCountEquals(1)

        vault.put("s", "DEMO_TOKEN", "演示令牌", SecretSource.UserInput, "synthetic-second".toByteArray())
        vault.show("s", "a2", "new", "DEMO_TOKEN", "第二次展示")
        rule.waitForIdle()

        rule.onAllNodesWithTag(UiTags.CHAT_SECRET_OUTPUT_CARD).assertCountEquals(2)
        rule.onAllNodesWithTag(UiTags.CHAT_SECRET_OUTPUT_REVEAL).assertCountEquals(1)
    }
}
