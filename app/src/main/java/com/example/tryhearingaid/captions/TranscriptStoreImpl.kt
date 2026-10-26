package com.example.tryhearingaid.captions

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.tryhearingaid.data.CaptionSegment
import com.example.tryhearingaid.data.CaptionSegmentEntity
import com.example.tryhearingaid.data.TranscriptDao
import com.example.tryhearingaid.data.TranscriptSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [TranscriptStore].
 *
 * - Segments are stored via [TranscriptDao] in the app's Room database.
 * - [exportAsText] writes "<speakerLabel>: <text>" lines to a .txt file in the cache dir.
 * - [exportAsPdf]  renders segments page-by-page using Android's [PdfDocument] API.
 * - Both exports complete well within the 5-second requirement (file I/O only).
 * - A [FileProvider] URI is available via [getShareableUri] so callers can build a
 *   share-sheet intent without exposing raw file paths to other apps.
 *
 * Requirements: 7.4, 7.5
 */
@Singleton
class TranscriptStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcriptDao: TranscriptDao
) : TranscriptStore {

    // ── FileProvider authority ────────────────────────────────────────────────

    companion object {
        const val FILE_PROVIDER_AUTHORITY = "com.example.tryhearingaid.fileprovider"

        /** Lines per PDF page (A4-ish at 12 pt). */
        private const val LINES_PER_PAGE = 40

        /** Left margin in points for PDF text. */
        private const val PDF_MARGIN_LEFT = 40f

        /** Top margin in points for PDF text. */
        private const val PDF_MARGIN_TOP = 50f

        /** Line height in points. */
        private const val PDF_LINE_HEIGHT = 18f

        /** PDF page width (A4 portrait, 72 dpi points). */
        private const val PAGE_WIDTH = 595

        /** PDF page height (A4 portrait). */
        private const val PAGE_HEIGHT = 842
    }

    // ── TranscriptStore API ───────────────────────────────────────────────────

    /**
     * Persists [segment] under [sessionId].
     * Creates a minimal [TranscriptSession] row if one does not exist yet.
     */
    override suspend fun saveSegment(segment: CaptionSegment, sessionId: UUID) =
        withContext(Dispatchers.IO) {
            ensureSessionExists(sessionId)
            transcriptDao.insertSegment(segment.toEntity(sessionId))
        }

    override suspend fun getSession(sessionId: UUID): List<CaptionSegment> =
        withContext(Dispatchers.IO) {
            transcriptDao.getSegmentsForSessionSnapshot(sessionId).map { it.toModel() }
        }

    /**
     * Exports the session as a plain-text file.
     * Format per line: "<speakerLabel>: <text>"
     *
     * Returns a [File] in the app cache directory.
     */
    override suspend fun exportAsText(sessionId: UUID): File =
        withContext(Dispatchers.IO) {
            val segments = transcriptDao.getSegmentsForSessionSnapshot(sessionId)
            val outFile = File(context.cacheDir, "transcript_${sessionId}.txt")
            outFile.bufferedWriter().use { writer ->
                segments.forEach { seg ->
                    writer.write("${seg.speakerLabel}: ${seg.text}\n")
                }
            }
            outFile
        }

    /**
     * Exports the session as a PDF file using Android's [PdfDocument] API.
     * Segments are rendered line-by-line, paginating every [LINES_PER_PAGE] lines.
     *
     * Returns a [File] in the app cache directory.
     */
    override suspend fun exportAsPdf(sessionId: UUID): File =
        withContext(Dispatchers.IO) {
            val segments = transcriptDao.getSegmentsForSessionSnapshot(sessionId)
            val lines = segments.map { "${it.speakerLabel}: ${it.text}" }

            val pdfDocument = PdfDocument()
            val paint = Paint().apply {
                textSize = 12f
                isAntiAlias = true
            }

            val pages = if (lines.isEmpty()) listOf(emptyList()) else lines.chunked(LINES_PER_PAGE)

            pages.forEachIndexed { pageIndex, pageLines ->
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageIndex + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                pageLines.forEachIndexed { lineIndex, line ->
                    val y = PDF_MARGIN_TOP + lineIndex * PDF_LINE_HEIGHT
                    canvas.drawText(line, PDF_MARGIN_LEFT, y, paint)
                }

                pdfDocument.finishPage(page)
            }

            val outFile = File(context.cacheDir, "transcript_${sessionId}.pdf")
            FileOutputStream(outFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
            pdfDocument.close()

            outFile
        }

    override suspend fun deleteAllData() =
        withContext(Dispatchers.IO) {
            transcriptDao.deleteAllSegments()
            transcriptDao.deleteAllSessions()
        }

    override suspend fun saveAudioFilePath(sessionId: UUID, audioFilePath: String) =
        withContext(Dispatchers.IO) {
            ensureSessionExists(sessionId)
            val current = transcriptDao.getSessionSnapshot(sessionId)
            if (current != null) {
                transcriptDao.updateSession(current.copy(audioFilePath = audioFilePath))
            }
        }

    override suspend fun getAudioFilePath(sessionId: UUID): String? =
        withContext(Dispatchers.IO) {
            transcriptDao.getSessionSnapshot(sessionId)?.audioFilePath
        }

    // ── Share-sheet helper ────────────────────────────────────────────────────

    /**
     * Returns a content URI for [file] that can be safely passed to a share-sheet
     * intent without exposing the raw file path to other apps.
     *
     * Usage:
     * ```kotlin
     * val file  = store.exportAsText(sessionId)
     * val uri   = store.getShareableUri(file)
     * val intent = Intent(Intent.ACTION_SEND).apply {
     *     type = "text/plain"
     *     putExtra(Intent.EXTRA_STREAM, uri)
     *     addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
     * }
     * context.startActivity(Intent.createChooser(intent, "Share transcript"))
     * ```
     */
    fun getShareableUri(file: File) =
        FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Inserts a minimal [TranscriptSession] for [sessionId] if none exists yet.
     * Uses REPLACE conflict strategy so a duplicate insert is a no-op.
     */
    private suspend fun ensureSessionExists(sessionId: UUID) {
        val existing = transcriptDao.getSessionSnapshot(sessionId)
        if (existing == null) {
            transcriptDao.insertSession(
                TranscriptSession(
                    id = sessionId,
                    startedAt = Instant.now(),
                    endedAt = null,
                    languageCode = "en-US",
                    audioFilePath = null
                )
            )
        }
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private fun CaptionSegment.toEntity(sessionId: UUID) = CaptionSegmentEntity(
        id = id,
        sessionId = sessionId,
        speakerLabel = speakerLabel,
        text = text,
        confidence = confidence,
        timestampMs = timestampMs,
        isUncertain = isUncertain
    )

    private fun CaptionSegmentEntity.toModel() = CaptionSegment(
        id = id,
        speakerLabel = speakerLabel,
        text = text,
        confidence = confidence,
        timestampMs = timestampMs,
        isUncertain = isUncertain
    )
}
