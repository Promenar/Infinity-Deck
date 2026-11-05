package com.promenar.infinity.data.git

import com.promenar.infinity.data.generation.QueuedGenerationCancellation
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRunner

/** 保留运行器的排队取消契约，并在每轮生成结束后失效 Git 状态。 */
internal class GitInvalidatingGenerationRunner(
    private val delegate: GenerationRunner,
    private val invalidate: () -> Unit,
) : GenerationRunner, QueuedGenerationCancellation {
    override suspend fun run(request: GenerationRequest, emit: suspend (GenerationEvent) -> Unit) {
        try { delegate.run(request, emit) } finally { runCatching { invalidate() } }
    }

    override suspend fun cancelBeforeStart(request: GenerationRequest) {
        (delegate as? QueuedGenerationCancellation)?.cancelBeforeStart(request)
    }
}
