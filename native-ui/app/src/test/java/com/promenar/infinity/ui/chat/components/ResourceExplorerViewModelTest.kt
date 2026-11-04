package com.promenar.infinity.ui.chat.components

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.share.core.ShareImportBatchResult
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.ui.chat.shouldOfferImportRetry
import com.promenar.infinity.ui.common.status.NoticeSeverity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    fun `same session deactivate and reload preserves running import until completion`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val importer = mockk<SharedFileImporter>()
        val uri = Uri.parse("content://test/resumed-import.txt")
        val importGate = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        coEvery { importer.inspect(any()) } returns listOf(item(uri, ShareImportStatus.Pending))
        coEvery { importer.import(any(), "root", null, "root") } coAnswers {
            try {
                importGate.await()
                ShareImportBatchResult(listOf(item(uri, ShareImportStatus.Created)))
            } catch (cancelled: CancellationException) {
                cancellationObserved.complete(Unit)
                throw cancelled
            }
        }
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
            importer,
            io = dispatcher,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()
        viewModel.importDocuments(listOf(uri))
        dispatcher.scheduler.runCurrent()
        assertThat(viewModel.isImporting.value).isTrue()
        assertThat(viewModel.importItems.value.map { it.status })
            .containsExactly(ShareImportStatus.Importing)

        viewModel.deactivateSession()
        viewModel.loadSession("session")
        dispatcher.scheduler.runCurrent()
        val cancellationAfterReload = cancellationObserved.isCompleted
        val importingAfterReload = viewModel.isImporting.value
        val statusesAfterReload = viewModel.importItems.value.map { it.status }

        importGate.complete(Unit)
        advanceUntilIdle()

        assertThat(cancellationAfterReload).isFalse()
        assertThat(importingAfterReload).isTrue()
        assertThat(statusesAfterReload).containsExactly(ShareImportStatus.Importing)
        assertThat(viewModel.isImporting.value).isFalse()
        assertThat(viewModel.importItems.value.map { it.status })
            .containsExactly(ShareImportStatus.Created)
        coVerify(exactly = 1) { importer.import(any(), "root", null, "root") }
    }

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

    @Test
    fun `SAF import exposes inspected items then per-item results and retry`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val importer = mockk<SharedFileImporter>()
        val first = Uri.parse("content://test/first.txt")
        val second = Uri.parse("content://test/second.pdf")
        val importGate = CompletableDeferred<Unit>()
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        coEvery { importer.inspect(any()) } answers {
            val uri = firstArg<com.promenar.infinity.ShareRequest>().uris.single()
            listOf(item(uri, ShareImportStatus.Pending))
        }
        coEvery { importer.import(any(), "root", null, "root") } coAnswers {
            importGate.await()
            val uri = firstArg<com.promenar.infinity.ShareRequest>().uris.single()
            ShareImportBatchResult(
                listOf(
                    if (uri == first) item(uri, ShareImportStatus.Created)
                    else item(uri, ShareImportStatus.Rejected, ShareRejectReason.ReadFailed)
                )
            )
        }
        coEvery { importer.import(any(), "root", setOf(second), "root") } returns
            ShareImportBatchResult(listOf(item(second, ShareImportStatus.Created)))
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
            importer,
            io = dispatcher,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.importDocuments(listOf(first, second))
        dispatcher.scheduler.runCurrent()

        assertThat(viewModel.isImporting.value).isTrue()
        assertThat(viewModel.importItems.value.map { it.uri }).containsExactly(first, second).inOrder()
        assertThat(viewModel.importItems.value.map { it.status }).containsExactly(
            ShareImportStatus.Importing,
            ShareImportStatus.Pending,
        ).inOrder()

        importGate.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.importItems.value.map { it.status }).containsExactly(
            ShareImportStatus.Created,
            ShareImportStatus.Rejected,
        ).inOrder()
        viewModel.retryImport(second)
        advanceUntilIdle()
        assertThat(viewModel.importItems.value.map { it.status }).containsExactly(
            ShareImportStatus.Created,
            ShareImportStatus.Created,
        ).inOrder()
    }

    @Test
    fun `unexpected importer failure becomes visible retryable rejection`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val importer = mockk<SharedFileImporter>()
        val uri = Uri.parse("content://test/failure.txt")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        coEvery { importer.inspect(any()) } returns listOf(item(uri, ShareImportStatus.Pending))
        coEvery { importer.import(any(), "root", null, "root") } throws IllegalStateException("unexpected")
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
            importer,
            io = dispatcher,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.importDocuments(listOf(uri))
        advanceUntilIdle()

        assertThat(viewModel.isImporting.value).isFalse()
        assertThat(viewModel.importItems.value.single().status).isEqualTo(ShareImportStatus.Rejected)
        assertThat(viewModel.importItems.value.single().reason).isEqualTo(ShareRejectReason.WriteFailed)

        coEvery { importer.import(any(), "root", setOf(uri), "root") } throws IllegalArgumentException("retry failed")
        viewModel.retryImport(uri)
        advanceUntilIdle()

        assertThat(viewModel.isImporting.value).isFalse()
        assertThat(viewModel.importItems.value.single().status).isEqualTo(ShareImportStatus.Rejected)
        assertThat(viewModel.importItems.value.single().reason).isEqualTo(ShareRejectReason.WriteFailed)
    }

    @Test
    fun `旧会话的迟到导入结果不能覆盖新会话`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val importer = mockk<SharedFileImporter>()
        val oldUri = Uri.parse("content://test/old.txt")
        val newUri = Uri.parse("content://test/new.txt")
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        coEvery { repo.ensureSessionRoot("old") } returns root("root-old")
        coEvery { repo.ensureSessionRoot("new") } returns root("root-new")
        coEvery { importer.inspect(any()) } answers {
            listOf(item(firstArg<com.promenar.infinity.ShareRequest>().uris.single(), ShareImportStatus.Pending))
        }
        coEvery { importer.import(any(), "root-old", null, "root-old") } coAnswers {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { oldGate.await() }
            ShareImportBatchResult(listOf(item(oldUri, ShareImportStatus.Created)))
        }
        coEvery { importer.import(any(), "root-new", null, "root-new") } coAnswers {
            newGate.await()
            ShareImportBatchResult(listOf(item(newUri, ShareImportStatus.Created)))
        }
        val vm = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(), repo, importer, io = dispatcher,
        )
        vm.loadSession("old")
        advanceUntilIdle()
        vm.importDocuments(listOf(oldUri))
        dispatcher.scheduler.runCurrent()
        vm.loadSession("new")
        advanceUntilIdle()
        vm.importDocuments(listOf(newUri))
        dispatcher.scheduler.runCurrent()
        oldGate.complete(Unit)
        dispatcher.scheduler.runCurrent()
        assertThat(vm.importItems.value.single().uri).isEqualTo(newUri)
        assertThat(vm.importItems.value.single().status).isEqualTo(ShareImportStatus.Importing)
        newGate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.importItems.value.single().status).isEqualTo(ShareImportStatus.Created)
    }

    @Test
    fun `retry action is offered only for retryable rejection reasons`() {
        val uri = Uri.parse("content://test/file.txt")

        assertThat(
            shouldOfferImportRetry(item(uri, ShareImportStatus.Rejected, ShareRejectReason.WriteFailed))
        ).isTrue()
        assertThat(
            shouldOfferImportRetry(item(uri, ShareImportStatus.Rejected, ShareRejectReason.UnsupportedMime))
        ).isFalse()
        assertThat(shouldOfferImportRetry(item(uri, ShareImportStatus.Created))).isFalse()
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

    private fun item(
        uri: Uri,
        status: ShareImportStatus,
        reason: ShareRejectReason? = null,
    ) = ShareImportItem(
        uri = uri,
        displayName = uri.lastPathSegment.orEmpty(),
        mimeType = if (uri.toString().endsWith(".pdf")) "application/pdf" else "text/plain",
        sizeBytes = 10,
        status = status,
        reason = reason,
    )

    private fun file(uuid: String, rootUuid: String = "root") = FileEntry(
        uuid = uuid,
        workspaceRootUuid = rootUuid,
        parentUuid = null,
        name = "$uuid.txt",
        hash = uuid,
        isDirectory = false,
        physicalRootPath = "/tmp/$rootUuid",
        materializedPath = "/$uuid.txt",
        createdAt = 1,
        updatedAt = 1,
    )
}
