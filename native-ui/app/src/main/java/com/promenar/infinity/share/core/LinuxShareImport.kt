package com.promenar.infinity.share.core

import com.promenar.infinity.data.rag.DocumentFormatException
import android.net.Uri
import com.promenar.infinity.ShareRequest
import com.promenar.infinity.data.linux.LinuxImportFiles
import com.promenar.infinity.data.linux.LinuxImportPathException
import com.promenar.infinity.data.repository.WorkspaceFileTooLargeException
import com.promenar.infinity.domain.linux.LinuxFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

internal suspend fun importShareToLinux(
    request: ShareRequest,
    path: String,
    retryUris: Set<Uri>?,
    source: SharedContentSource,
    files: LinuxFileSystem?,
    ready: () -> Boolean,
    fileSystem: com.promenar.infinity.data.storage.RealPathFileSystem,
    inspect: suspend () -> List<ShareImportItem>,
): ShareImportBatchResult = withContext(Dispatchers.IO) {
    val items = inspect().filter { retryUris == null || it.uri in retryUris }
    fun reject(item: ShareImportItem, reason: ShareRejectReason) = item.copy(status = ShareImportStatus.Rejected,
        reason = reason, created = null, fileUuid = null, indexTaskId = null, indexStatus = null, linuxPath = null)
    if (!ready() || files == null) return@withContext ShareImportBatchResult(items.map { reject(it, ShareRejectReason.LinuxUnavailable) })
    val sink = LinuxImportFiles(files, fileSystem)
    val target = try { sink.directory(path) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withContext ShareImportBatchResult(items.map { reject(it, ShareRejectReason.LinuxPathInvalid) }) }
    var accepted = 0L
    ShareImportBatchResult(items.map { item ->
        if (item.status == ShareImportStatus.Created) return@map item
        if (item.reason != null) return@map item
        if (!ready()) return@map reject(item, ShareRejectReason.LinuxUnavailable)
        val name = SharedFileImporter.normalizeName(item.displayName) ?: return@map reject(item, ShareRejectReason.InvalidName)
        val size = item.sizeBytes
        if (size != null && size <= 0) return@map reject(item, ShareRejectReason.EmptyFile)
        if (size != null && size > SharedFileImporter.MAX_ITEM_BYTES) return@map reject(item, ShareRejectReason.ItemTooLarge)
        if (size != null && accepted > SharedFileImporter.MAX_BATCH_BYTES - size) return@map reject(item, ShareRejectReason.BatchTooLarge)
        try {
            val mime = SharedFileImporter.normalizeMime(item.mimeType ?: request.mimeType)
            val contentMime = SharedFileImporter.parseableMime(mime, name)
            if (contentMime != null) {
                val sniffed = try { source.openForShare(item.uri).use { SharedFileImporter.sniffMime(it, contentMime) } }
                    catch (failure: ShareSourceReadException) { throw failure }
                    catch (_: IOException) { throw ShareContentMismatchException() }
                    catch (_: IllegalArgumentException) { throw ShareContentMismatchException() }
                if (!SharedFileImporter.mimeCompatible(request.mimeType, mime, sniffed, name))
                    return@map reject(item, ShareRejectReason.MimeMismatch)
                if (contentMime in SharedFileImporter.TEXT_MIME_TYPES) {
                    try { SharedFileImporter.validateText { source.openForShare(item.uri) } }
                    catch (_: DocumentFormatException) { return@map reject(item, ShareRejectReason.MimeMismatch) }
                }
            }
            var created: com.promenar.infinity.data.linux.LinuxImportFile? = null
            for (index in 0 until 10_000) {
                try {
                    created = sink.create(target, SharedFileImporter.collisionName(name, index),
                        minOf(SharedFileImporter.MAX_ITEM_BYTES, SharedFileImporter.MAX_BATCH_BYTES - accepted), source.contentSha256(item.uri)) { output ->
                        source.openForShare(item.uri).use { input -> input.copyTo(output) }
                    }
                    break
                } catch (_: FileAlreadyExistsException) { /* 同名时尝试下一个安全名称。 */ }
            }
            val result = created ?: throw IOException("同名文件过多")
            if (result.size == 0L || source.contentSha256(item.uri)?.let { it != result.sha256 } == true) {
                sink.remove(result)
                return@map reject(item, if (result.size == 0L) ShareRejectReason.EmptyFile else ShareRejectReason.ReadFailed)
            }
            accepted += result.size
            item.copy(displayName = result.path.substringAfterLast('/'), sizeBytes = result.size, status = ShareImportStatus.Created,
                reason = null, created = null, fileUuid = null, indexStatus = null, indexTaskId = null, linuxPath = result.path)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: LinuxImportPathException) { reject(item, ShareRejectReason.LinuxPathInvalid) }
        catch (_: WorkspaceFileTooLargeException) { reject(item, if (accepted > 0) ShareRejectReason.BatchTooLarge else ShareRejectReason.ItemTooLarge) }
        catch (_: ShareContentMismatchException) { reject(item, ShareRejectReason.MimeMismatch) }
        catch (_: SecurityException) { reject(item, ShareRejectReason.PermissionDenied) }
        catch (_: ShareSourceReadException) { reject(item, ShareRejectReason.ReadFailed) }
        catch (_: IOException) { reject(item, ShareRejectReason.WriteFailed) }
    })
}

