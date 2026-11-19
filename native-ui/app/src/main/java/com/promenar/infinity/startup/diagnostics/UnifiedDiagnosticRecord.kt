package com.promenar.infinity.startup.diagnostics

import com.promenar.infinity.BuildConfig
import com.promenar.infinity.distribution.DistributionCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class UnifiedDiagnosticRecord(
    val seq: Long = 0,
    val schemaVersion: Int = 2,
    val timestampMs: Long,
    val event: String,
    val fields: JsonObject = JsonObject(emptyMap()),
    val exception: DiagnosticEvent? = null,
) {
    fun toJsonLine(): String = diagnosticJson.encodeToString(this)
}

internal val diagnosticJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal fun diagnosticBuildFields(): JsonObject =
    JsonObject((DistributionCapabilities.diagnosticFields() + mapOf(
        "debug" to BuildConfig.DEBUG,
        "exactAlarmAvailable" to DistributionCapabilities.exactAlarmAvailable,
        "backgroundKeepAliveAvailable" to DistributionCapabilities.backgroundKeepAliveAvailable,
        "linuxRuntimeAvailable" to DistributionCapabilities.linuxRuntimeAvailable,
        "localInferenceAvailable" to BuildConfig.LOCAL_INFERENCE_AVAILABLE,
    )).mapValues { (_, value) ->
        when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    })
