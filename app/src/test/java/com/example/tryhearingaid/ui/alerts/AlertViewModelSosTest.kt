package com.example.tryhearingaid.ui.alerts

import com.example.tryhearingaid.ai.AIAssistant
import com.example.tryhearingaid.data.SoundCategory
import com.example.tryhearingaid.data.SoundDetection
import com.example.tryhearingaid.emergency.SmsSender
import com.example.tryhearingaid.emergency.SosController
import com.example.tryhearingaid.emergency.SosCoordinator
import com.example.tryhearingaid.emergency.SosLocation
import com.example.tryhearingaid.emergency.SosLocationSource
import com.example.tryhearingaid.environmental.NarrationController
import com.example.tryhearingaid.environmental.SoundNarrator
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for the SOS and environmental-awareness parts of [AlertViewModel]
 * (Requirements 10.3, 10.4, 15.1 to 15.5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertViewModelSosTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val fix = SosLocation(14.5995, 120.9842, 0L)

    private val location = object : SosLocationSource {
        override suspend fun current(): SosLocation? = fix
        override fun lastKnown(): SosLocation? = null
    }

    private fun sosController(contacts: List<String>, failFor: Set<String> = emptySet()) = SosController(
        contacts = { contacts },
        location = location,
        coordinator = SosCoordinator(SmsSender { number, _ -> number !in failFor }),
        dispatcher = Dispatchers.Unconfined
    )

    @Test
    fun `SOS without a controller reports an error`() {
        val vm = AlertViewModel()

        vm.handleIntent(AlertIntent.TriggerSos)

        (vm.uiState.value.sosStatus is SosStatus.Error) shouldBe true
    }

    @Test
    fun `SOS with no saved contacts reports NoContacts`() {
        val vm = AlertViewModel(sosController = sosController(emptyList()))

        vm.handleIntent(AlertIntent.TriggerSos)

        vm.uiState.value.sosStatus shouldBe SosStatus.NoContacts
    }

    @Test
    fun `SOS reaches every contact`() {
        val vm = AlertViewModel(sosController = sosController(listOf("111", "222")))

        vm.handleIntent(AlertIntent.TriggerSos)

        vm.uiState.value.sosStatus shouldBe SosStatus.Sent(2)
    }

    @Test
    fun `SOS where one contact fails is reported as partly failed`() {
        val vm = AlertViewModel(sosController = sosController(listOf("good", "bad"), failFor = setOf("bad")))

        vm.handleIntent(AlertIntent.TriggerSos)

        vm.uiState.value.sosStatus shouldBe SosStatus.PartlyFailed(sent = 1, failed = listOf("bad"))
    }

    @Test
    fun `SOS where every contact fails is reported as all failed`() {
        val vm = AlertViewModel(sosController = sosController(listOf("a", "b"), failFor = setOf("a", "b")))

        vm.handleIntent(AlertIntent.TriggerSos)

        vm.uiState.value.sosStatus shouldBe SosStatus.AllFailed(listOf("a", "b"))
    }

    @Test
    fun `dismissing the SOS result returns to Idle`() {
        val vm = AlertViewModel(sosController = sosController(listOf("111")))
        vm.handleIntent(AlertIntent.TriggerSos)

        vm.handleIntent(AlertIntent.DismissSosStatus)

        vm.uiState.value.sosStatus shouldBe SosStatus.Idle
    }

    @Test
    fun `awareness mode shows a card for a detection and clears it when switched off`() = runTest {
        val assistant = mockk<AIAssistant> {
            coEvery { describeSound(any(), any()) } returns ""
        }
        val detections = MutableSharedFlow<SoundDetection>()
        val narration = NarrationController(
            scope = CoroutineScope(Dispatchers.Unconfined + Job()),
            detections = detections,
            narrator = SoundNarrator(assistant)
        )
        val vm = AlertViewModel(narrationController = narration)

        vm.handleIntent(AlertIntent.SetAwareness(true))
        vm.uiState.value.awarenessEnabled shouldBe true

        detections.emit(SoundDetection(SoundCategory.DOORBELL, 0.9f, System.currentTimeMillis()))
        vm.uiState.value.descriptionCards.single().text shouldBe "A doorbell was detected nearby"

        vm.handleIntent(AlertIntent.SetAwareness(false))
        vm.uiState.value.awarenessEnabled shouldBe false
        vm.uiState.value.descriptionCards.size shouldBe 0
    }
}
