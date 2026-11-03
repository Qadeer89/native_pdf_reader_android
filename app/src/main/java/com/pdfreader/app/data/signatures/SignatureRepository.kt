package com.pdfreader.app.data.signatures

import android.graphics.Bitmap

/** A signature the user drew, typed, or imported, saved for reuse across documents (PDF-007). */
data class SavedSignature(
    val id: String,
    val bitmap: Bitmap,
    val createdAtEpochMillis: Long,
)

/**
 * A remembered stamp position/size for a given document (PDF-007 placement memory) —
 * [xFraction]/[yFraction] (top-left corner) and [widthFraction], same `0f..1f` convention as
 * [com.pdfreader.app.pdfengine.PdfEditEngine.addImageStamp]. Lets a repeat signer (e.g. the
 * same form signed by several people, or the same document reopened) place a signature at
 * the same spot again without re-tapping it, via [ReaderViewModel.placeSignatureAtLastPosition].
 */
data class SignaturePlacement(
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
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

    /**
     * The most recently saved [SignaturePlacement] for [documentKey] (an app-chosen stable
     * identifier for a document — e.g. its content Uri string), or null if nothing has been
     * placed on a document with this key before. PDF-007 placement memory.
     */
    suspend fun getLastPlacement(documentKey: String): SignaturePlacement?

    /** Remembers [placement] as the latest stamp position/size for [documentKey]. PDF-007. */
    suspend fun saveLastPlacement(documentKey: String, placement: SignaturePlacement)
}
