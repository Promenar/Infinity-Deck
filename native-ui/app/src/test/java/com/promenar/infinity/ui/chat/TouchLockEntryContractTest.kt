package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 触摸锁入口：窄窗口在顶栏 ⋮ 左侧，宽窗口在检视器卡片底栏与边缘条，运行坞里不再放锁。 */
class TouchLockEntryContractTest {
    private val screen = Files.readAllBytes(Path.of("app/src/main/java/com/promenar/infinity/ui/chat/ChatScreen.kt")).toString(Charsets.UTF_8)

    @Test
    fun `宽窗口检视器的卡片与边缘条在生成中都提供触摸锁`() {
        val entry = "onLockTouch = actions.onLockTouch.takeIf { uiState.isGenerating }"
        val card = screen.substringAfter("ChatInspectorCard(").substringBefore(") {")
        val strip = screen.substringAfter("ChatInspectorStrip(").substringBefore("\n                        )")
        assertThat(card).contains(entry)
        assertThat(strip).contains(entry)
        assertThat(screen).contains("onLockTouch = actions.onLockTouch.takeIf { uiState.isGenerating }")
    }

    @Test
    fun `窄窗口的锁在顶栏，只在生成中可点；运行坞不再接锁`() {
        assertThat(screen).contains("touchLock = if (inspectorMode) null else ChatTopBarTouchLock(enabled = uiState.isGenerating, onLock = actions.onLockTouch)")
        val dock = screen.substringAfter("components.RunDockRow(").substringBefore("\n                                    )")
        assertThat(dock).doesNotContain("onLockTouch")
    }
}
