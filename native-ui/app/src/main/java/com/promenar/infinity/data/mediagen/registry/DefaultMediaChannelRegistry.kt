package com.promenar.infinity.data.mediagen.registry

import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 首次发射表示模板首轮加载完成；加载中的空占位应由装配层过滤。 */
fun interface ComfyChannelSource {
    fun observeChannels(): Flow<List<MediaChannel>>
}

/** 所有来源都首发后才修剪失效项，避免把尚未加载的模板当成删除。 */
class DefaultMediaChannelRegistry(
    models: Flow<List<ModelInfo>>,
    providers: Flow<List<ProviderListItem>>,
    comfy: ComfyChannelSource,
    private val orders: MediaChannelOrderStore,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaChannelRegistry {
    private val mutex = Mutex()
    private val channels = combine(models, providers, comfy.observeChannels(), orders.orders) { ms, ps, cs, _ ->
        val enabled = ps.filter { it.enabled }.map { it.id }.toSet()
        val merged = ms.filter { it.providerId in enabled }.mapNotNull { it.toMediaChannel() } + cs
        require(merged.map { it.id }.distinct().size == merged.size) { "媒体通道 ID 重复" }
        val byId = merged.associateBy { it.id }
        mutex.withLock {
            val order = orders.reconcile(merged)
            MediaGroup.entries.flatMap { group -> order[group].orEmpty().mapNotNull { id -> byId[id]?.takeIf { it.group == group } } }
        }
    }.flowOn(dispatcher).distinctUntilChanged().shareIn(scope, SharingStarted.Eagerly, replay = 1)

    override fun observeChannels(): Flow<List<MediaChannel>> = channels
    override fun observeOrder(group: MediaGroup): Flow<List<String>> = channels.map { list -> list.filter { it.group == group }.map { it.id } }.distinctUntilChanged()
    override suspend fun setOrder(group: MediaGroup, channelIds: List<String>) = withContext(dispatcher) {
        channels.first()
        mutex.withLock { orders.reorder(group, channelIds) }
    }
    override suspend fun channel(id: String): MediaChannel? = channels.first().firstOrNull { it.id == id }
}
