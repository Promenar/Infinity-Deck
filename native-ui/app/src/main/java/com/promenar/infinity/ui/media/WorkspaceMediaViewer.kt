package com.promenar.infinity.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.promenar.infinity.ui.common.InfinityModalSheet
import com.promenar.infinity.ui.common.InfinitySheetHeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.ui.renderer.ImageLightbox
import com.promenar.infinity.ui.renderer.ImageLightboxActions
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 工作区图片查看器：沿用 [ImageLightbox]，保存、分享与用其它应用打开均经工作区来源，提示显示在查看器窗口内。 */
@Composable
fun WorkspaceImageViewer(
    available: MediaResolution.Available,
    model: WorkspaceMediaModel,
    onDismiss: () -> Unit,
) {
    val environment = LocalWorkspaceMedia.current ?: return
    val hostState = remember { SnackbarHostState() }
    val messenger = rememberSnackbarMediaMessenger(hostState)
    CompositionLocalProvider(LocalMediaMessenger provides messenger) {
        val runner = rememberMediaActionRunner(environment)
        ImageLightbox(
            imageUrl = model.toUri(),
            model = model,
            onDismiss = onDismiss,
            actions = ImageLightboxActions(
                onSave = { runner.save(available) },
                onShare = { runner.share(available) },
                onOpenWith = { runner.openWith(available) },
            ),
            overlay = {
                MediaSnackbarHost(
                    hostState = hostState,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
                )
            },
        )
    }
}

/**
 * 组内图片的全屏查看器：沿用 [ImageLightbox]，两侧按钮在 [paths] 之间切换，左上角显示页码，从 [startIndex] 开始。
 * 当前项尚未解析或不可用时仍保留查看器窗口，保存与分享提示文件不可用。
 */
@Composable
fun WorkspaceImageGroupViewer(
    paths: List<String>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    val environment = LocalWorkspaceMedia.current ?: return
    if (paths.isEmpty()) return
    var index by rememberSaveable { mutableIntStateOf(startIndex.coerceIn(0, paths.lastIndex)) }
    val current = index.coerceIn(0, paths.lastIndex)
    val path = paths[current]
    val resolved = rememberWorkspaceMediaResolution(environment, path)
    val available = resolved?.available
    val model = WorkspaceMediaModel(environment.sessionId, path, resolved?.identity)
    val hostState = remember { SnackbarHostState() }
    val messenger = rememberSnackbarMediaMessenger(hostState)
    val unavailableText = stringResource(R.string.media_failure_unavailable)
    CompositionLocalProvider(LocalMediaMessenger provides messenger) {
        val runner = rememberMediaActionRunner(environment)
        fun withAvailable(action: (MediaResolution.Available) -> Unit): () -> Unit = {
            if (available != null) action(available) else messenger.show(unavailableText, true)
        }
        ImageLightbox(
            imageUrl = model.toUri(),
            model = model,
            onDismiss = onDismiss,
            actions = ImageLightboxActions(
                onSave = withAvailable(runner::save),
                onShare = withAvailable(runner::share),
                onOpenWith = withAvailable(runner::openWith),
            ),
            overlay = {
                MediaPagerControls(
                    index = current,
                    count = paths.size,
                    onPrevious = { index = (current - 1).coerceAtLeast(0) },
                    onNext = { index = (current + 1).coerceAtMost(paths.lastIndex) },
                    counterAlignment = Alignment.TopStart,
                    modifier = Modifier.padding(8.dp),
                )
                MediaSnackbarHost(
                    hostState = hostState,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
                )
            },
        )
    }
}

/** 按类别打开已解析媒体：图片进 Lightbox，视频进全屏播放，音频与其它文件进底部面板。 */
@Composable
fun WorkspaceMediaViewerFor(
    available: MediaResolution.Available,
    model: WorkspaceMediaModel,
    onDismiss: () -> Unit,
) {
    when (available.ref.kind) {
        MediaKind.IMAGE -> WorkspaceImageViewer(available = available, model = model, onDismiss = onDismiss)
        MediaKind.VIDEO -> {
            val session = rememberMediaPlayerSession(available)
            LaunchedEffect(session) { session.play() }
            FullscreenVideoDialog(session = session, onDismiss = onDismiss)
        }
        MediaKind.AUDIO -> MediaPanelSheet(onDismiss = onDismiss) {
            WorkspaceAudioCard(available = available, autoPlay = true)
            MediaPanelActions(available)
        }
        MediaKind.FILE -> MediaPanelSheet(onDismiss = onDismiss) {
            WorkspaceFileCard(available = available, model = model)
        }
    }
}

/** 按虚拟路径打开查看器（资源管理器、正文内链接）；解析完成前不显示，不可用时在面板中显示占位。 */
@Composable
fun WorkspaceMediaViewer(path: String, onDismiss: () -> Unit) {
    val environment = LocalWorkspaceMedia.current ?: return
    val resolved = rememberWorkspaceMediaResolution(environment, path) ?: return
    when (val resolution = resolved.resolution) {
        is MediaResolution.Available -> WorkspaceMediaViewerFor(
            available = resolution,
            model = WorkspaceMediaModel(environment.sessionId, path, resolved.identity),
            onDismiss = onDismiss,
        )
        is MediaResolution.Unavailable -> MediaPanelSheet(onDismiss = onDismiss) {
            WorkspaceMediaUnavailable(
                path = path,
                reason = resolution.reason,
                onLocate = environment.onLocate
                    ?.takeIf { canLocateInExplorer(path) }
                    ?.let { locate -> mediaParentPath(path)?.let { parent -> { onDismiss(); locate(parent) } } },
            )
        }
    }
}

@Composable
private fun MediaPanelActions(available: MediaResolution.Available) {
    val environment = LocalWorkspaceMedia.current ?: return
    val runner = rememberMediaActionRunner(environment)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        MediaActionButtons(available = available, runner = runner)
    }
}

/** 音频、普通文件与不可用占位的面板；与全站上滑面板同一外壳，宽窗口居中显示。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPanelSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val hostState = remember { SnackbarHostState() }
    val messenger = rememberSnackbarMediaMessenger(hostState)
    InfinityModalSheet(onDismissRequest = onDismiss, height = InfinitySheetHeight.Content) {
        CompositionLocalProvider(LocalMediaMessenger provides messenger) {
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = InfinitySpacing.Large)
                        .padding(bottom = InfinitySpacing.Large)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                ) {
                    content()
                }
                MediaSnackbarHost(hostState = hostState, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}
