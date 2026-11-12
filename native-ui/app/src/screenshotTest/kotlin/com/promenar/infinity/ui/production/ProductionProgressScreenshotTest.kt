package com.promenar.infinity.ui.production

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.*

// 进行中的衔接链与登记项（含未回报）、派生与拼接候选的来源和分段、失败的链段、总览“进行中”与“未登记”、配乐版本。
@Composable
private fun ProgressFrame(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        }
    }
}

@Composable
private fun ShotProgress(dark: Boolean = false) = ProgressFrame(dark) {
    val samples = ProductionProgressSamples
    val shot = samples.shot
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TakeThumb(shot.takes.first(), 1, viewing = false, selected = false) {}
        PlaceholderThumb(ShotPlaceholder.Chain(samples.chain), viewing = true, now = 1) {}
        PlaceholderThumb(ShotPlaceholder.Run(samples.staleRun), viewing = false, now = System.currentTimeMillis()) {}
    }
    ProductionPlaceholderCard(ShotPlaceholder.Chain(samples.chain), now = 1, busy = false, onRun = {}, episodeId = "S1E01", shotCode = shot.code)
    ProductionPlaceholderCard(ShotPlaceholder.Run(samples.run), now = 1, busy = false, onRun = {}, episodeId = "S1E01", shotCode = shot.code)
}

@Composable
private fun TakeProvenance(dark: Boolean = false) = ProgressFrame(dark) {
    val samples = ProductionProgressSamples
    TakeSourceSection(samples.derivedTake, samples.shot, samples.production) {}
    TakeSourceSection(samples.joinedTake, samples.shot, samples.production) {}
    TakeSegmentsSection(samples.joinedTake)
    ChainSegmentStrip(samples.failedChain)
}

@Composable
private fun OverviewProgress(dark: Boolean = false) = ProgressFrame(dark) {
    val production = ProductionProgressSamples.production
    val now = System.currentTimeMillis()
    ProductionSection(stringResource(R.string.p2_tasks)) {
        production.progressItems().forEach { ProductionProgressRow(it, production, now, busy = false, onRun = {}, onShot = { _, _ -> }) }
    }
    ProductionUnregisteredSection(production)
}

@Composable
private fun ScoreVersions(dark: Boolean = false) = ProgressFrame(dark) {
    val samples = ProductionProgressSamples
    ScoreVersionList(samples.scoreAsset, samples.production, selected = 3) {}
}

@PreviewTest
@Preview(name = "四期 镜头进行中 浅中360", widthDp = 360, heightDp = 1100, locale = "zh-rCN")
@Composable
fun productionShotProgressPreview() = ShotProgress()

@PreviewTest
@Preview(name = "四期 镜头进行中 深英大字360", widthDp = 360, heightDp = 1300, locale = "en", fontScale = 1.3f)
@Composable
fun productionShotProgressEnglishPreview() = ShotProgress(dark = true)

@PreviewTest
@Preview(name = "四期 镜头进行中 内屏", widthDp = 720, heightDp = 1000, locale = "zh-rCN")
@Composable
fun productionShotProgressFoldPreview() = ShotProgress()

@PreviewTest
@Preview(name = "四期 候选来源 浅中360", widthDp = 360, heightDp = 1200, locale = "zh-rCN")
@Composable
fun productionTakeProvenancePreview() = TakeProvenance()

@PreviewTest
@Preview(name = "四期 候选来源 深英大字360", widthDp = 360, heightDp = 1400, locale = "en", fontScale = 1.3f)
@Composable
fun productionTakeProvenanceEnglishPreview() = TakeProvenance(dark = true)

@PreviewTest
@Preview(name = "四期 候选来源 内屏", widthDp = 720, heightDp = 1100, locale = "zh-rCN")
@Composable
fun productionTakeProvenanceFoldPreview() = TakeProvenance()

@PreviewTest
@Preview(name = "四期 总览进行中 浅中360", widthDp = 360, heightDp = 1000, locale = "zh-rCN")
@Composable
fun productionOverviewProgressPreview() = OverviewProgress()

@PreviewTest
@Preview(name = "四期 总览进行中 深英大字360", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun productionOverviewProgressEnglishPreview() = OverviewProgress(dark = true)

@PreviewTest
@Preview(name = "四期 总览进行中 内屏", widthDp = 720, heightDp = 900, locale = "zh-rCN")
@Composable
fun productionOverviewProgressFoldPreview() = OverviewProgress()

@PreviewTest
@Preview(name = "四期 配乐版本 浅中360", widthDp = 360, heightDp = 700, locale = "zh-rCN")
@Composable
fun productionScoreVersionsPreview() = ScoreVersions()

@PreviewTest
@Preview(name = "四期 配乐版本 深英大字360", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Composable
fun productionScoreVersionsEnglishPreview() = ScoreVersions(dark = true)

@PreviewTest
@Preview(name = "四期 配乐版本 内屏", widthDp = 720, heightDp = 700, locale = "zh-rCN")
@Composable
fun productionScoreVersionsFoldPreview() = ScoreVersions()
