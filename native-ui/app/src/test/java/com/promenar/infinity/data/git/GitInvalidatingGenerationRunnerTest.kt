package com.promenar.infinity.data.git

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.QueuedGenerationCancellation
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRunner
import com.promenar.infinity.domain.generation.GenerationRuntimePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GitInvalidatingGenerationRunnerTest {
    private val request = GenerationRequest("session", "assistant", "user", "hello", emptyList(), GenerationRuntimePolicy.BACKGROUND_ALLOWED)

    @Test fun `完成和取消均使状态失效，排队取消仍交给原运行器`() = runTest {
        var invalidations = 0
        var cancelQueued = 0
        var fail = false
        val delegate = object : GenerationRunner, QueuedGenerationCancellation {
            override suspend fun run(request: GenerationRequest, emit: suspend (GenerationEvent) -> Unit) {
                if (fail) throw CancellationException("cancelled")
            }
            override suspend fun cancelBeforeStart(request: GenerationRequest) { cancelQueued++ }
        }
        val runner = GitInvalidatingGenerationRunner(delegate) { invalidations++ }
        runner.run(request) {}
        fail = true
        try { runner.run(request) {} } catch (_: CancellationException) { }
        runner.cancelBeforeStart(request)
        assertThat(invalidations).isEqualTo(2)
        assertThat(cancelQueued).isEqualTo(1)
    }
}
