package com.promenar.infinity.share.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.MainActivity
import com.promenar.infinity.ShareDelivery
import com.promenar.infinity.ShareEnqueueResult
import com.promenar.infinity.ShareIntentQueue
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import com.promenar.infinity.data.repository.SecureWorkspaceFileOps
import com.promenar.infinity.data.repository.WorkspaceFileTooLargeException
import com.promenar.infinity.onboarding.OnboardingStateStore
import com.promenar.infinity.onboarding.OnboardingStep
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ShareImportAndroidEndToEndTest {
    private var lastStartupState: BackupStartupState? = null

    /**
     * 从完成引导的主界面开始。全新安装停在引导页，其灯珠字标持续逐帧动画，
     * 主线程始终不空闲，startActivitySync 会一直等到超时。
     */
    @Before
    fun completeOnboarding() {
        ApplicationProvider.getApplicationContext<InfinityApplication>()
            .getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putString("step", OnboardingStep.COMPLETED.name).commit()
    }

    @Test
    fun activityDispatchStagesBeforeClearingIntentAndFreshMainActivityMountsSheet() {
        val authority = "${com.promenar.infinity.test.BuildConfig.APPLICATION_ID}.sharefixture"
        val uri = uniqueFixtureUri(authority, ShareFixtureProvider.VALID_FILE)
        val context = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val intent = Intent(
            Intent.ACTION_SEND,
            uri,
            context,
            MainActivity::class.java,
        ).apply {
            type = "text/plain"
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var firstActivityIdentity = 0
        var firstActivityDestroyed = false
        val firstActivity = instrumentation.startActivitySync(intent) as MainActivity
        instrumentation.waitForIdleSync()
        try {
            val sheetShown = waitForShareSheet(instrumentation, firstActivity)
            assertWithMessage("启动状态 $lastStartupState").that(sheetShown).isTrue()
            instrumentation.runOnMainSync {
                firstActivityIdentity = System.identityHashCode(firstActivity)
                assertThat(firstActivity.intent.action).isEqualTo(Intent.ACTION_MAIN)
                assertThat(firstActivity.shareImportStateForTesting().visible).isTrue()
            }
        } finally {
            instrumentation.runOnMainSync {
                if (!firstActivity.isFinishing && !firstActivity.isDestroyed) firstActivity.finish()
            }
            firstActivityDestroyed = waitForDestroyed(instrumentation, firstActivity)
        }
        assertThat(firstActivityDestroyed).isTrue()

        val mainIntent = Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_MAIN }
        ActivityScenario.launch<MainActivity>(mainIntent).use { mainScenario ->
            val sheetShown = waitForShareSheet(mainScenario)
            assertWithMessage("启动状态 $lastStartupState").that(sheetShown).isTrue()
            mainScenario.onActivity { activity ->
                assertThat(System.identityHashCode(activity)).isNotEqualTo(firstActivityIdentity)
                assertThat(activity.intent.action).isEqualTo(Intent.ACTION_MAIN)
                assertThat(activity.intent.data).isNull()
                assertThat(activity.intent.hasExtra(Intent.EXTRA_STREAM)).isFalse()
                assertThat(activity.shareImportStateForTesting().visible).isTrue()
            }
        }
        runBlocking { drainInbox(DurableShareInbox.get(context.noBackupFilesDir)) }
    }

    @Test
    fun reshareOfConsumedStableUriMountsSheetAgain() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val authority = "${com.promenar.infinity.test.BuildConfig.APPLICATION_ID}.sharefixture"
        val uri = Uri.parse("content://$authority/${ShareFixtureProvider.VALID_FILE}")
        val inbox = DurableShareInbox.get(app.noBackupFilesDir)
        drainInbox(inbox)

        repeat(2) { attempt ->
            val activity = instrumentation.startActivitySync(shareActivityIntent(app, uri)) as MainActivity
            try {
                assertThat(waitForShareSheet(instrumentation, activity)).isTrue()
                instrumentation.runOnMainSync {
                    assertThat(activity.shareImportStateForTesting().pendingCount).isAtLeast(1)
                }
            } finally {
                instrumentation.runOnMainSync {
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                }
                waitForDestroyed(instrumentation, activity)
            }
            // 第一次 drain 让固定 URI 的指纹进入已消费记录；第二次分享必须仍被视为用户新的分享。
            drainInbox(inbox)
            assertThat(inbox.pendingCount.value).isEqualTo(0)
            if (attempt == 0) {
                val replay = inbox.stage(shareActivityIntent(app, uri), app.contentResolver, ShareDelivery.Redelivered)
                assertThat(replay).isEqualTo(ShareEnqueueResult.Duplicate)
            }
        }
    }

    @Test
    fun rapidConsecutiveSendUsesSingleActivityAndStagesBothRequestsOnce() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val authority = "${com.promenar.infinity.test.BuildConfig.APPLICATION_ID}.sharefixture"
        val firstUri = uniqueFixtureUri(authority, ShareFixtureProvider.BLOCKING_FILE)
        val secondUri = uniqueFixtureUri(authority, ShareFixtureProvider.SECOND_FILE)
        val inbox = DurableShareInbox.get(app.noBackupFilesDir)
        drainInbox(inbox)
        callFixture(app, authority, ShareFixtureProvider.METHOD_RESET_BLOCKING_READ)
        val firstIntent = shareActivityIntent(app, firstUri)
        val firstActivity = instrumentation.startActivitySync(firstIntent) as MainActivity
        val firstIdentity = System.identityHashCode(firstActivity)
        try {
            val blockingReadStarted = callFixture(
                app,
                authority,
                ShareFixtureProvider.METHOD_AWAIT_BLOCKING_READ,
            ).getBoolean(ShareFixtureProvider.RESULT_READY)
            assertWithMessage("启动状态 ${app.startupState.value}").that(blockingReadStarted).isTrue()
            app.startActivity(shareActivityIntent(app, secondUri))
            val delivered = waitForDeliveredShare(
                instrumentation = instrumentation,
                expectedActivityIdentity = firstIdentity,
                expectedStream = secondUri,
            )
            assertThat(delivered.resumedCount).isEqualTo(1)
            assertThat(delivered.activityIdentity).isEqualTo(firstIdentity)
            assertThat(delivered.action).isEqualTo(Intent.ACTION_SEND)
            assertThat(delivered.stream).isEqualTo(secondUri)
            assertThat(delivered.clipUris).containsExactly(secondUri)

            callFixture(app, authority, ShareFixtureProvider.METHOD_RELEASE_BLOCKING_READ)
            assertThat(waitForStagedNames(
                inbox,
                setOf(ShareFixtureProvider.BLOCKING_FILE, ShareFixtureProvider.SECOND_FILE),
            )).isTrue()
            val sheetShown = waitForShareSheet(instrumentation, firstActivity)
            assertWithMessage("启动状态 $lastStartupState").that(sheetShown).isTrue()
            instrumentation.runOnMainSync {
                assertThat(firstActivity.shareImportStateForTesting().pendingCount).isAtLeast(2)
            }
        } finally {
            callFixture(app, authority, ShareFixtureProvider.METHOD_RELEASE_BLOCKING_READ)
            instrumentation.runOnMainSync {
                if (!firstActivity.isFinishing && !firstActivity.isDestroyed) firstActivity.finish()
            }
            waitForDestroyed(instrumentation, firstActivity)
            drainInbox(inbox)
        }
    }

    @Test
    fun contentResolverBatchPersistsOnceAndOversizeFailureLeavesNoTemporaryFile() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val authority = "${com.promenar.infinity.test.BuildConfig.APPLICATION_ID}.sharefixture"
        val uri = Uri.parse("content://$authority/${ShareFixtureProvider.VALID_FILE}")
        val invalidPdf = Uri.parse("content://$authority/${ShareFixtureProvider.INVALID_PDF}")
        val sessionId = "__share_e2e_${UUID.randomUUID()}__"
        val now = System.currentTimeMillis()
        app.database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                agentId = "__test__",
                title = "Share E2E",
                createdAt = now,
                updatedAt = now,
            )
        )
        val root = app.workspaceRepository.ensureSessionRoot(sessionId)
        val inboxRoot = File(app.noBackupFilesDir, "share-e2e-${UUID.randomUUID()}").apply { mkdirs() }
        val inbox = DurableShareInbox(inboxRoot)
        val queue = ShareIntentQueue(inbox)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri, invalidPdf))
        }
        assertThat(queue.stageDurably(intent, app.contentResolver, ShareDelivery.Fresh)).isEqualTo(ShareEnqueueResult.Accepted)
        val lease = checkNotNull(queue.claimNextDurably())

        try {
            val result = SharedFileImporter(
                source = inbox.contentSource(),
                workspace = app.workspaceRepository,
                indexScheduler = AndroidShareIndexScheduler(app),
            ).import(lease.request, root.uuid)

            val created = result.created.single().created!!
            val rejected = result.rejected.single()
            // DurableInbox 会把原始 content URI 映射为同顺序的应用私有 infinity-stage URI。
            assertThat(rejected.uri).isEqualTo(lease.request.uris[1])
            assertThat(rejected.uri.scheme).isEqualTo("infinity-stage")
            assertThat(rejected.displayName).isEqualTo(ShareFixtureProvider.INVALID_PDF)
            assertThat(rejected.reason).isEqualTo(ShareRejectReason.MimeMismatch)
            val physical = File(created.physicalRootPath, created.materializedPath.trimStart('/'))
            assertThat(created.name).isEqualTo(ShareFixtureProvider.VALID_FILE)
            assertThat(physical.readBytes()).isEqualTo(ShareFixtureProvider.CONTENT)
            assertThat(app.workspaceRepository.getByUuid(root.uuid, created.uuid)).isEqualTo(created)
            assertThat(app.database.vectorizationTaskDao().getByWorkspaceFile(
                root.uuid,
                created.uuid,
                com.promenar.infinity.data.rag.VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            )).isNotNull()
            inbox.recordCreated(lease.request.requestId, result.created)
            assertThat(queue.nackDurably(lease.token)).isTrue()

            val recreatedQueue = ShareIntentQueue(DurableShareInbox(inboxRoot))
            val recreatedLease = recreatedQueue.claimNextDurably()!!
            assertThat(DurableShareInbox(inboxRoot).contentSource()
                .preflightItem(recreatedLease.request.uris.first())?.status)
                .isEqualTo(ShareImportStatus.Created)
            assertThat(app.workspaceRepository.getByMaterializedPath(root.uuid, "/${created.name}"))
                .isEqualTo(created)

            val rootPath = File(root.physicalRootPath).toPath()
            val oversizeFailure = runCatching {
                SecureWorkspaceFileOps().createFileStreaming(
                    root = rootPath,
                    relative = listOf("too-large.txt"),
                    maxBytes = 4,
                ) { output -> output.write(byteArrayOf(1, 2, 3, 4, 5)) }
            }.exceptionOrNull()
            assertThat(oversizeFailure).isInstanceOf(WorkspaceFileTooLargeException::class.java)
            assertThat(File(root.physicalRootPath, "too-large.txt").exists()).isFalse()
            assertThat(
                File(root.physicalRootPath).list().orEmpty().any { it.startsWith(".create-") }
            ).isFalse()
            assertThat(recreatedQueue.dropDurably(recreatedLease.token)).isTrue()
            app.workspaceRepository.permanentDelete(root.uuid, created.uuid)
            app.workspaceRepository.permanentDelete(root.uuid, created.uuid)
        } finally {
            app.database.sessionDao().deleteById(sessionId)
            inboxRoot.deleteRecursively()
        }
    }

    @Test
    fun contentResolverUtf8BoundaryPersistsExactBytesAndSchedulesReference() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val authority = "${com.promenar.infinity.test.BuildConfig.APPLICATION_ID}.sharefixture"
        val uri = Uri.parse("content://$authority/${ShareFixtureProvider.UTF8_BOUNDARY_FILE}")
        val sessionId = "__share_utf8_boundary_${UUID.randomUUID()}__"
        val now = System.currentTimeMillis()
        app.database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                agentId = "__test__",
                title = "Share UTF-8 Boundary E2E",
                createdAt = now,
                updatedAt = now,
            )
        )
        val root = app.workspaceRepository.ensureSessionRoot(sessionId)
        val inboxRoot = File(app.noBackupFilesDir, "share-utf8-boundary-${UUID.randomUUID()}").apply { mkdirs() }
        val inbox = DurableShareInbox(inboxRoot)
        val queue = ShareIntentQueue(inbox)
        var leaseToken: String? = null
        var created: FileEntry? = null
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            assertThat(queue.stageDurably(intent, app.contentResolver, ShareDelivery.Fresh))
                .isEqualTo(ShareEnqueueResult.Accepted)
            val lease = checkNotNull(queue.claimNextDurably())
            leaseToken = lease.token
            val scheduled = mutableListOf<Pair<String, String>>()
            val result = SharedFileImporter(
                source = inbox.contentSource(),
                workspace = app.workspaceRepository,
                indexScheduler = ShareIndexScheduler { workspaceRootUuid, entry ->
                    scheduled += workspaceRootUuid to entry.uuid
                    ShareIndexReceipt("synthetic-reference-${entry.uuid}", entry.uuid)
                },
            ).import(lease.request, root.uuid)

            val item = result.created.single()
            created = checkNotNull(item.created)
            val physical = File(created!!.physicalRootPath, created!!.materializedPath.trimStart('/'))
            assertThat(physical.readBytes()).isEqualTo(ShareFixtureProvider.UTF8_BOUNDARY_CONTENT)
            assertThat(String(physical.readBytes(), Charsets.UTF_8)).contains("界🙂")
            assertThat(app.workspaceRepository.getByUuid(root.uuid, created!!.uuid)).isEqualTo(created)
            assertThat(scheduled).containsExactly(root.uuid to created!!.uuid)
            assertThat(item.indexTaskId).isEqualTo("synthetic-reference-${created!!.uuid}")
            assertThat(physical.length()).isGreaterThan(8_192L)
            inbox.recordCreated(lease.request.requestId, result.created)
            val durableItem = checkNotNull(inbox.contentSource().preflightItem(lease.request.uris.single()))
            assertThat(durableItem.status).isEqualTo(ShareImportStatus.Created)
            assertThat(durableItem.fileUuid).isEqualTo(created!!.uuid)
            assertThat(queue.ackDurably(lease.token)).isTrue()
        } finally {
            leaseToken?.let { token ->
                bestEffort { queue.nackDurably(token) }
                bestEffort { queue.dropDurably(token) }
            }
            created?.let { entry ->
                bestEffort { app.workspaceRepository.permanentDelete(root.uuid, entry.uuid) }
                bestEffort { app.workspaceRepository.permanentDelete(root.uuid, entry.uuid) }
            }
            bestEffort { app.database.sessionDao().deleteById(sessionId) }
            inboxRoot.deleteRecursively()
        }
    }

    @Test
    fun contentResolverHtmlPersistsAndProductionExtractorChunksBodyWithoutRemoteContent() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<InfinityApplication>()
        val authority = "${com.promenar.infinity.test.BuildConfig.APPLICATION_ID}.sharefixture"
        val uri = Uri.parse("content://$authority/${ShareFixtureProvider.HTML_FILE}")
        val sessionId = "__share_html_${UUID.randomUUID()}__"
        val now = System.currentTimeMillis()
        app.database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                agentId = "__test__",
                title = "Share HTML E2E",
                createdAt = now,
                updatedAt = now,
            )
        )
        val root = app.workspaceRepository.ensureSessionRoot(sessionId)
        val inboxRoot = File(app.noBackupFilesDir, "share-html-${UUID.randomUUID()}").apply { mkdirs() }
        val inbox = DurableShareInbox(inboxRoot)
        val queue = ShareIntentQueue(inbox)
        var leaseToken: String? = null
        var created: FileEntry? = null
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            assertThat(queue.stageDurably(intent, app.contentResolver, ShareDelivery.Fresh))
                .isEqualTo(ShareEnqueueResult.Accepted)
            val lease = checkNotNull(queue.claimNextDurably())
            leaseToken = lease.token
            val scheduled = mutableListOf<Pair<String, String>>()
            val result = SharedFileImporter(
                source = inbox.contentSource(),
                workspace = app.workspaceRepository,
                indexScheduler = ShareIndexScheduler { workspaceRootUuid, entry ->
                    scheduled += workspaceRootUuid to entry.uuid
                    ShareIndexReceipt("synthetic-reference-${entry.uuid}", entry.uuid)
                },
            ).import(lease.request, root.uuid)

            val item = result.created.single()
            created = checkNotNull(item.created)
            val physical = File(created!!.physicalRootPath, created!!.materializedPath.trimStart('/'))
            assertThat(physical.readBytes()).isEqualTo(ShareFixtureProvider.HTML_CONTENT)
            assertThat(app.workspaceRepository.getByUuid(root.uuid, created!!.uuid)).isEqualTo(created)
            assertThat(scheduled).containsExactly(root.uuid to created!!.uuid)
            assertThat(item.indexTaskId).isEqualTo("synthetic-reference-${created!!.uuid}")

            val extraction = DocumentReferenceExtractor(
                chunkSize = 24,
                chunkOverlap = 4,
            ).extract(created!!)
            assertThat(extraction.graphText).contains("中文正文一")
            assertThat(extraction.graphText).contains("中文正文二")
            assertThat(extraction.graphText).doesNotContain("脚本密文不应被索引")
            assertThat(extraction.graphText).doesNotContain("https://remote.example")
            assertThat(extraction.chunks.size).isGreaterThan(1)
            assertThat(extraction.chunks.joinToString("\n")).contains("中文正文一")
            inbox.recordCreated(lease.request.requestId, result.created)
            val durableItem = checkNotNull(inbox.contentSource().preflightItem(lease.request.uris.single()))
            assertThat(durableItem.status).isEqualTo(ShareImportStatus.Created)
            assertThat(durableItem.fileUuid).isEqualTo(created!!.uuid)
            assertThat(queue.ackDurably(lease.token)).isTrue()
        } finally {
            leaseToken?.let { token ->
                bestEffort { queue.nackDurably(token) }
                bestEffort { queue.dropDurably(token) }
            }
            created?.let { entry ->
                bestEffort { app.workspaceRepository.permanentDelete(root.uuid, entry.uuid) }
                bestEffort { app.workspaceRepository.permanentDelete(root.uuid, entry.uuid) }
            }
            bestEffort { app.database.sessionDao().deleteById(sessionId) }
            inboxRoot.deleteRecursively()
        }
    }

    private fun waitForShareSheet(scenario: ActivityScenario<MainActivity>): Boolean {
        repeat(200) {
            var visible = false
            scenario.onActivity { activity -> visible = activity.shareSheetVisibleAfterStartup() }
            if (visible) return true
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private fun waitForShareSheet(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
    ): Boolean {
        repeat(200) {
            var visible = false
            instrumentation.runOnMainSync {
                if (!activity.isDestroyed) visible = activity.shareSheetVisibleAfterStartup()
            }
            if (visible) return true
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    /** 与生产路径一致：启动恢复就绪前不触碰分享导入状态（其依赖在启动迁移中初始化）。 */
    private fun MainActivity.shareSheetVisibleAfterStartup(): Boolean {
        val state = (application as InfinityApplication).startupState.value
        lastStartupState = state
        return state == BackupStartupState.Ready && shareImportStateForTesting().visible
    }

    private fun waitForDestroyed(
        instrumentation: android.app.Instrumentation,
        activity: MainActivity,
    ): Boolean {
        repeat(200) {
            var destroyed = false
            instrumentation.runOnMainSync { destroyed = activity.isDestroyed }
            if (destroyed) return true
            android.os.SystemClock.sleep(50)
        }
        return false
    }

    private fun shareActivityIntent(context: InfinityApplication, uri: Uri) = Intent(
        Intent.ACTION_SEND,
        uri,
        context,
        MainActivity::class.java,
    ).apply {
        type = "text/plain"
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(Intent.EXTRA_STREAM, uri)
    }

    /**
     * 收件箱按 action、MIME 与 URI 永久记录已消费的分享指纹；固定 URI 在同一设备上重跑会被判为重复而不再展示。
     * Provider 只按末段路径解析文件，前置一次性目录段使每次运行的指纹唯一，文件名不变。
     */
    private fun uniqueFixtureUri(authority: String, name: String): Uri =
        Uri.parse("content://$authority/${UUID.randomUUID()}/$name")

    private fun callFixture(context: InfinityApplication, authority: String, method: String) =
        checkNotNull(context.contentResolver.call(Uri.parse("content://$authority"), method, null, null))

    private fun waitForDeliveredShare(
        instrumentation: android.app.Instrumentation,
        expectedActivityIdentity: Int,
        expectedStream: Uri,
    ): DeliveredShareSnapshot {
        var last = DeliveredShareSnapshot(resumedCount = 0)
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (true) {
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                val activity = resumed.singleOrNull()
                @Suppress("DEPRECATION")
                last = DeliveredShareSnapshot(
                    resumedCount = resumed.size,
                    activityIdentity = activity?.let { System.identityHashCode(it) },
                    action = activity?.intent?.action,
                    stream = activity?.intent?.getParcelableExtra(Intent.EXTRA_STREAM),
                    clipUris = buildList {
                        activity?.intent?.clipData?.let { clip ->
                            repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
                        }
                    },
                )
            }
            if (last.resumedCount == 1 &&
                last.activityIdentity == expectedActivityIdentity &&
                last.stream == expectedStream
            ) return last
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) return last
            android.os.SystemClock.sleep(minOf(50L, remaining))
        }
    }

    private data class DeliveredShareSnapshot(
        val resumedCount: Int,
        val activityIdentity: Int? = null,
        val action: String? = null,
        val stream: Uri? = null,
        val clipUris: List<Uri> = emptyList(),
    )

    private suspend fun waitForStagedNames(inbox: DurableShareInbox, expected: Set<String>): Boolean {
        repeat(200) {
            val source = inbox.contentSource()
            val names = inbox.snapshot().flatMap { request ->
                request.uris.map { uri -> source.metadata(uri).displayName }
            }
            if (expected.all { expectedName -> names.count { it == expectedName } == 1 }) return true
            android.os.SystemClock.sleep(100)
        }
        return false
    }

    private suspend fun drainInbox(inbox: DurableShareInbox) {
        repeat(64) {
            val lease = inbox.claimNext() ?: return
            check(inbox.drop(lease.token))
        }
        error("测试收件箱超过清理上限")
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // 清理失败不掩盖验收断言；每个测试使用唯一会话和收件箱目录。
        }
    }
}
