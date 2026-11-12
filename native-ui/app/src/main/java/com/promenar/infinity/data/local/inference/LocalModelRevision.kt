package com.promenar.infinity.data.local.inference

import android.content.Context
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal object RecordingModelPackage {
    const val revision = "23749fefcc72300e3a2ad315e1317431b06b590a"
    const val sha256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
    const val bytes = 639446688L
    const val filename = "Qwen3-0.6B-Q8_0-23749fe.gguf"
    const val url = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/$revision/Qwen3-0.6B-Q8_0.gguf"
}

internal data class LocalModelRevision(val path: String, val sha256: String)

internal class LocalModelRevisionStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("local_model_revision", Context.MODE_PRIVATE)

    fun read(): LocalModelRevision? {
        val name = preferences.getString("name", null)?.takeIf { File(it).name == it } ?: return null
        val digest = preferences.getString("sha256", null) ?: return null
        return LocalModelRevision(File(File(context.filesDir, "models"), name).absolutePath, digest)
    }

    suspend fun pin(path: String): LocalModelRevision = withContext(Dispatchers.IO) {
        val file = File(path).canonicalFile
        require(file.parentFile == File(context.filesDir, "models").canonicalFile && file.isFile)
        require(file.extension.equals("gguf", ignoreCase = true))
        GgufParser.parse(file.absolutePath)
        val digest = Sha256Utils.hashFile(file)
        check(preferences.edit().putString("name", file.name).putString("sha256", digest).commit())
        requireNotNull(read())
    }

    suspend fun unpin() = withContext(Dispatchers.IO) { check(preferences.edit().clear().commit()) }

    suspend fun verify(revision: LocalModelRevision): Boolean = withContext(Dispatchers.IO) {
        val file = File(revision.path)
        file.isFile && file.canonicalFile.parentFile == File(context.filesDir, "models").canonicalFile &&
            Sha256Utils.hashFile(file) == revision.sha256
    }
}
