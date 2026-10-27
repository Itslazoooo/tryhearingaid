package com.example.tryhearingaid.privacy

import android.Manifest
import com.example.tryhearingaid.ui.permissions.PermissionUiEvent
import com.example.tryhearingaid.ui.permissions.PermissionsViewModel
import com.example.tryhearingaid.ui.permissions.permissionFeatureMap
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// ─── Property 31 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 31: Disabling a permission disables all dependent features

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class PermissionRevocationPropertyTest : FreeSpec({

    "Property 31: Disabling a permission disables all dependent features" {
        // Feature: advanced-hearing-aid-app, Property 31: Disabling a permission disables all dependent features
        val permissions = permissionFeatureMap.keys.toList()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            checkAll(
                PropTestConfig(iterations = 100),
                Arb.list(Arb.boolean(), permissions.size..permissions.size), // which permissions start granted
                Arb.int(0..permissions.size - 1)                              // which one gets revoked
            ) { startGranted, revokeIndex ->
                val revoked = permissions[revokeIndex]
                // The revoked permission must have been granted first, or there is nothing to revoke.
                val granted = permissions.filterIndexed { i, _ -> startGranted[i] || i == revokeIndex }

                val viewModel = PermissionsViewModel()
                val events = mutableListOf<PermissionUiEvent>()
                val collector = CoroutineScope(Dispatchers.Unconfined).launch {
                    viewModel.events.collect { events += it }
                }

                granted.forEach { viewModel.onPermissionResult(it, true) }
                viewModel.onPermissionResult(revoked, false)

                val enabled = viewModel.uiState.value.enabledFeatures
                val dependents = permissionFeatureMap.getValue(revoked)

                // Every feature that needs the revoked permission is now disabled ...
                dependents.isNotEmpty() shouldBe true
                dependents.none { it in enabled } shouldBe true
                // ... features that need a permission still granted are untouched ...
                granted.filter { it != revoked }.forEach { other ->
                    permissionFeatureMap.getValue(other).all { it in enabled } shouldBe true
                }
                // ... the user is told exactly which features were lost ...
                events.shouldContainExactly(PermissionUiEvent.PermissionRevoked(revoked, dependents))
                // ... and they come back once the permission is granted again.
                viewModel.onPermissionResult(revoked, true)
                dependents.all { it in viewModel.uiState.value.enabledFeatures } shouldBe true

                collector.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
})

// ─── Unit tests: refresh from the system ─────────────────────────────────────

/** Tests for [PermissionsViewModel.refresh] (Requirement 16.3). */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionsRefreshTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val allFeatures = permissionFeatureMap.values.flatten().toSet()

    @Test
    fun `refresh with everything granted enables every feature`() {
        val viewModel = PermissionsViewModel()

        viewModel.refresh { true }

        viewModel.uiState.value.enabledFeatures shouldBe allFeatures
    }

    @Test
    fun `refresh notices a permission switched off and names the lost feature`() {
        val viewModel = PermissionsViewModel()
        val events = mutableListOf<PermissionUiEvent>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.events.collect { events += it }
        }
        viewModel.refresh { true }

        viewModel.refresh { it != Manifest.permission.CAMERA }

        (viewModel.uiState.value.enabledFeatures.contains("Flashlight Alerts")) shouldBe false
        viewModel.uiState.value.isCameraGranted shouldBe false
        events.shouldContainExactly(
            PermissionUiEvent.PermissionRevoked(Manifest.permission.CAMERA, listOf("Flashlight Alerts"))
        )
        collector.cancel()
    }

    @Test
    fun `refreshing again with the same state raises no further events`() {
        val viewModel = PermissionsViewModel()
        val events = mutableListOf<PermissionUiEvent>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.events.collect { events += it }
        }
        viewModel.refresh { true }

        viewModel.refresh { true }
        viewModel.refresh { true }

        events.shouldBeEmpty()
        collector.cancel()
    }
}
