package com.example.tryhearingaid.audio

import com.example.tryhearingaid.data.Audiogram
import com.example.tryhearingaid.data.EqBand
import kotlinx.coroutines.flow.StateFlow

interface Equalizer : AudioProcessingStage {
    val bands: StateFlow<List<EqBand>>
    fun setBandGain(bandIndex: Int, gainDb: Float)   // -20..+40 dB
    fun applyAudiogram(audiogram: Audiogram)
    fun reset()
}
