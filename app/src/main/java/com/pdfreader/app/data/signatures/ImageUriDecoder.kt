package com.pdfreader.app.data.signatures

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_IMPORTED_SIGNATURE_DIMENSION_PX = 800

/** Decodes a picked image Uri into a [Bitmap], for importing a signature image (PDF-007). */
interface ImageUriDecoder {
    suspend fun decode(uri: Uri): Bitmap?
}

/**
 * Downscales to at most [MAX_IMPORTED_SIGNATURE_DIMENSION_PX] on its longest side — a signature
 * stamp never needs a full-resolution photo, and keeping it small keeps the exported PDF small.
 */
class ContentResolverImageUriDecoder(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageUriDecoder {

    override suspend fun decode(uri: Uri): Bitmap? = withContext(ioDispatcher) {
        val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: return@withContext null
        val longestSide = maxOf(original.width, original.height)
        if (longestSide <= MAX_IMPORTED_SIGNATURE_DIMENSION_PX) return@withContext original

        val scale = MAX_IMPORTED_SIGNATURE_DIMENSION_PX.toFloat() / longestSide
        Bitmap.createScaledBitmap(
            original,
            (original.width * scale).toInt().coerceAtLeast(1),
            (original.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
