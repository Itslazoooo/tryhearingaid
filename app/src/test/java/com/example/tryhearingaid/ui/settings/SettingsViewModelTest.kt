package com.example.tryhearingaid.ui.settings

import com.example.tryhearingaid.data.DataRepository
import com.example.tryhearingaid.data.DefaultAlerts
import com.example.tryhearingaid.data.InMemoryPreferencesRepository
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.profiles.ProfileManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val profileManager = mockk<ProfileManager>(relaxed = true) {
        every { profiles } returns MutableStateFlow(emptyList())
    }

    @Test
    fun `text to speech switch is saved`() = runTest {
        val prefs = InMemoryPreferencesRepository()
        val vm = SettingsViewModel(prefs, mockk(relaxed = true), profileManager)

        vm.setTtsEnabled(true)

        prefs.userPreferences.first().ttsEnabled shouldBe true
    }

    @Test
    fun `deleting all data calls the repository and reports success`() = runTest {
        val data = mockk<DataRepository>()
        coEvery { data.deleteAllData() } returns Unit
        val vm = SettingsViewModel(InMemoryPreferencesRepository(), data, profileManager)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }

        vm.deleteAllData()

        coVerify(exactly = 1) { data.deleteAllData() }
        vm.uiState.value.deleting shouldBe false
        vm.uiState.value.message!! shouldContain "deleted"
    }

    @Test
    fun `a failed deletion is reported and not hidden`() = runTest {
        val data = mockk<DataRepository>()
        coEvery { data.deleteAllData() } throws IllegalStateException("file locked")
        val prefs = InMemoryPreferencesRepository()
        val vm = SettingsViewModel(prefs, data, profileManager)
        // The state is only computed while something observes it.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { } }

        vm.deleteAllData()

        vm.uiState.value.message!! shouldContain "file locked"
    }

    @Test
    fun `first run alerts are a short list`() {
        (DefaultAlerts.CATEGORIES.size < SoundCategory.entries.size) shouldBe true
        (SoundCategory.DOORBELL in DefaultAlerts.CATEGORIES) shouldBe true
        (SoundCategory.BIRDS in DefaultAlerts.CATEGORIES) shouldBe false
    }
}
