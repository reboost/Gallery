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

sealed interface SafItemMutationResult {
    data object Completed : SafItemMutationResult
    data object InvalidName : SafItemMutationResult
    data object Unsupported : SafItemMutationResult
    data object PermissionRevoked : SafItemMutationResult
    data object Unavailable : SafItemMutationResult
}

/** Provider operations are gated by the capabilities advertised for the target document. */
class SafDocumentOperations(private val contentResolver: ContentResolver) {
    fun supportsCreate(treeUri: Uri, parentDocumentId: String): Boolean = runCatching {
        queryDocumentFlags(treeUri, parentDocumentId)
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
            val flags = queryDocumentFlags(treeUri, parentDocumentId)
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

    fun renameDocument(
        treeUri: Uri,
        documentId: String,
        displayName: String
    ): SafItemMutationResult {
        val name = displayName.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) {
            return SafItemMutationResult.InvalidName
        }

        return try {
            val flags = queryDocumentFlags(treeUri, documentId)
                ?: return SafItemMutationResult.Unavailable
            if (flags and Document.FLAG_SUPPORTS_RENAME == 0) {
                return SafItemMutationResult.Unsupported
            }

            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            if (DocumentsContract.renameDocument(contentResolver, documentUri, name) == null) {
                SafItemMutationResult.Unavailable
            } else {
                SafItemMutationResult.Completed
            }
        } catch (_: SecurityException) {
            SafItemMutationResult.PermissionRevoked
        } catch (_: Exception) {
            SafItemMutationResult.Unavailable
        }
    }

    fun deleteDocument(treeUri: Uri, documentId: String): SafItemMutationResult {
        return try {
            val flags = queryDocumentFlags(treeUri, documentId)
                ?: return SafItemMutationResult.Unavailable
            if (flags and Document.FLAG_SUPPORTS_DELETE == 0) {
                return SafItemMutationResult.Unsupported
            }

            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            if (DocumentsContract.deleteDocument(contentResolver, documentUri)) {
                SafItemMutationResult.Completed
            } else {
                SafItemMutationResult.Unavailable
            }
        } catch (_: SecurityException) {
            SafItemMutationResult.PermissionRevoked
        } catch (_: Exception) {
            SafItemMutationResult.Unavailable
        }
    }

    private fun queryDocumentFlags(treeUri: Uri, documentId: String): Int? {
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
