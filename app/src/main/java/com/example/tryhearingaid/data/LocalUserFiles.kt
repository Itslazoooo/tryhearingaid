package com.example.tryhearingaid.data

import java.io.File

/**
 * User data that lives in plain files rather than in the database or preferences
 * (Requirement 16.4, 16.5): lecture audio recordings and exported transcripts.
 */
fun interface LocalUserFiles {

    /**
     * Delete every such file. Tries all of them even if one cannot be deleted.
     *
     * @return true if everything is gone, false if at least one file is still there.
     */
    fun deleteAll(): Boolean

    companion object {
        /** For tests and previews that have no files. */
        val None = LocalUserFiles { true }
    }
}

/**
 * Deletes the lecture recordings in `<filesDir>/classroom_audio` and the transcript exports
 * (`transcript_*.txt` and `transcript_*.pdf`) in the cache folder.
 *
 * Takes plain folders instead of a Context so it can be tested on the JVM. In the app, build it
 * with `LocalUserFilesImpl(context.filesDir, context.cacheDir)`.
 *
 * The names below must match where the files are written: [CLASSROOM_AUDIO_DIR] by
 * `CaptionViewModel`, and [TRANSCRIPT_PREFIX] by `TranscriptStoreImpl`.
 */
class LocalUserFilesImpl(
    private val filesDir: File,
    private val cacheDir: File
) : LocalUserFiles {

    override fun deleteAll(): Boolean {
        var allGone = true

        val audioDir = File(filesDir, CLASSROOM_AUDIO_DIR)
        if (audioDir.exists() && !audioDir.deleteRecursively()) allGone = false

        cacheDir.listFiles { file -> file.isFile && file.name.startsWith(TRANSCRIPT_PREFIX) }
            ?.forEach { if (!it.delete()) allGone = false }

        return allGone
    }

    companion object {
        const val CLASSROOM_AUDIO_DIR = "classroom_audio"
        const val TRANSCRIPT_PREFIX = "transcript_"
    }
}
