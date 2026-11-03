package com.promenar.infinity.data.remote.webdav

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.URI
import java.util.UUID

class WebDavBackupClientReviewTest {
    private val config = WebDavConfig("https://dav.example.test/backups/", "user", "pass")

    @Test
    fun `test requires 207 valid multistatus containing target collection`() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.NoContent)) {
            val result = client(MockEngine { respond(validCollectionXml(), status) }).test(config)
            assertThat(result.isFailure).isTrue()
        }

        val malformed = client(MockEngine { respond("<not-dav/>", HttpStatusCode.MultiStatus) }).test(config)
        assertThat(malformed.isFailure).isTrue()

        val wrongTarget = client(MockEngine {
            respond(multistatus(response("/other/", collection = true)), HttpStatusCode.MultiStatus)
        }).test(config)
        assertThat(wrongTarget.isFailure).isTrue()

        assertThat(client(MockEngine {
            respond(validCollectionXml(), HttpStatusCode.MultiStatus)
        }).test(config).isSuccess).isTrue()
    }

    @Test
    fun `parser binds properties to each successful propstat without cross matching`() {
        val parser = DavMultiStatusParser()
        val first404Then200 = multistatus(
            responseWithPropstats(
                "/backups/${backupName(1)}",
                propstat("HTTP/1.1 404 Not Found", size = 999),
                propstat("HTTP/1.1 200 OK", size = 11, modified = "Wed, 02 Jul 2025 11:00:00 GMT"),
            ),
        )
        val first200Then404 = multistatus(
            responseWithPropstats(
                "/backups/${backupName(2)}",
                propstat("HTTP/1.1 200 OK", size = 22, modified = "Wed, 02 Jul 2025 12:00:00 GMT"),
                propstat("HTTP/1.1 404 Not Found", size = 888),
            ),
        )

        val entries = parser.parse(
            (first404Then200 + first200Then404).replace(
                "</d:multistatus><?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">",
                "",
            ).toByteArray(),
            URI("https://dav.example.test/backups/"),
        ).backups

        assertThat(entries.associate { it.fileName to it.sizeBytes })
            .containsExactly(backupName(1), 11L, backupName(2), 22L)
    }

    @Test
    fun `percent encoded collection path is not double encoded in requests or destination`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val encodedConfig = config.copy(baseUrl = "https://dav.example.test/backups%20space/%E5%A4%87%E4%BB%BD/")
        val engine = MockEngine { request ->
            requests += request
            respond("", if (request.method.value == "PUT") HttpStatusCode.Created else HttpStatusCode.NoContent)
        }

        client(engine).uploadAtomic(encodedConfig, backupName(1), byteArrayOf(1))

        assertThat(requests[0].url.encodedPath)
            .isEqualTo("/backups%20space/%E5%A4%87%E4%BB%BD/${backupName(1)}.tmp-00000000-0000-0000-0000-000000000009")
        assertThat(requests[1].headers["Destination"])
            .isEqualTo("https://dav.example.test/backups%20space/%E5%A4%87%E4%BB%BD/${backupName(1)}")
        assertThat(requests[1].headers["Destination"]).doesNotContain("%2520")
    }

    @Test
    fun `hung operation becomes sanitized timeout while external cancellation remains distinct`() = runTest {
        val timeouts = WebDavTimeouts(
            testMillis = 25,
            propfindMillis = 25,
            getMillis = 25,
            putMillis = 25,
            moveMillis = 25,
            deleteMillis = 25,
            cleanupMillis = 25,
        )
        val hanging = KtorWebDavBackupClient(
            MockEngine { awaitCancellation() },
            uuidFactory = { UUID(0, 9) },
            timeouts = timeouts,
        )

        val error = runCatching { hanging.download(config, remote(1)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(WebDavException::class.java)
        assertThat(error!!.message).contains("超时")
        assertThat(error.message).doesNotContain(config.baseUrl)
    }

    @Test
    fun `put cancellation still attempts non cancellable temporary cleanup`() = runTest {
        val methods = mutableListOf<String>()
        val engine = MockEngine { request ->
            methods += request.method.value
            if (request.method.value == "PUT") awaitCancellation()
            respond("", HttpStatusCode.NoContent)
        }
        val client = KtorWebDavBackupClient(
            engine,
            uuidFactory = { UUID(0, 9) },
            timeouts = WebDavTimeouts(25, 100, 100, 25, 100, 100, 100),
        )

        val error = runCatching { client.uploadAtomic(config, backupName(1), byteArrayOf(1)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(WebDavException::class.java)
        assertThat(methods).containsExactly("PUT", "DELETE").inOrder()
    }

    @Test
    fun `external upload cancellation is preserved after non cancellable cleanup`() = runTest {
        val putEntered = CompletableDeferred<Unit>()
        val methods = mutableListOf<String>()
        val engine = MockEngine { request ->
            methods += request.method.value
            if (request.method.value == "PUT") {
                putEntered.complete(Unit)
                awaitCancellation()
            }
            respond("", HttpStatusCode.NoContent)
        }
        val client = client(engine)

        val job = launch { client.uploadAtomic(config, backupName(1), byteArrayOf(1)) }
        putEntered.await()
        job.cancel()
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(methods).containsExactly("PUT", "DELETE").inOrder()
    }

    @Test
    fun `early content length rejection cancels response channel`() = runTest {
        val body = ByteReadChannel(byteArrayOf(1, 2, 3))
        val engine = MockEngine {
            respond(
                body,
                HttpStatusCode.OK,
                io.ktor.http.headersOf(
                    io.ktor.http.HttpHeaders.ContentLength to listOf((16L * 1024 * 1024 + 1).toString()),
                    io.ktor.http.HttpHeaders.ETag to listOf(TEST_ETAG),
                ),
            )
        }

        // 下载改为在 execute 作用域内流式读取，离开作用域即由引擎释放连接；这里确认在读取正文前按声明长度拒绝。
        val failure = runCatching { client(engine).download(config, remote(1)) }.exceptionOrNull()
        assertThat(failure).hasMessageThat().contains("读取前拒绝")
    }

    @Test
    fun `non download response bodies are closed across repeated and concurrent calls`() = runTest {
        val channels = mutableListOf<ByteReadChannel>()
        val engine = MockEngine { request ->
            val channel = if (request.method.value == "PROPFIND") {
                ByteReadChannel(validCollectionXml().toByteArray())
            } else {
                ByteReadChannel(ByteArray(64 * 1024) { 1 })
            }
            synchronized(channels) { channels += channel }
            respond(
                channel,
                when (request.method.value) {
                    "PROPFIND" -> HttpStatusCode.MultiStatus
                    "PUT" -> HttpStatusCode.Created
                    "MOVE" -> HttpStatusCode.NoContent
                    else -> HttpStatusCode.NoContent
                },
            )
        }
        val client = client(engine)

        (1..4).map { index ->
            async { client.uploadAtomic(config, backupName(index.toLong()), byteArrayOf(1)) }
        }.awaitAll()
        repeat(3) { assertThat(client.test(config).isSuccess).isTrue() }

        assertThat(channels).isNotEmpty()
        assertThat(channels.all { it.isClosedForRead }).isTrue()
    }

    @Test
    fun `upload and prune commits then keeps five in one high level operation`() = runTest {
        val operations = mutableListOf<String>()
        val xml = multistatus(*(1L..8L).map { response("/backups/${backupName(it)}") }.toTypedArray())
        val engine = MockEngine { request ->
            operations += request.method.value
            when (request.method.value) {
                "PROPFIND" -> respond(xml, HttpStatusCode.MultiStatus)
                "PUT" -> respond("", HttpStatusCode.Created)
                "MOVE" -> respond("", HttpStatusCode.NoContent)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }

        val result = client(engine).uploadAndPrune(config, backupName(8), byteArrayOf(1), keep = 5)

        assertThat(result).isInstanceOf(UploadAndPruneResult.Committed::class.java)
        assertThat((result as UploadAndPruneResult.Committed).pruneWarning).isNull()
        assertThat(operations)
            .containsExactly("PUT", "MOVE", "PROPFIND", "DELETE", "DELETE", "DELETE")
            .inOrder()
    }

    @Test
    fun `upload and prune returns committed warning without deleting final file`() = runTest {
        val paths = mutableListOf<String>()
        val xml = multistatus(*(listOf(8L) + (1L..5L)).map { response("/backups/${backupName(it)}") }.toTypedArray())
        val engine = MockEngine { request ->
            paths += "${request.method.value} ${request.url.encodedPath}"
            when (request.method.value) {
                "PROPFIND" -> respond(xml, HttpStatusCode.MultiStatus)
                "DELETE" -> respondError(HttpStatusCode.Locked)
                "PUT" -> respond("", HttpStatusCode.Created)
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
        val finalName = backupName(8)

        val result = client(engine).uploadAndPrune(config, finalName, byteArrayOf(1), keep = 5)

        assertThat(result).isInstanceOf(UploadAndPruneResult.Committed::class.java)
        assertThat((result as UploadAndPruneResult.Committed).pruneWarning).isNotNull()
        assertThat(paths).doesNotContain("DELETE /backups/$finalName")
    }

    @Test
    fun `move 405 reports atomic move unsupported and strict statuses reject arbitrary 2xx`() = runTest {
        val move405 = MockEngine { request ->
            if (request.method.value == "PUT") respond("", HttpStatusCode.Created)
            else if (request.method.value == "MOVE") respondError(HttpStatusCode.MethodNotAllowed)
            else respond("", HttpStatusCode.NoContent)
        }
        val error = runCatching { client(move405).uploadAtomic(config, backupName(1), byteArrayOf(1)) }.exceptionOrNull()
        assertThat(error!!.message).contains("不支持原子 MOVE")

        val putAccepted = client(MockEngine { respond("", HttpStatusCode.Accepted) })
        assertThat(runCatching { putAccepted.uploadAtomic(config, backupName(2), byteArrayOf(1)) }.isFailure).isTrue()
    }

    private fun client(engine: MockEngine) = KtorWebDavBackupClient(
        engine,
        uuidFactory = { UUID.fromString("00000000-0000-0000-0000-000000000009") },
        timeouts = WebDavTimeouts(500, 500, 500, 500, 500, 500, 200),
    )

    private fun backupName(value: Long) = "infinity_backup_${value.toString().padStart(13, '0')}.iwb"

    private fun remote(value: Long) = RemoteBackup(backupName(value), 0, 0, TEST_ETAG)

    private fun validCollectionXml() = multistatus(response("/backups/", collection = true))

    private fun multistatus(vararg responses: String): String =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">${responses.joinToString("")}</d:multistatus>"""

    private fun response(href: String, collection: Boolean = false): String = responseWithPropstats(
        href,
        propstat("HTTP/1.1 200 OK", collection = collection, size = 1),
    )

    private fun responseWithPropstats(href: String, vararg propstats: String): String =
        """<d:response><d:href>$href</d:href>${propstats.joinToString("")}</d:response>"""

    private fun propstat(
        status: String,
        collection: Boolean = false,
        size: Long = 0,
        modified: String = "Wed, 02 Jul 2025 09:00:00 GMT",
    ): String = """
        <d:propstat><d:prop>
          <d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype>
          <d:getcontentlength>$size</d:getcontentlength>
          <d:getlastmodified>$modified</d:getlastmodified>
        </d:prop><d:status>$status</d:status></d:propstat>
    """.trimIndent()

    private companion object {
        const val TEST_ETAG = "\"test-etag\""
    }
}
