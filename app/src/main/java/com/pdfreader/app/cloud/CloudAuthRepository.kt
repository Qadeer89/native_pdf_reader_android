package com.pdfreader.app.cloud

import kotlinx.coroutines.flow.StateFlow

/**
 * Cloud account identity for PDF-011 (DEC-003: Firebase Auth, approved 2026-11-03). Kept as
 * an interface — same pattern as [com.pdfreader.app.pdfengine.PdfEngine] — so the rest of
 * the app (and its tests) never depend on the real Firebase SDK directly.
 *
 * Google Sign-In is the default assumed sign-in method for the real implementation that
 * will back this interface, as the conventional choice for a "sync to the cloud" feature;
 * not yet separately confirmed with the user — revisit if a different method is wanted.
 */
interface CloudAuthRepository {
    /** Null when signed out. */
    val currentUser: StateFlow<CloudUser?>

    /** [idToken] is the Google Sign-In ID token to exchange for a Firebase credential. */
    suspend fun signInWithGoogle(idToken: String): Result<CloudUser>

    suspend fun signOut()
}

/**
 * Placeholder implementation used until the real Firebase project exists: this app has no
 * `google-services.json` yet (DEC-003's follow-up #2 — that file can only come from the
 * user creating the actual Firebase project in their own Google account, not something this
 * agent can do on their behalf), so wiring the real `firebase-auth` SDK now would fail to
 * build. Always reports signed-out; every sign-in attempt fails with a clear reason rather
 * than silently pretending to succeed.
 */
class NotConfiguredCloudAuthRepository(
    private val state: kotlinx.coroutines.flow.MutableStateFlow<CloudUser?> = kotlinx.coroutines.flow.MutableStateFlow(null),
) : CloudAuthRepository {
    override val currentUser: StateFlow<CloudUser?> = state

    override suspend fun signInWithGoogle(idToken: String): Result<CloudUser> =
        Result.failure(IllegalStateException("Cloud sync is not configured yet (no Firebase project connected)."))

    override suspend fun signOut() {
        state.value = null
    }
}
