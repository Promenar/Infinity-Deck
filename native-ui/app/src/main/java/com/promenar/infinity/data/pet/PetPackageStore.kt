package com.promenar.infinity.data.pet

import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.promenar.infinity.domain.pet.PetFile
import com.promenar.infinity.domain.pet.PetIssue
import com.promenar.infinity.domain.pet.PetIssueCode
import com.promenar.infinity.domain.pet.PetLimits
import com.promenar.infinity.domain.pet.PetManifest
import com.promenar.infinity.domain.pet.PetPackageValidator
import com.promenar.infinity.domain.pet.PetParser
import com.promenar.infinity.domain.pet.PetPersona
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 已安装的宠物包。 */
data class InstalledPet(val id: String, val manifest: PetManifest, val persona: PetPersona?, val directory: File)

enum class PetImportFailure { NOT_A_ZIP, MANIFEST_MISSING, MANIFEST_UNREADABLE, PERSONA_UNREADABLE, IO }

sealed interface PetImportResult {
    data class Installed(val pet: InstalledPet) : PetImportResult
    data class Invalid(val issues: List<PetIssue>) : PetImportResult
    data class Failed(val reason: PetImportFailure) : PetImportResult
}

fun interface PetMediaMetadataReader {
    fun read(file: File, path: String): PetFile
}

class AndroidPetMediaMetadataReader : PetMediaMetadataReader {
    override fun read(file: File, path: String): PetFile {
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext == "png" || ext == "webp") {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            return PetFile(path, file.length(), options.outWidth, options.outHeight)
        }
        if (ext == "mp4") {
            val retriever = MediaMetadataRetriever()
            val extractor = MediaExtractor()
            try {
                retriever.setDataSource(file.path)
                extractor.setDataSource(file.path)
                val mime = (0 until extractor.trackCount).asSequence().map { extractor.getTrackFormat(it) }
                    .mapNotNull { it.getString(MediaFormat.KEY_MIME) }.firstOrNull { it.startsWith("video/") }
                return PetFile(
                    path, file.length(),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(), mime,
                )
            } finally {
                retriever.release()
                extractor.release()
            }
        }
        return PetFile(path, file.length())
    }
}

