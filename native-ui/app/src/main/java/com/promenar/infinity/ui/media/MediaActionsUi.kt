package com.promenar.infinity.ui.media

import android.widget.Toast
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.promenar.infinity.R
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinitySnackbar
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.LocalInfinityNoticeController
import com.promenar.infinity.ui.common.SnackbarType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 媒体操作的结果提示通道；会话界面与查看器各自提供 Snackbar，未提供时退回 Toast。 */
fun interface MediaMessenger {
    fun show(message: String, isError: Boolean)
}

val LocalMediaMessenger = staticCompositionLocalOf<MediaMessenger?> { null }

private class MediaSnackbarVisuals(
    override val message: String,
    val isError: Boolean,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Short
}

/** 记住一个把提示投递到全站顶部通知胶囊的通道；无根宿主时回落到 [hostState]。 */
@Composable
fun rememberSnackbarMediaMessenger(hostState: SnackbarHostState): MediaMessenger {
    val globalNotice = LocalInfinityNoticeController.current
    val scope = rememberCoroutineScope()
    return remember(globalNotice, hostState, scope) {
        MediaMessenger { message, isError ->
            if (globalNotice != null) {
                globalNotice.show(
                    severity = if (isError) InfinityNoticeSeverity.ERROR else InfinityNoticeSeverity.SUCCESS,
                    message = message,
                )
            } else {
                scope.launch {
                    hostState.currentSnackbarData?.dismiss()
                    hostState.showSnackbar(MediaSnackbarVisuals(message, isError))
                }
            }
        }
    }
}

/** 与会话 Snackbar 同样式的媒体提示宿主。 */
@Composable
fun MediaSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val isError = (data.visuals as? MediaSnackbarVisuals)?.isError == true
        InfinitySnackbar(
            data = InfinitySnackbarData(
                message = data.visuals.message,
                type = if (isError) SnackbarType.ERROR else SnackbarType.SUCCESS,
            ),
        )
    }
}

/** 媒体操作运行在应用级作用域：卡片滚出屏幕或查看器关闭不会中断正在进行的保存。 */
private val mediaActionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** 保存、分享、用其它应用打开，并把结果转为本地化提示。 */
@Stable
class MediaActionRunner internal constructor(
    private val actions: MediaFileActions,
    private val messenger: MediaMessenger,
    private val text: (Int, String?) -> String,
) {
    fun save(available: MediaResolution.Available) = run(R.string.media_save_failed) { actions.save(available) }
    fun share(available: MediaResolution.Available) = run(R.string.media_share_failed) { actions.share(available) }
    fun openWith(available: MediaResolution.Available) = run(R.string.media_open_failed) { actions.openWith(available) }

    private fun run(failureTemplate: Int, block: suspend () -> MediaActionResult) {
        mediaActionScope.launch {
            when (val result = block()) {
                is MediaActionResult.Saved -> messenger.show(text(R.string.media_saved_to, result.location), false)
                MediaActionResult.Launched -> Unit
                is MediaActionResult.Failed -> {
                    val reason = text(
                        when (result.failure) {
                            MediaActionFailure.UNAVAILABLE -> R.string.media_failure_unavailable
                            MediaActionFailure.READ_FAILED -> R.string.media_failure_read
                            MediaActionFailure.WRITE_FAILED -> R.string.media_failure_write
                            MediaActionFailure.NO_APP -> R.string.media_failure_no_app
                        },
                        null,
                    )
                    messenger.show(text(failureTemplate, reason), true)
                }
            }
        }
    }
}

@Composable
fun rememberMediaActionRunner(environment: WorkspaceMediaEnvironment): MediaActionRunner {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val messenger = LocalMediaMessenger.current
    return remember(context, resources, environment, messenger) {
        MediaActionRunner(
            actions = MediaFileActions(context, environment.resolver, environment.sessionId),
            messenger = messenger ?: MediaMessenger { message, _ ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
            text = { id, arg -> if (arg == null) resources.getString(id) else resources.getString(id, arg) },
        )
    }
}
