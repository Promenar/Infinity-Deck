package com.promenar.infinity.ui.avatar

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AvatarEditorOutcome { SAVED, FAILED }

/** 编辑器状态：源图解码、取景状态与保存。取景状态写入 SavedStateHandle，进程重建后恢复。 */
internal class AvatarEditorViewModel(
    application: Application,
    private val handle: SavedStateHandle,
) : AndroidViewModel(application) {

    var image by mutableStateOf<AvatarSourceImage?>(null)
        private set
    var cropState by mutableStateOf(restoreCropState())
        private set
    var saving by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<AvatarEditorOutcome?>(null)
        private set

    private val replaceFailures = Channel<Unit>(Channel.CONFLATED)
    val replaceFailed: Flow<Unit> = replaceFailures.receiveAsFlow()

    private var loadJob: Job? = null

    /** 首次进入时解码来源；配置变更后重复调用不会重新解码。 */
    fun start(initialSource: Uri) {
        if (image != null || loadJob != null) return
        val source = handle.get<Uri>(KEY_SOURCE) ?: initialSource
        load(source, keepState = true)
    }

    /** “换一张”：解码成功后替换图片并重置取景；失败时保留当前图片。 */
    fun replace(source: Uri) {
        load(source, keepState = false)
    }

    fun update(change: (AvatarCropGeometry, AvatarCropState) -> AvatarCropState) {
        val geometry = image?.geometry ?: return
        if (saving) return
        applyCropState(change(geometry, cropState))
    }

    fun save(destination: Uri) {
        val current = image ?: return
        if (saving) return
        saving = true
        val state = cropState
        viewModelScope.launch {
            val saved = try {
                withContext(Dispatchers.IO) {
                    AvatarImageCodec.export(getApplication<Application>().contentResolver, current, state, destination)
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            } catch (_: OutOfMemoryError) {
                false
            }
            outcome = if (saved) AvatarEditorOutcome.SAVED else AvatarEditorOutcome.FAILED
        }
    }

    private fun load(source: Uri, keepState: Boolean) {
        val previous = loadJob
        loadJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            val loaded = try {
                withContext(Dispatchers.IO) {
                    AvatarImageCodec.load(getApplication<Application>().contentResolver, source)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            when {
                loaded != null -> {
                    handle[KEY_SOURCE] = source
                    image = loaded
                    applyCropState(
                        if (keepState) loaded.geometry.clamp(cropState) else loaded.geometry.initialState(),
                    )
                }
                image == null -> outcome = AvatarEditorOutcome.FAILED
                else -> replaceFailures.trySend(Unit)
            }
        }
    }

    private fun applyCropState(state: AvatarCropState) {
        cropState = state
        handle[KEY_STATE] = floatArrayOf(
            state.zoom,
            state.offsetX,
            state.offsetY,
            state.orientation.quarterTurns.toFloat(),
            if (state.orientation.mirrored) 1f else 0f,
        )
    }

    private fun restoreCropState(): AvatarCropState {
        val saved = handle.get<FloatArray>(KEY_STATE)?.takeIf { it.size == 5 } ?: return AvatarCropState()
        return AvatarCropState(
            zoom = saved[0],
            offsetX = saved[1],
            offsetY = saved[2],
            orientation = AvatarOrientation.of(saved[3].toInt(), saved[4] != 0f),
        )
    }

    private companion object {
        const val KEY_SOURCE = "avatar_editor_source"
        const val KEY_STATE = "avatar_editor_state"
    }
}
