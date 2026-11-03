package com.promenar.infinity.ui.avatar

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AvatarEditorContractTest {
    private val contract = AvatarEditorContract()
    private val source = Uri.parse("content://fixture/source")
    private val destination = Uri.parse("file:///cache/avatar-crops/result.jpg")

    @Test
    fun `intent targets the in-app editor with readable source and output location`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val intent = contract.createIntent(context, AvatarEditorRequest(source, destination))

        assertThat(intent.component?.className).isEqualTo(AvatarEditorActivity::class.java.name)
        assertThat(intent.data).isEqualTo(source)
        assertThat(IntentCompat.getParcelableExtra(intent, AvatarEditorContract.EXTRA_DESTINATION, Uri::class.java))
            .isEqualTo(destination)
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    @Test
    fun `result codes map to saved, failed and cancelled with the output location`() {
        val data = AvatarEditorContract.resultIntent(destination)

        assertThat(contract.parseResult(Activity.RESULT_OK, data)).isEqualTo(AvatarEditorResult.Saved(destination))
        assertThat(contract.parseResult(AvatarEditorContract.RESULT_FAILED, data))
            .isEqualTo(AvatarEditorResult.Failed(destination))
        assertThat(contract.parseResult(Activity.RESULT_CANCELED, data))
            .isEqualTo(AvatarEditorResult.Cancelled(destination))
    }

    @Test
    fun `missing output location is never reported as saved`() {
        assertThat(contract.parseResult(Activity.RESULT_OK, null)).isEqualTo(AvatarEditorResult.Cancelled(null))
        assertThat(contract.parseResult(AvatarEditorContract.RESULT_FAILED, null))
            .isEqualTo(AvatarEditorResult.Failed(null))
        assertThat(AvatarCropGeometry.OUTPUT_SIZE).isEqualTo(512)
        assertThat(AvatarCropGeometry.OUTPUT_JPEG_QUALITY).isEqualTo(95)
    }
}
