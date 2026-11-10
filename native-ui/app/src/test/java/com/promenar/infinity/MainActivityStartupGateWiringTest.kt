package com.promenar.infinity

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class MainActivityStartupGateWiringTest {
    private val source = Files.readAllBytes(
        Path.of("app/src/main/java/com/promenar/infinity/MainActivity.kt")
    ).toString(Charsets.UTF_8)
    private val manifest = Files.readAllBytes(Path.of("app/src/main/AndroidManifest.xml"))
        .toString(Charsets.UTF_8)

    @Test
    fun `activity opens startup gate before reading the frozen onboarding snapshot or building nav graph`() {
        val collect = source.indexOf("startupState.collectAsStateWithLifecycle()")
        val gate = source.indexOf("StartupGate(")
        val dependencies = source.indexOf("provideChatRouteDependencies(app)")
        val onboarding = source.indexOf("onboardingStore.state.value.step")
        val nav = source.indexOf("InfinityNavGraph(")

        assertThat(collect).isAtLeast(0)
        assertThat(gate).isGreaterThan(collect)
        assertThat(dependencies).isGreaterThan(gate)
        assertThat(onboarding).isGreaterThan(gate)
        assertThat(nav).isGreaterThan(onboarding)
    }

    @Test
    fun `share intents stage durably before startup ready presentation`() {
        val onCreate = functionBody("override fun onCreate(savedInstanceState: Bundle?)")
        val onNewIntent = functionBody("override fun onNewIntent(intent: Intent)")
        val consume = functionBody("private fun consumeShareIntentsIfReady()")

        assertThat(source).contains("ShareIntentViewModel.factory(durableShareInbox, applicationContext.contentResolver)")
        assertThat(source).contains("shareIntentViewModel.stagingCoordinator.submit")
        assertThat(onCreate).contains("val launchDelivery = ShareDelivery.of(savedInstanceState != null, intent?.flags ?: 0)")
        // 单个 Markdown 交给外部文档查看器，其余分享照常持久暂存。
        assertThat(onCreate).contains("if (!routeExternalDocumentIntent(intent, launchDelivery)) stageShareIntent(intent, launchDelivery)")
        assertThat(onCreate).doesNotContain("restoreConsumedState")
        assertThat(onNewIntent).contains(
            "stageShareIntent(intent, ShareDelivery.of(restoredInstanceState = false, intentFlags = intent.flags))"
        )
        assertThat(consume).contains("BackupStartupState.Ready")
        assertThat(consume).contains("shareImportViewModel.presentNext()")
        assertThat(source).doesNotContain("TODO: Implement workspace-based file import")
    }

    @Test
    fun `分享弹层重试入口按最近失败操作路由而非固定重放导入`() {
        val sheet = source.substring(source.indexOf("ShareImportSheet("))
            .substringBefore("SharePendingBanner(")

        assertThat(sheet).contains("onRetry = shareImportViewModel::retryLastFailure")
        assertThat(sheet).doesNotContain("onRetry = shareImportViewModel::retryRejected")
    }

    @Test
    fun `payload is replaced only after durable stage success or known duplicate`() {
        val stage = functionBody("private fun handleShareStageOutcome(outcome: ShareStageOutcome)")
        val accepted = stage.indexOf("ShareEnqueueResult.Accepted, ShareEnqueueResult.Duplicate")
        val replace = stage.indexOf("setIntent(cleanMainIntent())")

        assertThat(accepted).isAtLeast(0)
        assertThat(replace).isGreaterThan(accepted)
        assertThat(stage).contains("R.string.share_intent_invalid")
        assertThat(stage).contains("R.string.share_intent_queue_full")
        assertThat(stage).contains("revokeShareReadGrants")
        val invalidBranch = stage.substring(stage.indexOf("ShareEnqueueResult.RejectedInvalid"))
            .substringBefore("ShareEnqueueResult.RejectedCapacity")
        assertThat(invalidBranch.indexOf("revokeShareReadGrants")).isAtLeast(0)
        assertThat(invalidBranch.indexOf("setIntent(cleanMainIntent())"))
            .isGreaterThan(invalidBranch.indexOf("revokeShareReadGrants"))
        val acceptedBranch = stage.substring(accepted, stage.indexOf("ShareEnqueueResult.RejectedInvalid"))
        val capacityBranch = stage.substring(stage.indexOf("ShareEnqueueResult.RejectedCapacity"))
        assertThat(acceptedBranch).doesNotContain("revokeShareReadGrants")
        assertThat(capacityBranch).doesNotContain("revokeShareReadGrants")
        assertThat(acceptedBranch).contains("stagingCoordinator.acknowledge")
        assertThat(acceptedBranch).contains(
            "outcome.result == ShareEnqueueResult.Duplicate && outcome.delivery == ShareDelivery.Fresh"
        )
        assertThat(acceptedBranch).contains("R.string.share_intent_already_pending")
        assertThat(invalidBranch).contains("stagingCoordinator.acknowledge")
        assertThat(capacityBranch).doesNotContain("stagingCoordinator.acknowledge")
        assertThat(source).doesNotContain("saveConsumedState(outState)")
    }

    @Test
    fun `singleTask与ViewModel协调器保证唯一owner且不丢连续SEND`() {
        assertThat(manifest).contains("android:launchMode=\"singleTask\"")
        assertThat(source).doesNotContain("AtomicBoolean")
        assertThat(source).doesNotContain("shareStaging")
        val onNewIntent = functionBody("override fun onNewIntent(intent: Intent)")
        assertThat(onNewIntent.indexOf("setIntent(intent)")).isAtLeast(0)
        assertThat(onNewIntent.indexOf("stageShareIntent(intent, "))
            .isGreaterThan(onNewIntent.indexOf("setIntent(intent)"))
    }

    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        assertThat(start).isAtLeast(0)
        val brace = source.indexOf('{', start)
        var depth = 0
        for (index in brace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(brace + 1, index)
            }
        }
        error("函数未闭合: $signature")
    }
}
