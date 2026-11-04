package com.promenar.infinity.data.media.annotation

import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.model.SessionRunMode
import kotlinx.serialization.Serializable

/** 图层路径所属的会话与命名空间，独立于可能在重试时重新保存的预览附件。 */
@Serializable
data class ImageAnnotationContext(
    val sessionId: String,
    val workspaceId: String? = null,
    val linuxNamespace: Boolean,
    val worktreeDirectory: String? = null,
) {
    fun matches(session: Session?): Boolean = session != null && this == from(session)

    companion object {
        fun from(session: Session): ImageAnnotationContext = ImageAnnotationContext(
            session.id, session.workspaceId, session.runMode == SessionRunMode.LINUX, session.worktreeDirectory)
    }
}
