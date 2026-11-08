package org.fossify.gallery.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

sealed interface SafCreateFolderResult {
    data class Created(val documentUri: Uri) : SafCreateFolderResult
    data object InvalidName : SafCreateFolderResult
    data object Unsupported : SafCreateFolderResult
    data object PermissionRevoked : SafCreateFolderResult
    data object Unavailable : SafCreateFolderResult
}

/** Provider operations are gated by the capabilities advertised for the target document. */
class SafDocumentOperations(private val contentResolver: ContentResolver) {
    fun supportsCreate(treeUri: Uri, parentDocumentId: String): Boolean = runCatching {
        queryDirectoryFlags(treeUri, parentDocumentId)
            ?.let { it and Document.FLAG_DIR_SUPPORTS_CREATE != 0 }
            ?: false
    }.getOrDefault(false)

    fun createFolder(
        treeUri: Uri,
        parentDocumentId: String,
        displayName: String
    ): SafCreateFolderResult {
        val name = displayName.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) {
            return SafCreateFolderResult.InvalidName
        }

        return try {
            val flags = queryDirectoryFlags(treeUri, parentDocumentId)
                ?: return SafCreateFolderResult.Unavailable
            if (flags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                return SafCreateFolderResult.Unsupported
            }

            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
            val createdUri = DocumentsContract.createDocument(
                contentResolver,
                parentUri,
                Document.MIME_TYPE_DIR,
                name
            ) ?: return SafCreateFolderResult.Unavailable

            SafCreateFolderResult.Created(createdUri)
        } catch (_: SecurityException) {
            SafCreateFolderResult.PermissionRevoked
        } catch (_: Exception) {
            SafCreateFolderResult.Unavailable
        }
    }

    private fun queryDirectoryFlags(treeUri: Uri, documentId: String): Int? {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return contentResolver.query(
            documentUri,
            arrayOf(Document.COLUMN_FLAGS),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val flagsIndex = cursor.getColumnIndex(Document.COLUMN_FLAGS)
                if (flagsIndex < 0 || cursor.isNull(flagsIndex)) 0 else cursor.getInt(flagsIndex)
            }
        }
    }
}
