package com.promenar.infinity.share.core

import android.net.Uri
import com.promenar.infinity.data.local.db.entity.FileEntry

enum class ShareImportStatus { Pending, Importing, Created, Rejected }
enum class ShareIndexStatus { Pending, Partial, Failed, Completed }

data class ShareIndexReceipt(
    val taskId: String,
    val fileUuid: String,
)

enum class ShareRejectReason {
    TargetRequired,
    UnsupportedMime,
    MimeMismatch,
    InvalidName,
    PermissionDenied,
    EmptyFile,
    ItemTooLarge,
    BatchTooLarge,
    ReadFailed,
    WriteFailed,
    IndexScheduleFailed,
    /** 选了 Linux 目录，但 Linux 未安装、未就绪或渠道不支持。 */
    LinuxUnavailable,
    /** Linux 目标目录不存在、不是目录、越出 rootfs 或经符号链接逃逸。 */
    LinuxPathInvalid,
    ;

    val retryable: Boolean
        get() = this == WriteFailed || this == IndexScheduleFailed
}

data class SharedContentMetadata(
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: Long?,
)

data class ShareImportItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val status: ShareImportStatus = ShareImportStatus.Pending,
    val reason: ShareRejectReason? = null,
    val created: FileEntry? = null,
    val indexStatus: ShareIndexStatus? = null,
    val fileUuid: String? = created?.uuid,
    val indexTaskId: String? = null,
    /** 应用能解析并建索引的格式；false 表示原样保存、不建索引。 */
    val parseable: Boolean = true,
    /** 写入 Linux 目录时的容器内绝对路径。 */
    val linuxPath: String? = null,
)

data class ShareImportBatchResult(val items: List<ShareImportItem>) {
    val created: List<ShareImportItem> get() = items.filter { it.status == ShareImportStatus.Created }
    val rejected: List<ShareImportItem> get() = items.filter { it.status == ShareImportStatus.Rejected }
}

data class ShareImportTarget(
    val workspaceRootUuid: String,
    val label: String,
    val kind: ShareTargetKind,
)

enum class ShareTargetKind { CurrentSession, OtherSession, Linux, KnowledgeBase }

/** 用户在导入面板上选定的落点；“其他会话”只能来自用户在会话列表里的手动选择。 */
sealed interface ShareImportDestination {
    val kind: ShareTargetKind

    /** 会话的本会话文件夹（会话工作区根；Linux 模式下即 /home/session）。 */
    data class SessionFolder(
        val sessionId: String,
        val workspaceRootUuid: String,
        val current: Boolean,
    ) : ShareImportDestination {
        override val kind get() = if (current) ShareTargetKind.CurrentSession else ShareTargetKind.OtherSession
    }

    /** Linux rootfs 里的目录，[path] 为容器内绝对路径（例如 /root/inbox）。 */
    data class LinuxDirectory(val path: String) : ShareImportDestination {
        override val kind get() = ShareTargetKind.Linux
    }

    /** 存储空间里的文件夹；[folderUuid] 等于 [workspaceRootUuid] 表示根目录，[displayPath] 根目录为 "/"。 */
    data class KnowledgeFolder(
        val workspaceRootUuid: String,
        val folderUuid: String,
        val displayPath: String,
    ) : ShareImportDestination {
        override val kind get() = ShareTargetKind.KnowledgeBase
    }
}

data class ShareSessionChoice(
    val sessionId: String,
    val title: String,
    val updatedAt: Long,
)

data class ShareKnowledgeFolder(
    val uuid: String,
    val name: String,
    /** 存储空间内路径，根目录为 "/"。 */
    val path: String,
)

/** Linux 目标的可用性：就绪、可去安装（未安装/安装中/安装中断）、渠道或 ABI 不支持。 */
enum class ShareLinuxAvailability { Ready, NeedsInstall, Unsupported }

/** 导入面板打开时的选项快照。 */
data class ShareImportOptions(
    /** 没有打开的聊天会话时为 null，此时“当前会话”一项不出现。 */
    val currentSession: ShareSessionChoice?,
    val knowledgeRootUuid: String,
    val linux: ShareLinuxAvailability,
    /** Linux 目录选择器的起点与默认落点。 */
    val linuxDefaultDirectory: String = "/root",
)
