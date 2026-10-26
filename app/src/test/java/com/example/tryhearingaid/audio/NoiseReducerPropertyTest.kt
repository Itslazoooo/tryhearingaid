// Feature: advanced-hearing-aid-app, Property 2: Noise reducer environment preset is applied correctly
package com.example.tryhearingaid.audio

import android.content.Context
import android.content.res.AssetManager
import com.example.tryhearingaid.data.EnvironmentPreset
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.forAll
import io.mockk.every
import io.mockk.mockk
import java.io.IOException

/**
 * Property-based tests for [NoiseReducerImpl].
 *
 * **Validates: Requirements 2.4**
 */
class NoiseReducerPropertyTest : StringSpec({

    /**
     * Build a [NoiseReducerImpl] backed by a mock context whose AssetManager
     * always throws [IOException], forcing the spectral-subtraction fallback.
     * This ensures the test is fully deterministic with no model loading.
     */
    fun buildReducer(): NoiseReducerImpl {
        val assetManager = mockk<AssetManager>()
        val context = mockk<Context>()
        every { context.assets } returns assetManager
        every { assetManager.openFd(any()) } throws IOException("no model in test")
        return NoiseReducerImpl(context)
    }

    // ── Property 2 ────────────────────────────────────────────────────────────

    "Property 2 - for any EnvironmentPreset, getActivePreset returns the same value that was set" {
        forAll(
            PropTestConfig(iterations = 100),
            io.kotest.property.Arb.enum<EnvironmentPreset>()
        ) { preset ->
            val reducer = buildReducer()
            reducer.setEnvironmentPreset(preset)
            reducer.getActivePreset() == preset
        }
    }

    "Property 2 - preset round-trip: set then get is idempotent for any preset value" {
        forAll(
            PropTestConfig(iterations = 100),
            io.kotest.property.Arb.enum<EnvironmentPreset>()
        ) { preset ->
            val reducer = buildReducer()
            reducer.setEnvironmentPreset(preset)
            // Set the same value again and verify it is still returned correctly
            reducer.setEnvironmentPreset(preset)
            reducer.getActivePreset() == preset
        }
    }
})
