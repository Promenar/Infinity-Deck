package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.local.inference.SlotType
import com.promenar.infinity.data.local.inference.StoredModel

/** llama_pooling_type 区分生成、向量池化与重排分类模型。 */
internal fun StoredModel.supportsAssignment(slot: SlotType): Boolean =
    format == "GGUF" && when (slot) {
        SlotType.MAIN -> poolingType == null || poolingType == 0 || poolingType == -1
        SlotType.EMBEDDING -> poolingType == 1 || poolingType == 2 || poolingType == 3
        SlotType.RERANK -> poolingType == 4
    }
