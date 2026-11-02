package com.promenar.infinity.ui.rag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "知识库多选栏 浅中360", widthDp = 360, heightDp = 200, locale = "zh-rCN")
@Composable
fun ragHomeSelectionBarLightPreview() = SelectionBarFrame(dark = false)

@PreviewTest
@Preview(name = "知识库多选栏 深英360 字号130", widthDp = 360, heightDp = 260, locale = "en", fontScale = 1.3f)
@Composable
fun ragHomeSelectionBarDarkEnglishPreview() = SelectionBarFrame(dark = true)

@Composable
private fun SelectionBarFrame(dark: Boolean) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
                RagHomeSelectionBar(selectedCount = 3, operationsEnabled = true, onClear = {}, onMove = {}, onReindex = {}, onDelete = {})
            }
        }
    }
}
