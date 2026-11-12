package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.media.download.WorkspaceMediaDownloader
import com.promenar.infinity.data.media.MediaTypeSniffer
import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.network.AgentNetworkPolicy
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudMediaFilesTest {
    @get:Rule val temp = TemporaryFolder()
    // ISO BMFF 的 24 字节 ftyp box：主品牌 isom，兼容品牌 isom/mp42。
    private val mp4Header = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(0, 0, 2, 0) + "isommp42".toByteArray(Charsets.US_ASCII)
    private val request = MediaGenerationRequest("c", GenerationMode.TEXT, "p", emptyList(), "s", sessionId = "session", outputDir = "productions/test/takes")
    private class Files(private val root: File, private val kind: MediaKind) : WorkspaceMediaStore, WorkspaceMediaResolver {
        var saves = 0
        override suspend fun productionPath(sessionId: String, path: String) = "/$path"
        override suspend fun resolve(sessionId: String, path: String): MediaResolution {
            val f = File(root, path.trimStart('/'))
            return if (f.isFile) MediaResolution.Available(MediaRef(path, if (kind == MediaKind.VIDEO) "video/mp4" else "audio/mpeg", kind, f.length()), MediaSource.LocalFile(f))
            else MediaResolution.Unavailable(path, MediaUnavailableReason.NOT_FOUND)
        }
        override suspend fun open(resolution: MediaResolution.Available): InputStream = (resolution.source as MediaSource.LocalFile).file.inputStream()
        override suspend fun save(sessionId: String, payload: MediaPayload, origin: MediaOrigin, mimeTypeHint: String?, suggestedName: String?, targetPath: String?): MediaRef {
            val path = requireNotNull(targetPath)
            val f = File(root, path.trimStart('/')); f.parentFile!!.mkdirs()
            val input = when (payload) { is MediaPayload.Bytes -> payload.bytes.inputStream(); is MediaPayload.Stream -> payload.open() }
            input.use { source -> f.outputStream().use { source.copyTo(it) } }
            saves++
            return MediaRef(path, mimeTypeHint!!, kind, f.length())
        }
    }
    private fun downloader(code: Int = 200, declaredLength: Long? = null, payload: ByteArray = mp4Header, mime: String = "application/octet-stream"): WorkspaceMediaDownloader {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .header("Content-Type", mime)
                .apply { declaredLength?.let { header("Content-Length", it.toString()) } }
                .body(payload.toResponseBody(mime.toMediaType())).build()
        }.build()
        val policy = AgentNetworkPolicy(resolver = { arrayOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) })
        return WorkspaceMediaDownloader(client, MediaFetchUrlPolicy { false }, { false }, policy)
    }
    @Test fun downloadsIntoOutputDirectoryAndResumesWithoutOverwriting() = runTest {
        assertEquals(MediaKind.VIDEO, MediaTypeSniffer.sniff(mp4Header, null).kind)
        val files = Files(temp.newFolder(), MediaKind.VIDEO)
        val store = WorkspaceCloudMediaOutputStore(downloader(), files, files)
        val output = RemoteOutput("https://cdn.test/v.mp4", InputKind.VIDEO)
        val path = store.save(request, "task", 0, output)
        assertTrue(path.startsWith("productions/test/takes/"))
        val resolved = files.resolve("session", "/$path") as MediaResolution.Available
        assertArrayEquals(mp4Header, files.open(resolved).use { it.readBytes() })
        assertEquals(path, store.save(request, "task", 0, output))
        assertEquals(1, files.saves)
    }
    @Test fun rejectsTraversalBeforeDownload() = runTest {
        val files = Files(temp.newFolder(), MediaKind.VIDEO)
        val store = WorkspaceCloudMediaOutputStore(downloader(), files, files)
        try { store.save(request.copy(outputDir = "../outside"), "task", 0, RemoteOutput("https://cdn.test/v.mp4", InputKind.VIDEO)); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, files.saves)
    }
    @Test fun expiredLinkAndOversizedResponseDoNotCreateFiles() = runTest {
        val files = Files(temp.newFolder(), MediaKind.VIDEO)
        for ((client, expected) in listOf(downloader(code = 403) to "HTTP_ERROR", downloader(declaredLength = 300L * 1024 * 1024) to "TOO_LARGE")) {
            try { WorkspaceCloudMediaOutputStore(client, files, files).save(request, "task", 0, RemoteOutput("https://cdn.test/v.mp4", InputKind.VIDEO)); fail() }
            catch (e: WorkspaceMediaException) { assertEquals(expected, e.code) }
        }
        assertEquals(0, files.saves)
    }
    @Test fun localReferenceIsEncodedAndDoesNotExposeFileSystemPath() = runTest {
        val root = temp.newFolder(); File(root, "ref.mp3").writeText("ID3audio")
        val files = Files(root, MediaKind.AUDIO)
        val location = WorkspaceCloudMediaInputSource(files).remoteLocation(request, MediaInput("ref.mp3", InputKind.AUDIO, InputRole.REFERENCE_VOICE))
        assertTrue(location.startsWith("data:audio/mpeg;base64,"))
        assertFalse(location.contains(root.path))
    }
    @Test fun savesSpeechWithRequestedAudioExtension() = runTest {
        val files = Files(temp.newFolder(), MediaKind.AUDIO)
        val bytes = "RIFF0000WAVEaudio".toByteArray()
        val store = WorkspaceCloudMediaOutputStore(downloader(payload = bytes, mime = "audio/wav"), files, files)
        val path = store.saveWithFormat(request, "speech-task", 0, RemoteOutput("https://cdn.test/download", InputKind.AUDIO), "wav")
        assertTrue(path.endsWith(".wav"))
        assertEquals(1, files.saves)
        val ref = files.resolve("session", "/$path") as MediaResolution.Available
        assertArrayEquals(bytes, files.open(ref).use { it.readBytes() })
    }
}
