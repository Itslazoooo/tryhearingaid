package com.example.tryhearingaid.ai

import com.example.tryhearingaid.data.VocabTermEntity
import kotlinx.coroutines.flow.Flow

/**
 * Compatibility interface kept in the [com.example.tryhearingaid.ai] package so
 * that [AIAssistantImpl] (and its tests) continue to compile without modification.
 *
 * The canonical Room DAO is [com.example.tryhearingaid.data.VocabularyDao].
 * Production code should inject that type directly; this interface is retained
 * only for the AI layer's dependency-inversion boundary.
 *
 * The [InMemoryVocabularyDao] test-double is kept here for unit tests that
 * instantiate [AIAssistantImpl] without a real database.
 */
interface VocabularyDao {

    /** Emit all stored vocabulary terms, updating on each change. */
    fun getAllTerms(): Flow<List<VocabTermEntity>>

    /** Return true if [word] (case-insensitive) already exists in the vocabulary. */
    suspend fun contains(word: String): Boolean

    /** Insert [term]; existing entries with the same word are replaced. */
    suspend fun insert(term: VocabTermEntity)

    /** Return all stored terms as a snapshot list. */
    suspend fun getAll(): List<VocabTermEntity>

    /** Remove all vocabulary entries. */
    suspend fun clear()
}

// ─── In-memory implementation for tests ──────────────────────────────────────

class InMemoryVocabularyDao : VocabularyDao {
    private val store = mutableMapOf<String, VocabTermEntity>() // keyed on lower-case word
    private val _flow = kotlinx.coroutines.flow.MutableStateFlow<List<VocabTermEntity>>(emptyList())

    override fun getAllTerms(): Flow<List<VocabTermEntity>> = _flow

    override suspend fun contains(word: String): Boolean = store.containsKey(word.lowercase())

    override suspend fun insert(term: VocabTermEntity) {
        store[term.word.lowercase()] = term
        _flow.value = store.values.toList()
    }

    override suspend fun getAll(): List<VocabTermEntity> = store.values.toList()

    override suspend fun clear() {
        store.clear()
        _flow.value = emptyList()
    }
}
