package com.pdfreader.app.data.documents

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real test for [ContentResolverDocumentMetadataResolver] (PDF-001/PDF-004) — specifically
 * the "Unicode-filename" case called out as untested in `planning/IMPLEMENTATION_BACKLOG.md`
 * Phase 2. Registers a minimal fake content provider (Robolectric's in-process provider
 * registration, no manifest entry needed) rather than a fake [DocumentMetadataResolver], so
 * this actually exercises the real `ContentResolver.query`/`OpenableColumns` path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentResolverDocumentMetadataResolverTest {

    /** Returns a fixed Unicode display name/size for one known Uri; null cursor for anything else, same as a real SAF provider would for an unknown document. */
    class FakeDocumentProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            if (uri.lastPathSegment != "unicode-doc") return null
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf("文件 📄 ünïcödé.pdf", 12345L))
            }
        }

        override fun getType(uri: Uri): String? = "application/pdf"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Test
    fun resolvesAUnicodeDisplayNameAndSizeFromTheContentProvider() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Robolectric.buildContentProvider(FakeDocumentProvider::class.java).create("com.pdfreader.app.test.fake")
        val resolver = ContentResolverDocumentMetadataResolver(context, ioDispatcher = Dispatchers.Unconfined)

        val metadata = resolver.resolve("content://com.pdfreader.app.test.fake/document/unicode-doc")

        assertEquals("文件 📄 ünïcödé.pdf", metadata.displayName)
        assertEquals(12345L, metadata.sizeBytes)
    }

    @Test
    fun fallsBackToTheRawUriStringWhenNoProviderMetadataIsAvailable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = ContentResolverDocumentMetadataResolver(context, ioDispatcher = Dispatchers.Unconfined)

        // No provider is registered for this authority at all — the real-world equivalent of
        // a Uri whose provider has gone away (revoked permission, uninstalled app, etc.).
        val uriString = "content://com.pdfreader.app.test.nonexistent/document/missing"
        val metadata = resolver.resolve(uriString)

        assertEquals(uriString, metadata.displayName)
        assertNull(metadata.sizeBytes)
    }
}
