package com.promenar.infinity.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySwitch
import com.promenar.infinity.ui.common.LocalInfinityNoticeController
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityObsidianDialogContainer
import com.promenar.infinity.ui.theme.InfinityObsidianGlassTint
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.wallpaper.ChatWallpaperConfig
import com.promenar.infinity.ui.wallpaper.ChatWallpaperLayer
import com.promenar.infinity.ui.wallpaper.ProceduralWallpaperPresetArtwork
import com.promenar.infinity.ui.wallpaper.WallpaperPresetId
import com.promenar.infinity.ui.wallpaper.WallpaperSource
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val WallpaperWideMinWidth = 600.dp

@Composable
internal fun WallpaperPresetId.displayLabel(): String = when (this) {
    WallpaperPresetId.AURORA -> stringResource(R.string.wallpaper_preset_aurora)
    WallpaperPresetId.OBSIDIAN_TOPO -> stringResource(R.string.wallpaper_preset_obsidian_topo)
    WallpaperPresetId.PINE_MIST -> stringResource(R.string.wallpaper_preset_pine_mist)
    WallpaperPresetId.AMBER_DUSK -> stringResource(R.string.wallpaper_preset_amber_dusk)
    WallpaperPresetId.ABYSSAL_WAVE -> stringResource(R.string.wallpaper_preset_abyssal_wave)
    WallpaperPresetId.WARM_PAPER -> stringResource(R.string.wallpaper_preset_warm_paper)
}

@Composable
internal fun ChatWallpaperConfig.summarySegments(): List<String> {
    if (!isActive) return listOf(stringResource(R.string.wallpaper_entry_off))
    val sourceName = when (val s = source) {
        WallpaperSource.None -> return listOf(stringResource(R.string.wallpaper_entry_off))
        is WallpaperSource.Preset -> s.id.displayLabel()
        is WallpaperSource.Image -> stringResource(R.string.wallpaper_entry_custom_label)
    }
    val dimPercent = (clampedDimFraction * 100f).roundToInt()
    return buildList {
        add(sourceName)
        if (clampedBlurDp > 0) add(stringResource(R.string.wallpaper_meta_blur, clampedBlurDp))
        add(stringResource(R.string.wallpaper_meta_scrim, dimPercent))
    }
}

@Composable
fun WallpaperSettingsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as InfinityApplication
    val prefs by app.themePreferenceStore.state.collectAsStateWithLifecycle()
    val noticeController = LocalInfinityNoticeController.current
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    var importing by remember { mutableStateOf(false) }

    val accentAppliedMsg = stringResource(R.string.wallpaper_notice_accent_applied)
    val importFailedMsg = stringResource(R.string.wallpaper_notice_import_failed)

    fun notify(message: String, severity: InfinityNoticeSeverity = InfinityNoticeSeverity.INFO) {
        if (noticeController != null) {
            noticeController.show(severity = severity, message = message)
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val result = app.wallpaperAssetStore.importFromUri(uri)
            importing = false
            result.onSuccess { imported ->
                val updated = ChatWallpaperConfig.forImportedImage(
                    fileName = imported.fileName,
                    extractedAccentArgb = imported.extractedAccentArgb,
                    dark = dark,
                )
                app.themePreferenceStore.setWallpaper(updated)
                // 后台顺手预烘焙默认模糊缓存并清理旧图片。
                launch {
                    app.wallpaperAssetStore.resolveDisplayFile(imported.fileName, updated.clampedBlurDp)
                    app.wallpaperAssetStore.pruneUnused(keepFileName = imported.fileName)
                }
            }.onFailure {
                notify(importFailedMsg, InfinityNoticeSeverity.ERROR)
            }
        }
    }

    WallpaperSettingsScreenContent(
        config = prefs.wallpaper,
        importing = importing,
        onNavigateBack = onNavigateBack,
        onReset = {
            app.themePreferenceStore.resetWallpaper()
            scope.launch { app.wallpaperAssetStore.pruneUnused(keepFileName = null) }
        },
        onSelectNone = {
            app.themePreferenceStore.resetWallpaper()
        },
        onPickPhoto = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onSelectPreset = { preset ->
            val current = prefs.wallpaper
            app.themePreferenceStore.setWallpaper(
                ChatWallpaperConfig.forPreset(
                    preset = preset,
                    dark = dark,
                    assistantReadingCard = current.assistantReadingCard,
                ),
            )
        },
        onBlurChange = { blurDp ->
            app.themePreferenceStore.setWallpaper(prefs.wallpaper.copy(blurDp = blurDp))
        },
        onDimChange = { dimFraction ->
            app.themePreferenceStore.setWallpaper(prefs.wallpaper.copy(dimFraction = dimFraction))
        },
        onAssistantCardChange = { enabled ->
            app.themePreferenceStore.setWallpaper(prefs.wallpaper.copy(assistantReadingCard = enabled))
        },
        onApplyAccent = { argb ->
            app.themePreferenceStore.rememberCustomColor(argb)
            app.themePreferenceStore.setPrimaryColor(Color(argb).value.toLong())
            app.themePreferenceStore.setColorSource(InfinityColorSource.INFINITY)
            notify(accentAppliedMsg, InfinityNoticeSeverity.SUCCESS)
        },
    )
}

