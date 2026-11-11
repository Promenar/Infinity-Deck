package com.promenar.infinity.ui.skills.store

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.skills.store.SkillChannel
import com.promenar.infinity.data.skills.store.SkillStoreCategory
import com.promenar.infinity.data.skills.store.SkillStoreEntry
import com.promenar.infinity.data.skills.store.SkillStoreFile
import com.promenar.infinity.data.skills.store.SkillStoreSource
import com.promenar.infinity.data.skills.store.SourceStatus
import com.promenar.infinity.data.skills.store.StoreFailure
import com.promenar.infinity.data.skills.store.StoreFilter
import com.promenar.infinity.data.skills.store.StoreInstallState
import com.promenar.infinity.data.skills.store.StoreOperation
import com.promenar.infinity.data.skills.store.StoreSkillItem
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "技能商店 列表 深色中文", widthDp = 412, heightDp = 900, locale = "zh-rCN")
@Composable
fun skillStoreListDarkPreview() = StorePreviewTheme(dark = true) { SkillStoreListContent(PreviewListState, PreviewListActions) }

@PreviewTest
@Preview(name = "技能商店 列表 浅色英文 字号130", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun skillStoreListLightPreview() = StorePreviewTheme(dark = false) { SkillStoreListContent(PreviewListState, PreviewListActions) }

@PreviewTest
@Preview(name = "技能商店 详情 Play 受限 浅色中文", widthDp = 412, heightDp = 1100, locale = "zh-rCN")
@Composable
fun skillStoreDetailPlayLimitedPreview() = StorePreviewTheme(dark = false) {
    SkillStoreDetailContent(PreviewScripted.copy(limitedOnChannel = true), operation = null, actions = PreviewDetailActions)
}

@PreviewTest
@Preview(name = "技能商店 详情 下载中 深色英文 字号130", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun skillStoreDetailDownloadingPreview() = StorePreviewTheme(dark = true) {
    SkillStoreDetailContent(PreviewUpdate, operation = StoreOperation.Downloading(18_000, 48_000), actions = PreviewDetailActions)
}

@PreviewTest
@Preview(name = "技能商店 目录来源 浅色中文", widthDp = 412, heightDp = 900, locale = "zh-rCN")
@Composable
fun skillStoreSourcesPreview() = StorePreviewTheme(dark = false) {
    SkillStoreSourcesContent(PreviewSources, autoCheck = true, lastCheckedAt = null, actions = PreviewSourcesActions)
}

@PreviewTest
@Preview(name = "技能商店 目录来源 深色英文 字号130", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun skillStoreSourcesDarkPreview() = StorePreviewTheme(dark = true) {
    SkillStoreSourcesContent(PreviewSources, autoCheck = false, lastCheckedAt = null, actions = PreviewSourcesActions)
}

@Composable
private fun StorePreviewTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

private const val OfficialUrl = "https://infinityware.work/skills/index.json"
private const val ThirdUrl = "https://skills.example.com/index.json"

private val Official = SkillStoreSource(OfficialUrl, official = true, title = "官方目录", status = SourceStatus.Ready, skillCount = 11, fetchedAt = 0)
private val Third = SkillStoreSource(ThirdUrl, official = false, title = null, status = SourceStatus.Ready, skillCount = 3, fetchedAt = 0)
private val ThirdFailed = Third.copy(url = "https://broken.example.org/index.json", status = SourceStatus.Failed(StoreFailure.HTTP, 404), skillCount = 0)

private fun entry(
    name: String,
    title: String,
    summary: String,
    category: String,
    version: String = "1.0.0",
    size: Long = 9_000,
    scripts: Boolean = false,
    channels: Set<SkillChannel> = setOf(SkillChannel.SIDELOAD, SkillChannel.PLAY),
) = SkillStoreEntry(
    name = name, version = version, title = title, summary = summary, categoryId = category,
    author = "Infinity Deck", license = "MIT", archiveUrl = "https://infinityware.work/skills/$name-$version.zip",
    size = size, sha256 = "9f2c4b1e7a0d3c5f8e6b2a1d4c7f0e3b9a8d6c5e4f3a2b1c0d9e8f7a6b5c4a41e".take(64),
    files = buildList {
        add(SkillStoreFile("SKILL.md", 6_120))
        if (scripts) add(SkillStoreFile("scripts/convert.py", 4_210))
        add(SkillStoreFile("references/checklist.md", 2_048))
    },
    hasScripts = scripts, channels = channels, minAppVersionCode = null,
)

private fun item(entry: SkillStoreEntry, source: SkillStoreSource, state: StoreInstallState, enabled: Boolean? = null) = StoreSkillItem(
    key = "${source.url}#${entry.name}", entry = entry, source = source, installState = state, enabled = enabled,
    localModified = false, limitedOnChannel = false, requiresAppUpdate = false,
)

private val PreviewUpdate = item(
    entry("meeting-summary", "会议摘要", "提炼决议、待办与未决问题", "writing", version = "1.1.0", size = 48_000),
    Official, StoreInstallState.UpdateAvailable("1.0.0"), enabled = true,
)
private val PreviewScripted = item(
    entry("markitdown", "文档转 Markdown", "PDF、Office 与网页转为 Markdown", "data", size = 212_000, scripts = true, channels = setOf(SkillChannel.SIDELOAD)),
    Official, StoreInstallState.NotInstalled,
)

private val PreviewListState = SkillStoreUiState(
    sources = listOf(Official, Third, ThirdFailed),
    categories = listOf(
        SkillStoreCategory("writing", "写作"), SkillStoreCategory("research", "调研"),
        SkillStoreCategory("data", "数据"), SkillStoreCategory("dev", "开发"), SkillStoreCategory("design", "设计"),
    ),
    items = listOf(
        PreviewUpdate,
        PreviewScripted,
        item(entry("spec-intake", "需求澄清", "收敛目标、约束与验收标准", "dev"), Official, StoreInstallState.Installed("1.0.0"), enabled = true),
        item(entry("team-style", "团队写作规范", "统一术语与语气", "writing", version = "0.4.1", size = 6_000), Third, StoreInstallState.NotInstalled),
    ),
    query = "",
    filter = StoreFilter.ALL,
    categoryId = null,
    categoryCounts = mapOf(null to 4, "writing" to 2, "data" to 1, "dev" to 1),
    installedCount = 2,
    updateCount = 1,
    refreshing = false,
    autoCheck = true,
    lastCheckedAt = null,
    operations = mapOf("$ThirdUrl#team-style" to StoreOperation.Downloading(2_000, 6_000)),
)

private val PreviewSources = listOf(Official, Third, ThirdFailed)

private val PreviewListActions = SkillStoreListActions({}, {}, {}, {}, {}, {}, {}, {}, {})
private val PreviewDetailActions = SkillStoreDetailActions({}, {}, {}, {}, {})
private val PreviewSourcesActions = SkillStoreSourcesActions({}, {}, {}, {})
