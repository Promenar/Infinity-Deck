package com.promenar.infinity.data.model.catalog

import java.util.Collections

class ModelMetadataResolver private constructor(
    records: Collection<ModelMetadataRecord>,
    catalogAliases: Map<String, ModelMetadataRecord>,
) {
    constructor() : this(
        records = INFINITY_EXACT_MODEL_OVERRIDES.values.toSet(),
        catalogAliases = INFINITY_EXACT_MODEL_OVERRIDES,
    )

    internal constructor(exactRecords: Collection<ModelMetadataRecord>) : this(
        records = exactRecords,
        catalogAliases = emptyMap(),
    )

    private val recordSnapshots = records.map(ModelMetadataRecord::defensiveCopy)
    private val catalogAliasSnapshots = catalogAliases.mapValues { (_, record) -> record.defensiveCopy() }

    private val exactCandidatesById: Map<String, List<ModelMetadataRecord>> = buildMap {
        catalogAliasSnapshots.forEach { (alias, record) -> addCandidate(alias, record) }
        recordSnapshots.forEach { record ->
            addCandidate(record.canonicalModelId, record)
            record.exactAliases.forEach { alias -> addCandidate(alias, record) }
        }
    }.mapValues { (_, candidates) -> candidates.distinct() }

    fun resolve(
        remoteModelId: String,
        providerId: String? = null,
        sourceProviderId: String? = null,
        ownedBy: String? = null,
        providerMetadata: ModelMetadataOverride? = null,
        userOverride: ModelMetadataOverride? = null,
    ): ResolvedModelMetadata {
        val lookupIds = lookupCandidates(remoteModelId, ownedBy)
        val normalizedId = lookupIds.first()
        val remoteId = remoteModelId
        val allCandidates = (lookupIds.flatMap { exactCandidatesById[it].orEmpty() } + listOfNotNull(mediaModelCatalogRecord(remoteId))).distinct()
        val candidateResolution = resolveCatalogCandidates(allCandidates, sourceProviderId)
        val exactRecords = candidateResolution.fieldRecords
        val diagnostics = if (candidateResolution.ambiguous) {
            setOf(AMBIGUOUS_EXACT_MATCH)
        } else {
            emptySet()
        }

        val displayName = resolveField(
            catalog = exactField(exactRecords) { it.displayName },
            providerValue = providerMetadata?.displayName,
            userValue = userOverride?.displayName,
            fallback = remoteId,
        )
        val workload = resolveField(
            catalog = exactFieldPreferKnown(exactRecords, ModelWorkload.UNKNOWN) { it.workload },
            providerValue = providerMetadata?.workload,
            userValue = userOverride?.workload,
            fallback = ModelWorkload.UNKNOWN,
        )
        val contextTokens = resolveNullableField(
            catalog = exactField(exactRecords) { it.contextTokens },
            providerValue = providerMetadata?.contextTokens,
            userValue = userOverride?.contextTokens,
        )
        val outputTokens = resolveNullableField(
            catalog = exactField(exactRecords) { it.outputTokens },
            providerValue = providerMetadata?.outputTokens,
            userValue = userOverride?.outputTokens,
        )
        val providerInputTokens = resolveNullableField(
            catalog = exactField(exactRecords) { it.inputTokens },
            providerValue = providerMetadata?.inputTokens,
            userValue = userOverride?.inputTokens,
        )
        val capabilityResolution = ModelCapability.values().associateWith { capability ->
            resolveCapability(capability, exactRecords, providerMetadata, userOverride)
        }
        val exactFamily = exactField(exactRecords) { it.familyName }
        val familyName = exactFamily.value ?: familyNameFor(normalizedId)
        val familySource = when {
            exactFamily.value != null -> exactFamily.source
            familyName != null -> MetadataSource.FAMILY
            else -> MetadataSource.FALLBACK
        }
        val canonical = candidateResolution.canonicalRecord
        val offering = candidateResolution.offeringRecord
        val knowledgeCutoff = exactField(exactRecords) { it.knowledgeCutoff }

        return ResolvedModelMetadata(
            remoteModelId = remoteId,
            canonicalModelId = canonical?.canonicalModelId,
            offeringId = offering?.canonicalModelId,
            displayName = displayName.value,
            familyName = familyName,
            workload = workload.value,
            capabilities = immutableMap(capabilityResolution.mapValues { it.value.value }),
            contextTokens = contextTokens.value,
            inputTokens = providerInputTokens.value,
            outputTokens = outputTokens.value,
            knowledgeCutoff = knowledgeCutoff.value,
            sourceByField = immutableMap(buildMap {
                put("canonicalModelId", canonical?.source ?: MetadataSource.FALLBACK)
                put("offeringId", offering?.source ?: MetadataSource.FALLBACK)
                put("displayName", displayName.source)
                put("familyName", familySource)
                put("workload", workload.source)
                put("contextTokens", contextTokens.source)
                put(
                    "inputTokens",
                    providerInputTokens.source,
                )
                put("outputTokens", outputTokens.source)
                put(
                    "knowledgeCutoff",
                    knowledgeCutoff.source,
                )
                capabilityResolution.forEach { (capability, resolution) ->
                    put("capabilities.${capability.name}", resolution.source)
                }
            }),
            diagnostics = immutableSet(diagnostics),
        )
    }

    private fun resolveCatalogCandidates(
        candidates: List<ModelMetadataRecord>,
        sourceProviderId: String?,
    ): CandidateResolution {
        val globalCandidates = candidates.filter { it.providerScope == null }
        val scopedCandidates = sourceProviderId?.let { scope ->
            candidates.filter { it.providerScope == scope }
        }.orEmpty()
        if (scopedCandidates.isEmpty()) {
            val ambiguous = isTopPriorityAmbiguous(globalCandidates)
            val records = exactRecordsFor(globalCandidates)
            return CandidateResolution(
                fieldRecords = records,
                canonicalRecord = records.firstOrNull(),
                offeringRecord = null,
                ambiguous = ambiguous,
            )
        }
        if (isTopPriorityAmbiguous(scopedCandidates)) {
            return CandidateResolution(emptyList(), null, null, ambiguous = true)
        }
        val scopedRecords = exactRecordsFor(scopedCandidates)
        val offering = scopedRecords.firstOrNull()
            ?: return CandidateResolution(emptyList(), null, null, ambiguous = true)
        val matchingGlobalCandidates = globalCandidates.filter { global ->
            normalizeRemoteModelId(global.canonicalModelId) == normalizeRemoteModelId(offering.canonicalModelId)
        }
        val globalAmbiguous = isTopPriorityAmbiguous(matchingGlobalCandidates)
        val globalRecords = exactRecordsFor(matchingGlobalCandidates)
        return CandidateResolution(
            fieldRecords = scopedRecords + globalRecords,
            canonicalRecord = globalRecords.firstOrNull(),
            offeringRecord = offering,
            ambiguous = globalAmbiguous,
        )
    }

    private fun lookupCandidates(remoteModelId: String, ownedBy: String?): List<String> {
        val exact = normalizeRemoteModelId(remoteModelId)
        val prefixCandidate = when {
            ownedBy.equals("NEWAPI", ignoreCase = true) && remoteModelId.startsWith("newapi/") ->
                normalizeRemoteModelId(remoteModelId.removePrefix("newapi/"))
            ownedBy.equals("OpenAI ChatGPT", ignoreCase = true) && remoteModelId.startsWith("openai-chatgpt/") ->
                normalizeRemoteModelId(remoteModelId.removePrefix("openai-chatgpt/"))
            else -> null
        }
        return listOfNotNull(exact, prefixCandidate).distinct()
    }

    internal fun resolveExactOrNull(remoteModelId: String): ResolvedModelMetadata? {
        val resolved = resolve(remoteModelId)
        return resolved.takeIf {
            it.canonicalModelId != null && AMBIGUOUS_EXACT_MATCH !in it.diagnostics
        }
    }

    private fun MutableMap<String, MutableList<ModelMetadataRecord>>.addCandidate(
        alias: String,
        record: ModelMetadataRecord,
    ) {
        getOrPut(normalizeRemoteModelId(alias)) { mutableListOf() }.add(record)
    }

    private fun resolveCapability(
        capability: ModelCapability,
        exactRecords: List<ModelMetadataRecord>,
        providerMetadata: ModelMetadataOverride?,
        userOverride: ModelMetadataOverride?,
    ): FieldResolution<SupportState> {
        var result = exactFieldPreferKnown(exactRecords, SupportState.UNKNOWN) {
            it.capabilities[capability]
        }
            .withFallback(SupportState.UNKNOWN)
        if (providerMetadata?.capabilities?.containsKey(capability) == true) {
            result = FieldResolution(providerMetadata.capabilities.getValue(capability), MetadataSource.PROVIDER)
        }
        if (userOverride?.capabilities?.containsKey(capability) == true) {
            result = FieldResolution(userOverride.capabilities.getValue(capability), MetadataSource.USER)
        }
        return result
    }

    private fun <T : Any> resolveField(
        catalog: FieldResolution<T?>,
        providerValue: T?,
        userValue: T?,
        fallback: T,
    ): FieldResolution<T> {
        var result = catalog.withFallback(fallback)
        if (providerValue != null) result = FieldResolution(providerValue, MetadataSource.PROVIDER)
        if (userValue != null) result = FieldResolution(userValue, MetadataSource.USER)
        return result
    }

    private fun <T> resolveNullableField(
        catalog: FieldResolution<T?>,
        providerValue: T?,
        userValue: T?,
    ): FieldResolution<T?> {
        var result = catalog
        if (providerValue != null) result = FieldResolution(providerValue, MetadataSource.PROVIDER)
        if (userValue != null) result = FieldResolution(userValue, MetadataSource.USER)
        return result
    }

    private fun familyNameFor(normalizedId: String): String? {
        val modelName = normalizedId.substringAfterLast('/')
        return if (
            modelName == "deepseek" ||
            modelName.startsWith("deepseek-") ||
            modelName.startsWith("deepseek_") ||
            modelName.startsWith("deepseek.")
        ) {
            "DeepSeek"
        } else {
            null
        }
    }

    private fun exactSourcePriority(source: MetadataSource): Int = when (source) {
        MetadataSource.INFINITY_OVERRIDE -> 4
        MetadataSource.MODELS_DEV -> 3
        MetadataSource.LITELLM -> 2
        MetadataSource.OPENROUTER -> 1
        else -> 0
    }

    private data class FieldResolution<T>(val value: T, val source: MetadataSource)

    private data class CandidateResolution(
        val fieldRecords: List<ModelMetadataRecord>,
        val canonicalRecord: ModelMetadataRecord?,
        val offeringRecord: ModelMetadataRecord?,
        val ambiguous: Boolean,
    )

    private fun <T> exactField(
        records: List<ModelMetadataRecord>,
        selector: (ModelMetadataRecord) -> T?,
    ): FieldResolution<T?> {
        val record = records.firstOrNull { selector(it) != null }
        return FieldResolution(record?.let(selector), record?.source ?: MetadataSource.FALLBACK)
    }

    private fun <T> exactFieldPreferKnown(
        records: List<ModelMetadataRecord>,
        unknown: T,
        selector: (ModelMetadataRecord) -> T?,
    ): FieldResolution<T?> {
        val record = records.firstOrNull { selector(it)?.let { value -> value != unknown } == true }
            ?: records.firstOrNull { selector(it) != null }
        return FieldResolution(record?.let(selector), record?.source ?: MetadataSource.FALLBACK)
    }

    private fun <T : Any> FieldResolution<T?>.withFallback(fallback: T): FieldResolution<T> =
        if (value == null) FieldResolution(fallback, MetadataSource.FALLBACK) else FieldResolution(value, source)

    private fun exactRecordsFor(candidates: List<ModelMetadataRecord>): List<ModelMetadataRecord> {
        if (isTopPriorityAmbiguous(candidates)) return emptyList()
        return candidates
            .groupBy { exactSourcePriority(it.source) }
            .toSortedMap(compareByDescending { it })
            .values
            .mapNotNull { it.singleOrNull() }
    }

    private fun isTopPriorityAmbiguous(candidates: List<ModelMetadataRecord>): Boolean {
        val highestPriority = candidates.maxOfOrNull { exactSourcePriority(it.source) } ?: return false
        return candidates.count { exactSourcePriority(it.source) == highestPriority } > 1
    }

    private companion object {
        const val AMBIGUOUS_EXACT_MATCH = "ambiguous_exact_match"
    }
}

internal fun normalizeRemoteModelId(value: String): String = value
    .trim()
    .removePrefix("models/")
    .lowercase()

private fun ModelMetadataRecord.defensiveCopy(): ModelMetadataRecord = copy(
    exactAliases = immutableSet(exactAliases),
    capabilities = immutableMap(capabilities),
)

private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))

private fun <T> immutableSet(source: Set<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(source))
