package com.example.tryhearingaid.privacy

import com.example.tryhearingaid.data.AnalyticsDao
import com.example.tryhearingaid.data.DataRepositoryImpl
import com.example.tryhearingaid.data.HearingAidDatabase
import com.example.tryhearingaid.data.InMemoryPreferencesRepository
import com.example.tryhearingaid.data.LocalUserFiles
import com.example.tryhearingaid.data.LocalUserFilesImpl
import com.example.tryhearingaid.data.ProfileDao
import com.example.tryhearingaid.data.TranscriptDao
import com.example.tryhearingaid.data.UserPreferences
import com.example.tryhearingaid.data.VocabularyDao
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory stand-in for the database tables. The DAOs below are mocks that empty these lists,
 * so a test can ask "is anything still retrievable?" without a real Room database.
 * (A real-database check needs an instrumented test on a device or emulator.)
 */
private class FakeStores {
    val profiles = mutableListOf<Int>()
    val sessions = mutableListOf<Int>()
    val segments = mutableListOf<Int>()
    val exposures = mutableListOf<Int>()
    val vocabulary = mutableListOf<Int>()

    var insideTransaction = false
    var transactionRuns = 0
    var deletesOutsideTransaction = 0

    /** Set to make the first deletion fail, as a disk or database error would. */
    var failOnProfileDelete = false

    val allEmpty: Boolean
        get() = listOf(profiles, sessions, segments, exposures, vocabulary).all { it.isEmpty() }

    fun fill(profiles: Int, sessions: Int, segments: Int, exposures: Int, vocabulary: Int) {
        repeat(profiles) { this.profiles += it }
        repeat(sessions) { this.sessions += it }
        repeat(segments) { this.segments += it }
        repeat(exposures) { this.exposures += it }
        repeat(vocabulary) { this.vocabulary += it }
    }

    private fun onDelete(list: MutableList<Int>) {
        if (!insideTransaction) deletesOutsideTransaction++
        list.clear()
    }

    /** A runner that behaves like a transaction as far as these tests can tell. */
    val runner: suspend (suspend () -> Unit) -> Unit = { block ->
        transactionRuns++
        insideTransaction = true
        try {
            block()
        } finally {
            insideTransaction = false
        }
    }

    fun database(): HearingAidDatabase {
        val profileDao = mockk<ProfileDao>()
        val transcriptDao = mockk<TranscriptDao>()
        val analyticsDao = mockk<AnalyticsDao>()
        val vocabularyDao = mockk<VocabularyDao>()

        coEvery { profileDao.deleteAllProfiles() } coAnswers {
            if (failOnProfileDelete) throw IllegalStateException("disk error")
            onDelete(profiles)
        }
        coEvery { transcriptDao.deleteAllSegments() } coAnswers { onDelete(segments) }
        coEvery { transcriptDao.deleteAllSessions() } coAnswers { onDelete(sessions) }
        coEvery { analyticsDao.deleteAll() } coAnswers { onDelete(exposures) }
        coEvery { vocabularyDao.clearAll() } coAnswers { onDelete(vocabulary) }

        val database = mockk<HearingAidDatabase>()
        every { database.profileDao() } returns profileDao
        every { database.transcriptDao() } returns transcriptDao
        every { database.analyticsDao() } returns analyticsDao
        every { database.vocabularyDao() } returns vocabularyDao
        return database
    }
}

