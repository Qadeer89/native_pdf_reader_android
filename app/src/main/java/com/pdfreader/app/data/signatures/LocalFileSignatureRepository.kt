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
private const val PLACEMENTS_PREFS_NAME = "signature_placements"

/**
 * [SignatureRepository] backed by PNG files under the app's private `filesDir` — see
 * interface doc. Placement memory is backed by a separate `SharedPreferences` file (also
 * app-private, also excluded from Android auto-backup per `res/xml/data_extraction_rules.xml`
 * — same "never leaves this device by default" posture as the signature images themselves),
 * storing one `"x,y,width"` string per document key.
 */
class LocalFileSignatureRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SignatureRepository {

    private val signaturesDir: File
        get() = File(context.filesDir, SIGNATURES_DIR_NAME).apply { mkdirs() }

    private val placementPrefs
        get() = context.getSharedPreferences(PLACEMENTS_PREFS_NAME, Context.MODE_PRIVATE)

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

    override suspend fun getLastPlacement(documentKey: String): SignaturePlacement? = withContext(ioDispatcher) {
        val raw = placementPrefs.getString(documentKey, null) ?: return@withContext null
        val parts = raw.split(",")
        if (parts.size != 3) return@withContext null
        try {
            SignaturePlacement(
                xFraction = parts[0].toFloat(),
                yFraction = parts[1].toFloat(),
                widthFraction = parts[2].toFloat(),
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    override suspend fun saveLastPlacement(documentKey: String, placement: SignaturePlacement) = withContext(ioDispatcher) {
        placementPrefs.edit()
            .putString(documentKey, "${placement.xFraction},${placement.yFraction},${placement.widthFraction}")
            .apply()
    }
}
