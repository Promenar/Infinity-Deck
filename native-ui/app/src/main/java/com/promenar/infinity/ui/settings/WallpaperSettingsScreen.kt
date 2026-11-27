package com.promenar.infinity.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.utils.InfinityLogger
import com.promenar.infinity.data.wallpaper.ImportedWallpaperEntry
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityConfirmDialog
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
import com.promenar.infinity.ui.wallpaper.WallpaperFraming
import com.promenar.infinity.ui.wallpaper.WallpaperPresetId
import com.promenar.infinity.ui.wallpaper.WallpaperSource
import com.promenar.infinity.ui.wallpaper.isWideWallpaperViewport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "WallpaperSettingsScreen"
private val WallpaperStudioMaxWidth = 560.dp

private enum class WallpaperAspectPreviewMode { OUTER_PORTRAIT, INNER_WIDE }

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
    var libraryEntries by remember { mutableStateOf<List<ImportedWallpaperEntry>>(emptyList()) }
    var pendingDeleteFileName by remember { mutableStateOf<String?>(null) }

    val accentAppliedMsg = stringResource(R.string.wallpaper_notice_accent_applied)
    val importFailedMsg = stringResource(R.string.wallpaper_notice_import_failed)
    val deletedMsg = stringResource(R.string.wallpaper_notice_deleted)

    fun notify(message: String, severity: InfinityNoticeSeverity = InfinityNoticeSeverity.INFO) {
        if (noticeController != null) {
            noticeController.show(severity = severity, message = message)
        } else {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun reloadLibrary() {
        scope.launch {
            libraryEntries = app.wallpaperAssetStore.listImportedWallpapers()
        }
    }

    LaunchedEffect(Unit) {
        libraryEntries = app.wallpaperAssetStore.listImportedWallpapers()
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
                libraryEntries = app.wallpaperAssetStore.listImportedWallpapers()
                // 后台预烘焙默认模糊缓存并按上限维护本地壁纸库，保留多张历史图片供切换。
                launch {
                    app.wallpaperAssetStore.resolveDisplayFile(imported.fileName, updated.clampedBlurDp)
                    app.wallpaperAssetStore.enforceLibraryLimit(keepFileName = imported.fileName)
                    libraryEntries = app.wallpaperAssetStore.listImportedWallpapers()
                }
            }.onFailure { error ->
                InfinityLogger.logError(TAG, error)
                notify(importFailedMsg, InfinityNoticeSeverity.ERROR)
            }
        }
    }

    WallpaperSettingsScreenContent(
        config = prefs.wallpaper,
        importedWallpapers = libraryEntries,
        importing = importing,
        onNavigateBack = onNavigateBack,
        onReset = {
            app.themePreferenceStore.resetWallpaper()
        },
        onSelectNone = {
            app.themePreferenceStore.resetWallpaper()
        },
        onPickPhoto = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onSelectImportedPhoto = { entry ->
            scope.launch {
                val accent = app.wallpaperAssetStore.extractAccentForFile(entry.fileName)
                val current = prefs.wallpaper
                val switched = if (current.source is WallpaperSource.Image) {
                    current.copy(
                        source = WallpaperSource.Image(fileName = entry.fileName, extractedAccentArgb = accent),
                        compactFraming = WallpaperFraming.Default,
                        wideFraming = WallpaperFraming.Default,
                    )
                } else {
                    ChatWallpaperConfig.forImportedImage(
                        fileName = entry.fileName,
                        extractedAccentArgb = accent,
                        dark = dark,
                    ).copy(assistantReadingCard = current.assistantReadingCard)
                }
                app.themePreferenceStore.setWallpaper(switched)
                launch { app.wallpaperAssetStore.resolveDisplayFile(entry.fileName, switched.clampedBlurDp) }
            }
        },
        onRequestDeletePhoto = { fileName ->
            pendingDeleteFileName = fileName
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
        onFramingChange = { wide, framing ->
            app.themePreferenceStore.setWallpaper(prefs.wallpaper.withFraming(wide, framing))
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

    val deleteTarget = pendingDeleteFileName
    if (deleteTarget != null) {
        InfinityConfirmDialog(
            title = stringResource(R.string.wallpaper_delete_dialog_title),
            message = stringResource(R.string.wallpaper_delete_dialog_message),
            confirmText = stringResource(R.string.wallpaper_delete_dialog_confirm),
            cancelText = stringResource(R.string.common_cancel),
            isDestructive = true,
            onConfirm = {
                pendingDeleteFileName = null
                scope.launch {
                    val activeFile = (prefs.wallpaper.source as? WallpaperSource.Image)?.fileName
                    app.wallpaperAssetStore.deleteImportedWallpaper(deleteTarget)
                    if (activeFile == deleteTarget) {
                        app.themePreferenceStore.resetWallpaper()
                    }
                    reloadLibrary()
                    notify(deletedMsg, InfinityNoticeSeverity.INFO)
                }
            },
            onCancel = { pendingDeleteFileName = null },
        )
    }
}

@Composable
internal fun WallpaperSettingsScreenContent(
    config: ChatWallpaperConfig,
    importedWallpapers: List<ImportedWallpaperEntry> = emptyList(),
    importing: Boolean = false,
    onNavigateBack: () -> Unit,
    onReset: () -> Unit,
    onSelectNone: () -> Unit,
    onPickPhoto: () -> Unit,
    onSelectImportedPhoto: (ImportedWallpaperEntry) -> Unit = {},
    onRequestDeletePhoto: (String) -> Unit = {},
    onSelectPreset: (WallpaperPresetId) -> Unit,
    onFramingChange: (wide: Boolean, framing: WallpaperFraming) -> Unit = { _, _ -> },
    onBlurChange: (Int) -> Unit,
    onDimChange: (Float) -> Unit,
    onAssistantCardChange: (Boolean) -> Unit,
    onApplyAccent: (Int) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val configuration = LocalConfiguration.current
    val initialAspect = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        if (isWideWallpaperViewport(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())) {
            WallpaperAspectPreviewMode.INNER_WIDE
        } else {
            WallpaperAspectPreviewMode.OUTER_PORTRAIT
        }
    }
    var previewAspect by remember { mutableStateOf(initialAspect) }
    var showPreviewBubbles by remember { mutableStateOf(true) }

    // 滑块与手势拖动期间保持本地即时值，松手或短暂防抖后再落盘，保证 120fps 顺滑体验。
    var draftBlurDp by remember(config.source) { mutableIntStateOf(config.clampedBlurDp) }
    var draftDim by remember(config.source) { mutableFloatStateOf(config.clampedDimFraction) }
    var draftCompactFraming by remember(config.source) { mutableStateOf(config.compactFraming) }
    var draftWideFraming by remember(config.source) { mutableStateOf(config.wideFraming) }

    LaunchedEffect(config.clampedBlurDp) { draftBlurDp = config.clampedBlurDp }
    LaunchedEffect(config.clampedDimFraction) { draftDim = config.clampedDimFraction }
    LaunchedEffect(config.compactFraming) { draftCompactFraming = config.compactFraming }
    LaunchedEffect(config.wideFraming) { draftWideFraming = config.wideFraming }

    val activeAspectWide = previewAspect == WallpaperAspectPreviewMode.INNER_WIDE
    val activeFraming = if (activeAspectWide) draftWideFraming else draftCompactFraming

    // 手势构图防抖落盘（220ms 静默后写入偏好）。
    LaunchedEffect(draftCompactFraming, config.source) {
        if (config.source is WallpaperSource.Image && draftCompactFraming != config.compactFraming) {
            delay(220)
            onFramingChange(false, draftCompactFraming)
        }
    }
    LaunchedEffect(draftWideFraming, config.source) {
        if (config.source is WallpaperSource.Image && draftWideFraming != config.wideFraming) {
            delay(220)
            onFramingChange(true, draftWideFraming)
        }
    }

    val livePreviewConfig = remember(config, draftBlurDp, draftDim, draftCompactFraming, draftWideFraming) {
        config.copy(
            blurDp = draftBlurDp,
            dimFraction = draftDim,
            compactFraming = draftCompactFraming,
            wideFraming = draftWideFraming,
        )
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = WallpaperStudioMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                WallpaperInteractivePreviewStage(
                    config = livePreviewConfig,
                    dark = dark,
                    aspectMode = previewAspect,
                    showBubbles = showPreviewBubbles,
                    activeFraming = activeFraming,
                    onSelectAspect = { previewAspect = it },
                    onToggleBubbles = { showPreviewBubbles = !showPreviewBubbles },
                    onFramingGesture = { updated ->
                        if (activeAspectWide) {
                            draftWideFraming = updated
                        } else {
                            draftCompactFraming = updated
                        }
                    },
                    onResetFraming = {
                        if (activeAspectWide) {
                            draftWideFraming = WallpaperFraming.Default
                            onFramingChange(true, WallpaperFraming.Default)
                        } else {
                            draftCompactFraming = WallpaperFraming.Default
                            onFramingChange(false, WallpaperFraming.Default)
                        }
                    },
                )

                WallpaperControlsColumn(
                    config = config,
                    importedWallpapers = importedWallpapers,
                    blurDp = draftBlurDp,
                    dimFraction = draftDim,
                    importing = importing,
                    dark = dark,
                    onSelectNone = onSelectNone,
                    onPickPhoto = onPickPhoto,
                    onSelectImportedPhoto = onSelectImportedPhoto,
                    onRequestDeletePhoto = onRequestDeletePhoto,
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
    }
}

@Composable
private fun WallpaperInteractivePreviewStage(
    config: ChatWallpaperConfig,
    dark: Boolean,
    aspectMode: WallpaperAspectPreviewMode,
    showBubbles: Boolean,
    activeFraming: WallpaperFraming,
    onSelectAspect: (WallpaperAspectPreviewMode) -> Unit,
    onToggleBubbles: () -> Unit,
    onFramingGesture: (WallpaperFraming) -> Unit,
    onResetFraming: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val isCustomPhoto = config.source is WallpaperSource.Image
    val stageBg = if (dark) InfinityObsidianDialogContainer else scheme.surfaceContainerLow
    val frameBorder = if (dark) InfinityPureWhite.copy(alpha = 0.16f) else scheme.outlineVariant.copy(alpha = 0.65f)
    val dimPercent = (config.clampedDimFraction * 100f).roundToInt()

    val latestFraming by rememberUpdatedState(activeFraming)
    val latestOnFraming by rememberUpdatedState(onFramingGesture)

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = stageBg,
        border = BorderStroke(0.8.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wallpaper_live_preview"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 顶栏：外屏竖屏 / 内屏展开切换 + 纯构图 / 居中按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                WallpaperAspectSwitcher(
                    selected = aspectMode,
                    onSelect = onSelectAspect,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalButton(
                        onClick = onToggleBubbles,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Icon(
                            imageVector = if (showBubbles) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (showBubbles) R.string.wallpaper_preview_pure_framing
                                else R.string.wallpaper_preview_show_bubbles,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (isCustomPhoto) {
                        FilledTonalButton(
                            onClick = onResetFraming,
                            enabled = !activeFraming.isDefault,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Icon(Icons.Rounded.CenterFocusStrong, null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = stringResource(R.string.wallpaper_framing_center),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            // 中央实比例画布：支持对已导入图片直接双指缩放 + 拖动调焦点
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(238.dp),
                contentAlignment = Alignment.Center,
            ) {
                val frameRatio = if (aspectMode == WallpaperAspectPreviewMode.OUTER_PORTRAIT) 0.56f else 1.04f
                val gestureModifier = if (isCustomPhoto) {
                    Modifier.pointerInput(aspectMode, config.source) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val current = latestFraming
                            val newScale = (current.scale * zoom).coerceIn(
                                WallpaperFraming.MIN_SCALE,
                                WallpaperFraming.MAX_SCALE,
                            )
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            // 将手指拖动距离按预览框边长归一化，兼顾放大态精细微调与 1.0× 下裁切焦点平移。
                            val sensitivity = (2.2f / newScale).coerceIn(0.65f, 2.2f)
                            val newOffsetX = (current.offsetX + (pan.x / w) * sensitivity).coerceIn(-1f, 1f)
                            val newOffsetY = (current.offsetY + (pan.y / h) * sensitivity).coerceIn(-1f, 1f)
                            latestOnFraming(WallpaperFraming(newScale, newOffsetX, newOffsetY))
                        }
                    }
                } else {
                    Modifier
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.2.dp, frameBorder),
                    color = scheme.background,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(frameRatio)
                        .then(gestureModifier),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        ChatWallpaperLayer(
                            config = config,
                            dark = dark,
                            framingOverride = activeFraming,
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (!showBubbles) {
                            Canvas(Modifier.fillMaxSize()) {
                                val lineColor = (if (dark) InfinityPureWhite else scheme.onSurface).copy(alpha = 0.24f)
                                drawLine(lineColor, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), 1f)
                                drawLine(lineColor, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), 1f)
                                drawLine(lineColor, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), 1f)
                                drawLine(lineColor, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), 1f)
                                val focus = Offset(
                                    x = size.width * (0.5f - activeFraming.offsetX * 0.25f),
                                    y = size.height * (0.5f - activeFraming.offsetY * 0.25f),
                                )
                                drawCircle(InfinityPureWhite.copy(alpha = 0.85f), radius = 14f, center = focus, style = Stroke(2f))
                                drawCircle(InfinityPureWhite, radius = 3.5f, center = focus)
                            }
                        } else {
                            WallpaperMiniChatOverlay(
                                config = config,
                                dark = dark,
                                dimPercent = dimPercent,
                                frameBorder = frameBorder,
                            )
                        }
                    }
                }
            }

            // 底部提示条：图片壁纸显示双画幅构图手势说明与实时倍率
            if (isCustomPhoto) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.OpenWith, null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                        Text(
                            text = stringResource(R.string.wallpaper_framing_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = scheme.primary.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = String.format(Locale.US, "%.2f×", activeFraming.scale),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = InfinityMonospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = scheme.primary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperAspectSwitcher(
    selected: WallpaperAspectPreviewMode,
    onSelect: (WallpaperAspectPreviewMode) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.6.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.height(32.dp),
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf(
                Triple(WallpaperAspectPreviewMode.OUTER_PORTRAIT, Icons.Rounded.PhoneAndroid, R.string.wallpaper_aspect_outer),
                Triple(WallpaperAspectPreviewMode.INNER_WIDE, Icons.Rounded.TabletAndroid, R.string.wallpaper_aspect_inner),
            ).forEach { (mode, icon, labelRes) ->
                val active = mode == selected
                Surface(
                    shape = CircleShape,
                    color = if (active) scheme.primaryContainer else Color.Transparent,
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(
                            selected = active,
                            role = Role.Tab,
                            onClick = { onSelect(mode) },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            ),
                            color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WallpaperMiniChatOverlay(
    config: ChatWallpaperConfig,
    dark: Boolean,
    dimPercent: Int,
    frameBorder: Color,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (dark) InfinityObsidianGlassTint.copy(alpha = 0.72f)
                    else InfinityPureWhite.copy(alpha = 0.78f),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.wallpaper_preview_title),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
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
                InfinityMetaLine(
                    segments = badgeSegments,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontFamily = InfinityMonospace),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (config.isActive) {
                        if (dark) scheme.surfaceContainerHigh.copy(alpha = 0.86f)
                        else scheme.primaryContainer.copy(alpha = 0.90f)
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
                        text = stringResource(R.string.wallpaper_preview_short_user_msg),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, lineHeight = 13.sp),
                        color = if (config.isActive && !dark) scheme.onPrimaryContainer else scheme.onSurface,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
            }

            if (config.effectiveAssistantCard) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (dark) InfinityObsidianDialogContainer.copy(alpha = 0.78f)
                    else InfinityPureWhite.copy(alpha = 0.84f),
                    border = BorderStroke(
                        0.5.dp,
                        if (dark) InfinityPureWhite.copy(alpha = 0.10f)
                        else scheme.outlineVariant.copy(alpha = 0.45f),
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.wallpaper_preview_short_assistant_card_msg),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, lineHeight = 13.sp),
                        color = scheme.onSurface,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.wallpaper_preview_short_assistant_plain_msg),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, lineHeight = 13.sp),
                    color = scheme.onSurface,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                )
            }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Surface(
                shape = CircleShape,
                color = if (dark) InfinityObsidianGlassTint.copy(alpha = 0.76f)
                else InfinityPureWhite.copy(alpha = 0.84f),
                border = BorderStroke(0.6.dp, frameBorder),
                modifier = Modifier.fillMaxWidth().height(26.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.wallpaper_preview_composer_hint),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = scheme.onSurfaceVariant,
                    )
                    Surface(shape = CircleShape, color = scheme.primary, modifier = Modifier.size(16.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Rounded.Send, null, tint = scheme.onPrimary, modifier = Modifier.size(9.dp))
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
    importedWallpapers: List<ImportedWallpaperEntry>,
    blurDp: Int,
    dimFraction: Float,
    importing: Boolean,
    dark: Boolean,
    onSelectNone: () -> Unit,
    onPickPhoto: () -> Unit,
    onSelectImportedPhoto: (ImportedWallpaperEntry) -> Unit,
    onRequestDeletePhoto: (String) -> Unit,
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
    val activeImageFile = (config.source as? WallpaperSource.Image)?.fileName
    val accentArgb = config.recommendedAccentArgb()
    val accentColor = accentArgb?.let { Color(it) } ?: scheme.primary
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 1. 壁纸来源（纯净、相册导入、已导入多图管理、6 套程序化预设）
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.wallpaper_section_source),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurfaceVariant,
                )
                if (activeImageFile != null) {
                    OutlinedButton(
                        onClick = { onRequestDeletePhoto(activeImageFile) },
                        shape = CircleShape,
                        border = BorderStroke(0.8.dp, scheme.error.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("wallpaper_delete_current_button"),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.wallpaper_action_delete_current),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WallpaperGalleryTile(
                    label = stringResource(R.string.wallpaper_source_none),
                    selected = !config.isActive,
                    onClick = onSelectNone,
                    modifier = Modifier.testTag("wallpaper_source_none"),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(scheme.surfaceContainerHigh),
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
                    label = stringResource(R.string.wallpaper_source_gallery),
                    selected = false,
                    onClick = { if (!importing) onPickPhoto() },
                    modifier = Modifier.testTag("wallpaper_source_gallery"),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(scheme.primaryContainer.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (importing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Rounded.AddPhotoAlternate,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                // 本机已导入图片库：支持切换与单张删除
                importedWallpapers.forEachIndexed { idx, entry ->
                    val isSelected = activeImageFile == entry.fileName
                    val deleteCd = stringResource(R.string.wallpaper_action_delete_photo_cd, idx + 1)
                    WallpaperGalleryTile(
                        label = stringResource(R.string.wallpaper_saved_photo_label, idx + 1),
                        selected = isSelected,
                        onClick = { onSelectImportedPhoto(entry) },
                        onDeleteClick = { onRequestDeletePhoto(entry.fileName) },
                        deleteContentDescription = deleteCd,
                        modifier = Modifier.testTag("wallpaper_imported_${entry.fileName}"),
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(entry.file)
                                .crossfade(120)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                WallpaperPresetId.ordered(dark).forEach { preset ->
                    val selected = (config.source as? WallpaperSource.Preset)?.id == preset
                    WallpaperGalleryTile(
                        label = preset.displayLabel(),
                        selected = selected,
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

        // 2. 视觉与可读性调节
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.wallpaper_section_readability),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = scheme.onSurfaceVariant,
            )
            BettboxListGroup {
                WallpaperSliderRow(
                    icon = Icons.Rounded.BlurOn,
                    label = stringResource(R.string.wallpaper_blur_label),
                    valueText = stringResource(R.string.wallpaper_blur_value, blurDp),
                    value = blurDp.toFloat(),
                    valueRange = ChatWallpaperConfig.MIN_BLUR_DP.toFloat()..ChatWallpaperConfig.MAX_BLUR_DP.toFloat(),
                    enabled = enabled,
                    onValueChange = { onBlurPreview(it.roundToInt()) },
                    onValueChangeFinished = onBlurCommit,
                    sliderTag = "wallpaper_blur_slider",
                )
                InfinityGroupDivider(startInset = 16.dp)
                WallpaperSliderRow(
                    icon = Icons.Rounded.Contrast,
                    label = stringResource(
                        if (dark) R.string.wallpaper_scrim_dark_label else R.string.wallpaper_scrim_light_label,
                    ),
                    valueText = stringResource(R.string.wallpaper_scrim_value, (dimFraction * 100f).roundToInt()),
                    value = dimFraction,
                    valueRange = ChatWallpaperConfig.MIN_DIM_FRACTION..ChatWallpaperConfig.MAX_DIM_FRACTION,
                    enabled = enabled,
                    onValueChange = onDimPreview,
                    onValueChangeFinished = onDimCommit,
                    sliderTag = "wallpaper_dim_slider",
                )
                InfinityGroupDivider(startInset = 16.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled, role = Role.Switch) {
                            onAssistantCardChange(!config.assistantReadingCard)
                        }
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
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.45f),
                        )
                    }
                    InfinitySwitch(
                        checked = config.assistantReadingCard,
                        enabled = enabled,
                        onCheckedChange = onAssistantCardChange,
                        modifier = Modifier.testTag("wallpaper_assistant_card_switch"),
                    )
                }

                if (enabled && accentArgb != null) {
                    InfinityGroupDivider(startInset = 16.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                                .border(1.5.dp, scheme.outlineVariant, CircleShape),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.wallpaper_extract_accent_title),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = scheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.wallpaper_extract_accent_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(
                            onClick = { onApplyAccent(accentArgb) },
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
            }
        }

        Text(
            text = stringResource(R.string.wallpaper_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun WallpaperGalleryTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteClick: (() -> Unit)? = null,
    deleteContentDescription: String? = null,
    artwork: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .width(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(15.dp),
            border = BorderStroke(
                width = if (selected) 2.dp else 0.8.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.55f),
            ),
            modifier = Modifier.size(64.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                artwork()
                if (selected) {
                    Surface(
                        shape = CircleShape,
                        color = scheme.primary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
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
                if (onDeleteClick != null) {
                    Surface(
                        shape = CircleShape,
                        color = InfinityObsidianDialogContainer.copy(alpha = 0.86f),
                        border = BorderStroke(0.6.dp, InfinityPureWhite.copy(alpha = 0.24f)),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(3.dp)
                            .size(19.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onDeleteClick)
                            .semantics {
                                if (deleteContentDescription != null) {
                                    contentDescription = deleteContentDescription
                                }
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = null,
                                tint = InfinityPureWhite,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 11.sp,
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
    label: String,
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
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = label,
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
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .testTag(sliderTag),
        )
    }
}
