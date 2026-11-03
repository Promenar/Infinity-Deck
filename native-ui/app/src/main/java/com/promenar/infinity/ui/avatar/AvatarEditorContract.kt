package com.promenar.infinity.ui.avatar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat

internal data class AvatarEditorRequest(
    val source: Uri,
    val destination: Uri,
)

internal sealed interface AvatarEditorResult {
    /** 输出文件 Uri；取消或失败时由调用方清理。 */
    val destination: Uri?

    data class Saved(override val destination: Uri) : AvatarEditorResult
    data class Failed(override val destination: Uri?) : AvatarEditorResult
    data class Cancelled(override val destination: Uri?) : AvatarEditorResult
}

/** 启动头像编辑器并取回 512×512 JPEG 的输出位置。 */
internal class AvatarEditorContract : ActivityResultContract<AvatarEditorRequest, AvatarEditorResult>() {

    override fun createIntent(context: Context, input: AvatarEditorRequest): Intent =
        Intent(context, AvatarEditorActivity::class.java)
            .setData(input.source)
            .putExtra(EXTRA_DESTINATION, input.destination)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): AvatarEditorResult {
        val destination = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DESTINATION, Uri::class.java) }
        return when {
            resultCode == Activity.RESULT_OK && destination != null -> AvatarEditorResult.Saved(destination)
            resultCode == RESULT_FAILED -> AvatarEditorResult.Failed(destination)
            else -> AvatarEditorResult.Cancelled(destination)
        }
    }

    companion object {
        internal const val EXTRA_DESTINATION = "com.promenar.infinity.avatar.DESTINATION"
        internal const val RESULT_FAILED = Activity.RESULT_FIRST_USER

        fun resultIntent(destination: Uri): Intent = Intent().putExtra(EXTRA_DESTINATION, destination)
    }
}
