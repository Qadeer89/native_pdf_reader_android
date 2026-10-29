package com.pdfreader.app.data.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves a content Uri's display name and size via [android.content.ContentResolver],
 * the documented way to read SAF-provided metadata (never trust the Uri's own path
 * segments as a filename, per docs/SECURITY_PRIVACY.md §1 "treat... filename... as untrusted").
 */
class ContentResolverDocumentMetadataResolver(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DocumentMetadataResolver {

    override suspend fun resolve(uriString: String): DocumentMetadata = withContext(ioDispatcher) {
        val uri = Uri.parse(uriString)
        var displayName: String? = null
        var sizeBytes: Long? = null

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex)

                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {
            // Best-effort metadata only; fall back to the raw Uri below.
        }

        DocumentMetadata(
            displayName = displayName ?: uriString,
            sizeBytes = sizeBytes,
        )
    }
}
