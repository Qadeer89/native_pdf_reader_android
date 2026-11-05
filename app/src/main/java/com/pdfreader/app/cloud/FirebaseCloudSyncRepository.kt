package com.pdfreader.app.cloud

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import java.security.MessageDigest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/**
 * Real Firestore + Storage implementation of [CloudSyncRepository] (PDF-011, DEC-003/SEC-002),
 * gated behind the real Firebase project and its owner-scoped security rules
 * (`firestore.rules`/`storage.rules`, verified by `security-rules-tests/`). Every path is
 * `users/{uid}/documents/{docId}` — metadata in Firestore, PDF bytes in Storage — matching
 * exactly what those rules scope access to.
 *
 * [localUri] (a `content://...` string identifying the document on this device) has no
 * guaranteed-safe characters for a Firestore document id or Storage object path, so it is
 * never used directly as one; [docIdFor] derives a stable SHA-256-based id from it instead.
 *
 * Deliberately **not** wired to [com.pdfreader.app.data.cloud.OfflineUploadQueueRepository] —
 * same reasoning as that repository's own class doc: this repository only attempts a direct
 * upload/download/delete against the live backend and reports success or failure; deciding
 * *when* to retry a failed/offline attempt against the queue is a separate, not-yet-built
 * concern (`planning/IMPLEMENTATION_BACKLOG.md` PDF-011 item 4 — retry/backoff scheduling).
 *
 * [cancelUpload] is best-effort only: it does not hold a reference to any in-flight
 * `UploadTask`, so it cannot actually abort bytes already being sent — it only resets the
 * Firestore status back to [CloudSyncStatus.Local] if the document is currently marked
 * [CloudSyncStatus.Uploading]. A real mid-flight cancel would need `enqueueUpload` to keep
 * its `UploadTask` around, which is out of scope here.
 */
class FirebaseCloudSyncRepository(
    private val context: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
) : CloudSyncRepository {

    private fun documentsCollection(uid: String) =
        firestore.collection("users").document(uid).collection("documents")

    private fun storageRef(uid: String, docId: String) =
        storage.reference.child("users/$uid/documents/$docId")

    override fun observeSyncStatus(localUri: String): Flow<CloudSyncStatus> {
        val uid = firebaseAuth.currentUser?.uid ?: return flowOf(CloudSyncStatus.Local)
        val docId = docIdFor(localUri)

        return callbackFlow {
            val registration = documentsCollection(uid).document(docId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        trySend(CloudSyncStatus.Failed(error.message ?: "Listen failed."))
                        return@addSnapshotListener
                    }
                    trySend(snapshot?.toSyncStatus() ?: CloudSyncStatus.Local)
                }
            awaitClose { registration.remove() }
        }
    }

    override suspend fun enqueueUpload(localUri: String): Result<Unit> {
        val uid = firebaseAuth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not signed in — cannot upload."))
        val docId = docIdFor(localUri)
        val docRef = documentsCollection(uid).document(docId)

        return try {
            docRef.set(
                mapOf("localUri" to localUri, "status" to "Uploading"),
            ).await()

            val bytes = context.contentResolver.openInputStream(Uri.parse(localUri))?.use { it.readBytes() }
                ?: throw IllegalStateException("Could not open $localUri for upload.")

            storageRef(uid, docId).putBytes(bytes).await()

            docRef.set(
                mapOf(
                    "localUri" to localUri,
                    "status" to "Synced",
                    "syncedAtEpochMillis" to System.currentTimeMillis(),
                ),
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            docRef.set(
                mapOf("localUri" to localUri, "status" to "Failed", "reason" to (e.message ?: "Upload failed.")),
            ).await()
            Result.failure(e)
        }
    }

    override suspend fun cancelUpload(localUri: String) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        val docRef = documentsCollection(uid).document(docIdFor(localUri))
        val snapshot = docRef.get().await()
        if (snapshot.getString("status") == "Uploading") {
            docRef.delete().await()
        }
    }

    override suspend fun deleteFromCloud(localUri: String): Result<Unit> {
        val uid = firebaseAuth.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not signed in — nothing to delete."))
        val docId = docIdFor(localUri)
        return try {
            storageRef(uid, docId).delete().await()
            documentsCollection(uid).document(docId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteAllAccountData(): Result<Unit> {
        val user = firebaseAuth.currentUser
            ?: return Result.failure(IllegalStateException("Not signed in — nothing to delete."))
        val uid = user.uid
        return try {
            val allDocs = documentsCollection(uid).get().await()
            for (doc in allDocs.documents) {
                storageRef(uid, doc.id).delete().await()
                doc.reference.delete().await()
            }
            user.delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

private fun com.google.firebase.firestore.DocumentSnapshot.toSyncStatus(): CloudSyncStatus =
    when (getString("status")) {
        "Uploading" -> CloudSyncStatus.Uploading
        "Synced" -> CloudSyncStatus.Synced(getLong("syncedAtEpochMillis") ?: 0L)
        "Conflicted" -> CloudSyncStatus.Conflicted
        "Failed" -> CloudSyncStatus.Failed(getString("reason") ?: "Unknown error.")
        else -> CloudSyncStatus.Local
    }

private fun docIdFor(localUri: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(localUri.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
