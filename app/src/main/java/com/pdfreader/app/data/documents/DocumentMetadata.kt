package com.pdfreader.app.data.documents

/** Display-facing metadata for a document (PDF-004 "document properties"). */
data class DocumentMetadata(
    val displayName: String,
    val sizeBytes: Long?,
)

interface DocumentMetadataResolver {
    /** Resolves [uriString]'s display name and size; never throws — falls back to the raw Uri string. */
    suspend fun resolve(uriString: String): DocumentMetadata
}
