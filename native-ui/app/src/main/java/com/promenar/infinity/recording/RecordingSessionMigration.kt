package com.promenar.infinity.recording

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun decodeRecordingSession(text: String): RecordingSession {
    val json = Json { ignoreUnknownKeys = true }
    val fields = json.parseToJsonElement(text).jsonObject.toMutableMap()
    val old = fields.remove("glossary")?.jsonPrimitive?.contentOrNull
    if ("hotwords" !in fields && old != null) {
        fields["hotwords"] = json.encodeToJsonElement(
            if (old == "Infinity Deck、Zipformer、GGUF、llama.cpp") emptyList()
            else RecordingHotwordPolicy.splitSession(old),
        )
    }
    val session = json.decodeFromJsonElement<RecordingSession>(JsonObject(fields))
    return session.copy(hotwords = RecordingHotwordPolicy.sessionWords(session.hotwords))
}
