package com.promenar.infinity.ui.chat.components

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.git.GitChangeKind
import com.promenar.infinity.data.git.GitChangeList
import com.promenar.infinity.data.git.GitChangesService
import com.promenar.infinity.data.git.GitFileChange
import com.promenar.infinity.data.git.GitInspectError
import com.promenar.infinity.data.git.GitInspectResult
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.share.core.ShareImportBatchResult
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.share.core.SharedFileImporter
import com.promenar.infinity.ui.chat.shouldOfferImportRetry
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.git.GitEntrySummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
    fun `switching session cancels old root collectors and exposes only current root`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val aFiles = MutableSharedFlow<List<FileEntry>>()
        val bFiles = MutableSharedFlow<List<FileEntry>>()
        val aRecycle = MutableSharedFlow<List<FileEntry>>()
        val bRecycle = MutableSharedFlow<List<FileEntry>>()
        coEvery { repo.ensureSessionRoot("a") } returns root("root-a")
        coEvery { repo.ensureSessionRoot("b") } returns root("root-b")
        every { repo.observeChildren("root-a", "root-a") } returns aFiles
        every { repo.observeChildren("root-b", "root-b") } returns bFiles
        every { repo.observeRecycleBin("root-a") } returns aRecycle
        every { repo.observeRecycleBin("root-b") } returns bRecycle
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )

        viewModel.loadSession("a")
        advanceUntilIdle()
        assertThat(aFiles.subscriptionCount.value).isEqualTo(1)
        assertThat(aRecycle.subscriptionCount.value).isEqualTo(1)
        viewModel.loadSession("b")
        advanceUntilIdle()

        assertThat(aFiles.subscriptionCount.value).isEqualTo(0)
        assertThat(aRecycle.subscriptionCount.value).isEqualTo(0)
        assertThat(bFiles.subscriptionCount.value).isEqualTo(1)
        assertThat(bRecycle.subscriptionCount.value).isEqualTo(1)
        assertThat(viewModel.workspaceRootUuid.value).isEqualTo("root-b")
        verify(exactly = 0) { repo.observeRoots(any()) }
    }

    @Test
    fun `deactivating explorer stops session observers and reopening creates one fresh pair`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val files = MutableSharedFlow<List<FileEntry>>()
        val recycle = MutableSharedFlow<List<FileEntry>>()
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns files
        every { repo.observeRecycleBin("root") } returns recycle
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )

        assertThat(files.subscriptionCount.value).isEqualTo(0)
        assertThat(recycle.subscriptionCount.value).isEqualTo(0)
        viewModel.loadSession("session")
        advanceUntilIdle()
        assertThat(files.subscriptionCount.value).isEqualTo(1)
        assertThat(recycle.subscriptionCount.value).isEqualTo(1)

        val deactivate = viewModel.javaClass.methods.singleOrNull { it.name == "deactivateSession" }
        assertThat(deactivate).isNotNull()
        deactivate?.invoke(viewModel)
        advanceUntilIdle()
        assertThat(files.subscriptionCount.value).isEqualTo(0)
        assertThat(recycle.subscriptionCount.value).isEqualTo(0)

        viewModel.loadSession("session")
        advanceUntilIdle()
        assertThat(files.subscriptionCount.value).isEqualTo(1)
        assertThat(recycle.subscriptionCount.value).isEqualTo(1)
    }

    @Test
    fun `same session deactivate and reload preserves running import until completion`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val importer = mockk<SharedFileImporter>()
        val uri = Uri.parse("content://test/resumed-import.txt")
        val importGate = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(emptyList())
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
    fun `same session deactivate and reload preserves running recycle operation until completion`() =
        runTest(dispatcher) {
            val repo = mockk<IWorkspaceRepository>()
            val recycledFile = recycled("resumed-recycle")
            val restoreGate = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            coEvery { repo.ensureSessionRoot("session") } returns root("root")
            every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
            every { repo.observeRecycleBin("root") } returns flowOf(listOf(recycledFile))
            coEvery { repo.restoreFromRecycleBin("root", recycledFile.uuid) } coAnswers {
                try {
                    restoreGate.await()
                } catch (cancelled: CancellationException) {
                    cancellationObserved.complete(Unit)
                    throw cancelled
                }
            }
            val viewModel = ResourceExplorerViewModel(
                ApplicationProvider.getApplicationContext<Application>(),
                repo,
            )
            viewModel.loadSession("session")
            advanceUntilIdle()
            viewModel.restoreRecycledFiles(listOf(recycledFile))
            dispatcher.scheduler.runCurrent()
            val running = RecycleOperationState.Running(
                RecycleOperation.Restore,
                listOf(recycledFile.uuid),
            )
            assertThat(viewModel.recycleOperationState.value).isEqualTo(running)

            viewModel.deactivateSession()
            viewModel.loadSession("session")
            dispatcher.scheduler.runCurrent()
            val cancellationAfterReload = cancellationObserved.isCompleted
            val stateAfterReload = viewModel.recycleOperationState.value

            restoreGate.complete(Unit)
            advanceUntilIdle()

            assertThat(cancellationAfterReload).isFalse()
            assertThat(stateAfterReload).isEqualTo(running)
            assertThat(viewModel.recycleOperationState.value).isEqualTo(
                RecycleOperationState.Success(
                    RecycleOperation.Restore,
                    listOf(recycledFile.uuid),
                )
            )
            coVerify(exactly = 1) { repo.restoreFromRecycleBin("root", recycledFile.uuid) }
        }

    @Test
    fun `same session observer failure preserves running import and valid root`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val importer = mockk<SharedFileImporter>()
        val uri = Uri.parse("content://test/observer-failure-import.txt")
        val importGate = CompletableDeferred<Unit>()
        val observerFailureGate = CompletableDeferred<Unit>()
        val initialFiles = MutableSharedFlow<List<FileEntry>>()
        val initialRecycle = MutableSharedFlow<List<FileEntry>>()
        val resumedRecycle = MutableSharedFlow<List<FileEntry>>()
        val failingFiles = flow<List<FileEntry>> {
            observerFailureGate.await()
            throw IllegalStateException("resumed observer failed")
        }
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returnsMany listOf(initialFiles, failingFiles)
        every { repo.observeRecycleBin("root") } returnsMany listOf(initialRecycle, resumedRecycle)
        coEvery { importer.inspect(any()) } returns listOf(item(uri, ShareImportStatus.Pending))
        coEvery { importer.import(any(), "root", null, "root") } coAnswers {
            importGate.await()
            ShareImportBatchResult(listOf(item(uri, ShareImportStatus.Created)))
        }
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
            importer,
        )
        viewModel.loadSession("session")
        dispatcher.scheduler.runCurrent()
        viewModel.importDocuments(listOf(uri))
        dispatcher.scheduler.runCurrent()
        val importingBeforeReload = viewModel.isImporting.value

        viewModel.deactivateSession()
        viewModel.loadSession("session")
        dispatcher.scheduler.runCurrent()
        observerFailureGate.complete(Unit)
        dispatcher.scheduler.runCurrent()
        val errorAfterFailure = viewModel.loadError.value
        val rootAfterFailure = viewModel.workspaceRootUuid.value
        val importingAfterFailure = viewModel.isImporting.value
        val statusesAfterFailure = viewModel.importItems.value.map { it.status }

        importGate.complete(Unit)
        advanceUntilIdle()
        val finalRoot = viewModel.workspaceRootUuid.value
        val finalStatuses = viewModel.importItems.value.map { it.status }
        viewModel.deactivateSession()
        dispatcher.scheduler.runCurrent()

        assertThat(errorAfterFailure?.code).isEqualTo(ResourceExplorerNotice.CODE_WORKSPACE_LOAD_FAILED)
        assertThat(importingBeforeReload).isTrue()
        assertThat(rootAfterFailure).isEqualTo("root")
        assertThat(importingAfterFailure).isTrue()
        assertThat(statusesAfterFailure).containsExactly(ShareImportStatus.Importing)
        assertThat(finalRoot).isEqualTo("root")
        assertThat(finalStatuses).containsExactly(ShareImportStatus.Created)
        coVerify(exactly = 1) { importer.import(any(), "root", null, "root") }
    }

    @Test
    fun `late failure from cancelled observer cannot clear reopened same session`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val oldObserverStarted = CompletableDeferred<Unit>()
        val oldCancellationObserved = CompletableDeferred<Unit>()
        val lateFailureGate = CompletableDeferred<Unit>()
        val oldFiles = flow<List<FileEntry>> {
            oldObserverStarted.complete(Unit)
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    oldCancellationObserved.complete(Unit)
                    lateFailureGate.await()
                }
                throw IllegalStateException("late failure from cancelled observer")
            }
        }
        val oldRecycle = MutableSharedFlow<List<FileEntry>>()
        val newRootFile = recycled("new-root-file")
        val newRecycledFile = recycled("new-recycled-file")
        val newFiles = MutableSharedFlow<List<FileEntry>>(replay = 1).apply {
            tryEmit(listOf(newRootFile))
        }
        val newRecycle = MutableSharedFlow<List<FileEntry>>(replay = 1).apply {
            tryEmit(listOf(newRecycledFile))
        }
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returnsMany listOf(oldFiles, newFiles)
        every { repo.observeRecycleBin("root") } returnsMany listOf(oldRecycle, newRecycle)
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        dispatcher.scheduler.runCurrent()
        val oldStartedBeforeDeactivate = oldObserverStarted.isCompleted

        viewModel.deactivateSession()
        dispatcher.scheduler.runCurrent()
        val oldCancelledBeforeReload = oldCancellationObserved.isCompleted
        viewModel.loadSession("session")
        dispatcher.scheduler.runCurrent()
        val newFileSubscriptionsBeforeLateFailure = newFiles.subscriptionCount.value
        val newRecycleSubscriptionsBeforeLateFailure = newRecycle.subscriptionCount.value
        val filesBeforeLateFailure = viewModel.rootFiles.value
        val recycleBeforeLateFailure = viewModel.recycledFiles.value

        lateFailureGate.complete(Unit)
        dispatcher.scheduler.runCurrent()
        val errorAfterLateFailure = viewModel.loadError.value
        val rootAfterLateFailure = viewModel.workspaceRootUuid.value
        val filesAfterLateFailure = viewModel.rootFiles.value
        val recycleAfterLateFailure = viewModel.recycledFiles.value
        val newFileSubscriptions = newFiles.subscriptionCount.value
        val newRecycleSubscriptions = newRecycle.subscriptionCount.value
        viewModel.deactivateSession()
        dispatcher.scheduler.runCurrent()

        assertThat(oldStartedBeforeDeactivate).isTrue()
        assertThat(oldCancelledBeforeReload).isTrue()
        assertThat(newFileSubscriptionsBeforeLateFailure).isEqualTo(1)
        assertThat(newRecycleSubscriptionsBeforeLateFailure).isEqualTo(1)
        assertThat(filesBeforeLateFailure).containsExactly(newRootFile)
        assertThat(recycleBeforeLateFailure).containsExactly(newRecycledFile)
        assertThat(errorAfterLateFailure).isNull()
        assertThat(rootAfterLateFailure).isEqualTo("root")
        assertThat(filesAfterLateFailure).containsExactly(newRootFile)
        assertThat(recycleAfterLateFailure).containsExactly(newRecycledFile)
        assertThat(newFileSubscriptions).isEqualTo(1)
        assertThat(newRecycleSubscriptions).isEqualTo(1)
        assertThat(newFiles.subscriptionCount.value).isEqualTo(0)
        assertThat(newRecycle.subscriptionCount.value).isEqualTo(0)
    }

    @Test
    fun `root creation failure is recoverable and retry starts scoped collectors`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val files = MutableSharedFlow<List<FileEntry>>()
        val recycle = MutableSharedFlow<List<FileEntry>>()
        coEvery { repo.ensureSessionRoot("broken") } throws IllegalStateException("disk unavailable") andThen root("root-ok")
        every { repo.observeChildren("root-ok", "root-ok") } returns files
        every { repo.observeRecycleBin("root-ok") } returns recycle
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
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
        assertThat(files.subscriptionCount.value).isEqualTo(1)
        assertThat(recycle.subscriptionCount.value).isEqualTo(1)
    }

    @Test
    fun `SAF import exposes inspected items then per-item results and retry`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val importer = mockk<SharedFileImporter>()
        val files = MutableSharedFlow<List<FileEntry>>()
        val recycle = MutableSharedFlow<List<FileEntry>>()
        val first = Uri.parse("content://test/first.txt")
        val second = Uri.parse("content://test/second.pdf")
        val importGate = CompletableDeferred<Unit>()
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns files
        every { repo.observeRecycleBin("root") } returns recycle
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
        val files = MutableSharedFlow<List<FileEntry>>()
        val recycle = MutableSharedFlow<List<FileEntry>>()
        val uri = Uri.parse("content://test/failure.txt")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns files
        every { repo.observeRecycleBin("root") } returns recycle
        coEvery { importer.inspect(any()) } returns listOf(item(uri, ShareImportStatus.Pending))
        coEvery { importer.import(any(), "root", null, "root") } throws IllegalStateException("unexpected")
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
            importer,
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

    @Test
    fun `recycle operation is single flight across operation types`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val restoreGate = CompletableDeferred<Unit>()
        val first = recycled("first")
        val second = recycled("second")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(listOf(first, second))
        coEvery { repo.restoreFromRecycleBin("root", first.uuid) } coAnswers { restoreGate.await() }
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.restoreRecycledFiles(listOf(first))
        dispatcher.scheduler.runCurrent()
        assertThat(viewModel.recycleOperationState.value)
            .isEqualTo(RecycleOperationState.Running(RecycleOperation.Restore, listOf(first.uuid)))

        viewModel.permanentlyDeleteRecycledFiles(listOf(second))
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 0) { repo.permanentDelete(any(), any()) }

        restoreGate.complete(Unit)
        advanceUntilIdle()
        assertThat(viewModel.recycleOperationState.value)
            .isEqualTo(RecycleOperationState.Success(RecycleOperation.Restore, listOf(first.uuid)))
    }

    @Test
    fun `batch restore continues after failures and exposes partial failure items`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val first = recycled("first")
        val second = recycled("second")
        val third = recycled("third")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(listOf(first, second, third))
        coEvery { repo.restoreFromRecycleBin("root", first.uuid) } throws IllegalStateException("first failed")
        coEvery { repo.restoreFromRecycleBin("root", second.uuid) } returns Unit
        coEvery { repo.restoreFromRecycleBin("root", third.uuid) } throws IllegalArgumentException("third failed")
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.restoreRecycledFiles(listOf(first, second, third))
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.restoreFromRecycleBin("root", second.uuid) }
        assertThat(viewModel.recycleOperationState.value).isEqualTo(
            RecycleOperationState.PartialFailure(
                operation = RecycleOperation.Restore,
                succeededItemUuids = listOf(second.uuid),
                failedItemUuids = listOf(first.uuid, third.uuid),
            )
        )
    }

    @Test
    fun `retry recycle operation invokes only the previous failed items`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val failed = recycled("failed")
        val succeeded = recycled("succeeded")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(listOf(failed, succeeded))
        coEvery { repo.permanentDelete("root", failed.uuid) } throws
            IllegalStateException("first attempt failed") andThen Unit
        coEvery { repo.permanentDelete("root", succeeded.uuid) } returns Unit
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.permanentlyDeleteRecycledFiles(listOf(failed, succeeded))
        advanceUntilIdle()
        assertThat(viewModel.recycleOperationState.value).isInstanceOf(
            RecycleOperationState.PartialFailure::class.java
        )

        viewModel.retryFailedRecycleOperation()
        advanceUntilIdle()

        coVerify(exactly = 2) { repo.permanentDelete("root", failed.uuid) }
        coVerify(exactly = 1) { repo.permanentDelete("root", succeeded.uuid) }
        assertThat(viewModel.recycleOperationState.value).isEqualTo(
            RecycleOperationState.Success(RecycleOperation.PermanentDelete, listOf(failed.uuid))
        )
    }

    @Test
    fun `successful recycle operation refreshes exposed recycle bin`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val file = recycled("file")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returnsMany listOf(
            flowOf(listOf(file)),
            flowOf(emptyList()),
        )
        coEvery { repo.restoreFromRecycleBin("root", file.uuid) } returns Unit
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()
        assertThat(viewModel.recycledFiles.value).containsExactly(file)

        viewModel.restoreRecycledFiles(listOf(file))
        advanceUntilIdle()

        assertThat(viewModel.recycledFiles.value).isEmpty()
        assertThat(viewModel.recycleBinCount.value).isEqualTo(0)
        assertThat(viewModel.recycleOperationState.value)
            .isEqualTo(RecycleOperationState.Success(RecycleOperation.Restore, listOf(file.uuid)))
        verify(exactly = 2) { repo.observeRecycleBin("root") }
    }

    @Test
    fun `empty recycle selections and empty bin keep operation idle`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(emptyList())
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.restoreRecycledFiles(emptyList())
        viewModel.permanentlyDeleteRecycledFiles(emptyList())
        viewModel.emptyRecycleBin()
        advanceUntilIdle()

        assertThat(viewModel.recycleOperationState.value).isEqualTo(RecycleOperationState.Idle)
        coVerify(exactly = 0) { repo.restoreFromRecycleBin(any(), any()) }
        coVerify(exactly = 0) { repo.permanentDelete(any(), any()) }
        coVerify(exactly = 0) { repo.emptyRecycleBin(any()) }
    }

    @Test
    fun `failed empty recycle bin remains retryable without synthetic item ids`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        val file = recycled("file")
        coEvery { repo.ensureSessionRoot("session") } returns root("root")
        every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
        every { repo.observeRecycleBin("root") } returns flowOf(listOf(file))
        coEvery { repo.emptyRecycleBin("root") } throws
            IllegalStateException("first attempt failed") andThen Unit
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
        )
        viewModel.loadSession("session")
        advanceUntilIdle()

        viewModel.emptyRecycleBin()
        advanceUntilIdle()
        assertThat(viewModel.recycleOperationState.value).isEqualTo(
            RecycleOperationState.Failure(RecycleOperation.Empty, emptyList())
        )

        viewModel.retryFailedRecycleOperation()
        advanceUntilIdle()

        coVerify(exactly = 2) { repo.emptyRecycleBin("root") }
        assertThat(viewModel.recycleOperationState.value).isEqualTo(
            RecycleOperationState.Success(RecycleOperation.Empty, emptyList())
        )
    }

    @Test
    fun `switching session cancels running recycle operation without writing old result into new session`() =
        runTest(dispatcher) {
            val repo = mockk<IWorkspaceRepository>()
            val oldFile = recycled("old-file", "old-root")
            val restoreGate = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            coEvery { repo.ensureSessionRoot("old-session") } returns root("old-root")
            coEvery { repo.ensureSessionRoot("new-session") } returns root("new-root")
            every { repo.observeChildren("old-root", "old-root") } returns flowOf(emptyList())
            every { repo.observeChildren("new-root", "new-root") } returns flowOf(emptyList())
            every { repo.observeRecycleBin("old-root") } returns flowOf(listOf(oldFile))
            every { repo.observeRecycleBin("new-root") } returns flowOf(emptyList())
            coEvery { repo.restoreFromRecycleBin("old-root", oldFile.uuid) } coAnswers {
                try {
                    restoreGate.await()
                } catch (cancelled: CancellationException) {
                    cancellationObserved.complete(Unit)
                    throw cancelled
                }
            }
            val viewModel = ResourceExplorerViewModel(
                ApplicationProvider.getApplicationContext<Application>(),
                repo,
            )
            viewModel.loadSession("old-session")
            advanceUntilIdle()

            viewModel.restoreRecycledFiles(listOf(oldFile))
            dispatcher.scheduler.runCurrent()
            assertThat(viewModel.recycleOperationState.value).isEqualTo(
                RecycleOperationState.Running(RecycleOperation.Restore, listOf(oldFile.uuid))
            )

            viewModel.loadSession("new-session")
            advanceUntilIdle()
            restoreGate.complete(Unit)
            advanceUntilIdle()

            assertThat(cancellationObserved.isCompleted).isTrue()
            assertThat(viewModel.workspaceRootUuid.value).isEqualTo("new-root")
            assertThat(viewModel.recycledFiles.value).isEmpty()
            assertThat(viewModel.recycleOperationState.value).isEqualTo(RecycleOperationState.Idle)
            coVerify(exactly = 1) { repo.restoreFromRecycleBin("old-root", oldFile.uuid) }
            verify(exactly = 1) { repo.observeRecycleBin("old-root") }
        }

    @Test
    fun `refresh failure preserves partial result without replaying successful destructive item`() =
        runTest(dispatcher) {
            val repo = mockk<IWorkspaceRepository>()
            val deleted = recycled("deleted")
            val failed = recycled("failed")
            coEvery { repo.ensureSessionRoot("session") } returns root("root")
            every { repo.observeChildren("root", "root") } returns flowOf(emptyList())
            every { repo.observeRecycleBin("root") } returnsMany listOf(
                flowOf(listOf(deleted, failed)),
                flow { throw IllegalStateException("refresh failed") },
            )
            coEvery { repo.permanentDelete("root", deleted.uuid) } returns Unit
            coEvery { repo.permanentDelete("root", failed.uuid) } throws IllegalStateException("delete failed")
            val viewModel = ResourceExplorerViewModel(
                ApplicationProvider.getApplicationContext<Application>(),
                repo,
            )
            viewModel.loadSession("session")
            advanceUntilIdle()

            viewModel.permanentlyDeleteRecycledFiles(listOf(deleted, failed))
            advanceUntilIdle()

            assertThat(viewModel.recycleOperationState.value).isEqualTo(
                RecycleOperationState.PartialFailure(
                    operation = RecycleOperation.PermanentDelete,
                    succeededItemUuids = listOf(deleted.uuid),
                    failedItemUuids = listOf(failed.uuid),
                )
            )
            coVerify(exactly = 1) { repo.permanentDelete("root", deleted.uuid) }
            coVerify(exactly = 1) { repo.permanentDelete("root", failed.uuid) }
            verify(exactly = 2) { repo.observeRecycleBin("root") }
        }

    @Test
    fun `git entry shows branch and change count for repository sessions only`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        coEvery { repo.ensureSessionRoot(any()) } answers { root("root-${firstArg<String>()}") }
        every { repo.observeChildren(any(), any()) } returns flowOf(emptyList())
        every { repo.observeRecycleBin(any()) } returns flowOf(emptyList())
        val git = mockk<GitChangesService>()
        val status = GitStatus(
            branch = "main", detached = false, head = "a".repeat(40), unborn = false,
            staged = GitChangeList(listOf(GitFileChange("a.txt", GitChangeKind.MODIFIED))),
            unstaged = GitChangeList.Empty,
            untracked = GitChangeList(listOf(GitFileChange("b.txt", GitChangeKind.ADDED)), truncated = true),
        )
        coEvery { git.status("repo") } returns GitInspectResult.Success(status)
        coEvery { git.status("plain") } returns GitInspectResult.Failure(GitInspectError.NotRepository)
        coEvery { git.isRepository("repo") } returns true
        coEvery { git.isRepository("plain") } returns false
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
            injectedGitChanges = git,
        )

        viewModel.loadSession("repo")
        advanceUntilIdle()
        assertThat(viewModel.gitEntry.value).isEqualTo(
            GitEntrySummary.Repository(
                branch = "main", detached = false, headShortSha = "aaaaaaa", unborn = false, changeCount = 2, truncated = true,
            ),
        )

        viewModel.loadSession("plain")
        assertThat(viewModel.gitEntry.value).isEqualTo(GitEntrySummary.Hidden)
        advanceUntilIdle()
        assertThat(viewModel.gitEntry.value).isEqualTo(GitEntrySummary.Hidden)

        coEvery { git.status("plain") } returns GitInspectResult.Failure(GitInspectError.PythonUnavailable)
        viewModel.refreshGitEntry()
        advanceUntilIdle()
        assertThat(viewModel.gitEntry.value).isEqualTo(GitEntrySummary.PythonUnavailable)
        coVerify(exactly = 1) { git.status("repo") }
    }

    @Test
    fun `git entry shows a loading placeholder until the first status arrives and keeps the old summary on refresh`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        coEvery { repo.ensureSessionRoot(any()) } answers { root("root-${firstArg<String>()}") }
        every { repo.observeChildren(any(), any()) } returns flowOf(emptyList())
        every { repo.observeRecycleBin(any()) } returns flowOf(emptyList())
        val git = mockk<GitChangesService>()
        val status = GitStatus(
            branch = "main", detached = false, head = "a".repeat(40), unborn = false,
            staged = GitChangeList.Empty, unstaged = GitChangeList.Empty, untracked = GitChangeList.Empty,
        )
        val first = kotlinx.coroutines.CompletableDeferred<GitInspectResult<GitStatus>>()
        coEvery { git.isRepository("repo") } returns true
        coEvery { git.status("repo") } coAnswers { first.await() }
        val viewModel = ResourceExplorerViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            repo,
            injectedGitChanges = git,
        )

        viewModel.loadSession("repo")
        advanceUntilIdle()
        assertThat(viewModel.gitEntry.value).isEqualTo(GitEntrySummary.Loading)
        first.complete(GitInspectResult.Success(status))
        advanceUntilIdle()
        val ready = viewModel.gitEntry.value
        assertThat(ready).isInstanceOf(GitEntrySummary.Repository::class.java)

        val second = kotlinx.coroutines.CompletableDeferred<GitInspectResult<GitStatus>>()
        coEvery { git.status("repo") } coAnswers { second.await() }
        viewModel.refreshGitEntry()
        advanceUntilIdle()
        assertThat(viewModel.gitEntry.value).isEqualTo(ready)
        second.complete(GitInspectResult.Success(status))
        advanceUntilIdle()
        assertThat(viewModel.gitEntry.value).isEqualTo(ready)
    }

    @Test
    fun `git entry stays hidden without an injected git service in tests`() = runTest(dispatcher) {
        val repo = mockk<IWorkspaceRepository>()
        coEvery { repo.ensureSessionRoot("a") } returns root("root-a")
        every { repo.observeChildren(any(), any()) } returns flowOf(emptyList())
        every { repo.observeRecycleBin(any()) } returns flowOf(emptyList())
        val viewModel = ResourceExplorerViewModel(ApplicationProvider.getApplicationContext<Application>(), repo)
        viewModel.loadSession("a")
        advanceUntilIdle()
        assertThat(viewModel.gitEntry.value).isEqualTo(GitEntrySummary.Hidden)
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

    private fun recycled(uuid: String, rootUuid: String = "root") = FileEntry(
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
        inRecycleBin = true,
        originalMaterializedPath = "/$uuid.txt",
        recycledAt = 2,
    )
}
