package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.state.*
import com.promenar.infinity.ui.chat.components.RunDockRow
import com.promenar.infinity.ui.chat.components.runDockState
import com.promenar.infinity.ui.chat.components.StateBoardDetails
import com.promenar.infinity.ui.theme.*
import kotlinx.serialization.json.*

// 收起时状态面板只在运行坞右侧露出入口；展开后是运行详情“状态”页的正文。
@PreviewTest
@Preview(name = "状态 收起 深中411", widthDp = 411, heightDp = 120, locale = "zh-rCN")
@Composable fun stateCollapsedDarkChinese() = StatePreview(false, false)

@PreviewTest
@Preview(name = "状态 收起 浅英360 大字号", widthDp = 360, heightDp = 140, locale = "en", fontScale = 1.3f)
@Composable fun stateCollapsedLightEnglish() = StatePreview(true, false)

@PreviewTest
@Preview(name = "状态 展开 深中411", widthDp = 411, heightDp = 620, locale = "zh-rCN")
@Composable fun stateExpandedDarkChinese() = StatePreview(false, true)

@PreviewTest
@Preview(name = "状态 展开 浅英360 大字号", widthDp = 360, heightDp = 640, locale = "en", fontScale = 1.3f)
@Composable fun stateExpandedLightEnglish() = StatePreview(true, true)

private const val STATE_NOW = 1_792_400_000_000L

// 生命值与金币两两并排；位置刚被 Agent 更新，背包是用户手动改过的字段。
@Composable private fun StatePreview(english: Boolean, expanded: Boolean) {
    val board = StateBoard(if (english) "Mountain journey" else "山中历险", listOf(
        StateField("hp", if (english) "Health" else "生命值", "meter", buildJsonObject { put("current", 65); put("max", 100) }, updatedAt = STATE_NOW - 600_000),
        StateField("gold", if (english) "Gold" else "金币", "number", JsonPrimitive(120), unit = if (english) "coins" else "枚", updatedAt = STATE_NOW - 600_000),
        StateField("place", if (english) "Location" else "位置", "text", JsonPrimitive(if (english) "Hidden cave" else "隐蔽山洞"), updatedAt = STATE_NOW - 20_000),
        StateField("bag", if (english) "Inventory" else "背包", "list", JsonArray(listOf(JsonPrimitive(if (english) "Torch" else "火把"), JsonPrimitive(if (english) "Rope" else "绳子"))), updatedAt = STATE_NOW - 300_000),
    ), manualKeys = listOf("bag"))
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (english) InfinityThemeMode.LIGHT else InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            if (expanded) StateBoardDetails(board, onEdit = {}, onRemove = {}, nowMillis = STATE_NOW)
            else Box(Modifier.padding(vertical = 20.dp)) {
                RunDockRow(
                    state = requireNotNull(runDockState(null, false, 0, 0, 0, board.title, null)),
                    onOpen = {},
                )
            }
        }
    }
}
