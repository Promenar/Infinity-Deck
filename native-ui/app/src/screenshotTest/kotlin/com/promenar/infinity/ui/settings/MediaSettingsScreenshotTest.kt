package com.promenar.infinity.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.mediagen.cloud.*
import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.theme.*
import kotlinx.serialization.json.JsonObject

/** 纯展示预览：不创建 ViewModel，不读写设置，不探测主机。 */
@Composable
private fun MediaPreviewTheme(content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(
        mode = if (isSystemInDarkTheme()) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
        colorSource = InfinityColorSource.INFINITY,
    )) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content) }
}

private val previewCaps = MediaCapabilities(
    setOf(GenerationMode.TEXT, GenerationMode.REFERENCE, GenerationMode.FIRST_FRAME, GenerationMode.FIRST_LAST_FRAME),
    maxImages = 3, maxVideos = 3, maxAudio = 3, nativeAudio = true, resolutions = listOf("720p", "1080p"),
    minDurationSeconds = 4, maxDurationSeconds = 15, promptFormat = PromptFormat.H3,
    referenceOrder = listOf("front_full_body", "side_full_body", "back_full_body"),
)
private val previewHost = ComfyHost("main", "MAIN", "http://main:8188", ComfyAuthKind.BEARER)
private val previewTemplate = ComfyTemplate("h3", JsonObject(emptyMap()),
    slots = listOf(ComfySlot("6", "text", SlotKind.PROMPT), ComfySlot("ref_images", "ref_image_0", SlotKind.REFERENCE_IMAGES, 3),
        ComfySlot("20", "width", SlotKind.WIDTH), ComfySlot("20", "height", SlotKind.HEIGHT), ComfySlot("20", "length", SlotKind.FRAMES),
        ComfySlot("31", "noise_seed", SlotKind.SEED), ComfySlot("40", "videos", SlotKind.OUTPUT, outputKind = InputKind.VIDEO)),
    frameBase = 5, frameStep = 17, locked = true, capabilities = previewCaps, hostId = "main", group = MediaGroup.VIDEO,
    name = "H3 Reference", maxFrames = 260)
private val previewEntry = ComfyTemplateState(previewTemplate, PreflightResult(PreflightStatus.PASS))
private val previewModel = ModelInfo("Seedance 2.5", "seedance", "", true, "video", providerName = "Volcengine Ark", providerId = "ark",
    mediaSettings = MediaModelSettings(MediaGroup.VIDEO, previewCaps.copy(promptFormat = PromptFormat.SEEDANCE),
        tariff = MediaTariff("CNY", listOf(MediaRate(BillingUnit.TOKENS, "70", 1_000_000)))))
private val previewImage = ModelInfo("Image model", "image", "", true, "image", providerName = "OpenAI", providerId = "openai")
private val previewAudio = ModelInfo("Speech 2.6", "speech", "", true, "audio", providerName = "MiniMax", providerId = "minimax",
    mediaSettings = MediaModelSettings(MediaGroup.AUDIO, MediaCapabilities(setOf(GenerationMode.SPEECH))))
private val previewSnapshot = MediaSettingsSnapshot(
    channels = listOf(previewImage.toMediaChannel()!!, previewModel.toMediaChannel()!!, previewTemplate.channel(), previewAudio.toMediaChannel()!!),
    models = listOf(previewImage, previewModel, previewAudio), hosts = listOf(previewHost),
    templates = listOf(previewEntry,
        ComfyTemplateState(previewTemplate.copy(id = "wan", name = "Wan 2.2"), PreflightResult(PreflightStatus.MISSING, listOf("Node A", "Model B"))),
        ComfyTemplateState(previewTemplate.copy(id = "music", name = "Music 3", group = MediaGroup.AUDIO), PreflightResult(PreflightStatus.REVIEW))),
    credentialHosts = setOf("main"), preflightTimes = mapOf("h3" to 1_794_480_000_000L),
)
private val previewState = MediaSettingsUiState(previewSnapshot, loaded = true, connections = mapOf("main" to ComfyConnection.ONLINE))

@PreviewTest
@Preview(name = "媒体模型 深色中文", widthDp = 360, heightDp = 1000, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "媒体模型 浅色英文", widthDp = 360, heightDp = 1000, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "媒体模型 深色英文130", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "媒体模型 浅色中文130", widthDp = 360, heightDp = 1100, locale = "zh-rCN", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun mediaModelsSettingsPreview() = MediaPreviewTheme { MediaModelsScreenContent(previewState) }

@PreviewTest
@Preview(name = "模型详情 深色中文", widthDp = 360, heightDp = 1800, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "模型详情 浅色英文", widthDp = 360, heightDp = 1800, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "模型详情 深色英文130", widthDp = 360, heightDp = 2000, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "模型详情 浅色中文130", widthDp = 360, heightDp = 2000, locale = "zh-rCN", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun mediaModelDetailSettingsPreview() = MediaPreviewTheme { MediaModelDetailScreenContent(previewModel, previewState) }

@PreviewTest
@Preview(name = "ComfyUI主机 深色中文", widthDp = 360, heightDp = 1200, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "ComfyUI主机 浅色英文", widthDp = 360, heightDp = 1200, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "ComfyUI主机 深色英文130", widthDp = 360, heightDp = 1400, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "ComfyUI主机 浅色中文130", widthDp = 360, heightDp = 1400, locale = "zh-rCN", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun comfyHostSettingsPreview() = MediaPreviewTheme { ComfyHostScreenContent(previewHost, previewState) }

@PreviewTest
@Preview(name = "模板详情 深色中文", widthDp = 360, heightDp = 2400, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "模板详情 浅色英文", widthDp = 360, heightDp = 2400, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "模板详情 深色英文130", widthDp = 360, heightDp = 2600, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "模板详情 浅色中文130", widthDp = 360, heightDp = 2600, locale = "zh-rCN", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun comfyTemplateSettingsPreview() = MediaPreviewTheme { ComfyTemplateScreenContent(previewEntry, previewState) }

@PreviewTest
@Preview(name = "主机表单 深色中文130", widthDp = 360, heightDp = 1100, locale = "zh-rCN", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "主机表单 浅色英文130", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
fun comfyHostFormSettingsPreview() = MediaPreviewTheme { ComfyHostFormScreenContent(previewHost, true, previewState) }

/** 制作面板内打开的媒体模型子页：检视器宽度（宽屏）与整屏容器（窄屏），都带返回。 */
@PreviewTest
@Preview(name = "媒体模型 面板内 检视器 浅中", widthDp = 400, heightDp = 880, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "媒体模型 面板内 检视器 深英大字", widthDp = 400, heightDp = 1000, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "媒体模型 面板内 整屏 浅中360", widthDp = 360, heightDp = 880, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "媒体模型 面板内 整屏 深英360", widthDp = 360, heightDp = 880, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun mediaModelsPanelHostPreview() = MediaPreviewTheme {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.systemBars),
    ) {
        MediaModelsScreenContent(previewState, onBack = {})
    }
}
