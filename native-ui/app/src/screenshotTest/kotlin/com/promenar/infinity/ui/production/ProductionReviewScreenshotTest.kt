package com.promenar.infinity.ui.production

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.production.ReviewEntry
import com.promenar.infinity.data.production.ReviewKind
import com.promenar.infinity.data.production.ReviewTarget
import com.promenar.infinity.ui.theme.*

// 审核构件：页头“发送”可点与不可点；资产版本、候选与场的结论标签（待审、已批准未发送、已否决附原因、未发送的建议），
// 以及批准、否决、建议三个动作。
@Composable
private fun ReviewFrame(dark: Boolean) {
    val zhou = ReviewTarget.assetVersion("char-zhou", 1)
    val take02 = ReviewTarget.take("S1E01", "SC01-02", "t02")
    val take03 = ReviewTarget.take("S1E01", "SC01-02", "t03")
    val scene = ReviewTarget.scene("S1E01", "SC02")
    val production = ProductionPhaseTwoSamples.production.copy(reviews = ProductionPhaseTwoSamples.production.reviews + listOf(
        ReviewEntry(scene, ReviewKind.REJECT, "货架之间缺少过渡镜头", at = 4_000),
    ))
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CompositionLocalProvider(LocalProductionSend provides ProductionSendState(count = production.unsentCount(), sending = false) {}) {
                    ProductionTopBar("SC01-02 林晚回头", listOf("第 1 集", "第 1 场", "4.5 秒"), onBack = {}, metaAccent = "生成 7 次")
                }
                CompositionLocalProvider(LocalProductionSend provides ProductionSendState(count = 0, sending = false) {}) {
                    ProductionTopBar("雾港", listOf("剧集"), onBack = null)
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    ProductionReviewBlock(production, zhou, listOf("老周", "v1"), busy = false, onRun = {}, onSubmit = { null })
                    ProductionReviewBlock(production, take02, listOf("SC01-02", "候选 1"), busy = false, onRun = {}, onSubmit = { null })
                    ProductionReviewBlock(production, take03, listOf("SC01-02", "候选 2"), busy = false, onRun = {}, onSubmit = { null })
                    ProductionReviewBlock(production, scene, listOf("第 2 场"), busy = true, onRun = {}, onSubmit = { null })
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "审核构件 浅中360", widthDp = 360, heightDp = 1100, locale = "zh-rCN")
@Composable
fun productionReviewLightChinesePreview() = ReviewFrame(dark = false)

@PreviewTest
@Preview(name = "审核构件 深英大字360", widthDp = 360, heightDp = 1400, locale = "en", fontScale = 1.3f)
@Composable
fun productionReviewDarkEnglishPreview() = ReviewFrame(dark = true)
