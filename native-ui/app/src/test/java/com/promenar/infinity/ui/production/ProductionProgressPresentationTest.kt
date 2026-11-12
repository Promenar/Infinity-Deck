package com.promenar.infinity.ui.production

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.*
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class ProductionProgressPresentationTest {
    private val samples = ProductionProgressSamples
    private val production = samples.production

    @Test fun `镜头占位项只含进行中的链与指向该镜头的进行中登记项`() {
        val withEnded = production.copy(runs = production.runs + samples.run.copy(id = "done", state = RunState.COMPLETED) +
            samples.run.copy(id = "other", target = RunTarget(episodeId = "S1E01", shotCode = "SC01-03")))
        val keys = withEnded.shotPlaceholders("S1E01", "SC01-02").map { it.key }
        // 失败的链 c0 与已完成、指向别的镜头的登记项都不出现；链在前。
        assertThat(keys).containsExactly("chain:c1", "run:r1", "run:r2").inOrder()
        assertThat(withEnded.shotPlaceholders("S1E01", "SC01-03").map { it.key }).containsExactly("run:other")
        assertThat(withEnded.shotPlaceholders("S1E01", "missing")).isEmpty()
    }

    @Test fun `总览进行中按链再登记项列出且只含进行中`() {
        val canceled = production.copy(runs = production.runs.map { if (it.id == "r1") it.copy(state = RunState.CANCELED) else it })
        val items = canceled.progressItems()
        assertThat(items.map { (it as? ProgressItem.Chain)?.chain?.id ?: (it as ProgressItem.Run).run.id }).containsExactly("c1", "r2").inOrder()
        assertThat((items.first() as ProgressItem.Chain).location.shot.code).isEqualTo("SC01-02")
    }

    @Test fun `未回报按 30 分钟判定且只算进行中`() {
        val started = 1_000_000L
        val run = samples.run.copy(reportedAt = started)
        val production = production.copy(runs = listOf(run, run.copy(id = "ended", state = RunState.FAILED, reportedAt = 0)))
        assertThat(production.unreportedRuns(started + RUN_UNREPORTED_MILLIS - 1)).isEqualTo(0)
        assertThat(production.unreportedRuns(started + RUN_UNREPORTED_MILLIS)).isEqualTo(1)
    }

    @Test fun `链进度与当前段序号`() {
        val chain = samples.chain
        assertThat(chain.completedSegments()).isEqualTo(1)
        assertThat(chain.progress()).isWithin(1e-6f).of(1f / 3)
        assertThat(chain.currentNumber()).isEqualTo(2)
        assertThat(chain.copy(current = 9).currentNumber()).isEqualTo(3)
        assertThat(chain.copy(segments = emptyList()).progress()).isNull()
    }

    @Test fun `链分段时间按计划时长累加且按下标排序`() {
        val shuffled = samples.chain.copy(segments = samples.chain.segments.reversed())
        assertThat(shuffled.segmentRanges()).containsExactly(0.0 to 5.0, 5.0 to 10.0, 10.0 to 14.0).inOrder()
    }

    @Test fun `登记项进度只在声明步数时给出`() {
        assertThat(samples.run.progress()).isWithin(1e-6f).of(1f / 3)
        assertThat(samples.run.copy(completedSteps = 5).progress()).isEqualTo(1f)
        assertThat(samples.staleRun.progress()).isNull()
        assertThat(samples.run.copy(steps = 0).progress()).isNull()
    }

    @Test fun `派生参数按键名排序`() {
        val derivation = TakeDerivation("t02", "超分", "upscale", mapOf("scale" to JsonPrimitive(2), "fast" to JsonPrimitive(true), "model" to JsonPrimitive("x4")))
        assertThat(derivation.paramSegments()).containsExactly("fast true", "model x4", "scale 2").inOrder()
    }

    @Test fun `未登记文件显示为项目根下的相对路径`() {
        assertThat(production.unregisteredRelative()).containsExactly("S1E01/takes/h3-ext-01.mp4", "S1E01/takes/h3-ext-02.mp4", "assets/score/draft.wav").inOrder()
    }

    @Test fun `登记项对象为镜头编号加标题或资产名称`() {
        assertThat(production.runTargetTitle(samples.run)).isEqualTo("SC01-02 林晚回头")
        assertThat(production.runTargetTitle(samples.run.copy(target = RunTarget(assetId = "snd-theme")))).isEqualTo("雾港主题")
        assertThat(production.runTargetTitle(samples.run.copy(target = RunTarget(assetId = "gone")))).isEqualTo("gone")
        assertThat(production.runTargetTitle(samples.run.copy(target = RunTarget()))).isNull()
    }
}
