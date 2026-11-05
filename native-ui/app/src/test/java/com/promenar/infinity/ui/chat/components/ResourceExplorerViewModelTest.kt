package com.promenar.infinity.ui.chat.components

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.ui.common.status.NoticeSeverity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ResourceExplorerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `root creation failure is recoverable on retry`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        coEvery { repo.ensureSessionRoot("broken") } throws IllegalStateException("disk unavailable") andThen root("root-ok")
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
            io = dispatcher,
        )

        viewModel.loadSession("broken")
        advanceUntilIdle()

        assertThat(viewModel.workspaceRootUuid.value).isNull()
        assertThat(viewModel.loadError.value).isNotNull()
        assertThat(viewModel.loadError.value?.code).isEqualTo(ResourceExplorerNotice.CODE_WORKSPACE_LOAD_FAILED)
        assertThat(viewModel.loadError.value?.severity).isEqualTo(NoticeSeverity.Error)
        assertThat(viewModel.loadError.value?.technical).isEqualTo("disk unavailable")

        viewModel.retryLoadSession()
        advanceUntilIdle()

        assertThat(viewModel.loadError.value).isNull()
        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("root-ok")
    }

    private fun root(uuid: String) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = uuid,
        parentUuid = null,
        name = "root",
        hash = "",
        isDirectory = true,
        physicalRootPath = "/tmp/$uuid",
        materializedPath = "/",
        createdAt = 1,
        updatedAt = 1,
    )
}
