package com.promenar.infinity.ui.hub

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.model.Agent
import org.junit.jupiter.api.Test

class HubThreePaneTest {
    @Test
    fun `折叠屏内屏与平板分三列，外屏、横屏手机与较窄的内屏不分`() {
        assertThat(hubThreePaneAvailable(widthDp = 814, heightDp = 900)).isTrue()
        assertThat(hubThreePaneAvailable(widthDp = 1280, heightDp = 800)).isTrue()
        assertThat(hubThreePaneAvailable(widthDp = 411, heightDp = 900)).isFalse()
        assertThat(hubThreePaneAvailable(widthDp = 720, heightDp = 900)).isFalse()
        assertThat(hubThreePaneAvailable(widthDp = 900, heightDp = 411)).isFalse()
    }

    @Test
    fun `内屏 814dp 三列为 80、296、438，展开后极简条 40、会话 774`() {
        val three = hubPaneGeometry(814.dp, railWidth = 80.dp, expanded = false)
        assertThat(three.listStart).isEqualTo(80.dp)
        assertThat(three.detailStart).isEqualTo(376.dp)
        assertThat(three.detailWidth).isEqualTo(438.dp)
        assertThat(three.shift).isEqualTo(336.dp)

        val expanded = hubPaneGeometry(814.dp, railWidth = 80.dp, expanded = true)
        assertThat(expanded.detailStart).isEqualTo(40.dp)
        assertThat(expanded.detailWidth).isEqualTo(774.dp)
        assertThat(expanded.shift).isEqualTo(336.dp)
    }

    @Test
    fun `会话列按目标宽度布局，只靠平移从三列位置移到极简条右侧`() {
        // 展开：已按 774dp 布局在 40dp 处，起点平移回三列时的左缘 376dp，终点不平移。
        val expanding = hubPaneGeometry(814.dp, railWidth = 80.dp, expanded = true)
        assertThat(expanding.detailStart + expanding.detailTranslation(0f)).isEqualTo(376.dp)
        assertThat(expanding.detailTranslation(1f)).isEqualTo(0.dp)
        // 还原：已按 438dp 布局在 376dp 处，起点在极简条右侧 40dp，终点不平移。
        val restoring = hubPaneGeometry(814.dp, railWidth = 80.dp, expanded = false)
        assertThat(restoring.detailStart + restoring.detailTranslation(1f)).isEqualTo(40.dp)
        assertThat(restoring.detailTranslation(0f)).isEqualTo(0.dp)
        // 过程中两种布局的会话左缘一致，切换目标不跳位。
        assertThat(expanding.detailStart + expanding.detailTranslation(0.4f))
            .isEqualTo(restoring.detailStart + restoring.detailTranslation(0.4f))
    }

    @Test
    fun `左侧有安全区时导航栏变宽，列表与会话一起右移，展开后仍停在极简条右侧`() {
        val three = hubPaneGeometry(814.dp, railWidth = 104.dp, expanded = false)
        assertThat(three.detailStart).isEqualTo(400.dp)
        assertThat(three.shift).isEqualTo(360.dp)
        assertThat(hubPaneGeometry(814.dp, railWidth = 104.dp, expanded = true).detailStart).isEqualTo(40.dp)
    }

    private val coder = AgentDisplayItem(Agent(id = "coder", name = "Coder"), "代码助手", "")
    private val writer = AgentDisplayItem(Agent(id = "writer", name = "Writer"), "写作助手", "")

    private fun session(
        id: String,
        agentId: String,
        updatedAt: Long,
        pinned: Boolean = false,
        parentSessionId: String? = null,
    ) = Session(
        id = id,
        agentId = agentId,
        title = id,
        isPinned = pinned,
        parentSessionId = parentSessionId,
        createdAt = 0,
        updatedAt = updatedAt,
    )

    @Test
    fun `图标条列出置顶与最近会话，当前会话不在其中时排在最前`() {
        val sessions = mapOf(
            "coder" to (1..6).map { session("c$it", "coder", it * 10L) } + session("p", "coder", 1, pinned = true),
            "writer" to listOf(session("w1", "writer", 15)),
            "__system__" to listOf(session("rag", "__system__", 99)),
        )
        val strip = hubStripEntries(listOf(coder, writer), sessions, selectedSessionId = null)
        assertThat(strip.map { it.session.id }).containsExactly("p", "c6", "c5", "c4", "c3", "c2").inOrder()
        assertThat(strip.first().agent).isEqualTo(coder)

        // 较早的 c1 不在“最近”里：选中后排在最前；已在其中的不重复。
        assertThat(hubStripEntries(listOf(coder, writer), sessions, "c1").map { it.session.id }.take(2))
            .containsExactly("c1", "p").inOrder()
        assertThat(hubStripEntries(listOf(coder, writer), sessions, "c6").map { it.session.id })
            .isEqualTo(strip.map { it.session.id })
        // 系统助手名下的会话不出现。
        assertThat(hubStripEntries(listOf(coder, writer), sessions, "rag").map { it.session.id }).doesNotContain("rag")
    }

    @Test
    fun `极简条按父会话聚为独立家族，补齐根祖先并抵御孤儿与环`() {
        val f1Root = session("r1", "coder", updatedAt = 1, pinned = true)
        val f1BranchA = session("b1a", "coder", updatedAt = 50, parentSessionId = "r1")
        val f1BranchB = session("b1b", "coder", updatedAt = 58, parentSessionId = "r1")
        val f2Root = session("r2", "coder", updatedAt = 2) // 较早、未置顶，应由分支反查补入
        val f2Branch = session("b2", "coder", updatedAt = 60, parentSessionId = "r2")
        val unrelated1 = session("u1", "coder", updatedAt = 55)
        val unrelated2 = session("u2", "coder", updatedAt = 54)
        val unrelated3 = session("u3", "coder", updatedAt = 10)
        val sessions = mapOf(
            "coder" to listOf(f1Root, f1BranchA, f1BranchB, f2Root, f2Branch, unrelated1, unrelated2, unrelated3),
        )

        val entries = hubStripEntries(listOf(coder), sessions, selectedSessionId = "b1a")
        assertThat(entries.map { it.session.id }).contains("r2")

        val clusters = hubStripClusters(entries)
        val clusterR1 = clusters.first { it.root.session.id == "r1" }
        val clusterR2 = clusters.first { it.root.session.id == "r2" }
        assertThat(clusterR1.branches.map { it.session.id }).containsExactly("b1a", "b1b")
        assertThat(clusterR2.branches.map { it.session.id }).containsExactly("b2")

        // 孤立分支（父会话不存在）退化为独立根；循环 parentSessionId 不死循环。
        val orphan = HubStripEntry(session("orphan", "coder", 10, parentSessionId = "missing"), coder)
        val cycA = HubStripEntry(session("cyc-a", "coder", 12, parentSessionId = "cyc-b"), coder)
        val cycB = HubStripEntry(session("cyc-b", "coder", 11, parentSessionId = "cyc-a"), coder)
        val safeClusters = hubStripClusters(listOf(orphan, cycA, cycB))
        assertThat(safeClusters).hasSize(2)
        assertThat(safeClusters[0].root.session.id).isEqualTo("orphan")
        assertThat(safeClusters[0].branches).isEmpty()
        assertThat(safeClusters[1].root.session.id).isEqualTo("cyc-a")
        assertThat(safeClusters[1].branches.map { it.session.id }).containsExactly("cyc-b")
    }
}
