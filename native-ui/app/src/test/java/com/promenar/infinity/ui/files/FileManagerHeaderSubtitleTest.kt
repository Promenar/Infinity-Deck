package com.promenar.infinity.ui.files

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileLocation
import com.promenar.infinity.domain.files.FileLocationCapabilities
import com.promenar.infinity.domain.files.FileLocationKind
import com.promenar.infinity.domain.files.FileLocationStorageKind
import org.junit.Test

class FileManagerHeaderSubtitleTest {

    private fun sampleLocation(
        kind: FileLocationKind,
        storageKind: FileLocationStorageKind,
        writable: Boolean = true,
    ) = FileLocation(
        id = "loc",
        kind = kind,
        title = "Workspace",
        capabilities = FileLocationCapabilities(writable = writable, deletable = writable, archive = writable),
        storageKind = storageKind,
    )

    @Test
    fun `非Git普通文件夹按存储来源映射清晰副标题`() {
        assertThat(plainFolderSubtitleRes(sampleLocation(FileLocationKind.SESSION_WORKSPACE, FileLocationStorageKind.BUILTIN)))
            .isEqualTo(R.string.fm_source_local_workspace)
        assertThat(plainFolderSubtitleRes(sampleLocation(FileLocationKind.KNOWLEDGE_BASE, FileLocationStorageKind.BUILTIN)))
            .isEqualTo(R.string.fm_source_storage)
        assertThat(plainFolderSubtitleRes(sampleLocation(FileLocationKind.PROJECT, FileLocationStorageKind.APP_PROJECT)))
            .isEqualTo(R.string.fm_source_project_workspace)
        assertThat(plainFolderSubtitleRes(sampleLocation(FileLocationKind.PROJECT, FileLocationStorageKind.RETAINED_SESSION)))
            .isEqualTo(R.string.fm_source_retained_workspace)
        assertThat(plainFolderSubtitleRes(sampleLocation(FileLocationKind.EXTERNAL_FOLDER, FileLocationStorageKind.EXTERNAL_MOUNT, writable = true)))
            .isEqualTo(R.string.fm_source_external_folder)
        assertThat(plainFolderSubtitleRes(sampleLocation(FileLocationKind.EXTERNAL_FOLDER, FileLocationStorageKind.EXTERNAL_MOUNT, writable = false)))
            .isEqualTo(R.string.fm_source_external_folder_readonly)
        assertThat(plainFolderSubtitleRes(sampleLocation(FileLocationKind.PROJECT, FileLocationStorageKind.SHARED_STORAGE)))
            .isEqualTo(R.string.fm_source_shared_storage)
        assertThat(plainFolderSubtitleRes(sampleLocation(FileLocationKind.LINUX_HOME, FileLocationStorageKind.LINUX_HOME)))
            .isEqualTo(R.string.fm_source_linux_home)
        assertThat(plainFolderSubtitleRes(sampleLocation(FileLocationKind.BRANCH_WORKTREE, FileLocationStorageKind.BRANCH_WORKTREE)))
            .isEqualTo(R.string.fm_source_worktree)
    }
}
