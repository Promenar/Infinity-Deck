package com.promenar.infinity.ui.document.external

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.document.external.ExternalSendMode
import com.promenar.infinity.share.core.ShareSessionChoice
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private val Body = """
# 周报 2026W45

## 本周完成

- 同步引擎改为增量合并，冲突率从 **3.2%** 降到 0.4%
- 弱网回归覆盖 3G 与高丢包场景

| 指标 | 上周 | 本周 |
| --- | --- | --- |
| 冲突率 | 3.2% | 0.4% |
| 同步耗时 P95 | 4.1 s | 1.7 s |

## 下周

```
./gradlew :sync:test --tests '*Merge*'
```

> 灰度到 20% 用户前先跑一轮弱网回归。
""".trimIndent()

private val Viewing = ExternalDocumentUiState(
    visible = true,
    document = ExternalDocumentView(name = "周报-2026W45.md", sizeBytes = 18_432, chunks = listOf(Body), attachmentAllowed = true),
)

private val Saved = Viewing.copy(saved = ExternalSavedLocation("doc-1", "/工作"))

private val SendPanel = Viewing.copy(
    panel = ExternalDocumentPanel.Send(
        mode = ExternalSendMode.Reference,
        attachmentAllowed = false,
        sessions = listOf(
            ShareSessionChoice("s1", "同步引擎重构", 0L),
            ShareSessionChoice("s2", "周报整理", 0L),
        ),
        agents = listOf(ExternalAgentChoice("a1", "写作")),
        loading = false,
    ),
)

@Composable
private fun Viewer(mode: InfinityThemeMode, state: ExternalDocumentUiState) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        ExternalDocumentViewerContent(state = state, callbacks = ExternalDocumentViewerCallbacks())
    }
}

@PreviewTest
@Preview(name = "外部文档 查看 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "外部文档 查看 浅英360 大字", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun externalDocumentViewingPreview() = Viewer(InfinityThemeMode.DARK, Viewing)

@PreviewTest
@Preview(name = "外部文档 已存入 浅中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun externalDocumentSavedPreview() = Viewer(InfinityThemeMode.LIGHT, Saved)

@PreviewTest
@Preview(name = "外部文档 宽屏 深中840", widthDp = 840, heightDp = 900, locale = "zh-rCN")
@Composable
fun externalDocumentWidePreview() = Viewer(InfinityThemeMode.DARK, Viewing)

@PreviewTest
@Preview(name = "外部文档 宽屏发送 浅中840", widthDp = 840, heightDp = 900, locale = "zh-rCN")
@Preview(name = "外部文档 宽屏发送 浅英840 大字", widthDp = 840, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun externalDocumentWideSendPreview() = Viewer(InfinityThemeMode.LIGHT, SendPanel)
