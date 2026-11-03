package com.pdfreader.app.data.documents

import android.net.Uri
import java.io.File

/**
 * Commits an already-written-and-validated local file to a user-chosen destination Uri
 * (typically from `ACTION_CREATE_DOCUMENT`). Kept as its own interface so [source] file
 * handling stays in one place and the engine layer (`PdfEditEngine`) never needs to know
 * about SAF/`ContentResolver` Uris — it only ever produces/validates a local temp file.
 */
interface DestinationWriter {
    /** Copies [source]'s bytes to [destination]. Returns false (never throws) on failure. */
    suspend fun writeFileToUri(source: File, destination: Uri): Boolean

    /**
     * Creates a new document named [displayName] inside the directory tree [treeUri]
     * (from `ACTION_OPEN_DOCUMENT_TREE`) and copies [source]'s bytes into it — batch
     * counterpart to [writeFileToUri] for extracting several files at once (PDF-009's
     * multi-attachment extraction) without a separate picker round trip per file. Returns
     * false (never throws) on failure.
     */
    suspend fun writeFileIntoTree(source: File, treeUri: Uri, displayName: String): Boolean
}
