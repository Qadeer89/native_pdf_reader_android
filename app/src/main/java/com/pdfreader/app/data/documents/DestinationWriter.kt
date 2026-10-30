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
}
