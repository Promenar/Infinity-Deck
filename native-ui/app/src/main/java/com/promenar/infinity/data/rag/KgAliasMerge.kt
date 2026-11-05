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
        fun type(value: String) = KgIdentity.normalize(value).ifBlank { "concept" }
        fun metadata(node: ExtractedNode): JsonObject? = try {
            node.metadata?.let { Json.parseToJsonElement(it) as? JsonObject }
        } catch (_: Exception) { null }
        fun aliases(node: ExtractedNode): List<String> = (metadata(node)?.get("aliases") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull } ?: emptyList()
        val parents = linkedMapOf<Pair<String, String>, Pair<String, String>>()
        nodes.forEach { node ->
            val identity = key(node.name) to type(node.type)
            parents.putIfAbsent(identity, identity)
        }
        fun root(name: Pair<String, String>): Pair<String, String> {
            var current = name
            while (parents[current] != null && parents[current] != current) current = parents.getValue(current)
            return current
        }
        nodes.forEach { node ->
            val canonical = key(node.name) to type(node.type)
            aliases(node).forEach { alias ->
                val other = key(alias) to type(node.type)
                if (other in parents) {
                    val targetRoot = root(canonical)
                    val otherRoot = root(other)
                    if (targetRoot != otherRoot) parents[otherRoot] = targetRoot
                }
            }
        }
        val grouped = linkedMapOf<Pair<String, String>, MutableList<ExtractedNode>>()
        nodes.forEach { grouped.getOrPut(root(key(it.name) to type(it.type))) { mutableListOf() }.add(it) }
        val canonicalNames = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        val merged = grouped.map { (group, members) ->
            val first = members.firstOrNull { key(it.name) == group.first } ?: members.first()
            val description = members.mapNotNull { (metadata(it)?.get("description") as? JsonPrimitive)?.contentOrNull }
                .maxByOrNull { it.length }
            val allAliases = (members.flatMap(::aliases) + members.map { it.name })
                .filter { key(it) != key(first.name) }.distinctBy(::key)
            val fields = metadata(first)?.toMutableMap() ?: mutableMapOf()
            if (description != null) fields["description"] = JsonPrimitive(description)
            if (allAliases.isNotEmpty()) fields["aliases"] = JsonArray(allAliases.map { JsonPrimitive(it) })
            (members.map { it.name } + allAliases).forEach { name ->
                canonicalNames.getOrPut(key(name) to group.second) { mutableSetOf() }.add(first.name)
            }
            first.copy(metadata = if (fields.isEmpty()) first.metadata else JsonObject(fields).toString())
        }
        val namesByKey = canonicalNames.entries.groupBy { it.key.first }
        fun resolve(name: String, nodeType: String?): String? {
            val matches = namesByKey[key(name)].orEmpty().asSequence().filter { (identity, _) ->
                nodeType.isNullOrBlank() || identity.second == type(nodeType.orEmpty())
            }.flatMap { (identity, names) -> names.asSequence().map { it to identity.second } }.distinct().toList()
            return when (matches.size) {
                0 -> name
                1 -> matches.single().first
                else -> null
            }
        }
        return ExtractionResult(merged, edges.mapNotNull {
            val source = resolve(it.source, it.sourceType) ?: return@mapNotNull null
            val target = resolve(it.target, it.targetType) ?: return@mapNotNull null
            it.copy(source = source, target = target)
        }.distinct())
    }
}
