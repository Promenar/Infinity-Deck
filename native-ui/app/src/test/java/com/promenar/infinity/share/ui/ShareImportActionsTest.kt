package com.promenar.infinity.share.ui

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareIndexStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareImportActionsTest {
    private fun item(status: ShareImportStatus, indexStatus: ShareIndexStatus? = null) =
        ShareImportItem(Uri.parse("content://fixture/a.txt"), "a.txt", "text/plain", 4, status = status, indexStatus = indexStatus)

    @Test
    fun 全部导入但索引失败时保留重试与完成() {
        val state = ShareImportUiState(visible = true, items = listOf(item(ShareImportStatus.Created, ShareIndexStatus.Failed)))

        assertThat(shareImportActions(state)).containsExactly(ShareImportAction.RETRY, ShareImportAction.DONE).inOrder()
    }

    @Test
    fun 全部导入且索引正常时只有完成() {
        val state = ShareImportUiState(visible = true, items = listOf(item(ShareImportStatus.Created, ShareIndexStatus.Completed)))

        assertThat(shareImportActions(state)).containsExactly(ShareImportAction.DONE)
    }

    @Test
    fun 尚有待导入项时显示导入且失败项可重试() {
        val state = ShareImportUiState(
            visible = true,
            items = listOf(item(ShareImportStatus.Created, ShareIndexStatus.Partial), item(ShareImportStatus.Pending)),
        )

        assertThat(shareImportActions(state)).containsExactly(ShareImportAction.RETRY, ShareImportAction.IMPORT).inOrder()
    }
}
