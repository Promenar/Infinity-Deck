package com.promenar.infinity.ui.common.motion

import com.promenar.infinity.ui.theme.GenerationIndicatorStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexingIndicatorLevelsTest {
    private val level = FloatArray(32)
    private val color = IntArray(32)

    private fun levels(state: IndexMotionState, n: Int, graphTrack: Boolean = false, separate: Boolean = false, t: Float = 0.37f) {
        level.fill(-1f)
        color.fill(-1)
        indexTrackLevels(state, graphTrack, separate, n, t, level, color)
    }

    private fun lit(n: Int) = (0 until n).count { level[it] >= IndexLedOff }

    @Test
    fun `每种生成指示都映射到一种索引指示，交换机口与两种 ∞ 分轨`() {
        GenerationIndicatorStyle.entries.forEach { it.indexingKind() }
        assertEquals(IndexIndicatorKind.MATRIX, GenerationIndicatorStyle.MATRIX.indexingKind())
        assertEquals(IndexIndicatorKind.BAR, GenerationIndicatorStyle.WAVE.indexingKind())
        assertEquals(IndexIndicatorKind.STACKED, GenerationIndicatorStyle.PORTS.indexingKind())
        assertEquals(
            setOf(IndexIndicatorKind.STACKED, IndexIndicatorKind.INFINITY_LINE, IndexIndicatorKind.INFINITY_LED),
            IndexIndicatorKind.entries.filter { it.separateTracks }.toSet(),
        )
        IndexIndicatorKind.entries.forEach { assertTrue(it.trackUnits <= 32) }
    }

    @Test
    fun `向量化时已完成的格子常亮且只用主色，前沿之后熄灭`() {
        levels(IndexMotionState(IndexMotionPhase.VECTORIZING, vectorProgress = 0.5f), n = 30)
        for (i in 0 until 15) {
            assertEquals(0.7f, level[i], 0f)
            assertEquals(IndexLed.PRIMARY, color[i])
        }
        for (i in 16 until 30) assertEquals(0f, level[i], 0f)
    }

    @Test
    fun `重试等待时前沿只做压暗的等间隔闪`() {
        val state = IndexMotionState(IndexMotionPhase.RETRYING, vectorProgress = 0.5f)
        levels(state, n = 16, t = 0.1f)
        assertEquals(0.35f, level[8], 0f)
        levels(state, n = 16, t = 0.5f)
        assertEquals(0f, level[8], 0f)
        assertEquals(IndexLed.PRIMARY, color[8])
    }

    @Test
    fun `分轨样式抽图谱时向量轨压暗常亮，图谱轨按进度换图谱色`() {
        val state = IndexMotionState(IndexMotionPhase.EXTRACTING_GRAPH, vectorProgress = 1f, graphProgress = 0.25f, withGraph = true)
        levels(state, n = 8, graphTrack = false, separate = true)
        assertEquals(8, lit(8))
        assertTrue((0 until 8).all { color[it] == IndexLed.PRIMARY && level[it] < 0.6f })
        levels(state, n = 8, graphTrack = true, separate = true)
        assertEquals(IndexLed.GRAPH, color[0])
        assertEquals(IndexLed.GRAPH, color[1])
        assertEquals(0f, level[7], 0f)
    }

    @Test
    fun `共用轨道抽图谱时未抽取的格子以压暗主色表示向量已就绪`() {
        levels(IndexMotionState(IndexMotionPhase.EXTRACTING_GRAPH, vectorProgress = 1f, graphProgress = 0.1f, withGraph = true), n = 30)
        assertEquals(IndexLed.GRAPH, color[0])
        assertEquals(0.22f, level[29], 0f)
        assertEquals(IndexLed.PRIMARY, color[29])
    }

    @Test
    fun `等待确认只让居中几格琥珀慢闪，其余格子不用琥珀`() {
        levels(IndexMotionState(IndexMotionPhase.NEEDS_CONFIRMATION, vectorProgress = 1f), n = 30)
        val amber = (0 until 30).filter { color[it] == IndexLed.AMBER }
        assertEquals((12..17).toList(), amber)
    }

    @Test
    fun `向量失败时琥珀落在向量前沿，图谱失败时落在图谱前沿`() {
        levels(IndexMotionState(IndexMotionPhase.FAILED, vectorProgress = 0.25f), n = 8, graphTrack = false, separate = true)
        assertEquals(IndexLed.AMBER, color[2])
        levels(IndexMotionState(IndexMotionPhase.FAILED, vectorProgress = 0.25f), n = 8, graphTrack = true, separate = true)
        assertTrue((0 until 8).none { color[it] == IndexLed.AMBER })

        levels(IndexMotionState(IndexMotionPhase.FAILED, vectorProgress = 1f, graphProgress = 0.5f), n = 8, graphTrack = true, separate = true)
        assertEquals(IndexLed.AMBER, color[4])
        levels(IndexMotionState(IndexMotionPhase.FAILED, vectorProgress = 1f, graphProgress = 0.5f), n = 8, graphTrack = false, separate = true)
        assertTrue((0 until 8).none { color[it] == IndexLed.AMBER })
    }

    @Test
    fun `关系数据包只在已抽取的格子上沿同一行移动`() {
        val n = 30
        val state = IndexMotionState(IndexMotionPhase.EXTRACTING_GRAPH, vectorProgress = 1f, graphProgress = 0.6f, withGraph = true)
        val litCells = litCount(state.graphProgress, n)
        val row = litCells % 3
        var t = 0f
        while (t < 6f) {
            levels(state, n, t = t)
            val before = color.copyOf()
            matrixRelationPacket(state.graphProgress, rows = 3, n = n, t = t, outLevel = level, outColor = color)
            for (i in 0 until n) {
                if (color[i] != before[i] || color[i] == IndexLed.HOT) {
                    assertTrue("t=$t i=$i 越过前沿", i < litCells)
                    assertEquals("t=$t i=$i 离开前沿所在行", row, i % 3)
                }
            }
            t += 0.05f
        }
    }

    @Test
    fun `排队时只有首格呼吸，进度满时没有前沿`() {
        levels(IndexMotionState(IndexMotionPhase.QUEUED), n = 16)
        assertEquals(1, lit(16))
        assertEquals(16, litCount(1f, 16))
        assertEquals(0, litCount(-0.5f, 16))
    }
}
