package com.pdfreader.app.pdfengine

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies a bundled asset PDF into an app-private cache file so [PdfEngine.open] has a
 * real, seekable file descriptor to work with (`PdfRenderer` cannot render directly from
 * a compressed asset stream).
 *
 * The vertical slice only opens a bundled fixture; a real SAF-picked `content://` Uri
 * (PDF-001, Phase 2) will go through the same "copy into app-private temp storage" path
 * per docs/ARCHITECTURE.md §4, using an unpredictable filename per docs/SECURITY_PRIVACY.md §4.
 */
class AssetPdfCopier(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun copyAssetToCache(assetPath: String): File = withContext(ioDispatcher) {
        val destination = File(context.cacheDir, "pdf-${UUID.randomUUID()}.pdf")
        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        destination
    }
}
