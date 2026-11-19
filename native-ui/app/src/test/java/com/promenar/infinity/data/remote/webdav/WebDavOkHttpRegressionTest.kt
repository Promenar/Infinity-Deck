package com.promenar.infinity.data.remote.webdav

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.LoopbackUploadServer
import io.ktor.client.engine.okhttp.OkHttp
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test

class WebDavOkHttpRegressionTest {
    @Test fun `真实 OkHttp 完整写入流式 PUT 后原子 MOVE`() = runBlocking(Dispatchers.IO) {
        val bytes = ByteArray(200_031) { (it * 7).toByte() }
        val file = Files.createTempFile("webdav-test", ".bin")
        try {
            Files.write(file, bytes)
            LoopbackUploadServer(201 to "", 201 to "").use { server ->
                val config = WebDavConfig("${server.url}/backups", "test-user", "test-password")
                val engine = OkHttp.create()
                try {
                    KtorWebDavBackupClient(engine).uploadAtomic(config, "infinity_backup_0000000001000.iwb", file)
                } finally {
                    engine.close()
                }
                val (put, move) = server.requests()
                assertThat(put.line).startsWith("PUT /backups/infinity_backup_0000000001000.iwb.tmp-")
                assertThat(put.body).isEqualTo(bytes)
                assertThat(move.line).startsWith("MOVE /backups/infinity_backup_0000000001000.iwb.tmp-")
                assertThat(move.headers["destination"]).isEqualTo("${server.url}/backups/infinity_backup_0000000001000.iwb")
            }
        } finally {
            Files.deleteIfExists(file)
        }
        Unit
    }
}
