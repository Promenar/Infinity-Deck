package com.promenar.infinity.ui.production

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.theme.*

@Composable
private fun PhaseTwoFrame(dark: Boolean = false, tab: ProductionTab = ProductionTab.OVERVIEW, shot: Boolean = false, detail: ShotDetailTab? = null, character: Boolean = false, sessionId: String = "") {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            ProductionPanelContent(
                snapshot = ProductionPhaseTwoSamples.snapshot, onAction = { _, _ -> null }, onResolve = { null }, initialTab = tab,
                initialShot = if (shot) "S1E01" to "SC01-02" else null, initialShotTab = detail,
                initialAssetId = if (character) "char-linwan" else null,
                initialPlanPreview = if (shot) ProductionPhaseTwoSamples.preview else null,
                sessionId = sessionId, services = ProductionPhaseTwoSamples.services, tasks = ProductionPhaseTwoSamples.tasks,
                channels = ProductionPhaseTwoSamples.channels, channelOrder = ProductionPhaseTwoSamples.order,
            )
        }
    }
}

@PreviewTest
@Preview(name = "二期 总览 浅中360", widthDp = 360, heightDp = 1100, locale = "zh-rCN")
@Composable
fun productionPhaseTwoOverviewPreview() = PhaseTwoFrame()

@PreviewTest
@Preview(name = "二期 总览 深英大字360", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun productionPhaseTwoOverviewDarkEnglishPreview() = PhaseTwoFrame(dark = true)

@PreviewTest
@Preview(name = "二期 分镜 深中360", widthDp = 360, heightDp = 1000, locale = "zh-rCN")
@Composable
fun productionPhaseTwoBoardPreview() = PhaseTwoFrame(dark = true, tab = ProductionTab.STORYBOARD)

@PreviewTest
@Preview(name = "二期 分镜 浅英大字360", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun productionPhaseTwoBoardEnglishPreview() = PhaseTwoFrame(tab = ProductionTab.STORYBOARD)

@PreviewTest
@Preview(name = "二期 出片 浅中360", widthDp = 360, heightDp = 1000, locale = "zh-rCN")
@Composable
fun productionPhaseTwoOutputPreview() = PhaseTwoFrame(tab = ProductionTab.OUTPUT)

@PreviewTest
@Preview(name = "二期 出片 深英大字360", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun productionPhaseTwoOutputEnglishPreview() = PhaseTwoFrame(dark = true, tab = ProductionTab.OUTPUT)

@PreviewTest
@Preview(name = "二期 镜头分镜 浅中360", widthDp = 360, heightDp = 1200, locale = "zh-rCN")
@Composable
fun productionPhaseTwoShotBoardPreview() = PhaseTwoFrame(shot = true, detail = ShotDetailTab.STORYBOARD)

@PreviewTest
@Preview(name = "二期 镜头分镜 深英大字360", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun productionPhaseTwoShotBoardEnglishPreview() = PhaseTwoFrame(dark = true, shot = true, detail = ShotDetailTab.STORYBOARD)

@PreviewTest
@Preview(name = "二期 镜头候选 浅中360", widthDp = 360, heightDp = 1000, locale = "zh-rCN")
@Composable
fun productionPhaseTwoShotCandidatesPreview() = PhaseTwoFrame(shot = true, detail = ShotDetailTab.CANDIDATES)

@PreviewTest
@Preview(name = "二期 镜头候选 深英大字360", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun productionPhaseTwoShotCandidatesEnglishPreview() = PhaseTwoFrame(dark = true, shot = true, detail = ShotDetailTab.CANDIDATES)

@PreviewTest
@Preview(name = "二期 角色参考包 浅中360", widthDp = 360, heightDp = 1200, locale = "zh-rCN")
@Composable
fun productionPhaseTwoCharacterPreview() = PhaseTwoFrame(character = true)

@PreviewTest
@Preview(name = "二期 角色参考包 深英大字360", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun productionPhaseTwoCharacterEnglishPreview() = PhaseTwoFrame(dark = true, character = true)

@PreviewTest
@Preview(name = "二期 角色参考包 内屏", widthDp = 720, heightDp = 1000, locale = "zh-rCN")
@Composable
fun productionPhaseTwoCharacterFoldPreview() = PhaseTwoFrame(character = true)

@PreviewTest
@Preview(name = "二期 剪辑 内屏", widthDp = 720, heightDp = 1000, locale = "zh-rCN")
@Composable
fun productionPhaseTwoCutFoldPreview() = PhaseTwoFrame(tab = ProductionTab.CUT)

@PreviewTest
@Preview(name = "三期 剪辑 浅中360", widthDp = 360, heightDp = 1200, locale = "zh-rCN")
@Composable
fun productionPhaseThreeCutPreview() = PhaseTwoFrame(tab = ProductionTab.CUT, sessionId = "preview")

@PreviewTest
@Preview(name = "三期 剪辑 深英大字360", widthDp = 360, heightDp = 1300, locale = "en", fontScale = 1.3f)
@Composable
fun productionPhaseThreeCutEnglishPreview() = PhaseTwoFrame(dark = true, tab = ProductionTab.CUT, sessionId = "preview")
