package com.promenar.infinity.ui.library

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.storage.RemoteOverwritePolicy
import com.promenar.infinity.domain.storage.RemoteTransferDirection
import com.promenar.infinity.domain.storage.RemoteTransferState
import com.promenar.infinity.domain.storage.RemoteTransferTask
import com.promenar.infinity.ui.chat.RemoteMountApprovalAction
import com.promenar.infinity.ui.chat.remoteMountApprovalPreview
import com.promenar.infinity.ui.files.FileHeaderBadge
import com.promenar.infinity.ui.files.shouldPlaceFolderBadgeInTitleMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class RemoteTransferManagerUiTest {
    private fun task(
        id: String,
        state: RemoteTransferState,
        direction: RemoteTransferDirection = RemoteTransferDirection.UPLOAD,
    ) = RemoteTransferTask(
        id = id,
        direction = direction,
        state = state,
        mountId = "m1",
        hostAlias = "fnos",
        fileName = "$id.mp4",
        sourceLabel = "Personal-Archive/$id.mp4",
        targetLabel = "fnos:/vol1/1000/Codex-Assets/$id.mp4",
        mountVirtualPath = "/mnt/assets/$id.mp4",
        remoteFullPath = "/vol1/1000/Codex-Assets/$id.mp4",
        localTargetPath = null,
        bytesTotal = 1000L,
        bytesTransferred = 480L,
        bytesPerSecond = 200L,
        etaSeconds = 3L,
        overwritePolicy = RemoteOverwritePolicy.REFUSE_DIFFERING,
        canResume = true,
        receipt = null,
        errorCode = null,
        errorMessage = null,
        sessionId = null,
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Test
    fun `传输管理筛选与各状态计数准确分组`() {
        val tasks = listOf(
            task("t1", RemoteTransferState.TRANSFERRING),
            task("t2", RemoteTransferState.VERIFYING),
            task("t3", RemoteTransferState.PAUSED, RemoteTransferDirection.DOWNLOAD),
            task("t4", RemoteTransferState.SUCCEEDED),
            task("t5", RemoteTransferState.FAILED),
            task("t6", RemoteTransferState.CANCELLED),
        )
        val counts = computeRemoteTransferFilterCounts(tasks)
        assertThat(counts.all).isEqualTo(6)
        assertThat(counts.active).isEqualTo(2)
        assertThat(counts.paused).isEqualTo(1)
        assertThat(counts.completed).isEqualTo(1)
        assertThat(counts.failed).isEqualTo(2)

        assertThat(filterRemoteTransfers(tasks, RemoteTransferFilter.ACTIVE).map { it.id })
            .containsExactly("t1", "t2").inOrder()
        assertThat(filterRemoteTransfers(tasks, RemoteTransferFilter.PAUSED).map { it.id })
            .containsExactly("t3")
        assertThat(filterRemoteTransfers(tasks, RemoteTransferFilter.COMPLETED).map { it.id })
            .containsExactly("t4")
        assertThat(filterRemoteTransfers(tasks, RemoteTransferFilter.FAILED).map { it.id })
            .containsExactly("t5", "t6").inOrder()
    }

    @Test
    fun `进度比例、速率与短摘要格式化正确处理边界值`() {
        assertThat(transferProgressFraction(0L, 0L)).isNull()
        assertThat(transferProgressFraction(50L, 100L)).isEqualTo(0.5f)
        assertThat(transferProgressFraction(150L, 100L)).isEqualTo(1f)
        assertThat(formatTransferSpeed(0L)).isNull()
        assertThat(formatTransferSpeed(2_048L)).isNotNull()
        assertThat(shortSha256("9f8a4c2e00112233445566778899aabbccddeeff001122334455667788993b1d"))
            .isEqualTo("9f8a4c2e…3b1d")
    }

    @Test
    fun `remote_mount 工具内联审批预览准确解析目标主机、路径与大小`() {
        val args = Json.parseToJsonElement(
            """{"workspace_path":"raw/demo.mp4","mount_path":"/mnt/assets/videos/demo.mp4","overwrite":false}""",
        ).jsonObject
        val target = """["remote_mount","fnos","/mnt/assets/videos/demo.mp4",{"host":"fnos","bytes_total":149736652,"mountPath":"/mnt/assets/videos/demo.mp4","localPath":"raw/demo.mp4"}]"""
        val preview = remoteMountApprovalPreview("remote_mount_archive", args, target)
        assertThat(preview).isNotNull()
        assertThat(preview!!.action).isEqualTo(RemoteMountApprovalAction.ARCHIVE)
        assertThat(preview.hostAlias).isEqualTo("fnos")
        assertThat(preview.workspacePath).isEqualTo("raw/demo.mp4")
        assertThat(preview.mountPath).isEqualTo("/mnt/assets/videos/demo.mp4")
        assertThat(preview.bytesTotal).isEqualTo(149736652L)
        assertThat(preview.overwrite).isFalse()

        val statusArgs = Json.parseToJsonElement("""{"transfer_id":"t1"}""").jsonObject
        assertThat(remoteMountApprovalPreview("remote_mount_transfer_status", statusArgs, null)).isNull()
    }

    @Test
    fun `远端校验工具名格式化去除内部前缀`() {
        assertThat(formatRemoteChecksumMethod("ssh_exec_sha256sum")).isEqualTo("sha256sum")
        assertThat(formatRemoteChecksumMethod("ssh_exec_python3")).isEqualTo("python3")
        assertThat(formatRemoteChecksumMethod("ssh_exec_shasum")).isEqualTo("shasum")
        assertThat(formatRemoteChecksumMethod("sha256sum")).isEqualTo("sha256sum")
    }

    @Test
    fun `外屏窄栏 SFTP 非可点信息徽章或带宿主页头按钮时统一收进标题下方信息行避免重叠`() {
        val sftpBadge = FileHeaderBadge(text = "SFTP · 可归档", description = "fnos:/vol2/assets · SFTP · 可归档")
        val kgBadge = FileHeaderBadge(text = "2/4", description = "已抽取 2/4", onClick = {})

        assertThat(
            shouldPlaceFolderBadgeInTitleMeta(sftpBadge, isInnerScreen = false, inInspector = false, hasHeaderActions = false),
        ).isTrue()
        assertThat(
            shouldPlaceFolderBadgeInTitleMeta(sftpBadge, isInnerScreen = false, inInspector = false, hasHeaderActions = true),
        ).isTrue()
        assertThat(
            shouldPlaceFolderBadgeInTitleMeta(kgBadge, isInnerScreen = false, inInspector = false, hasHeaderActions = true),
        ).isTrue()
        assertThat(
            shouldPlaceFolderBadgeInTitleMeta(kgBadge, isInnerScreen = false, inInspector = false, hasHeaderActions = false),
        ).isFalse()
        assertThat(
            shouldPlaceFolderBadgeInTitleMeta(kgBadge, isInnerScreen = true, inInspector = false, hasHeaderActions = false),
        ).isTrue()
    }
}
