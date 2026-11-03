package com.promenar.infinity.ui.avatar

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.UUID

internal class AvatarCropLauncher internal constructor(
    private val launchPicker: () -> Unit,
) {
    fun launch() = launchPicker()
}

/** Agent 与用户头像共用同一选择、圆形取景、实时预览和方形输出合同。 */
@Composable
internal fun rememberAvatarCropLauncher(
    onCropped: (Uri) -> Unit,
    onFailure: () -> Unit = {},
): AvatarCropLauncher {
    val context = LocalContext.current
    val currentOnCropped by rememberUpdatedState(onCropped)
    val currentOnFailure by rememberUpdatedState(onFailure)
    var pendingOutput by rememberSaveable { mutableStateOf<Uri?>(null) }

    val editorLauncher = rememberLauncherForActivityResult(AvatarEditorContract()) { result ->
        val output = result.destination ?: pendingOutput
        when (result) {
            is AvatarEditorResult.Saved -> currentOnCropped(result.destination)
            is AvatarEditorResult.Failed -> {
                AvatarCropFiles.deleteIfOwned(context.cacheDir, output)
                currentOnFailure()
            }
            is AvatarEditorResult.Cancelled -> AvatarCropFiles.deleteIfOwned(context.cacheDir, output)
        }
        pendingOutput = null
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { source ->
        if (source != null) {
            val destination = runCatching { AvatarCropFiles.createDestination(context.cacheDir) }.getOrNull()
            if (destination == null) {
                currentOnFailure()
            } else {
                pendingOutput = destination
                editorLauncher.launch(AvatarEditorRequest(source = source, destination = destination))
            }
        }
    }

    return remember(photoPicker) {
        AvatarCropLauncher {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

internal object AvatarCropFiles {
    private const val DIRECTORY = "avatar-crops"

    fun createDestination(cacheDirectory: File): Uri {
        val directory = File(cacheDirectory, DIRECTORY)
        check(directory.exists() || directory.mkdirs()) { "无法创建头像裁剪缓存目录" }
        directory.listFiles()
            ?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > MAX_AGE_MILLIS }
            ?.forEach(File::delete)
        return Uri.fromFile(File(directory, "crop-${UUID.randomUUID()}.jpg"))
    }

    fun deleteIfOwned(cacheDirectory: File, uri: Uri?) {
        if (uri?.scheme != "file") return
        val file = uri.path?.let(::File) ?: return
        val expectedParent = File(cacheDirectory, DIRECTORY)
        val owned = runCatching {
            file.canonicalFile.parentFile == expectedParent.canonicalFile
        }.getOrDefault(false)
        if (owned) file.delete()
    }

    private const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
}
