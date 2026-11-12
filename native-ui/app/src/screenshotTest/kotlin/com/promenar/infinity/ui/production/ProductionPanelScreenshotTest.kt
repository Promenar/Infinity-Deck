package com.promenar.infinity.ui.production

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.production.ManifestSync
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/* 制作面板的窄屏、大字号、中英文与深浅色布局。截图环境没有工作区媒体，缩略图与播放器显示占位。 */

@Composable
private fun Frame(dark: Boolean = false, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun Panel(
    tab: ProductionTab,
    snapshot: ProductionSnapshot = ProductionSamples.snapshot(),
    shot: Pair<String, String>? = null,
) {
    ProductionPanelContent(
        snapshot = snapshot,
        onAction = { _, _ -> null },
        onResolve = { null },
        initialTab = tab,
        initialShot = shot,
    )
}

@PreviewTest
@Preview(name = "制作面板 总览 浅中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun productionOverviewLightChinesePreview() = Frame { Panel(ProductionTab.OVERVIEW) }

@PreviewTest
@Preview(name = "制作面板 总览 深英360 大字", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun productionOverviewDarkEnglishLargeFontPreview() = Frame(dark = true) { Panel(ProductionTab.OVERVIEW) }

@PreviewTest
@Preview(name = "制作面板 外部改动 浅中360", widthDp = 360, heightDp = 640, locale = "zh-rCN")
@Composable
fun productionManifestExternalChangePreview() = Frame {
    Panel(ProductionTab.OVERVIEW, snapshot = ProductionSamples.snapshot(ManifestSync.ExternalChange(fileRevision = 11)))
}

@PreviewTest
@Preview(name = "制作面板 剧本 浅中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun productionScriptLightChinesePreview() = Frame { Panel(ProductionTab.SCRIPT) }

@PreviewTest
@Preview(name = "制作面板 资产 浅中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun productionAssetsLightChinesePreview() = Frame { Panel(ProductionTab.ASSETS) }

@PreviewTest
@Preview(name = "制作面板 分镜 深中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun productionStoryboardDarkChinesePreview() = Frame(dark = true) { Panel(ProductionTab.STORYBOARD) }

@PreviewTest
@Preview(name = "制作面板 镜头详情 浅中360", widthDp = 360, heightDp = 1000, locale = "zh-rCN")
@Composable
fun productionShotDetailLightChinesePreview() = Frame { Panel(ProductionTab.STORYBOARD, shot = "S1E01" to "SC01-02") }

@PreviewTest
@Preview(name = "制作面板 镜头详情 浅英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun productionShotDetailEnglishLargeFontPreview() = Frame { Panel(ProductionTab.STORYBOARD, shot = "S1E01" to "SC01-02") }

@PreviewTest
@Preview(name = "制作面板 已定镜头 浅中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun productionLockedShotPreview() = Frame { Panel(ProductionTab.STORYBOARD, shot = "S1E01" to "SC01-01") }

@PreviewTest
@Preview(name = "制作面板 空 浅中360", widthDp = 360, heightDp = 400, locale = "zh-rCN")
@Composable
fun productionEmptyPreview() = Frame { ProductionPanelEmpty() }
