package com.pdfreader.app.data.documents

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContentResolverDestinationWriter(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DestinationWriter {

    override suspend fun writeFileToUri(source: File, destination: Uri): Boolean = withContext(ioDispatcher) {
        try {
            context.contentResolver.openOutputStream(destination)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return@withContext false
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun writeFileIntoTree(source: File, treeUri: Uri, displayName: String): Boolean =
        withContext(ioDispatcher) {
            try {
                val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
                val childUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    treeDocumentUri,
                    "application/octet-stream",
                    displayName,
                ) ?: return@withContext false
                context.contentResolver.openOutputStream(childUri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: return@withContext false
                true
            } catch (_: Exception) {
                false
            }
        }
}
