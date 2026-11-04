package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.promenar.infinity.ui.common.MARKDOWN_BATCH2_EN
import com.promenar.infinity.ui.common.MARKDOWN_BATCH2_ZH
import com.promenar.infinity.ui.common.MARKDOWN_BATCH2_HTML
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.renderer.infinityMarkdownTypography
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "助手正文 长回复 深色中文", widthDp = 411, heightDp = 1500, locale = "zh-rCN")
@Composable
fun chatMarkdownBatch2DarkChinese() = ChatMarkdownBatch2Preview()

@PreviewTest
@Preview(name = "助手正文 长回复 窄屏大字", widthDp = 360, heightDp = 1900, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun chatMarkdownBatch2NarrowLargeFont() = ChatMarkdownBatch2Preview()

@PreviewTest
@Preview(name = "助手正文 HTML长代码 深色中文", widthDp = 411, heightDp = 1200, locale = "zh-rCN")
@Composable
fun chatMarkdownBatch2HtmlArtifact() = ChatMarkdownBatch2Preview(MARKDOWN_BATCH2_HTML)

@PreviewTest
@Preview(name = "代码首列字形 与未裁剪文字对照", widthDp = 411, heightDp = 260, locale = "zh-rCN")
@Composable
fun chatMarkdownCodeInkRegression() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp)) {
                MarkdownText("```kotlin\nval message = 1\nprintln(message)\npjgyf()\n```", fontSize = 15)
                Spacer(Modifier.height(16.dp))
                Text("println(message)\npjgyf()", style = infinityMarkdownTypography(15).code, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@PreviewTest
@Preview(name = "引用与列表长代码 可横向滚动", widthDp = 411, heightDp = 340, locale = "zh-rCN")
@Composable
fun chatMarkdownNestedCodeRegression() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp)) {
                MarkdownText(
                    "> ```kotlin\n> println(\"${"code ".repeat(25)}\")\n> ```\n\n- 代码\n\n  ```kotlin\n  println(\"${"code ".repeat(25)}\")\n  ```",
                    fontSize = 15,
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "宽表格 按内容列宽与行内格式", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun chatMarkdownWideTableRegression() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp)) {
                MarkdownText(
                    "| 序号 | 模型 | 上下文 | 说明 | 状态 |\n" +
                        "|---|---|---:|---|:---:|\n" +
                        "| 1 | **DeepSeek V4** | 128K | 支持 `tool_call` 与长上下文推理，适合代码与文档任务 | 可用 |\n" +
                        "| 2 | *MiniMax M3* | 1M | 见 [发布说明](https://example.com) | ~~预览~~ |\n\n" +
                        "| 项目 | 结果 |\n|---|---|\n| 正文 | **清晰** |\n| 间距 | 统一 |",
                    fontSize = 15,
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "行内代码中的美元符号 不按公式排版", widthDp = 411, heightDp = 260, locale = "zh-rCN")
@Composable
fun chatMarkdownInlineCodeDollarRegression() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp)) {
                MarkdownText(
                    "**3. 克隆 `\$HOME/src-demo` → `\$INFINITY_SKILLS/cloned-demo`** ✅\n\n" +
                        "- 设置 `\$JAVA_HOME` 与 `\$ANDROID_HOME` 后重试\n" +
                        "- 运行 `echo \$\$` 查看进程号，价格区间 \$5/\$10",
                    fontSize = 15,
                )
            }
        }
    }
}

@Composable
private fun ChatMarkdownBatch2Preview(content: String? = null) {
    val markdown = content ?: if (LocalConfiguration.current.locales[0].language == "zh") MARKDOWN_BATCH2_ZH else MARKDOWN_BATCH2_EN
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                MarkdownText(markdown = markdown, fontSize = 15, onContentChange = {})
            }
        }
    }
}

// 行内代码换行：左右留白被断行器单独留在行末或推到行首时，不再画出空底色小块。
@PreviewTest
@Preview(name = "行内代码换行 不留空底色 深色中文", widthDp = 411, heightDp = 700, locale = "zh-rCN")
@Composable
fun chatMarkdownInlineCodeWrapRegression() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                listOf(371, 362, 354, 347).forEach { width ->
                    MarkdownText(INLINE_CODE_WRAP_SAMPLE, fontSize = 16, modifier = Modifier.width(width.dp))
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

private const val INLINE_CODE_WRAP_SAMPLE =
    "技能目录复检：24 个目录 / 587 文件 / 缓存残留 0。`comfyui-generation` 源 79 文件、安装 80 文件，" +
        "差的就是我新增的 `comfyui_host.py`，符合预期。`__pycache__` 消失后，输出已写回（`Qwen21_00001_.png`，1152×1536）。"
