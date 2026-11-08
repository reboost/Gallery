package org.fossify.gallery.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Base64

data class SafLocation(
    val treeUri: Uri,
    val displayName: String,
    val grantedFlags: Int,
    val activeFlags: Int
) {
    val hasReadAccess: Boolean
        get() = activeFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0

    val hasWriteAccess: Boolean
        get() = activeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
}

/**
 * Stores selected SAF roots separately from Gallery's filesystem-path preferences.
 * The URI string is the identity; provider display names are labels only.
 */
class SafLocationStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun add(treeUri: Uri, displayName: String, grantedFlags: Int) {
        val key = keyFor(treeUri)
        val uris = preferences.getStringSet(KEY_URIS, emptySet()).orEmpty().toMutableSet()
        uris.add(treeUri.toString())

        preferences.edit()
            .putStringSet(KEY_URIS, uris)
            .putString("${KEY_NAME}_$key", displayName)
            .putInt("${KEY_FLAGS}_$key", grantedFlags and READ_WRITE_FLAGS)
            .apply()
    }

    fun getLocations(): List<SafLocation> {
        val persistedPermissions = appContext.contentResolver.persistedUriPermissions
        return preferences.getStringSet(KEY_URIS, emptySet()).orEmpty().mapNotNull { value ->
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@mapNotNull null
            val key = keyFor(uri)
            val grantedFlags = preferences.getInt("${KEY_FLAGS}_$key", 0) and READ_WRITE_FLAGS
            val persisted = persistedPermissions.firstOrNull { it.uri == uri }
            val activeFlags = if (persisted == null) {
                0
            } else {
                (if (persisted.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (persisted.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            } and grantedFlags

            SafLocation(
                treeUri = uri,
                displayName = preferences.getString("${KEY_NAME}_$key", null)
                    ?: uri.lastPathSegment
                    ?: uri.authority.orEmpty(),
                grantedFlags = grantedFlags,
                activeFlags = activeFlags
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    fun remove(treeUri: Uri) {
        val resolver = appContext.contentResolver
        resolver.persistedUriPermissions
            .filter { it.uri == treeUri }
            .forEach { permission ->
                val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                if (flags != 0) {
                    runCatching { resolver.releasePersistableUriPermission(treeUri, flags) }
                }
            }

        val key = keyFor(treeUri)
        val uris = preferences.getStringSet(KEY_URIS, emptySet()).orEmpty().toMutableSet()
        uris.remove(treeUri.toString())
        preferences.edit()
            .putStringSet(KEY_URIS, uris)
            .remove("${KEY_NAME}_$key")
            .remove("${KEY_FLAGS}_$key")
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "saf_locations"
        private const val KEY_URIS = "tree_uris"
        private const val KEY_NAME = "name"
        private const val KEY_FLAGS = "flags"
        private val READ_WRITE_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun queryDisplayName(contentResolver: ContentResolver, treeUri: Uri): String? = runCatching {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            contentResolver.query(
                documentUri,
                arrayOf(Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME))
                        ?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }.getOrNull()

        private fun keyFor(uri: Uri): String = Base64.encodeToString(
            uri.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