class PetPackageStore(
    filesDir: File,
    private val preferences: PetPreferenceStore,
    private val metadataReader: PetMediaMetadataReader = AndroidPetMediaMetadataReader(),
    private val limits: PetLimits = PetLimits(),
) {
    private val root = File(filesDir, "pets")
    private val mutex = Mutex()
    private var loaded = false
    private val _pets = MutableStateFlow<List<InstalledPet>>(emptyList())
    val pets: StateFlow<List<InstalledPet>> = _pets.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            mutex.withLock { ensureLoaded() }
        }
    }

    suspend fun import(input: InputStream): PetImportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val staging = File(root, ".staging-${UUID.randomUUID()}")
            try {
                check(root.isDirectory || root.mkdirs())
                check(staging.mkdir())
                val stream = PushbackInputStream(input, 4)
                val header = ByteArray(4)
                var count = 0
                while (count < header.size) {
                    val size = stream.read(header, count, header.size - count)
                    if (size < 0) break
                    count += size
                }
                if (count > 0) stream.unread(header, 0, count)
                if (count != 4 || header[0] != 0x50.toByte() || header[1] != 0x4b.toByte() ||
                    !((header[2] == 3.toByte() && header[3] == 4.toByte()) ||
                        (header[2] == 5.toByte() && header[3] == 6.toByte()))
                ) return@withLock PetImportResult.Failed(PetImportFailure.NOT_A_ZIP)
                extract(stream, staging)
                val parsed = parse(staging)
                if (parsed !is PetImportResult.Installed) return@withLock parsed
                val id = parsed.pet.id
                val destination = File(root, id)
                val trash = File(root, ".trash-${UUID.randomUUID()}")
                val hadOld = destination.exists()
                if (hadOld) check(destination.renameTo(trash))
                if (!staging.renameTo(destination)) {
                    if (hadOld) check(trash.renameTo(destination))
                    error("无法安装宠物包")
                }
                trash.deleteRecursively()
                val pet = parsed.pet.copy(directory = destination)
                _pets.value = (_pets.value.filterNot { it.id == id } + pet).sortedBy { it.manifest.name }
                PetImportResult.Installed(pet)
            } catch (invalid: InvalidPackage) {
                PetImportResult.Invalid(listOf(invalid.issue))
            } catch (_: Exception) {
                PetImportResult.Failed(PetImportFailure.IO)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            if (!validId(id)) return@withLock
            val destination = File(root, id)
            if (destination.exists()) {
                val trash = File(root, ".trash-${UUID.randomUUID()}")
                check(destination.renameTo(trash))
                trash.deleteRecursively()
            }
            if (preferences.defaultPetId.value == id) preferences.setDefaultPetId(null)
            preferences.modelPets.value.filterValues { it == id }.keys.forEach { preferences.setModelPet(it, null) }
            _pets.value = _pets.value.filterNot { it.id == id }
        }
    }

    suspend fun export(id: String, output: OutputStream): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoaded()
            val pet = _pets.value.firstOrNull { it.id == id && it.directory.isDirectory } ?: return@withLock false
            if (!pet.manifest.license.redistribute) return@withLock false
            ZipOutputStream(output).use { zip ->
                pet.directory.walkTopDown().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(pet.directory).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            true
        }
    }

    private fun extract(input: InputStream, staging: File) {
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val path = entry.name
                val parts = path.split('/')
                if (path.startsWith('/') || '\\' in path || Regex("^[A-Za-z]:").containsMatchIn(path) ||
                    parts.any { it.isEmpty() || it == "." || it == ".." } || '\u0000' in path
                ) throw InvalidPackage(PetIssue(PetIssueCode.UNSAFE_PATH, file = path))
                if (!seen.add(path)) throw InvalidPackage(PetIssue(PetIssueCode.DUPLICATE_PATH, file = path))
                if (seen.size > limits.maxFiles) throw InvalidPackage(PetIssue(PetIssueCode.FILE_COUNT))
                if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) !in setOf("json", "webp", "png", "mp4", "txt")) {
                    throw InvalidPackage(PetIssue(PetIssueCode.UNSUPPORTED_EXTENSION, file = path))
                }
                val target = File(staging, path)
                check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
                target.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = zip.read(buffer)
                        if (size < 0) break
                        total += size
                        if (total > limits.maxPackageBytes) throw InvalidPackage(PetIssue(PetIssueCode.PACKAGE_SIZE))
                        out.write(buffer, 0, size)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun parse(directory: File): PetImportResult {
        val manifestFile = File(directory, "manifest.json")
        if (!manifestFile.isFile) return PetImportResult.Failed(PetImportFailure.MANIFEST_MISSING)
        val manifest = runCatching { PetParser.parseManifest(manifestFile.readText()).getOrThrow() }.getOrNull()
            ?: return PetImportResult.Failed(PetImportFailure.MANIFEST_UNREADABLE)
        if (!validId(manifest.id)) {
            return PetImportResult.Invalid(listOf(PetIssue(PetIssueCode.INVALID_FIELD, field = "id", actual = manifest.id)))
        }
        val personaFile = File(directory, "persona.json")
        val persona = if (personaFile.exists()) {
            runCatching { PetParser.parsePersona(personaFile.readText()).getOrThrow() }.getOrNull()
                ?: return PetImportResult.Failed(PetImportFailure.PERSONA_UNREADABLE)
        } else null
        val files = directory.walkTopDown().filter { it.isFile }.map { file ->
            val path = file.relativeTo(directory).invariantSeparatorsPath
            runCatching { metadataReader.read(file, path) }.getOrElse { PetFile(path, file.length()) }
        }.toList()
        val issues = PetPackageValidator(limits).validate(manifest, persona, files)
        return if (issues.isEmpty()) PetImportResult.Installed(InstalledPet(manifest.id, manifest, persona, directory))
        else PetImportResult.Invalid(issues)
    }

    private fun ensureLoaded() {
        if (loaded) return
        _pets.value = root.listFiles().orEmpty().filter { it.isDirectory && validId(it.name) }.mapNotNull { directory ->
            val pet = runCatching { parse(directory) }.getOrNull() as? PetImportResult.Installed
            pet?.pet?.takeIf { it.id == directory.name }
        }.sortedBy { it.manifest.name }
        loaded = true
    }

    private fun validId(id: String): Boolean = id.length in 1..64 && !id.startsWith('.') && ID.matches(id)

    private class InvalidPackage(val issue: PetIssue) : Exception()

    private companion object {
        val ID = Regex("[a-z0-9._-]+")
    }
}
