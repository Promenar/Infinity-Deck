package com.promenar.infinity.data.remote.protocol

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException

internal const val MAX_MODEL_LIST_BYTES = 16 * 1024 * 1024
internal const val MAX_REMOTE_MODEL_COUNT = 30_000

internal suspend fun HttpResponse.readBoundedModelListBody(): String? {
    val channel: ByteReadChannel = bodyAsChannel()
    val declaredHeader = headers[HttpHeaders.ContentLength]
    val declared = declaredHeader?.toLongOrNull()
    if (declaredHeader != null && (declared == null || declared !in 0..MAX_MODEL_LIST_BYTES.toLong())) {
        channel.cancel(null)
        return null
    }
    val output = ByteArrayOutputStream(minOf(declared?.toInt() ?: 8192, MAX_MODEL_LIST_BYTES))
    val buffer = ByteArray(8192)
    try {
        while (true) {
            channel.closedCause?.let { throw it }
            var count = channel.readAvailable(buffer)
            if (count == 0) {
                buffer[0] = channel.readByte()
                count = 1
            }
            if (count < 0) {
                channel.closedCause?.let { throw it }
                break
            }
            if (output.size() > MAX_MODEL_LIST_BYTES - count) {
                channel.cancel(null)
                return null
            }
            output.write(buffer, 0, count)
        }
    } catch (cancelled: CancellationException) {
        channel.cancel(cancelled)
        throw cancelled
    } catch (_: Exception) {
        channel.cancel(null)
        return null
    }
    return runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray()))
            .toString()
    }.getOrNull()
}
