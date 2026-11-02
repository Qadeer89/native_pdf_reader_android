package com.pdfreader.app.data.cloud

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One document queued for upload (PDF-011's offline queue) — exists independently of
 * whether a cloud backend is actually connected, so a document can be queued while offline
 * (or before `app/google-services.json` even exists) and uploaded once a connection/backend
 * is available. Only local identity and queue bookkeeping is stored — never the document's
 * bytes, same rule as `data/recents` (`docs/ARCHITECTURE.md` §1).
 *
 * [localUri] is the same stable identity `data/recents` keys on; re-enqueuing the same
 * document upserts this row rather than duplicating it.
 */
@Entity(tableName = "pending_uploads")
data class PendingUploadEntity(
    @PrimaryKey val localUri: String,
    val queuedAtEpochMillis: Long,
    val retryCount: Int = 0,
    val lastError: String? = null,
)
