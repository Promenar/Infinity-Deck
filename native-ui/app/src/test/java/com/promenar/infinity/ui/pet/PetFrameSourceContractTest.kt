package com.promenar.infinity.ui.pet

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 宠物按目标帧率推进时每次只渲染一帧：推进后直接让绘制与跟随布局失效（不经快照状态，否则重绘推迟一帧、多画一帧），
 * 图层自带图形层（否则每次重绘连带重画会话列表，毛玻璃浮岛与顶栏下一帧跟着重做模糊）。
 */
class PetFrameSourceContractTest {
    private val dir = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        .resolve("com/promenar/infinity/ui/pet")

    @Test fun `帧计数不是快照状态，推进后直接通知失效`() {
        val runtime = dir.resolve("PetRuntime.kt").readText()
        assertThat(runtime).doesNotContain("mutableLongStateOf")
        assertThat(runtime).contains("fun publishFrame()")
        val overlay = dir.resolve("PetOverlay.kt").readText()
        assertThat(overlay).contains("runtime.publishFrame()")
        assertThat(overlay).doesNotContain("runtime.frame")
        assertThat(dir.resolve("PetVideoLayer.kt").readText()).doesNotContain("runtime.frame")
    }

    @Test fun `宠物图层有独立图形层`() {
        val overlay = dir.resolve("PetOverlay.kt").readText()
        val layer = overlay.substringAfter("    Box(\n        modifier\n            .fillMaxSize()").substringBefore(".testTag(PET_LAYER_TAG)")
        assertThat(layer).contains(".graphicsLayer()")
    }
}
