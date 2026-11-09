package com.promenar.infinity.ui.keychain

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.keychain.KeychainHost
import com.promenar.infinity.domain.keychain.KeychainAction
import com.promenar.infinity.domain.keychain.KeychainCategory
import com.promenar.infinity.domain.keychain.KeychainDeleteResult
import com.promenar.infinity.domain.keychain.KeychainItem
import com.promenar.infinity.domain.keychain.KeychainMetadata
import com.promenar.infinity.domain.keychain.KeychainRepository
import com.promenar.infinity.domain.keychain.KeychainUsage
import com.promenar.infinity.utils.InfinityLogger
import java.nio.ByteBuffer
import java.nio.CharBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn

/** 钥匙串页面记录使用时的位置标识，详情页显示为“钥匙串”。 */
internal const val KEYCHAIN_USAGE_CONTEXT = "keychain"

enum class KeychainSaveError { INVALID_URL, FAILED }

sealed interface KeychainSaveResult {
    data class Saved(val ref: String) : KeychainSaveResult
    data class Failed(val error: KeychainSaveError) : KeychainSaveResult
}

/**
 * 钥匙串页面共用的状态与操作。值只在 [readValue] 里短暂解码成字符数组交给调用方，调用方用完清零；
 * 设备验证由界面在调用前完成。
 */
class KeychainViewModel(private val repository: KeychainRepository) : ViewModel() {
    /** null 表示仍在读取。 */
    val items: StateFlow<List<KeychainItem>?> = repository.entries()
        .catch {
            InfinityLogger.log("[Keychain] 读取条目失败 type=${it.javaClass.simpleName}")
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun usage(ref: String): Flow<List<KeychainUsage>> = repository.usage(ref).catch { emit(emptyList()) }

    /** 读取一个值并记一条使用记录；[usageRef] 是条目引用，[valueRef] 是具体分量。 */
    suspend fun readValue(usageRef: String, valueRef: String, action: KeychainAction): CharArray? {
        val bytes = repository.reveal(valueRef) ?: return null
        val chars = try { decodeUtf8(bytes) } finally { bytes.fill(0) }
        runCatching { repository.recordUsage(usageRef, action, KEYCHAIN_USAGE_CONTEXT) }
            .onFailure { if (it is CancellationException) throw it }
        return chars
    }

    /** 已在页面上显示的值再复制时只补记一条使用记录。 */
    suspend fun recordUsage(usageRef: String, action: KeychainAction) {
        runCatching { repository.recordUsage(usageRef, action, KEYCHAIN_USAGE_CONTEXT) }
            .onFailure { if (it is CancellationException) throw it }
    }

    /**
     * 新建或编辑自定义与网站登录条目：填了网址就是网站登录。编辑时 [value] 为 null 表示保留原值；
     * 类别因网址增删而改变时以新类别重建条目再删除旧条目。[value] 在函数内清零。
     */
    suspend fun save(
        editing: KeychainItem?,
        name: String,
        url: String,
        username: String,
        note: String,
        value: CharArray?,
    ): KeychainSaveResult {
        val valueBytes = value?.let { encodeUtf8(it) }
        value?.fill('\u0000')
        try {
            val web = url.isNotBlank()
            if (web && KeychainHost.fromUrl(url) == null) return KeychainSaveResult.Failed(KeychainSaveError.INVALID_URL)
            val metadata = KeychainMetadata(
                name = name.trim(),
                url = url.trim().takeIf { web },
                username = username.trim().ifEmpty { null },
                note = note.trim().ifEmpty { null },
            )
            val category = if (web) KeychainCategory.WEB_LOGIN else KeychainCategory.CUSTOM
            val saved = when {
                editing == null -> create(category, metadata, requireNotNull(valueBytes))
                editing.category == category -> repository.update(editing.ref, metadata, valueBytes)
                else -> {
                    val carried = valueBytes ?: repository.reveal(editing.ref) ?: return KeychainSaveResult.Failed(KeychainSaveError.FAILED)
                    try {
                        create(category, metadata, carried).also { repository.delete(editing.ref) }
                    } finally {
                        if (carried !== valueBytes) carried.fill(0)
                    }
                }
            }
            return KeychainSaveResult.Saved(saved.ref)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return KeychainSaveResult.Failed(KeychainSaveError.FAILED)
        } finally {
            valueBytes?.fill(0)
        }
    }

    private suspend fun create(category: KeychainCategory, metadata: KeychainMetadata, value: ByteArray): KeychainItem =
        if (category == KeychainCategory.WEB_LOGIN) repository.createWebLogin(metadata, value) else repository.createCustom(metadata, value)

    /** 删除钥匙串自有条目；失败返回 null。 */
    suspend fun delete(ref: String): KeychainDeleteResult? = try {
        repository.delete(ref)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                KeychainViewModel((application as InfinityApplication).keychainRepository) as T
        }
    }
}

/** UTF-8 字节解码为字符数组，中间缓冲区随即清零。 */
internal fun decodeUtf8(bytes: ByteArray): CharArray {
    val buffer = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
    return try { CharArray(buffer.remaining()).also { buffer.get(it) } } finally {
        if (buffer.hasArray()) buffer.array().fill('\u0000')
    }
}

/** 字符数组编码为 UTF-8 字节，中间缓冲区随即清零。 */
internal fun encodeUtf8(chars: CharArray): ByteArray {
    val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
    return try { ByteArray(buffer.remaining()).also { buffer.get(it) } } finally {
        if (buffer.hasArray()) buffer.array().fill(0)
    }
}