@Composable
internal fun WallpaperSettingsScreenContent(
    config: ChatWallpaperConfig,
    importing: Boolean = false,
    onNavigateBack: () -> Unit,
    onReset: () -> Unit,
    onSelectNone: () -> Unit,
    onPickPhoto: () -> Unit,
    onSelectPreset: (WallpaperPresetId) -> Unit,
    onBlurChange: (Int) -> Unit,
    onDimChange: (Float) -> Unit,
    onAssistantCardChange: (Boolean) -> Unit,
    onApplyAccent: (Int) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    // 滑块拖动期间保持本地流畅值，松手后落盘并触发图片模糊缓存预烘焙，避免拖动期间频繁重写磁盘。
    var draftBlurDp by remember(config.source) { mutableIntStateOf(config.clampedBlurDp) }
    var draftDim by remember(config.source) { mutableFloatStateOf(config.clampedDimFraction) }
    LaunchedEffect(config.clampedBlurDp) { draftBlurDp = config.clampedBlurDp }
    LaunchedEffect(config.clampedDimFraction) { draftDim = config.clampedDimFraction }

    val livePreviewConfig = remember(config, draftBlurDp, draftDim) {
        config.copy(blurDp = draftBlurDp, dimFraction = draftDim)
    }

    InfinitySettingsPageLayout(
        title = stringResource(R.string.wallpaper_title),
        onBack = onNavigateBack,
        horizontalContentPadding = 0.dp,
        actions = {
            if (config.isActive) {
                FilledTonalButton(
                    onClick = onReset,
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .height(32.dp)
                        .testTag("wallpaper_reset_button"),
                ) {
                    Text(
                        text = stringResource(R.string.wallpaper_action_reset),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val wide = maxWidth >= WallpaperWideMinWidth
            if (wide) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Wallpaper,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.wallpaper_preview_title),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        WallpaperMiniChatPreview(
                            config = livePreviewConfig,
                            dark = dark,
                            height = 460.dp,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        WallpaperControlsColumn(
                            config = config,
                            blurDp = draftBlurDp,
                            dimFraction = draftDim,
                            importing = importing,
                            dark = dark,
                            horizontalPadding = 0.dp,
                            onSelectNone = onSelectNone,
                            onPickPhoto = onPickPhoto,
                            onSelectPreset = onSelectPreset,
                            onBlurPreview = { draftBlurDp = it },
                            onBlurCommit = { onBlurChange(draftBlurDp) },
                            onDimPreview = { draftDim = it },
                            onDimCommit = { onDimChange(draftDim) },
                            onAssistantCardChange = onAssistantCardChange,
                            onApplyAccent = onApplyAccent,
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    WallpaperMiniChatPreview(
                        config = livePreviewConfig,
                        dark = dark,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    WallpaperControlsColumn(
                        config = config,
                        blurDp = draftBlurDp,
                        dimFraction = draftDim,
                        importing = importing,
                        dark = dark,
                        horizontalPadding = 16.dp,
                        onSelectNone = onSelectNone,
                        onPickPhoto = onPickPhoto,
                        onSelectPreset = onSelectPreset,
                        onBlurPreview = { draftBlurDp = it },
                        onBlurCommit = { onBlurChange(draftBlurDp) },
                        onDimPreview = { draftDim = it },
                        onDimCommit = { onDimChange(draftDim) },
                        onAssistantCardChange = onAssistantCardChange,
                        onApplyAccent = onApplyAccent,
                    )
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun WallpaperMiniChatPreview(
    config: ChatWallpaperConfig,
    dark: Boolean,
    height: Dp = 236.dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = if (dark) InfinityPureWhite.copy(alpha = 0.12f) else scheme.outlineVariant.copy(alpha = 0.60f)
    val dimPercent = (config.clampedDimFraction * 100f).roundToInt()

    Surface(
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, borderColor),
        color = scheme.background,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .testTag("wallpaper_live_preview"),
    ) {
        Box(Modifier.fillMaxSize()) {
            ChatWallpaperLayer(
                config = config,
                dark = dark,
                modifier = Modifier.fillMaxSize(),
            )
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (dark) InfinityObsidianGlassTint.copy(alpha = 0.72f)
                            else InfinityPureWhite.copy(alpha = 0.76f),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = scheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.wallpaper_preview_chat_title),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (config.isActive) {
                        val badgeSegments = buildList {
                            if (config.clampedBlurDp > 0) add(stringResource(R.string.wallpaper_meta_blur, config.clampedBlurDp))
                            add(stringResource(R.string.wallpaper_meta_scrim, dimPercent))
                        }
                        Surface(
                            shape = CircleShape,
                            color = if (dark) InfinityPureWhite.copy(alpha = 0.10f)
                            else scheme.onSurface.copy(alpha = 0.06f),
                        ) {
                            InfinityMetaLine(
                                segments = badgeSegments,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (config.isActive) {
                                if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.86f)
                                else scheme.primaryContainer.copy(alpha = 0.88f)
                            } else {
                                scheme.surfaceContainerHigh
                            },
                            border = if (config.isActive) {
                                BorderStroke(
                                    0.5.dp,
                                    if (dark) InfinityPureWhite.copy(alpha = 0.11f)
                                    else scheme.outlineVariant.copy(alpha = 0.40f),
                                )
                            } else {
                                null
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.wallpaper_preview_user_msg),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                                color = if (config.isActive && !dark) scheme.onPrimaryContainer else scheme.onSurface,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            )
                        }
                    }

                    if (config.effectiveAssistantCard) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (dark) InfinityObsidianDialogContainer.copy(alpha = 0.78f)
                            else InfinityPureWhite.copy(alpha = 0.82f),
                            border = BorderStroke(
                                0.5.dp,
                                if (dark) InfinityPureWhite.copy(alpha = 0.09f)
                                else scheme.outlineVariant.copy(alpha = 0.45f),
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.wallpaper_preview_assistant_card_msg),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                                color = scheme.onSurface,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.wallpaper_preview_assistant_plain_msg),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (dark) InfinityObsidianGlassTint.copy(alpha = 0.76f)
                        else InfinityPureWhite.copy(alpha = 0.84f),
                        border = BorderStroke(
                            0.8.dp,
                            if (dark) InfinityPureWhite.copy(alpha = 0.14f)
                            else scheme.outlineVariant.copy(alpha = 0.55f),
                        ),
                        modifier = Modifier.fillMaxWidth().height(34.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.wallpaper_preview_composer_hint),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = scheme.onSurfaceVariant,
                            )
                            Surface(
                                shape = CircleShape,
                                color = scheme.primary,
                                modifier = Modifier.size(22.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Send,
                                        contentDescription = null,
                                        tint = scheme.onPrimary,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperControlsColumn(
    config: ChatWallpaperConfig,
    blurDp: Int,
    dimFraction: Float,
    importing: Boolean,
    dark: Boolean,
    horizontalPadding: Dp,
    onSelectNone: () -> Unit,
    onPickPhoto: () -> Unit,
    onSelectPreset: (WallpaperPresetId) -> Unit,
    onBlurPreview: (Int) -> Unit,
    onBlurCommit: () -> Unit,
    onDimPreview: (Float) -> Unit,
    onDimCommit: () -> Unit,
    onAssistantCardChange: (Boolean) -> Unit,
    onApplyAccent: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val enabled = config.isActive
    val isCustomImage = config.source is WallpaperSource.Image
    val accentArgb = config.recommendedAccentArgb()
    val accentColor = accentArgb?.let { Color(it) } ?: scheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. 壁纸来源
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.wallpaper_section_source),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurfaceVariant,
                )
                if (isCustomImage) {
                    FilledTonalButton(
                        onClick = onPickPhoto,
                        enabled = !importing,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.wallpaper_action_replace_photo),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WallpaperGalleryTile(
                    label = stringResource(R.string.wallpaper_source_none),
                    selected = !config.isActive,
                    onClick = onSelectNone,
                    modifier = Modifier.testTag("wallpaper_source_none"),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (dark) Color(0xFF0A0B0F) else Color(0xFFF2F3F6)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Block,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                WallpaperGalleryTile(
                    label = stringResource(
                        if (isCustomImage) R.string.wallpaper_source_custom_photo else R.string.wallpaper_source_gallery,
                    ),
                    selected = isCustomImage,
                    onClick = { if (!importing) onPickPhoto() },
                    modifier = Modifier.testTag("wallpaper_source_gallery"),
                ) {
                    if (importing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(scheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else if (isCustomImage) {
                        Box(Modifier.fillMaxSize()) {
                            ChatWallpaperLayer(
                                config = config.copy(blurDp = 0, dimFraction = 0.15f),
                                dark = dark,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Surface(
                                shape = CircleShape,
                                color = InfinityObsidianGlassTint.copy(alpha = 0.65f),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(5.dp)
                                    .size(20.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.PhotoLibrary,
                                        contentDescription = null,
                                        tint = InfinityPureWhite,
                                        modifier = Modifier.size(11.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(scheme.primaryContainer.copy(alpha = if (dark) 0.35f else 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.PhotoLibrary,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                WallpaperPresetId.ordered(dark).forEach { preset ->
                    val isSelected = (config.source as? WallpaperSource.Preset)?.id == preset
                    WallpaperGalleryTile(
                        label = preset.displayLabel(),
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        modifier = Modifier.testTag("wallpaper_preset_${preset.storageKey}"),
                    ) {
                        ProceduralWallpaperPresetArtwork(
                            preset = preset,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // 2. 可读性调节
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.wallpaper_section_readability),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurfaceVariant,
            )
            BettboxListGroup {
                WallpaperSliderRow(
                    icon = Icons.Rounded.BlurOn,
                    title = stringResource(R.string.wallpaper_blur_label),
                    valueText = stringResource(R.string.wallpaper_blur_value, blurDp),
                    value = blurDp.toFloat(),
                    valueRange = ChatWallpaperConfig.MIN_BLUR_DP.toFloat()..ChatWallpaperConfig.MAX_BLUR_DP.toFloat(),
                    enabled = enabled,
                    onValueChange = { onBlurPreview(it.roundToInt()) },
                    onValueChangeFinished = onBlurCommit,
                    sliderTag = "wallpaper_blur_slider",
                )
                InfinityGroupDivider()
                WallpaperSliderRow(
                    icon = Icons.Rounded.Contrast,
                    title = stringResource(if (dark) R.string.wallpaper_scrim_dark_label else R.string.wallpaper_scrim_light_label),
                    valueText = stringResource(R.string.wallpaper_scrim_value, (dimFraction * 100f).roundToInt()),
                    value = dimFraction,
                    valueRange = ChatWallpaperConfig.MIN_DIM_FRACTION..ChatWallpaperConfig.MAX_DIM_FRACTION,
                    enabled = enabled,
                    onValueChange = onDimPreview,
                    onValueChangeFinished = onDimCommit,
                    sliderTag = "wallpaper_scrim_slider",
                )
                InfinityGroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onAssistantCardChange(!config.assistantReadingCard) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.wallpaper_assistant_card_title),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.45f),
                        )
                        Text(
                            text = stringResource(R.string.wallpaper_assistant_card_desc),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    InfinitySwitch(
                        checked = config.assistantReadingCard,
                        onCheckedChange = onAssistantCardChange,
                        enabled = enabled,
                        modifier = Modifier.testTag("wallpaper_assistant_card_switch"),
                    )
                }
            }
        }

        // 3. 主题联动
        Column(
            modifier = Modifier.padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.wallpaper_section_theme_sync),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurfaceVariant,
            )
            BettboxListGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accentColor.copy(alpha = if (enabled) 0.22f else 0.10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = if (enabled) 1f else 0.45f))
                                .border(1.dp, InfinityPureWhite.copy(alpha = 0.45f), CircleShape),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.wallpaper_extract_accent_title),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.45f),
                        )
                        Text(
                            text = stringResource(R.string.wallpaper_extract_accent_desc),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = { accentArgb?.let(onApplyAccent) },
                        enabled = enabled && accentArgb != null,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("wallpaper_apply_accent_button"),
                    ) {
                        Icon(Icons.Rounded.Colorize, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.wallpaper_action_apply_accent),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.wallpaper_footnote),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun WallpaperGalleryTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .width(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(bottom = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 68.dp, height = 92.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = if (selected) 2.dp else 0.8.dp,
                    color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(14.dp),
                ),
        ) {
            content()
            if (selected) {
                Surface(
                    shape = CircleShape,
                    color = scheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(18.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = scheme.onPrimary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 11.5.sp,
            ),
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WallpaperSliderRow(
    icon: ImageVector,
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    sliderTag: String,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.45f),
                )
            }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = InfinityMonospace),
                color = if (enabled) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
                inactiveTrackColor = scheme.surfaceContainerHighest,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .testTag(sliderTag),
        )
    }
}
