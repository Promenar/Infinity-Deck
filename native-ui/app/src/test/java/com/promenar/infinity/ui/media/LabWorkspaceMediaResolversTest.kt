package com.promenar.infinity.ui.media

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.media.MediaResolution
import com.promenar.infinity.domain.media.MediaUnavailableReason
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import java.io.InputStream
import org.junit.Test

class LabWorkspaceMediaResolversTest {
    private class Stub : WorkspaceMediaResolver {
        override suspend fun resolve(sessionId: String, path: String): MediaResolution =
            MediaResolution.Unavailable(path, MediaUnavailableReason.NOT_FOUND)

        override suspend fun open(resolution: MediaResolution.Available): InputStream = error("不读取")
    }

    @Test
    fun `只对登记的会话生效，注销只移除同一个解析器`() {
        val first = Stub()
        val second = Stub()
        LabWorkspaceMediaResolvers.register("lab-test", first)
        assertThat(LabWorkspaceMediaResolvers.forSession("lab-test")).isSameInstanceAs(first)
        assertThat(LabWorkspaceMediaResolvers.forSession("real-session")).isNull()

        // 新场景先登记、旧场景后注销：旧场景的注销不能移除新场景的解析器。
        LabWorkspaceMediaResolvers.register("lab-test", second)
        LabWorkspaceMediaResolvers.unregister("lab-test", first)
        assertThat(LabWorkspaceMediaResolvers.forSession("lab-test")).isSameInstanceAs(second)

        LabWorkspaceMediaResolvers.unregister("lab-test", second)
        assertThat(LabWorkspaceMediaResolvers.forSession("lab-test")).isNull()
    }
}
