package org.fossify.gallery.saf

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

sealed interface SafBrowserItem {
    val documentId: String
    val displayName: String
    val documentUri: Uri

    data class Directory(
        override val documentId: String,
        override val displayName: String,
        override val documentUri: Uri,
        val flags: Int
    ) : SafBrowserItem {
        val supportsCreate: Boolean
            get() = flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0
    }

    data class Media(
        override val documentId: String,
        override val displayName: String,
        override val documentUri: Uri,
        val mimeType: String?,
        val size: Long?,
        val lastModified: Long?,
        val type: SafMediaType
    ) : SafBrowserItem
}

enum class SafMediaType {
    IMAGE,
    VIDEO
}

sealed interface SafBrowseResult {
    data class Success(val items: List<SafBrowserItem>) : SafBrowseResult
    data object PermissionRevoked : SafBrowseResult
    data object Unavailable : SafBrowseResult
    data object Cancelled : SafBrowseResult
}

/** Queries only the immediate children of an opened document. */
class SafDocumentEnumerator(private val contentResolver: ContentResolver) {
    fun listChildren(
        treeUri: Uri,
        parentDocumentId: String,
        cancellationSignal: CancellationSignal
    ): SafBrowseResult {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS
        )

        return try {
            val cursor = contentResolver.query(childrenUri, projection, null, null, null, cancellationSignal)
                ?: return SafBrowseResult.Unavailable

            val items = cursor.use {
                val idIndex = it.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val nameIndex = it.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = it.getColumnIndex(Document.COLUMN_MIME_TYPE)
                val sizeIndex = it.getColumnIndex(Document.COLUMN_SIZE)
                val modifiedIndex = it.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
                val flagsIndex = it.getColumnIndex(Document.COLUMN_FLAGS)
                val documents = ArrayList<SafBrowserItem>()

                while (it.moveToNext()) {
                    cancellationSignal.throwIfCanceled()
                    val documentId = it.getString(idIndex)?.takeIf(String::isNotEmpty) ?: continue
                    val displayName = nameIndex.takeIf { index -> index >= 0 }
                        ?.let(it::getString)
                        ?.takeIf(String::isNotBlank)
                        ?: "Unnamed document"
                    if (displayName.startsWith(".")) {
                        continue
                    }

                    val mimeType = mimeIndex.takeIf { index -> index >= 0 }?.let(it::getString)
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    if (mimeType == Document.MIME_TYPE_DIR) {
                        val flags = flagsIndex.takeIf { index -> index >= 0 && !it.isNull(index) }
                            ?.let(it::getInt)
                            ?: 0
                        documents.add(SafBrowserItem.Directory(documentId, displayName, documentUri, flags))
                    } else {
                        val mediaType = classifyMedia(mimeType, displayName) ?: continue
                        val size = sizeIndex.takeIf { index -> index >= 0 && !it.isNull(index) }
                            ?.let(it::getLong)
                            ?.takeIf { value -> value >= 0L }
                        val lastModified = modifiedIndex.takeIf { index -> index >= 0 && !it.isNull(index) }
                            ?.let(it::getLong)
                            ?.takeIf { value -> value > 0L }
                        documents.add(
                            SafBrowserItem.Media(
                                documentId, displayName, documentUri, mimeType, size, lastModified, mediaType
                            )
                        )
                    }
                }
                documents.sortedWith(compareBy({ it !is SafBrowserItem.Directory }, { it.displayName.lowercase() }))
            }
            SafBrowseResult.Success(items)
        } catch (_: SecurityException) {
            SafBrowseResult.PermissionRevoked
        } catch (_: OperationCanceledException) {
            SafBrowseResult.Cancelled
        } catch (_: Exception) {
            SafBrowseResult.Unavailable
        }
    }

    private fun classifyMedia(mimeType: String?, displayName: String): SafMediaType? {
        when {
            mimeType?.startsWith("image/", ignoreCase = true) == true -> return SafMediaType.IMAGE
            mimeType?.startsWith("video/", ignoreCase = true) == true -> return SafMediaType.VIDEO
        }

        val extension = displayName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "webp", "gif", "apng", "heic", "heif", "avif", "bmp",
            "tif", "tiff", "jxl", "svg", "dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf" ->
                SafMediaType.IMAGE
            "mp4", "m4v", "mov", "mkv", "avi", "webm", "3gp", "mpeg", "mpg", "ts", "mts",
            "m2ts", "wmv", "flv" -> SafMediaType.VIDEO
            else -> null
        }
    }
}
