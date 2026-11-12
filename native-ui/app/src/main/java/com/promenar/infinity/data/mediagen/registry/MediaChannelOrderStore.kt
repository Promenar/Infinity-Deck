package com.promenar.infinity.data.mediagen.registry

import android.content.SharedPreferences
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** 三组顺序写入现有 settings 偏好，配置备份不包含凭证。 */
class MediaChannelOrderStore(private val prefs: SharedPreferences) {
    private val lock = Any()
    private fun key(group: MediaGroup) = "media_channel_order_${group.name.lowercase()}"
    private fun read(group: MediaGroup): List<String> = runCatching {
        Json.parseToJsonElement(prefs.getString(key(group), "[]") ?: "[]").jsonArray
            .map { it.jsonPrimitive.content }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())
    private val _orders = MutableStateFlow(MediaGroup.entries.associateWith(::read))
    val orders: StateFlow<Map<MediaGroup, List<String>>> = _orders.asStateFlow()

    // 强引用监听器，使备份恢复与其它设置页的写入立即可见。
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
        if (MediaGroup.entries.any { key(it) == changed }) synchronized(lock) {
            _orders.value = MediaGroup.entries.associateWith(::read)
        }
    }.also(prefs::registerOnSharedPreferenceChangeListener)

    fun reconcile(channels: List<MediaChannel>): Map<MediaGroup, List<String>> = synchronized(lock) {
        val next = MediaGroup.entries.associateWith { group ->
            val available = channels.filter { it.group == group }.map { it.id }.distinct()
            val saved = _orders.value[group].orEmpty()
            val initial = if (group == MediaGroup.IMAGE && !prefs.contains(key(group))) {
                prefs.getString("preset_image_model", null)?.takeIf { it.isNotBlank() }?.let { listOf("cloud:$it") }.orEmpty()
            } else saved
            initial.filter { it in available } + available.filter { it !in initial }
        }
        write(next)
        next
    }

    /** 允许传入需要优先的子集，其余有效通道保持原顺序并排在其后。 */
    fun reorder(group: MediaGroup, channelIds: List<String>) = synchronized(lock) {
        require(channelIds.distinct().size == channelIds.size && channelIds.all { it in _orders.value[group].orEmpty() }) { "顺序含重复或失效通道" }
        write(_orders.value + (group to (channelIds.toList() + _orders.value[group].orEmpty().filter { it !in channelIds })))
    }

    private fun write(next: Map<MediaGroup, List<String>>) {
        if (next == _orders.value && MediaGroup.entries.all { prefs.contains(key(it)) }) return
        val editor = prefs.edit()
        next.forEach { (group, order) -> editor.putString(key(group), JsonArray(order.map(::JsonPrimitive)).toString()) }
        val firstImage = next[MediaGroup.IMAGE].orEmpty().firstOrNull()
        editor.putString("preset_image_model", firstImage?.takeIf { it.startsWith("cloud:") }?.removePrefix("cloud:").orEmpty())
        check(editor.commit()) { "媒体通道顺序保存失败" }
        _orders.value = next
    }
}
