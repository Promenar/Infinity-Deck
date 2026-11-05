package com.promenar.infinity.data.rag

import com.promenar.infinity.data.local.db.KgIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 只使用模型明确声明的别名，不根据名称相似度推断身份。 */
internal object KgAliasMerge {
    fun merge(nodes: List<ExtractedNode>, edges: List<ExtractedEdge>): ExtractionResult {
        fun key(name: String) = KgIdentity.normalize(name)
        fun metadata(node: ExtractedNode): JsonObject? = try {
            node.metadata?.let { Json.parseToJsonElement(it) as? JsonObject }
        } catch (_: Exception) { null }
        fun aliases(node: ExtractedNode): List<String> = (metadata(node)?.get("aliases") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull } ?: emptyList()
        val parents = linkedMapOf<String, String>()
        nodes.forEach { parents.putIfAbsent(key(it.name), key(it.name)) }
        fun root(name: String): String {
            var current = name
            while (parents[current] != null && parents[current] != current) current = parents.getValue(current)
            return current
        }
        nodes.forEach { node ->
            val canonical = key(node.name)
            aliases(node).forEach { alias ->
                val other = key(alias)
                if (other in parents) {
                    val targetRoot = root(canonical)
                    val otherRoot = root(other)
                    if (targetRoot != otherRoot) parents[otherRoot] = targetRoot
                }
            }
        }
        val grouped = linkedMapOf<String, MutableList<ExtractedNode>>()
        nodes.forEach { grouped.getOrPut(root(key(it.name))) { mutableListOf() }.add(it) }
        val canonicalNames = mutableMapOf<String, String>()
        val merged = grouped.map { (group, members) ->
            val first = members.firstOrNull { key(it.name) == group } ?: members.first()
            val counts = members.groupingBy { it.type }.eachCount()
            val maxCount = counts.values.maxOrNull() ?: 0
            val type = if (counts[first.type] == maxCount) first.type else members.first { counts[it.type] == maxCount }.type
            val description = members.mapNotNull { (metadata(it)?.get("description") as? JsonPrimitive)?.contentOrNull }
                .maxByOrNull { it.length }
            val allAliases = (members.flatMap(::aliases) + members.map { it.name })
                .filter { key(it) != key(first.name) }.distinctBy(::key)
            val fields = metadata(first)?.toMutableMap() ?: mutableMapOf()
            if (description != null) fields["description"] = JsonPrimitive(description)
            if (allAliases.isNotEmpty()) fields["aliases"] = JsonArray(allAliases.map { JsonPrimitive(it) })
            members.forEach { canonicalNames[key(it.name)] = first.name }
            allAliases.forEach { canonicalNames[key(it)] = first.name }
            first.copy(type = type, metadata = if (fields.isEmpty()) first.metadata else JsonObject(fields).toString())
        }
        return ExtractionResult(merged, edges.map {
            it.copy(source = canonicalNames[key(it.source)] ?: it.source, target = canonicalNames[key(it.target)] ?: it.target)
        }.distinct())
    }
}
