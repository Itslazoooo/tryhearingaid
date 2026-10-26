package com.example.tryhearingaid.captions

import com.example.tryhearingaid.data.CaptionSegment
import java.io.File
import java.util.UUID

/**
 * Room-backed repository for caption segments and session metadata.
 *
 * - [saveSegment] persists a single [CaptionSegment] under [sessionId].
 * - [getSession] returns all segments for a session in timestamp order.
 * - [exportAsText] / [exportAsPdf] produce shareable files.
 * - [deleteAllData] wipes every stored segment and session.
 *
 * A [TranscriptSession] row must exist (or be created on demand) before
 * saving segments.
 */
interface TranscriptStore {

    /**
     * Persist [segment] associated with [sessionId].
     * Creates a session record if none exists yet for this [sessionId].
     */
    suspend fun saveSegment(segment: CaptionSegment, sessionId: UUID)

    /**
     * Return all [CaptionSegment]s for [sessionId], ordered by
     * [CaptionSegment.timestampMs] ascending.
     */
    suspend fun getSession(sessionId: UUID): List<CaptionSegment>

    /**
     * Export the session transcript as a plain-text [File].
     * Each segment is written as "<speakerLabel>: <text>".
     */
    suspend fun exportAsText(sessionId: UUID): File

    /**
     * Export the session transcript as a PDF [File].
     */
    suspend fun exportAsPdf(sessionId: UUID): File

    /** Irreversibly delete every stored segment and session. */
    suspend fun deleteAllData()

    /**
     * Save [audioFilePath] alongside the [sessionId] record so it can be
     * retrieved when reviewing a classroom session.
     */
    suspend fun saveAudioFilePath(sessionId: UUID, audioFilePath: String)

    /**
     * Retrieve the audio file path saved for [sessionId], or null if none was saved.
     */
    suspend fun getAudioFilePath(sessionId: UUID): String?
}

// ─── In-memory implementation for tests ──────────────────────────────────────

/**
 * Fully in-memory [TranscriptStore] for unit and integration tests.
 * No real file I/O is performed for [exportAsText] / [exportAsPdf].
 */
class InMemoryTranscriptStore : TranscriptStore {

    /** sessionId → mutable list of segments */
    private val sessions = mutableMapOf<UUID, MutableList<CaptionSegment>>()

    /** sessionId → audio file path */
    private val audioPaths = mutableMapOf<UUID, String>()

    override suspend fun saveSegment(segment: CaptionSegment, sessionId: UUID) {
        sessions.getOrPut(sessionId) { mutableListOf() }.add(segment)
    }

    override suspend fun getSession(sessionId: UUID): List<CaptionSegment> =
        sessions[sessionId]
            ?.sortedBy { it.timestampMs }
            ?: emptyList()

    override suspend fun exportAsText(sessionId: UUID): File {
        val tmpFile = File.createTempFile("transcript_$sessionId", ".txt")
        val segments = getSession(sessionId)
        tmpFile.bufferedWriter().use { writer ->
            segments.forEach { seg ->
                writer.write("${seg.speakerLabel}: ${seg.text}\n")
            }
        }
        return tmpFile
    }

    override suspend fun exportAsPdf(sessionId: UUID): File {
        // Minimal stub — write text content to a .pdf-named temp file for tests
        val tmpFile = File.createTempFile("transcript_$sessionId", ".pdf")
        val segments = getSession(sessionId)
        tmpFile.bufferedWriter().use { writer ->
            writer.write("%PDF-1.4 stub\n")
            segments.forEach { seg ->
                writer.write("${seg.speakerLabel}: ${seg.text}\n")
            }
        }
        return tmpFile
    }

    override suspend fun deleteAllData() {
        sessions.clear()
        audioPaths.clear()
    }

    override suspend fun saveAudioFilePath(sessionId: UUID, audioFilePath: String) {
        audioPaths[sessionId] = audioFilePath
    }

    override suspend fun getAudioFilePath(sessionId: UUID): String? =
        audioPaths[sessionId]

    // ── Test helpers ──────────────────────────────────────────────────────────

    /** Returns true if any segment has been saved under [sessionId]. */
    fun hasSession(sessionId: UUID): Boolean = sessions.containsKey(sessionId)

    /** Total number of sessions currently stored. */
    val sessionCount: Int get() = sessions.size
}
