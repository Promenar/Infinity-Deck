package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatApprovalScrollLogicTest {
    @Test
    fun `尾部索引按 LazyColumn 实际顺序累加`() {
        val none = resolveChatTrailingItemIndices(
            pipelineGroupCount = 4,
            hasCompression = false,
            hasQuestion = false,
            hasSecretInput = false,
            hasBranchPlan = false,
        )
        assertThat(none.compression).isEqualTo(4)
        assertThat(none.question).isEqualTo(4)
        assertThat(none.secretInput).isEqualTo(4)
        assertThat(none.branchPlan).isEqualTo(4)
        assertThat(none.approval).isEqualTo(4)

        val all = resolveChatTrailingItemIndices(
            pipelineGroupCount = 4,
            hasCompression = true,
            hasQuestion = true,
            hasSecretInput = true,
            hasBranchPlan = true,
        )
        assertThat(all.compression).isEqualTo(4)
        assertThat(all.question).isEqualTo(5)
        assertThat(all.secretInput).isEqualTo(6)
        assertThat(all.branchPlan).isEqualTo(7)
        assertThat(all.approval).isEqualTo(8)

        val partial = resolveChatTrailingItemIndices(
            pipelineGroupCount = 6,
            hasCompression = false,
            hasQuestion = true,
            hasSecretInput = false,
            hasBranchPlan = true,
        )
        assertThat(partial.question).isEqualTo(6)
        assertThat(partial.branchPlan).isEqualTo(7)
        assertThat(partial.approval).isEqualTo(8)
    }

    @Test
    fun `短卡对齐底边，超长审批卡开启顶边保护时不把当前项顶出视口`() {
        assertThat(
            computeScrollIntoViewDelta(
                itemOffset = 600,
                itemSize = 200,
                viewportStart = 0,
                targetBottom = 850,
                preserveTop = true,
            ),
        ).isEqualTo(0)

        assertThat(
            computeScrollIntoViewDelta(
                itemOffset = 500,
                itemSize = 400,
                viewportStart = 0,
                targetBottom = 800,
                preserveTop = false,
            ),
        ).isEqualTo(100)

        // 展开后的审批卡高于可用视口：itemOffset=200，targetBottom=800，total bottom=1400；
        // 若不保护顶边会滚动 +600 把顶部当前待批调用顶出视口；开启保护后最多滚 +200 让顶边齐平 viewportStart=0。
        assertThat(
            computeScrollIntoViewDelta(
                itemOffset = 200,
                itemSize = 1200,
                viewportStart = 0,
                targetBottom = 800,
                preserveTop = true,
            ),
        ).isEqualTo(200)

        // 当顶边已经贴顶（或更上方）时不再继续向下滚走。
        assertThat(
            computeScrollIntoViewDelta(
                itemOffset = -40,
                itemSize = 1200,
                viewportStart = 0,
                targetBottom = 800,
                preserveTop = true,
            ),
        ).isEqualTo(0)
    }
}
