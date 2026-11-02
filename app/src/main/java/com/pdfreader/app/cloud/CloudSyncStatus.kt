package com.pdfreader.app.cloud

/**
 * Per-document cloud sync state (PDF-011) — the exact vocabulary
 * `docs/PRODUCT_REQUIREMENTS.md` PDF-011 requires surfacing to the user: "local, uploading,
 * synced, conflicted or failed."
 */
sealed class CloudSyncStatus {
    /** Never uploaded, or removed from the cloud — exists only on this device. */
    object Local : CloudSyncStatus()

    object Uploading : CloudSyncStatus()

    /** Matches the cloud copy as of [syncedAtEpochMillis]. */
    data class Synced(val syncedAtEpochMillis: Long) : CloudSyncStatus()

    /** Both the local and cloud copies changed since the last sync — never silently resolved. */
    object Conflicted : CloudSyncStatus()

    data class Failed(val reason: String) : CloudSyncStatus()
}
