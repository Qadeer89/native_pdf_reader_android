package com.pdfreader.app.data.recents

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local metadata for a document the user previously opened via the SAF picker or a
 * share/view intent (PDF-004). Only what's needed to re-open and label it in a "Recent"
 * list is stored — never the document's bytes (docs/ARCHITECTURE.md §1: "avoid storing
 * PDF bytes in database by default") and never anything from the document's content.
 *
 * [uri] is the stable identity (a SAF/share Uri string); re-opening the same document
 * upserts this row rather than duplicating it.
 */
@Entity(tableName = "recent_documents")
data class RecentDocumentEntity(
    @PrimaryKey val uri: String,
    val displayName: String,
    val pageCount: Int,
    val lastOpenedAtEpochMillis: Long,
)
