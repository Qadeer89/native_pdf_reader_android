package com.pdfreader.app.data.signatures

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SIGNATURES_DIR_NAME = "signatures"

/** [SignatureRepository] backed by PNG files under the app's private `filesDir` — see interface doc. */
class LocalFileSignatureRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SignatureRepository {

    private val signaturesDir: File
        get() = File(context.filesDir, SIGNATURES_DIR_NAME).apply { mkdirs() }

    override suspend fun listAll(): List<SavedSignature> = withContext(ioDispatcher) {
        signaturesDir.listFiles { file -> file.extension == "png" }
            ?.sortedBy { it.lastModified() }
            ?.mapNotNull { file ->
                BitmapFactory.decodeFile(file.path)?.let { bitmap ->
                    SavedSignature(id = file.nameWithoutExtension, bitmap = bitmap, createdAtEpochMillis = file.lastModified())
                }
            }
            .orEmpty()
    }

    override suspend fun save(bitmap: Bitmap): SavedSignature = withContext(ioDispatcher) {
        val id = UUID.randomUUID().toString()
        val file = File(signaturesDir, "$id.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        SavedSignature(id = id, bitmap = bitmap, createdAtEpochMillis = file.lastModified())
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) {
        File(signaturesDir, "$id.png").delete()
        Unit
    }
}
