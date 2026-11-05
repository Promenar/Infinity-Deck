package com.promenar.infinity.data.rag

private val noKgProgress: suspend (KgExtractionProgress) -> Unit = {}
private val noEmbeddingProgress: suspend (EmbeddingBatchProgress) -> Unit = {}
private val noIndexStage: suspend () -> Unit = {}

sealed interface FileIndexEvent {
    val workspaceRootUuid: String
    val fileUuid: String
    val contentHash: String?

    data class Changed(
        override val workspaceRootUuid: String,
        override val fileUuid: String,
        override val contentHash: String,
        val targetEpoch: Long,
        val activeTaskId: String? = null,
        val kgRunId: String? = null,
        val skipVectorization: Boolean = false,
        val kgStrategy: String? = null,
        val useConfiguredKgStrategy: Boolean = true,
        val onKgProgress: suspend (KgExtractionProgress) -> Unit = noKgProgress,
        val onEmbeddingProgress: suspend (EmbeddingBatchProgress) -> Unit = noEmbeddingProgress,
        val onGraphStart: suspend () -> Unit = noIndexStage,
        val onSaving: suspend () -> Unit = noIndexStage,
    ) : FileIndexEvent {
        init {
            require(targetEpoch > 0) { "索引目标 epoch 必须大于 0" }
        }
    }

    data class Deleted(
        override val workspaceRootUuid: String,
        override val fileUuid: String,
    ) : FileIndexEvent {
        override val contentHash: String? = null
    }
}

fun interface FileIndexEventSink {
    suspend fun publish(event: FileIndexEvent)

    companion object {
        val None = FileIndexEventSink { }
    }
}
