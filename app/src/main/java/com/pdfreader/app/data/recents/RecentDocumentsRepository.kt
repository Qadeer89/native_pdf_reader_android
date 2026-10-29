package com.pdfreader.app.data.recents

import kotlinx.coroutines.flow.Flow

/** Thin repository boundary over [RecentDocumentDao] (PDF-004) so callers don't depend on Room directly. */
class RecentDocumentsRepository(private val dao: RecentDocumentDao) {

    fun observeAll(): Flow<List<RecentDocumentEntity>> = dao.observeAll()

    suspend fun recordOpened(uri: String, displayName: String, pageCount: Int, openedAtEpochMillis: Long) {
        dao.upsert(
            RecentDocumentEntity(
                uri = uri,
                displayName = displayName,
                pageCount = pageCount,
                lastOpenedAtEpochMillis = openedAtEpochMillis,
            ),
        )
    }

    suspend fun clearHistory() = dao.clearAll()

    suspend fun remove(uri: String) = dao.delete(uri)
}
