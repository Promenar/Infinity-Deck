package com.promenar.infinity.data.model.catalog

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CatalogUpdatePhase { IDLE, UPDATING, CURRENT, FAILED, APPLY_FAILED }

data class CatalogUpdateStatus(
    val phase: CatalogUpdatePhase = CatalogUpdatePhase.IDLE,
    val generatedAt: String? = null,
    val recordCount: Int = 0,
    val version: Long? = null,
    val cached: Boolean = false,
)

internal fun interface CatalogTransport {
    suspend fun read(file: String, maxBytes: Int): ByteArray
}

/** 独立无凭据客户端，URL 只能是固定目录中的签名信封或内容摘要文件。 */
internal class PagesCatalogTransport : CatalogTransport {
    override suspend fun read(file: String, maxBytes: Int): ByteArray {
        require(file == "manifest.json" || file.matches(Regex("catalog-[a-f0-9]{64}\\.json")))
        val client = HttpClient(OkHttp) {
            followRedirects = false
            engine { config { followRedirects(false); followSslRedirects(false) } }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 20_000
            }
        }
        try {
            return client.prepareGet("https://promenar.github.io/Infinity-Deck/model-catalog/v1/$file").execute { response ->
                require(response.status.value == 200) { "目录服务返回非成功状态" }
                response.headers["Content-Length"]?.toLongOrNull()?.let {
                    require(it in 1..maxBytes.toLong()) { "目录响应过大" }
                }
                val channel = response.bodyAsChannel()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count == -1) break
                    require(output.size() + count <= maxBytes) { "目录响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { client.close() }
    }
}

internal class ModelCatalogUpdater(
    private val cache: ModelCatalogCache,
    private val verifier: CatalogEnvelopeVerifier,
    private val transport: CatalogTransport,
    private val lastCheckedAt: () -> Long,
    private val markCheckedAt: (Long) -> Unit,
    private val onInstalled: (PublishedModelCatalog) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow(CatalogUpdateStatus())
    val status = _status.asStateFlow()
    private var loaded = false
    private var lastAttemptAt: Long? = null

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) {
                var cached: VerifiedCatalog? = null
                try {
                    cached = cache.load()
                    cached?.let {
                        onInstalled(it.catalog)
                        _status.value = it.toStatus(CatalogUpdatePhase.IDLE)
                    }
                    loaded = true
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _status.value = cached?.toStatus(CatalogUpdatePhase.APPLY_FAILED)
                        ?: _status.value.copy(phase = CatalogUpdatePhase.FAILED)
                    return@withLock
                }
            }
            val now = clock()
            val checked = lastCheckedAt()
            val recentlyChecked = checked > 0 && now >= checked && now - checked < DAY_MILLIS
            val recentlyAttempted = lastAttemptAt?.let { now >= it && now - it < HOUR_MILLIS } == true
            if (!force && (recentlyChecked || recentlyAttempted)) return@withLock
            lastAttemptAt = now
            val previous = _status.value
            _status.value = previous.copy(phase = CatalogUpdatePhase.UPDATING)
            var committed: VerifiedCatalog? = null
            try {
                val envelope = transport.read("manifest.json", MAX_MANIFEST_BYTES)
                val manifest = verifier.readManifest(envelope)
                cache.checkVersion(manifest)
                val existing = cache.load()
                val verified = if (existing?.manifest == manifest) existing else {
                    verifier.verify(envelope, transport.read(manifest.catalogFile, manifest.catalogBytes))
                }
                cache.install(verified)
                committed = verified
                onInstalled(verified.catalog)
                markCheckedAt(now)
                _status.value = verified.toStatus(CatalogUpdatePhase.CURRENT)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    _status.value = previous
                    throw error
                }
                // 缓存是崩溃恢复的提交点；投影失败明确显示并在下一次操作重新应用。
                loaded = false
                _status.value = committed?.toStatus(CatalogUpdatePhase.APPLY_FAILED)
                    ?: previous.copy(phase = CatalogUpdatePhase.FAILED)
            }
        }
    }

    private fun VerifiedCatalog.toStatus(phase: CatalogUpdatePhase) = CatalogUpdateStatus(
        phase, manifest.generatedAt, manifest.recordCount, manifest.catalogVersion, cached = true,
    )

    companion object {
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val HOUR_MILLIS = 60 * 60 * 1000L

        fun create(application: Application, onInstalled: (PublishedModelCatalog) -> Unit): ModelCatalogUpdater {
            val verifier = CatalogEnvelopeVerifier(catalogPublicKeys())
            val prefs = application.getSharedPreferences("catalog_update_schedule", 0)
            return ModelCatalogUpdater(
                ModelCatalogCache(application.noBackupFilesDir.resolve("model-catalog"), verifier),
                verifier,
                PagesCatalogTransport(),
                lastCheckedAt = { prefs.getLong("checkedAt", 0) },
                markCheckedAt = { prefs.edit().putLong("checkedAt", it).apply() },
                onInstalled = onInstalled,
            )
        }
    }
}

internal fun catalogPublicKeys(): Map<String, ByteArray> {
    val publicKey = Base64.getDecoder().decode(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEzD29At+nig0aL8gom0jbIrgwWlwqm7cbrkkA47AJ8xqtFeq0ccrmYsJxI7Vu12ysijxzM5jFWRnt1LO05662Bg==",
    )
    return mapOf(
        "infinity-catalog-p256-20260922" to publicKey,
        // 旧 keyId 是项目改名前已发布目录的兼容别名。
        "nexara-catalog-p256-20260922" to publicKey,
    )
}

internal fun readCatalogLimited(input: InputStream, limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(result.size() + count <= limit) { "目录缓存过大" }
        result.write(buffer, 0, count)
    }
    return result.toByteArray()
}
