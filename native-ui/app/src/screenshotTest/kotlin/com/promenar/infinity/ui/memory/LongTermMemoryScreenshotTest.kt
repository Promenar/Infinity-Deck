package com.promenar.infinity.ui.memory

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.memory.LongTermMemory
import com.promenar.infinity.data.memory.LongTermMemoryKind
import com.promenar.infinity.ui.chat.LocalMemoryChangeHost
import com.promenar.infinity.ui.chat.MemoryChange
import com.promenar.infinity.ui.chat.MemoryChangeAction
import com.promenar.infinity.ui.chat.MemoryChangeHost
import com.promenar.infinity.ui.chat.MemoryChangeRows
import com.promenar.infinity.ui.chat.MemorySnapshot
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

// 长期记忆管理页：开关在上，按类型分组列出；编辑面板与会话里的“已记住”步骤行共用同一套数据。
@PreviewTest
@Preview(name = "长期记忆 列表 深中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable fun longTermMemoryListDarkChinese() = ListPreview(english = false)

@PreviewTest
@Preview(name = "长期记忆 列表 浅英360 大字号", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable fun longTermMemoryListLightEnglish() = ListPreview(english = true)

@PreviewTest
@Preview(name = "长期记忆 空列表 深中411", widthDp = 411, heightDp = 360, locale = "zh-rCN")
@Composable fun longTermMemoryEmptyDarkChinese() = MemoryTheme(english = false) {
    LongTermMemoryContent(true, emptyList(), "", {}, {}, PaddingValues(vertical = 16.dp), nowMs = MEMORY_NOW)
}

@PreviewTest
@Preview(name = "长期记忆 编辑 深中411", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable fun longTermMemoryEditDarkChinese() = EditPreview(english = false)

@PreviewTest
@Preview(name = "长期记忆 编辑 浅英360 大字号", widthDp = 360, heightDp = 680, locale = "en", fontScale = 1.3f)
@Composable fun longTermMemoryEditLightEnglish() = EditPreview(english = true)

@PreviewTest
@Preview(name = "会话 记忆步骤行 深中411", widthDp = 411, heightDp = 180, locale = "zh-rCN")
@Composable fun chatMemoryRowsDarkChinese() = ChatRowsPreview(english = false)

@PreviewTest
@Preview(name = "会话 记忆步骤行 浅英360 大字号", widthDp = 360, heightDp = 220, locale = "en", fontScale = 1.3f)
@Composable fun chatMemoryRowsLightEnglish() = ChatRowsPreview(english = true)

private const val MEMORY_NOW = 1_792_400_000_000L
private const val DAY = 86_400_000L

private fun sampleMemories(english: Boolean): List<LongTermMemoryListItem> {
    fun memory(id: String, kind: LongTermMemoryKind, title: String, content: String, daysAgo: Int) =
        LongTermMemory(id, kind, title, content, "a1", "s1", null, MEMORY_NOW - daysAgo * DAY, MEMORY_NOW - daysAgo * DAY)
    val agent = if (english) "Coding helper" else "编程助手"
    return listOf(
        LongTermMemoryListItem(memory("1", LongTermMemoryKind.USER,
            if (english) "How to address me" else "称呼",
            if (english) "Call me Leng. I work on Android apps and prefer concise answers." else "叫我小冷。主要做 Android 应用，偏好简短直接的回答。", 0), agent),
        LongTermMemoryListItem(memory("2", LongTermMemoryKind.FEEDBACK,
            if (english) "Reply language" else "回复语言",
            if (english) "Always reply in Simplified Chinese, conclusions first." else "始终使用简体中文，结论先行，不要套话。", 3), agent),
        LongTermMemoryListItem(memory("3", LongTermMemoryKind.PROJECT,
            if (english) "Infinity Deck release checks" else "Infinity Deck 发布检查",
            if (english) "Run targeted tests during development and the full gate only once at the end." else "开发中只跑定向测试，全量门禁在最后集成时跑一次。", 1), null),
        LongTermMemoryListItem(memory("4", LongTermMemoryKind.REFERENCE,
            if (english) "Design tokens" else "设计规范",
            if (english) "Spacing and colors live in ui/theme; check them before adding new values." else "间距与颜色在 ui/theme，新增前先查现有定义。", 12), agent),
    )
}

@Composable private fun MemoryTheme(english: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (english) InfinityThemeMode.LIGHT else InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surface) { content() }
    }
}

@Composable private fun ListPreview(english: Boolean) = MemoryTheme(english) {
    LongTermMemoryContent(true, sampleMemories(english), "", {}, {}, PaddingValues(vertical = 16.dp), nowMs = MEMORY_NOW)
}

@Composable private fun EditPreview(english: Boolean) = MemoryTheme(english) {
    LongTermMemoryEditForm(
        memory = sampleMemories(english)[1].memory,
        source = LongTermMemorySourceLabel(if (english) "Coding helper" else "编程助手", if (english) "Release planning" else "发布计划"),
        onSave = { _, _, _ -> },
        onDelete = {},
    )
}

// 第一条刚记住可撤销，第二条已撤销，第三条之后又被改过，不再提供撤销。
@Composable private fun ChatRowsPreview(english: Boolean) = MemoryTheme(english) {
    val saved = MemorySnapshot(LongTermMemoryKind.FEEDBACK, if (english) "Reply language" else "回复语言", "zh")
    val updated = MemorySnapshot(LongTermMemoryKind.PROJECT, if (english) "Release checks" else "发布检查", "new")
    val forgotten = MemorySnapshot(LongTermMemoryKind.REFERENCE, if (english) "Old wiki link" else "旧文档地址", "url")
    val changes = listOf(
        MemoryChange("c1", MemoryChangeAction.CREATED, "m1", saved),
        MemoryChange("c2", MemoryChangeAction.UPDATED, "m2", updated, previous = updated.copy(content = "old")),
        MemoryChange("c3", MemoryChangeAction.FORGOTTEN, "m3", forgotten),
    )
    fun stored(id: String, snapshot: MemorySnapshot) =
        LongTermMemory(id, snapshot.kind, snapshot.title, snapshot.content, null, null, null, MEMORY_NOW, MEMORY_NOW)
    val host = object : MemoryChangeHost {
        override val memories: State<Map<String, LongTermMemory>> = mutableStateOf(mapOf(
            "m1" to stored("m1", saved),
            "m2" to stored("m2", updated.copy(content = "old")),
            "m3" to stored("m3", forgotten.copy(content = "edited")),
        ))
        override fun undo(change: MemoryChange) = Unit
    }
    CompositionLocalProvider(LocalMemoryChangeHost provides host) {
        MemoryChangeRows(changes, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}
