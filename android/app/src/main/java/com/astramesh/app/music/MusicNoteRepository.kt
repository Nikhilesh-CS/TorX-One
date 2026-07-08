package com.astramesh.app.music

import com.astramesh.app.data.MusicNoteDao
import com.astramesh.app.data.MusicNoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

interface MusicNoteRepository {
    fun observeActiveNotes(): Flow<List<MusicNoteEntity>>
    suspend fun saveNote(note: MusicNoteEntity)
    suspend fun removeNote(noteId: String)
    suspend fun pruneExpired()
}

class MusicNoteRepositoryImpl(
    private val dao: MusicNoteDao
) : MusicNoteRepository {
    override fun observeActiveNotes(): Flow<List<MusicNoteEntity>> {
        return dao.observeActiveNotes().flowOn(Dispatchers.IO)
    }

    override suspend fun saveNote(note: MusicNoteEntity) {
        withContext(Dispatchers.IO) {
            dao.insertNote(note)
        }
    }

    override suspend fun removeNote(noteId: String) {
        withContext(Dispatchers.IO) {
            dao.deleteNote(noteId)
        }
    }

    override suspend fun pruneExpired() {
        withContext(Dispatchers.IO) {
            dao.deleteExpired()
        }
    }
}
