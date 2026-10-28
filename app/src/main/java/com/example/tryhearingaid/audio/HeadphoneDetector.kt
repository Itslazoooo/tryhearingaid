package com.example.tryhearingaid.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tells whether headphones (or a hearing aid or earbuds) are connected to this phone. */
interface HeadphoneDetector {
    val isConnected: StateFlow<Boolean>
}

/** Which kinds of audio output count as headphones. Kept separate so it can be tested. */
object HeadphonePolicy {
    // Values of the AudioDeviceInfo.TYPE_* constants. Written as numbers because some were added
    // in newer Android versions than the app's minimum.
    private const val WIRED_HEADSET = 3
    private const val WIRED_HEADPHONES = 4
    private const val BLUETOOTH_A2DP = 8
    private const val USB_HEADSET = 22
    private const val HEARING_AID = 23
    private const val BLE_HEADSET = 26

    private val HEADPHONE_TYPES = setOf(
        WIRED_HEADSET, WIRED_HEADPHONES, BLUETOOTH_A2DP, USB_HEADSET, HEARING_AID, BLE_HEADSET
    )

    /** True for outputs worn on or in the ear. The phone's own speaker and earpiece are not. */
    fun isHeadphone(deviceType: Int): Boolean = deviceType in HEADPHONE_TYPES

    fun anyHeadphone(deviceTypes: Collection<Int>): Boolean = deviceTypes.any(::isHeadphone)
}

@Singleton
class AndroidHeadphoneDetector @Inject constructor(
    @ApplicationContext context: Context
) : HeadphoneDetector {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isConnected = MutableStateFlow(currentlyConnected())
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
        }, Handler(Looper.getMainLooper()))
    }

    private fun refresh() {
        _isConnected.value = currentlyConnected()
    }

    private fun currentlyConnected(): Boolean =
        HeadphonePolicy.anyHeadphone(
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        )
}
