package com.promenar.infinity.ui.media

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceMediaPlayersControlsTest {
    @Test
    fun `窄分屏内联叠层播放控件在宽度小于460dp时切换双行布局并让进度条独占顶行`() {
        assertThat(CompactOverlaySingleRowMinWidth.value).isEqualTo(460f)
        assertThat(useSingleRowCompactOverlayControls(320f)).isFalse()
        assertThat(useSingleRowCompactOverlayControls(412f)).isFalse()
        assertThat(useSingleRowCompactOverlayControls(459.9f)).isFalse()
        assertThat(useSingleRowCompactOverlayControls(460f)).isTrue()
        assertThat(useSingleRowCompactOverlayControls(640f)).isTrue()
    }

    @Test
    fun `内联叠层控件使用BoxWithConstraints自适应单双行且保留叠层文字配色`() {
        val path = Path.of("app/src/main/java/com/promenar/infinity/ui/media/WorkspaceMediaPlayers.kt").let {
            if (Files.exists(it)) it else Path.of("src/main/java/com/promenar/infinity/ui/media/WorkspaceMediaPlayers.kt")
        }
        val source = Files.readString(path)
        assertThat(source).contains("BoxWithConstraints(modifier = modifier.fillMaxWidth())")
        assertThat(source).contains("useSingleRowCompactOverlayControls(maxWidth.value)")
        assertThat(source).contains("CompositionLocalProvider(LocalContentColor provides overlayColor)")
    }
}
