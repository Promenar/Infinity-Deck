package com.promenar.infinity.ui.common

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * 窗格里打开的对话框：[InfinityBackScope] 把 Activity 调度器作为组合局部值传进对话框窗口，
 * 对话框内的 BackHandler 要经 [InfinityWindowBackScope] 改回窗口自己的调度器，返回键才先到页面内导航，而不是直接关闭对话框。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InfinityWindowBackScopeTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val calls = mutableListOf<String>()

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
    }

    private fun showDialog(windowScope: Boolean) {
        activity.setContent {
            InfinityBackScope(active = true) {
                Dialog(onDismissRequest = { calls += "dismiss" }) {
                    val inner: @Composable () -> Unit = { BackHandler { calls += "inner" } }
                    if (windowScope) InfinityWindowBackScope(inner) else inner()
                }
            }
        }
        settle()
    }

    private fun pressDialogBack() {
        (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        settle()
    }

    @Test
    fun `对话框内的返回处理优先于关闭对话框`() {
        showDialog(windowScope = true)
        pressDialogBack()
        assertThat(calls).containsExactly("inner")
    }

    @Test
    fun `不改回窗口调度器时返回直接关闭对话框`() {
        showDialog(windowScope = false)
        pressDialogBack()
        assertThat(calls).containsExactly("dismiss")
    }
}
