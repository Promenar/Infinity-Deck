package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.mediagen.comfy.ComfyAuthKind
import com.promenar.infinity.data.mediagen.comfy.ComfyHost
import com.promenar.infinity.domain.mediagen.MediaGroup
import org.junit.Test

class ComfyAgentConfigureTest {
    private val main = ComfyHost("main", "MAIN", "http://main:8188", ComfyAuthKind.NONE)
    private val lab = ComfyHost("lab", "LAB", "http://lab:8188", ComfyAuthKind.NONE)

    @Test fun `未填写目标时指令保持原样，填写后附在末尾`() {
        assertThat(comfyAgentInstruction("配置主机", null)).isEqualTo("配置主机")
        assertThat(comfyAgentInstruction("配置主机", "目标工作流：Qwen Image 2.1 文生图。"))
            .isEqualTo("配置主机\n\n目标工作流：Qwen Image 2.1 文生图。")
    }

    @Test fun `分组末尾只列出在该分组下没有模板的主机`() {
        val groups = mapOf("main" to setOf(MediaGroup.VIDEO), "lab" to setOf(MediaGroup.IMAGE, MediaGroup.VIDEO))
        assertThat(comfyHostsMissingGroup(MediaGroup.IMAGE, listOf(main, lab), groups)).containsExactly(main)
        assertThat(comfyHostsMissingGroup(MediaGroup.VIDEO, listOf(main, lab), groups)).isEmpty()
        assertThat(comfyHostsMissingGroup(MediaGroup.IMAGE, listOf(main, lab), emptyMap())).containsExactly(main, lab).inOrder()
    }

    @Test fun `声音分组不显示配置行`() {
        assertThat(comfyHostsMissingGroup(MediaGroup.AUDIO, listOf(main), emptyMap())).isEmpty()
    }
}