// ─── Property 32 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 32: Data deletion leaves no retrievable user data

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class DataDeletionPropertyTest : FreeSpec({

    "Property 32: Data deletion leaves no retrievable user data" {
        // Feature: advanced-hearing-aid-app, Property 32: Data deletion leaves no retrievable user data
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.int(0..10), Arb.int(0..10), Arb.int(0..10), Arb.int(0..10), // profiles, sessions, segments, exposures
            Arb.int(0..10), Arb.int(0..6), Arb.int(0..6), Arb.int(0..5)     // vocabulary, recordings, exports, contacts
        ) { profiles, sessions, segments, exposures, vocabulary, recordings, exports, contacts ->
            val stores = FakeStores().apply { fill(profiles, sessions, segments, exposures, vocabulary) }
            val filesDir = Files.createTempDirectory("files").toFile()
            val cacheDir = Files.createTempDirectory("cache").toFile()
            try {
                val audioDir = File(filesDir, LocalUserFilesImpl.CLASSROOM_AUDIO_DIR).apply { mkdirs() }
                repeat(recordings) { File(audioDir, "session_$it.m4a").writeText("audio") }
                repeat(exports) { File(cacheDir, "${LocalUserFilesImpl.TRANSCRIPT_PREFIX}$it.txt").writeText("text") }
                File(cacheDir, "unrelated.tmp").writeText("not user data")

                val prefs = InMemoryPreferencesRepository().apply {
                    updateSosContacts((1..contacts).map { "09170000$it" })
                    updateTtsEnabled(true)
                    updateCloudAiEnabled(true)
                }
                val repository = DataRepositoryImpl(
                    db = stores.database(),
                    preferencesRepository = prefs,
                    localFiles = LocalUserFilesImpl(filesDir, cacheDir),
                    inTransaction = stores.runner
                )

                repository.deleteAllData()

                // Nothing is retrievable from any table ...
                stores.allEmpty shouldBe true
                // ... recordings and exports are gone, but files that are not user data are left alone ...
                File(filesDir, LocalUserFilesImpl.CLASSROOM_AUDIO_DIR).exists() shouldBe false
                cacheDir.list()!!.toList().shouldContainExactly("unrelated.tmp")
                // ... every preference, including the SOS contacts, is back to its default ...
                prefs.userPreferences.first() shouldBe UserPreferences()
                // ... and all table deletions ran inside the single transaction.
                stores.transactionRuns shouldBe 1
                stores.deletesOutsideTransaction shouldBe 0
            } finally {
                filesDir.deleteRecursively()
                cacheDir.deleteRecursively()
            }
        }
    }
})

// ─── Unit tests: failure cases and file cleaner ──────────────────────────────

/** Requirement 16.5: deletion must be complete, and must never claim success when it was not. */
class DataDeletionTest {

    @Test
    fun `a database failure leaves preferences and files untouched`() {
        runBlocking {
            val stores = FakeStores().apply {
                fill(profiles = 2, sessions = 2, segments = 2, exposures = 2, vocabulary = 2)
                failOnProfileDelete = true
            }
            val prefs = InMemoryPreferencesRepository().apply { updateSosContacts(listOf("111")) }
            var filesDeleted = false
            val repository = DataRepositoryImpl(
                db = stores.database(),
                preferencesRepository = prefs,
                localFiles = LocalUserFiles { filesDeleted = true; true },
                inTransaction = stores.runner
            )

            shouldThrow<IllegalStateException> { repository.deleteAllData() }

            filesDeleted shouldBe false
            prefs.userPreferences.first().sosContacts.shouldContainExactly("111")
        }
    }

    @Test
    fun `a file that cannot be deleted makes deletion fail loudly after preferences are cleared`() {
        runBlocking {
            val stores = FakeStores().apply { fill(1, 1, 1, 1, 1) }
            val prefs = InMemoryPreferencesRepository().apply { updateSosContacts(listOf("111")) }
            val repository = DataRepositoryImpl(
                db = stores.database(),
                preferencesRepository = prefs,
                localFiles = LocalUserFiles { false },
                inTransaction = stores.runner
            )

            shouldThrow<IllegalStateException> { repository.deleteAllData() }

            stores.allEmpty shouldBe true
            prefs.userPreferences.first() shouldBe UserPreferences()
        }
    }

    @Test
    fun `local file cleaner removes recordings and exports only`() {
        val filesDir = Files.createTempDirectory("files").toFile()
        val cacheDir = Files.createTempDirectory("cache").toFile()
        try {
            val audioDir = File(filesDir, LocalUserFilesImpl.CLASSROOM_AUDIO_DIR).apply { mkdirs() }
            File(audioDir, "a.m4a").writeText("x")
            File(audioDir, "nested").mkdirs()
            File(audioDir, "nested/b.m4a").writeText("x")
            File(filesDir, "datastore.keep").writeText("x") // other app files are not touched here
            File(cacheDir, "transcript_1.txt").writeText("x")
            File(cacheDir, "transcript_2.pdf").writeText("x")
            File(cacheDir, "image_cache.bin").writeText("x")

            LocalUserFilesImpl(filesDir, cacheDir).deleteAll() shouldBe true

            audioDir.exists() shouldBe false
            File(filesDir, "datastore.keep").exists() shouldBe true
            cacheDir.list()!!.toList().shouldContainExactly("image_cache.bin")
        } finally {
            filesDir.deleteRecursively()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `local file cleaner succeeds when there is nothing to delete`() {
        val filesDir = Files.createTempDirectory("files").toFile()
        val cacheDir = Files.createTempDirectory("cache").toFile()
        try {
            LocalUserFilesImpl(filesDir, cacheDir).deleteAll() shouldBe true
        } finally {
            filesDir.deleteRecursively()
            cacheDir.deleteRecursively()
        }
    }
}
