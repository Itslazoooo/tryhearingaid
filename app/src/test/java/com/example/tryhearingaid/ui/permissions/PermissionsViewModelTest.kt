package com.example.tryhearingaid.ui.permissions

import android.Manifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for [PermissionsViewModel].
 *
 * Tests verify:
 * 4.4a — the rationale screen gate: RECORD_AUDIO starts as not-granted, so the
 *         "Continue" button on [PermissionRationaleScreen] is disabled by default,
 *         ensuring the rationale screen is always shown before audio features activate.
 * 4.4b — revoking a previously-granted permission disables the affected feature and
 *         emits a [PermissionUiEvent.PermissionRevoked] event.
 *
 * Satisfies Requirements 1.4, 16.1, 16.3.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class PermissionsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: PermissionsViewModel

    @Before
    fun setUp() {
        // Replace the Main dispatcher with the test dispatcher so viewModelScope
        // coroutines run under our control in unit tests (no Android Looper needed).
        Dispatchers.setMain(testDispatcher)
        viewModel = PermissionsViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ─── 4.4a: Rationale screen appears before RECORD_AUDIO request ────────────

    /**
     * RECORD_AUDIO starts as not-granted in the initial state, which means
     * [PermissionsUiState.isAudioFeatureEnabled] is false and the "Continue"
     * button on [PermissionRationaleScreen] is disabled — enforcing that the
     * rationale screen must be presented before the audio engine can start.
     */
    @Test
    fun `rationale screen gate - RECORD_AUDIO not granted initially so audio feature is disabled`() {
        val state = viewModel.uiState.value

        assertFalse(
            "RECORD_AUDIO should not be granted before the user has seen the rationale screen",
            state.isRecordAudioGranted
        )
        assertFalse(
            "Audio feature must be disabled when RECORD_AUDIO is not granted",
            state.isAudioFeatureEnabled
        )
    }

    /**
     * After the user grants RECORD_AUDIO (post rationale), the audio feature
     * becomes enabled and the "Continue" button can be pressed.
     */
    @Test
    fun `granting RECORD_AUDIO updates state to granted`() = runTest {
        viewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, true)

        val state = viewModel.uiState.value

        assertTrue(
            "isRecordAudioGranted should be true after granting",
            state.isRecordAudioGranted
        )
        assertTrue(
            "isAudioFeatureEnabled should be true when RECORD_AUDIO is granted",
            state.isAudioFeatureEnabled
        )
    }

    // ─── 4.4b: Revocation disables feature and emits event ────────────────────

    /**
     * Revoking a previously-granted RECORD_AUDIO permission:
     * 1. Sets [PermissionsUiState.isAudioFeatureEnabled] to false.
     * 2. Emits [PermissionUiEvent.PermissionRevoked] containing "Audio Amplification"
     *    in [PermissionUiEvent.PermissionRevoked.affectedFeatures].
     */
    @Test
    fun `RECORD_AUDIO revocation disables audio feature and emits event`() = runTest {
        val collectedEvents = mutableListOf<PermissionUiEvent>()

        // Collect events in a child coroutine before triggering the revocation
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        // Grant first, then revoke
        viewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, true)
        viewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, false)

        // Advance the test dispatcher to process queued coroutines (viewModelScope.launch)
        testScheduler.advanceUntilIdle()

        // Verify state: audio feature is now disabled
        val state = viewModel.uiState.value
        assertFalse(
            "isAudioFeatureEnabled must be false after revoking RECORD_AUDIO",
            state.isAudioFeatureEnabled
        )
        assertFalse(
            "isRecordAudioGranted must be false after revocation",
            state.isRecordAudioGranted
        )

        // Verify event was emitted
        assertEquals("Expected exactly 1 revocation event", 1, collectedEvents.size)

        val event = collectedEvents.first() as PermissionUiEvent.PermissionRevoked
        assertEquals(Manifest.permission.RECORD_AUDIO, event.permission)
        assertTrue(
            "Affected features must include 'Audio Amplification'",
            event.affectedFeatures.contains("Audio Amplification")
        )
        assertTrue(
            "Affected features must include 'Live Captions'",
            event.affectedFeatures.contains("Live Captions")
        )

        collectJob.cancel()
    }

    // ─── No spurious events ───────────────────────────────────────────────────

    /**
     * Revoking a permission that was never granted must NOT emit a revocation event,
     * since there is nothing meaningful to notify the user about.
     */
    @Test
    fun `revoking non-granted permission does not emit revocation event`() = runTest {
        val collectedEvents = mutableListOf<PermissionUiEvent>()

        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        // Revoke without ever granting
        viewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, false)

        // Advance to process any pending coroutines
        testScheduler.advanceUntilIdle()

        assertTrue(
            "No event should be emitted when revoking a permission that was never granted",
            collectedEvents.isEmpty()
        )

        collectJob.cancel()
    }

    // ─── Other permissions ────────────────────────────────────────────────────

    /**
     * Revoking CAMERA disables the camera feature and emits an event with
     * "Flashlight Alerts" in the affected features list.
     */
    @Test
    fun `CAMERA revocation disables camera feature and emits event`() = runTest {
        val collectedEvents = mutableListOf<PermissionUiEvent>()

        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        viewModel.onPermissionResult(Manifest.permission.CAMERA, true)
        viewModel.onPermissionResult(Manifest.permission.CAMERA, false)

        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isCameraFeatureEnabled)

        assertEquals(1, collectedEvents.size)
        val event = collectedEvents.first() as PermissionUiEvent.PermissionRevoked
        assertTrue(event.affectedFeatures.contains("Flashlight Alerts"))

        collectJob.cancel()
    }

    /**
     * Granting a permission twice should NOT emit a revocation event on the
     * second grant call.
     */
    @Test
    fun `granting already-granted permission does not emit revocation event`() = runTest {
        val collectedEvents = mutableListOf<PermissionUiEvent>()

        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        viewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, true)
        viewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, true) // grant again

        testScheduler.advanceUntilIdle()

        assertTrue("No revocation event should be emitted on duplicate grant", collectedEvents.isEmpty())

        collectJob.cancel()
    }
}
