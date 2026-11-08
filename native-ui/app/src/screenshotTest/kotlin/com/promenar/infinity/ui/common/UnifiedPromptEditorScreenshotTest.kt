package com.promenar.infinity.ui.common

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.theme.InfinityTheme

private val SamplePrompt = """
    # 角色
    你是 Infinity Deck 的写作助手，帮助用户整理思路。

    ## 规则
    - 先给**结论**，再展开细节
    - 引用资料时标注来源
    - 不确定时直接说明，命令写成 `ls -la`

    ## 输出格式
    使用 Markdown，标题不超过三级。
""".trimIndent()

@PreviewTest
@Preview(
    name = "Prompt editor compact large font",
    widthDp = 360,
    heightDp = 640,
    locale = "en",
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun unifiedPromptEditorCompactLargeFontPreview() {
    InfinityTheme {
        UnifiedPromptEditor(
            show = true,
            onDismiss = {},
            onSave = { Result.success(Unit) },
            initialText = (1..80).joinToString("\n") { line ->
                "$line. Long prompt content remains editable and save stays reachable."
            },
        )
    }
}

@PreviewTest
@Preview(
    name = "Prompt editor compact Chinese",
    widthDp = 360,
    heightDp = 720,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun unifiedPromptEditorCompactChinesePreview() {
    InfinityTheme {
        UnifiedPromptEditor(
            show = true,
            onDismiss = {},
            onSave = { Result.success(Unit) },
            initialText = SamplePrompt,
            title = "系统提示词",
        )
    }
}

@PreviewTest
@Preview(
    name = "Prompt editor split Chinese tablet",
    widthDp = 840,
    heightDp = 900,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun unifiedPromptEditorSplitTabletPreview() {
    InfinityTheme {
        UnifiedPromptEditor(
            show = true,
            onDismiss = {},
            onSave = { Result.success(Unit) },
            initialText = SamplePrompt,
            title = "系统提示词",
        )
    }
}

@PreviewTest
@Preview(
    name = "Prompt editor empty Chinese tablet",
    widthDp = 840,
    heightDp = 900,
    locale = "zh-rCN",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun unifiedPromptEditorEmptyTabletPreview() {
    InfinityTheme {
        UnifiedPromptEditor(
            show = true,
            onDismiss = {},
            onSave = { Result.success(Unit) },
            initialText = "",
        )
    }
}

@PreviewTest
@Preview(
    name = "Prompt editor script light",
    widthDp = 360,
    heightDp = 640,
    locale = "zh-rCN",
)
@Composable
fun unifiedPromptEditorScriptPreview() {
    InfinityTheme(darkTheme = false) {
        UnifiedPromptEditor(
            show = true,
            onDismiss = {},
            onSave = { Result.success(Unit) },
            initialText = "#!/bin/sh\n# 每日备份\nset -e\ntar -czf backup.tgz ~/notes\necho done",
            title = "backup.sh",
            showPreviewTab = false,
        )
    }
}
