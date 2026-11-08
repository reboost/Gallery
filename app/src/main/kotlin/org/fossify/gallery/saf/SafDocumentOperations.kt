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
    data object SourceRetained : SafItemMutationResult
    data object PartialTransfer : SafItemMutationResult
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
        if (!hasPersistedWriteGrant(treeUri)) return SafCreateFolderResult.PermissionRevoked
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
        if (!hasPersistedWriteGrant(treeUri)) return SafItemMutationResult.PermissionRevoked
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
        if (!hasPersistedWriteGrant(treeUri)) return SafItemMutationResult.PermissionRevoked
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

    fun copyDocument(
        sourceTreeUri: Uri,
        sourceDocumentId: String,
        displayName: String,
        mimeType: String,
        isDirectory: Boolean,
        targetTreeUri: Uri,
        targetParentDocumentId: String
    ): SafItemMutationResult {
        if (!hasPersistedReadGrant(sourceTreeUri) || !hasPersistedWriteGrant(targetTreeUri)) {
            return SafItemMutationResult.PermissionRevoked
        }

        return try {
            val sourceFlags = queryDocumentFlags(sourceTreeUri, sourceDocumentId)
                ?: return SafItemMutationResult.Unavailable
            val targetFlags = queryDocumentFlags(targetTreeUri, targetParentDocumentId)
                ?: return SafItemMutationResult.Unavailable
            if (targetFlags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                return SafItemMutationResult.Unsupported
            }

            val sourceUri = DocumentsContract.buildDocumentUriUsingTree(sourceTreeUri, sourceDocumentId)
            val targetParentUri = DocumentsContract.buildDocumentUriUsingTree(targetTreeUri, targetParentDocumentId)
            if (sourceTreeUri.authority == targetTreeUri.authority &&
                sourceFlags and Document.FLAG_SUPPORTS_COPY != 0
            ) {
                if (DocumentsContract.copyDocument(contentResolver, sourceUri, targetParentUri) != null) {
                    SafItemMutationResult.Completed
                } else {
                    SafItemMutationResult.Unavailable
                }
            } else if (isDirectory) {
                SafItemMutationResult.Unsupported
            } else {
                copyFileUsingStreams(sourceUri, targetParentUri, displayName, mimeType)
            }
        } catch (_: SecurityException) {
            SafItemMutationResult.PermissionRevoked
        } catch (_: Exception) {
            SafItemMutationResult.Unavailable
        }
    }

    fun moveDocument(
        sourceTreeUri: Uri,
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        displayName: String,
        mimeType: String,
        isDirectory: Boolean,
        targetTreeUri: Uri,
        targetParentDocumentId: String
    ): SafItemMutationResult {
        if (!hasPersistedWriteGrant(sourceTreeUri) || !hasPersistedWriteGrant(targetTreeUri)) {
            return SafItemMutationResult.PermissionRevoked
        }
        if (sourceTreeUri == targetTreeUri && sourceParentDocumentId == targetParentDocumentId) {
            return SafItemMutationResult.Unsupported
        }

        return try {
            val sourceFlags = queryDocumentFlags(sourceTreeUri, sourceDocumentId)
                ?: return SafItemMutationResult.Unavailable
            val targetFlags = queryDocumentFlags(targetTreeUri, targetParentDocumentId)
                ?: return SafItemMutationResult.Unavailable
            if (targetFlags and Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
                return SafItemMutationResult.Unsupported
            }

            val sourceUri = DocumentsContract.buildDocumentUriUsingTree(sourceTreeUri, sourceDocumentId)
            val sourceParentUri = DocumentsContract.buildDocumentUriUsingTree(sourceTreeUri, sourceParentDocumentId)
            val targetParentUri = DocumentsContract.buildDocumentUriUsingTree(targetTreeUri, targetParentDocumentId)
            if (sourceTreeUri.authority == targetTreeUri.authority &&
                sourceFlags and Document.FLAG_SUPPORTS_MOVE != 0
            ) {
                if (DocumentsContract.moveDocument(contentResolver, sourceUri, sourceParentUri, targetParentUri) != null) {
                    return SafItemMutationResult.Completed
                }
                return SafItemMutationResult.Unavailable
            }

            if (isDirectory || sourceFlags and Document.FLAG_SUPPORTS_DELETE == 0) {
                return SafItemMutationResult.Unsupported
            }

            val copyResult = copyFileUsingStreams(sourceUri, targetParentUri, displayName, mimeType)
            if (copyResult != SafItemMutationResult.Completed) {
                return copyResult
            }

            val refreshedFlags = queryDocumentFlags(sourceTreeUri, sourceDocumentId)
            if (refreshedFlags == null || refreshedFlags and Document.FLAG_SUPPORTS_DELETE == 0 ||
                !DocumentsContract.deleteDocument(contentResolver, sourceUri)
            ) {
                SafItemMutationResult.SourceRetained
            } else {
                SafItemMutationResult.Completed
            }
        } catch (_: SecurityException) {
            SafItemMutationResult.PermissionRevoked
        } catch (_: Exception) {
            SafItemMutationResult.Unavailable
        }
    }

    private fun copyFileUsingStreams(
        sourceUri: Uri,
        targetParentUri: Uri,
        displayName: String,
        mimeType: String
    ): SafItemMutationResult {
        var createdUri: Uri? = null
        return try {
            val input = contentResolver.openInputStream(sourceUri)
                ?: return SafItemMutationResult.Unavailable
            input.use { source ->
                val targetUri = DocumentsContract.createDocument(
                    contentResolver,
                    targetParentUri,
                    mimeType.ifBlank { "application/octet-stream" },
                    displayName
                ) ?: return SafItemMutationResult.Unavailable
                createdUri = targetUri
                val output = contentResolver.openOutputStream(targetUri, "w")
                    ?: return if (removePartialDocument(targetUri)) {
                        SafItemMutationResult.Unavailable
                    } else {
                        SafItemMutationResult.PartialTransfer
                    }
                output.use { destination -> source.copyTo(destination) }
            }
            SafItemMutationResult.Completed
        } catch (_: SecurityException) {
            if (removePartialDocument(createdUri)) {
                SafItemMutationResult.PermissionRevoked
            } else {
                SafItemMutationResult.PartialTransfer
            }
        } catch (_: Exception) {
            if (removePartialDocument(createdUri)) {
                SafItemMutationResult.Unavailable
            } else {
                SafItemMutationResult.PartialTransfer
            }
        }
    }

    private fun removePartialDocument(documentUri: Uri?): Boolean {
        if (documentUri == null) return true
        return runCatching { DocumentsContract.deleteDocument(contentResolver, documentUri) }.getOrDefault(false)
    }

    private fun hasPersistedReadGrant(treeUri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    private fun hasPersistedWriteGrant(treeUri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

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
