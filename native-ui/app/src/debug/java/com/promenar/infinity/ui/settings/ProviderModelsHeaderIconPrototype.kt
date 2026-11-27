package com.promenar.infinity.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityHeaderTitle
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.ModelBrandIcon
import com.promenar.infinity.ui.common.ModelBrandResolver
import com.promenar.infinity.ui.common.brandAccentColor
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 隔离无状态视觉原型 v1：模型管理页顶部 Header 栏提供商图标前置设计。
 * 复用全站 ProviderAvatar 品牌解析与色彩令牌，支持自定义中转中性矢量图标回退与超长名称省略。
 */

@Composable
private fun PrototypeHeaderProviderIcon(
    name: String,
    baseUrl: String?,
    onTailnet: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val brand = remember(name, baseUrl) { ModelBrandResolver.brandKey(name, baseUrl) }
    val accent = brandAccentColor(brand)
    val bg = accent?.copy(alpha = 0.14f) ?: scheme.surfaceContainerHighest.copy(alpha = 0.90f)
    val rim = accent?.copy(alpha = 0.24f) ?: scheme.outlineVariant.copy(alpha = 0.40f)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(0.5.dp, rim, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (brand != null) {
            ModelBrandIcon(
                modelId = name.ifBlank { baseUrl },
                modifier = Modifier.size(16.dp),
                contentDescription = null,
                fallbackTint = accent ?: scheme.onSurfaceVariant,
                brandOverride = brand,
            )
        } else {
            Icon(
                imageVector = if (onTailnet) Icons.Rounded.Lan else Icons.Rounded.Hub,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrototypeProviderModelsHeaderDemo(
    providerName: String,
    baseUrl: String?,
    enabledCount: Int,
    totalCount: Int,
    onTailnet: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { InfinityBackButton(onClick = {}) },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PrototypeHeaderProviderIcon(
                            name = providerName,
                            baseUrl = baseUrl,
                            onTailnet = onTailnet,
                        )
                        InfinityHeaderTitle(
                            text = providerName,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = scheme.onBackground,
                    navigationIconContentColor = scheme.onSurfaceVariant,
                    actionIconContentColor = scheme.onSurfaceVariant,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfinitySearchBar(
                value = "",
                onValueChange = {},
                placeholder = "搜索模型…",
                borderless = true,
            )
            androidx.compose.material3.Text(
                text = "已启用 $enabledCount / $totalCount · 规格库 11-26 版",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
    }
}

@Preview(name = "HeaderIcon_OpenRouter_Dark", widthDp = 390, heightDp = 240, showBackground = true)
@Composable
fun ProviderModelsHeaderIconOpenRouterDarkPreview() {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = InfinityThemeMode.DARK,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        PrototypeProviderModelsHeaderDemo(
            providerName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            enabledCount = 8,
            totalCount = 477,
        )
    }
}

@Preview(name = "HeaderIcon_MiniMax_Light", widthDp = 390, heightDp = 240, showBackground = true)
@Composable
fun ProviderModelsHeaderIconMiniMaxLightPreview() {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        PrototypeProviderModelsHeaderDemo(
            providerName = "MiniMax",
            baseUrl = "https://api.minimax.cn/anthropic",
            enabledCount = 4,
            totalCount = 12,
        )
    }
}

@Preview(name = "HeaderIcon_LongCustomGateway_360_LargeFont", widthDp = 360, heightDp = 240, fontScale = 1.3f, showBackground = true)
@Composable
fun ProviderModelsHeaderIconLongCustomPreview() {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = InfinityThemeMode.DARK,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        PrototypeProviderModelsHeaderDemo(
            providerName = "My Production Gateway Cluster",
            baseUrl = "https://relay.example.internal/v1",
            enabledCount = 3,
            totalCount = 19,
        )
    }
}
