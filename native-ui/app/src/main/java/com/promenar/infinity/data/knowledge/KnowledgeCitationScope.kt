package com.promenar.infinity.data.knowledge

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 为自动注入传递本轮取号函数，使正文与可恢复的引用编号一致。 */
class KnowledgeCitationScope(val citation: (String) -> String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<KnowledgeCitationScope>
}
