package com.promenar.infinity.data.local.inference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

class ModelDownloaderTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun genericDownloadCancelsKeepsPartialAndUsesValidatedRangeOnResume() = runBlocking {
        val directory = Files.createTempDirectory("download-resume-").toFile()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val ranges = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val range = chain.request().header("Range")
            ranges += range
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (range == null) 200 else 206).message("OK")
                .header("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                .body(bytes.copyOfRange(start, bytes.size).toResponseBody()).build()
        }.build()
        try {
            val downloader = ModelDownloader(directory, client)
            val failed = downloader.downloadFile("https://example.invalid/model", "model.tar.bz2", directory, hash(bytes), bytes.size.toLong(),
                onProgress = { done, _ -> if (done > 0) downloader.cancel() })
            assertTrue(failed.exceptionOrNull() is CancellationException)
            val partial = File(directory, "model.tar.bz2.part")
            val retained = partial.length()
            assertTrue(retained in 1 until bytes.size.toLong())
            val completed = downloader.downloadFile("https://example.invalid/model", "model.tar.bz2", directory, hash(bytes), bytes.size.toLong()).getOrThrow()
            assertEquals(listOf(null, "bytes=$retained-"), ranges)
            assertArrayEquals(bytes, completed.readBytes())
            assertFalse(partial.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun serverIgnoringRangeRestartsAndHashMismatchDeletesPartial() = runBlocking {
        val directory = Files.createTempDirectory("download-restart-").toFile()
        val bytes = "archive".toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        try {
            val downloader = ModelDownloader(directory, client)
            File(directory, "model.part").writeText("old")
            assertArrayEquals(bytes, downloader.downloadFile("https://example.invalid/model", "model", directory, hash(bytes), bytes.size.toLong()).getOrThrow().readBytes())
            val failed = downloader.downloadFile("https://example.invalid/model", "wrong", directory, "0".repeat(64), bytes.size.toLong())
            assertTrue(failed.exceptionOrNull() is ModelDownloadIntegrityException)
            assertFalse(File(directory, "wrong.part").exists())
            assertFalse(File(directory, "wrong").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun completePartialIsVerifiedWithoutNetworkAndBadRangeNeverAppends() = runBlocking {
        val directory = Files.createTempDirectory("download-complete-").toFile()
        var calls = 0
        val bytes = "complete".toByteArray()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("OK")
                .header("Content-Range", "bytes 0-7/8").body(bytes.toResponseBody()).build()
        }.build()
        try {
            val downloader = ModelDownloader(directory, client)
            File(directory, "model.part").writeBytes(bytes)
            downloader.downloadFile("https://example.invalid/model", "model", directory, hash(bytes), bytes.size.toLong()).getOrThrow()
            assertEquals(0, calls)
            File(directory, "another.part").writeText("old")
            assertTrue(downloader.downloadFile("https://example.invalid/model", "another", directory, hash(bytes), bytes.size.toLong()).isFailure)
            assertEquals("old", File(directory, "another.part").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun ggufEntryPointPreservesModelMetadataAndRejectsOtherExtensions() = runBlocking {
        val directory = Files.createTempDirectory("download-gguf-").toFile()
        val bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).put("GGUF".toByteArray())
            .putInt(3).putLong(0).putLong(0).array()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        try {
            val downloader = ModelDownloader(directory, client)
            val model = downloader.download("https://example.invalid/model", "fixture.gguf", hash(bytes), bytes.size.toLong()).getOrThrow()
            assertEquals("GGUF", model.format)
            assertEquals(File(directory, "fixture.gguf").absolutePath, model.filePath)
            assertEquals(bytes.size.toLong(), model.sizeBytes)
            assertTrue(downloader.download("https://example.invalid/model", "fixture.bin").isFailure)
        } finally { directory.deleteRecursively() }
    }
}
