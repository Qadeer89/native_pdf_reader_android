package com.pdfreader.app.signing

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the raw bytes of a picked file Uri — used for importing a PKCS#12 certificate (PDF-008). */
interface BytesUriReader {
    suspend fun read(uri: Uri): ByteArray?
}

class ContentResolverBytesUriReader(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BytesUriReader {
    override suspend fun read(uri: Uri): ByteArray? = withContext(ioDispatcher) {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }
}
