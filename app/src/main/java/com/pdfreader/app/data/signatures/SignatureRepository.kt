package com.pdfreader.app.data.signatures

import android.graphics.Bitmap

/** A signature the user drew, typed, or imported, saved for reuse across documents (PDF-007). */
data class SavedSignature(
    val id: String,
    val bitmap: Bitmap,
    val createdAtEpochMillis: Long,
)

/**
 * Stores saved signatures per docs/DECISIONS_AND_OPEN_QUESTIONS.md's "visual signature...
 * private local storage, excluded from sync by default": signatures never go through SAF
 * or any content provider, and [LocalFileSignatureRepository]'s directory is excluded from
 * Android auto-backup (see `res/xml/data_extraction_rules.xml`) so they are never uploaded
 * to a device backup either, let alone a cloud account (cloud sync, PDF-011, is not yet
 * implemented at all).
 */
interface SignatureRepository {
    suspend fun listAll(): List<SavedSignature>
    suspend fun save(bitmap: Bitmap): SavedSignature
    suspend fun delete(id: String)
}
