package com.example.tryhearingaid.profiles

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Builds the sound for one hearing-test tone. Pure maths, so it can be tested without a device.
 *
 * The level scale is NOT clinically calibrated: 0 dBHL is mapped to -90 dBFS and 80 dBHL to
 * -10 dBFS, so every 5 dBHL step is exactly 5 dB. Real phone speakers and earbuds differ, so
 * results are a rough guide only and are not a medical hearing test.
 */
object ToneGenerator {

    const val SAMPLE_RATE = 44_100
    private const val DBFS_AT_0_DBHL = -90.0
    private const val MAX_DBHL = 80

    /** Peak amplitude (0..1) for a presentation level, clamped to 0..80 dBHL. */
    fun amplitudeFor(levelDbhl: Int): Double {
        val level = levelDbhl.coerceIn(0, MAX_DBHL)
        return 10.0.pow((level + DBFS_AT_0_DBHL) / 20.0)
    }

    /**
     * One second of interleaved stereo 16-bit audio holding a sine wave at [frequencyHz] in the
     * chosen [ear] only (the other channel is silent). One second holds a whole number of cycles,
     * so the buffer can be looped with no click at the join.
     */
    fun generate(frequencyHz: Int, levelDbhl: Int, ear: Ear): ShortArray {
        val peak = amplitudeFor(levelDbhl) * Short.MAX_VALUE
        val out = ShortArray(SAMPLE_RATE * 2)
        val toneChannel = if (ear == Ear.LEFT) 0 else 1
        for (i in 0 until SAMPLE_RATE) {
            val v = (sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE) * peak).roundToInt()
            out[i * 2 + toneChannel] = v.toShort()
        }
        return out
    }
}

/** Plays hearing-test tones through the phone's normal media output (speaker or headphones). */
@Singleton
class AudioTrackTonePlayer @Inject constructor() : TonePlayer {

    private var track: AudioTrack? = null

    @Synchronized
    override fun play(frequencyHz: Int, levelDbhl: Int, ear: Ear) {
        releaseTrack()
        val samples = ToneGenerator.generate(frequencyHz, levelDbhl, ear)
        try {
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(ToneGenerator.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            newTrack.write(samples, 0, samples.size)
            newTrack.setLoopPoints(0, ToneGenerator.SAMPLE_RATE, -1)
            newTrack.play()
            track = newTrack
        } catch (e: Exception) {
            Log.w(TAG, "Could not play the test tone.", e)
        }
    }

    @Synchronized
    override fun stop() = releaseTrack()

    private fun releaseTrack() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    private companion object {
        const val TAG = "AudioTrackTonePlayer"
    }
}
