package com.example.tryhearingaid.ui.alerts

import com.example.tryhearingaid.classification.EmergencyDetector
import com.example.tryhearingaid.classification.SoundClassifier
import com.example.tryhearingaid.data.AlertChannel
import com.example.tryhearingaid.data.EmergencyEvent
import com.example.tryhearingaid.data.FlashlightPattern
import com.example.tryhearingaid.data.InMemoryPreferencesRepository
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for how [AlertViewModel] connects to saved settings, the live classifier and the
 * emergency detector (Requirements 9.3, 9.4, 9.5, 10.2, 10.3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertViewModelPipelineTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeClassifier : SoundClassifier {
        val source = MutableSharedFlow<SoundDetection>()
        override val detections: SharedFlow<SoundDetection> = source
        override fun setEnabledCategories(categories: Set<SoundCategory>) = Unit
        override fun setEnabled(enabled: Boolean) = Unit
        override fun submitAudioFrame(samples: ShortArray) = Unit
    }

    private class FakeEmergencyDetector : EmergencyDetector {
        val source = MutableSharedFlow<EmergencyEvent>()
        override val emergencyEvents: SharedFlow<EmergencyEvent> = source
        override val isActive: StateFlow<Boolean> = MutableStateFlow(true)
        override fun start() = Unit
        override fun stop() = Unit
    }

    @Test
    fun `saved alert settings are shown when the screen opens`() = runTest {
        val prefs = InMemoryPreferencesRepository()
        prefs.updateEnabledSoundCategories(setOf("DOORBELL"))
        prefs.updateAlertChannels(setOf("VIBRATION"))
        prefs.updateFlashlightPattern("SOS")

        val vm = AlertViewModel(preferences = prefs)

        vm.uiState.value.enabledCategories shouldBe setOf(SoundCategory.DOORBELL)
        vm.uiState.value.alertChannels shouldBe setOf(AlertChannel.VIBRATION)
        vm.uiState.value.flashlightPattern shouldBe FlashlightPattern.SOS
    }

    @Test
    fun `turning a category off is saved`() = runTest {
        val prefs = InMemoryPreferencesRepository()
        val vm = AlertViewModel(preferences = prefs)

        vm.handleIntent(AlertIntent.ToggleCategory(SoundCategory.BIRDS, enabled = false))

        val saved = prefs.userPreferences.first().enabledSoundCategories
        (SoundCategory.BIRDS.name in saved) shouldBe false
        (SoundCategory.DOORBELL.name in saved) shouldBe true
    }

    @Test
    fun `changing a channel and the flashlight pattern is saved`() = runTest {
        val prefs = InMemoryPreferencesRepository()
        val vm = AlertViewModel(preferences = prefs)

        vm.handleIntent(AlertIntent.ToggleChannel(AlertChannel.FLASHLIGHT, enabled = false))
        vm.handleIntent(AlertIntent.SetFlashlightPattern(FlashlightPattern.LONG))

        val saved = prefs.userPreferences.first()
        (AlertChannel.FLASHLIGHT.name in saved.alertChannels) shouldBe false
        saved.flashlightPattern shouldBe FlashlightPattern.LONG.name
    }

    @Test
    fun `without saved settings a toggle still changes the screen`() {
        val vm = AlertViewModel()

        vm.handleIntent(AlertIntent.ToggleCategory(SoundCategory.BIRDS, enabled = false))

        (SoundCategory.BIRDS in vm.uiState.value.enabledCategories) shouldBe false
    }

    @Test
    fun `emergency contacts are cleaned, saved and shown`() = runTest {
        val prefs = InMemoryPreferencesRepository()
        val vm = AlertViewModel(preferences = prefs)

        vm.handleIntent(AlertIntent.SaveSosContacts(listOf(" 111 ", "", "222", "111")))

        vm.uiState.value.sosContacts.shouldContainExactly("111", "222")
        prefs.userPreferences.first().sosContacts.shouldContainExactly("111", "222")
    }

    @Test
    fun `a live detection from the classifier appears in recent detections`() = runTest {
        val classifier = FakeClassifier()
        val vm = AlertViewModel(soundClassifier = classifier)
        val detection = SoundDetection(SoundCategory.DOORBELL, 0.9f, 1_000L)

        classifier.source.emit(detection)

        vm.uiState.value.recentDetections.shouldContainExactly(detection)
    }

    @Test
    fun `a live detection for a disabled category is not shown`() = runTest {
        val classifier = FakeClassifier()
        val vm = AlertViewModel(soundClassifier = classifier)
        vm.handleIntent(AlertIntent.ToggleCategory(SoundCategory.DOORBELL, enabled = false))

        classifier.source.emit(SoundDetection(SoundCategory.DOORBELL, 0.9f, 1_000L))

        vm.uiState.value.recentDetections.size shouldBe 0
    }

    @Test
    fun `an emergency from the detector raises the emergency overlay`() = runTest {
        val detector = FakeEmergencyDetector()
        val vm = AlertViewModel(emergencyDetector = detector)
        val event = EmergencyEvent(SoundCategory.SMOKE_ALARM, 0.95f, 2_000L)

        detector.source.emit(event)

        vm.uiState.value.emergencyEvent shouldBe event
    }
}
