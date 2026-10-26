package com.example.tryhearingaid.wearable

import com.example.tryhearingaid.data.BluetoothDeviceInfo
import com.example.tryhearingaid.data.CaptionSegment
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

// ─── Property 26 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 26: Battery level is present for devices that expose it, null otherwise

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class WearableBatteryPropertyTest : FreeSpec({

    "Property 26: Battery level is present for devices that expose it, null otherwise" {
        // Feature: advanced-hearing-aid-app, Property 26: Battery level is present for devices that expose it, null otherwise

        checkAll(
            PropTestConfig(iterations = 100),
            Arb.boolean(),          // does the device expose the GATT battery characteristic?
            Arb.int(0, 100),        // the battery level it would report
            Arb.string(1, 20)       // device name (must not affect battery handling)
        ) { exposesBattery, level, name ->
            val address = "AA:BB:CC:DD:EE:FF"
            val bridge = RecordingWearableBridge(
                batteryByAddress = mapOf(address to if (exposesBattery) level else null)
            )

            val info = runBlocking { bridge.buildDeviceInfo(address, name, -1) }

            if (exposesBattery) {
                info.batteryPercent shouldNotBe null
                info.batteryPercent!! shouldBeInRange 0..100
                info.batteryPercent shouldBe level
            } else {
                info.batteryPercent shouldBe null
            }
        }
    }
})

// ─── Property 27 ──────────────────────────────────────────────────────────────
// Feature: advanced-hearing-aid-app, Property 27: Caption text is forwarded to display-capable wearables

@RunWith(io.kotest.runner.junit4.KotestTestRunner::class)
class WearableCaptionPropertyTest : FreeSpec({

    "Property 27: Caption text is forwarded to display-capable wearables" {
        // Feature: advanced-hearing-aid-app, Property 27: Caption text is forwarded to display-capable wearables

        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(Arb.boolean(), 0..6),  // one entry per connected device: supportsDisplay?
            Arb.string(1, 60)               // caption text
        ) { displayFlags, text ->
            val bridge = RecordingWearableBridge()
            displayFlags.forEachIndexed { index, supportsDisplay ->
                bridge.addConnectedDevice(
                    BluetoothDeviceInfo(
                        address = "dev-$index",
                        name = "device $index",
                        batteryPercent = null,
                        supportsDisplay = supportsDisplay
                    )
                )
            }
            val segment = CaptionSegment(
                speakerLabel = "Speaker 1",
                text = text,
                confidence = 0.9f,
                timestampMs = 0L,
                isUncertain = false
            )

            bridge.sendCaptions(segment)

            val expectedRecipients = displayFlags
                .mapIndexedNotNull { index, supportsDisplay -> if (supportsDisplay) "dev-$index" else null }
                .toSet()

            // Exactly the display-capable devices received the caption, carrying its text...
            bridge.captionDeliveries.map { it.first }.toSet() shouldBe expectedRecipients
            bridge.captionDeliveries.all { it.second.text == text } shouldBe true
            // ...and nothing is recorded as sent when no device can display it.
            bridge.sentCaptions.isNotEmpty() shouldBe expectedRecipients.isNotEmpty()
        }
    }
})
