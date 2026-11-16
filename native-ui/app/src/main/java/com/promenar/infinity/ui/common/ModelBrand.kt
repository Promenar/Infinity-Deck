package com.promenar.infinity.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.infinityBrandAccentColor

/**
 * 模型 ID / family / 提供商名 → 品牌资产键映射。
 * 常见品牌优先同步渲染内置矢量 Drawable，其余通过 assets/model_brands/ 下的 Lobe Icons SVG 渲染。
 */
object ModelBrandResolver {
    private val rules = listOf(
        "opencode" to "opencode",
        "openrouter" to "openrouter",
        "siliconflow" to "siliconflow",
        "siliconcloud" to "siliconflow",
        "deepseek" to "deepseek",
        "sensenova" to "sensenova",
        "claude" to "anthropic",
        "anthropic" to "anthropic",
        "gemini" to "google",
        "gemma" to "google",
        "google" to "google",
        "gpt" to "openai",
        "codex" to "openai",
        "chatgpt" to "openai",
        "openai" to "openai",
        "glm" to "zhipu",
        "chatglm" to "zhipu",
        "zhipu" to "zhipu",
        "bigmodel" to "zhipu",
        "智谱" to "zhipu",
        "doubao" to "volcengine",
        "seed" to "volcengine",
        "volcengine" to "volcengine",
        "volces" to "volcengine",
        "豆包" to "volcengine",
        "方舟" to "volcengine",
        "kimi" to "kimi",
        "moonshot" to "kimi",
        "minimax" to "minimax",
        "minimaxi" to "minimax",
        "abab" to "minimax",
        "qwen" to "qwen",
        "qwq" to "qwen",
        "dashscope" to "qwen",
        "bailian" to "qwen",
        "通义" to "qwen",
        "百炼" to "qwen",
        "baichuan" to "baichuan",
        "百川" to "baichuan",
        "llama" to "meta",
        "mistral" to "mistral",
        "ministral" to "mistral",
        "mixtral" to "mistral",
        "grok" to "grok",
        "xai" to "grok",
        "groq" to "groq",
        "nemotron" to "nvidia",
        "nvidia" to "nvidia",
        "command" to "cohere",
        "cohere" to "cohere",
        "jamba" to "ai21",
        "ai21" to "ai21",
        "ollama" to "ollama",
        "local" to "local",
        "本地" to "local",
    )

    val knownBrands: Set<String> = rules.map { it.second }.toSet()

    /** 依次在给定的来源字符串（模型 ID、family、提供商名称、地址等）中查找品牌键；找不到返回 null。 */
    fun brandKey(vararg sources: String?): String? {
        for (raw in sources) {
            val source = raw?.lowercase() ?: continue
            for ((needle, brand) in rules) {
                if (needle in source) return brand
            }
        }
        return null
    }
}

/** 同步矢量 Drawable 映射：避免异步图片加载延迟导致列表或预览显示白块或首字回退。 */
@DrawableRes
internal fun localBrandDrawableRes(brand: String): Int? = when (brand) {
    "openai" -> R.drawable.ic_provider_openai
    "anthropic" -> R.drawable.ic_provider_anthropic
    "google" -> R.drawable.ic_provider_gemini
    "deepseek" -> R.drawable.ic_provider_deepseek
    "qwen" -> R.drawable.ic_provider_qwen
    "mistral" -> R.drawable.ic_provider_mistral
    "kimi" -> R.drawable.ic_provider_kimi
    "zhipu" -> R.drawable.ic_provider_zhipu
    "volcengine" -> R.drawable.ic_provider_doubao
    "baichuan" -> R.drawable.ic_provider_baichuan
    "minimax" -> R.drawable.ic_provider_minimax
    "opencode" -> R.drawable.ic_provider_opencode
    "openrouter" -> R.drawable.ic_provider_openrouter
    "siliconflow" -> R.drawable.ic_provider_siliconflow
    "grok" -> R.drawable.ic_provider_xai
    "cohere" -> R.drawable.ic_provider_cohere
    "ollama" -> R.drawable.ic_provider_ollama
    "meta" -> R.drawable.ic_provider_meta
    "groq" -> R.drawable.ic_provider_groq
    "nvidia" -> R.drawable.ic_provider_nvidia
    "local" -> R.drawable.ic_provider_local
    else -> null
}

/** 品牌对应的辨识强调色（委托主题色表集中定义）。 */
internal fun brandAccentColor(brand: String?): Color? = infinityBrandAccentColor(brand)

/**
 * 模型品牌图标：优先同步渲染矢量资源并配以品牌强调色；
 * 未内置矢量资源时回退到 assets/model_brands/ SVG，仍失败时显示通用芯片图标。
 */
@Composable
fun ModelBrandIcon(
    modelId: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallbackTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    tintOverride: Color? = null,
) {
    val brand = ModelBrandResolver.brandKey(modelId)
    val localRes = brand?.let(::localBrandDrawableRes)
    if (localRes != null) {
        val tint = tintOverride ?: brandAccentColor(brand) ?: fallbackTint
        Icon(
            painter = painterResource(localRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = modifier,
        )
        return
    }
    if (brand == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Memory,
                contentDescription = contentDescription,
                tint = fallbackTint,
            )
        }
        return
    }
    SubcomposeAsyncImage(
        model = "file:///android_asset/model_brands/$brand.svg",
        contentDescription = contentDescription,
        modifier = modifier,
        error = {
            Box(modifier = Modifier, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Memory,
                    contentDescription = contentDescription,
                    tint = fallbackTint,
                )
            }
        },
    )
}

/** 品牌 Logo 的标准底座：柔和品牌色底板配同步矢量图标，可传入形状以适配圆标或圆角方形头像。 */
@Composable
fun ModelBrandTile(
    modelId: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    shape: Shape = CircleShape,
) {
    val brand = ModelBrandResolver.brandKey(modelId)
    val accent = brandAccentColor(brand)
    val container = accent?.copy(alpha = 0.14f)
        ?: MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f)
    Box(
        modifier = modifier
            .size(size)
            .background(container, shape),
        contentAlignment = Alignment.Center,
    ) {
        ModelBrandIcon(
            modelId = modelId,
            modifier = Modifier.size(size * 0.56f),
            contentDescription = contentDescription,
            fallbackTint = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
