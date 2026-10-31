package com.example.tryhearingaid.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Level meaning "no sound at all" in the diagnostic readings (dB relative to full scale). */
const val SILENCE_DB = -90f

/**
 * A live health reading of the amplifier, to find out why the sound is bad.
 * Levels are in dB relative to full scale (0 dB is the loudest a recording can be).
 */
data class AudioDiagnostics(
    val running: Boolean = false,
    val sampleRate: Int = 0,
    val frameMs: Float = 0f,
    /** Delay held in the system buffers, in ms. */
    val bufferMs: Float = 0f,
    val inputPeakDb: Float = SILENCE_DB,
    val inputRmsDb: Float = SILENCE_DB,
    val outputPeakDb: Float = SILENCE_DB,
    val outputRmsDb: Float = SILENCE_DB,
    /** Output samples that hit the ceiling in the last window: they sound harsh and crackly. */
    val clippedSamples: Int = 0,
    /** Times the microphone returned an error or an empty read since starting. */
    val readProblems: Int = 0,
    /** Times the output ran out of sound since starting (heard as crackle). */
    val underrunsTotal: Int = 0,
    val underrunsRecent: Int = 0,
    /** How much of each frame's time the processing takes (100 means it is too slow). */
    val loadPercent: Int = 0,
    val maxLoadPercent: Int = 0,
    val micSource: String = "",
    /** Android output device type number (see [AudioDiagnosticsAdvisor.deviceName]). */
    val outputDeviceType: Int = -1,
    val rawMode: Boolean = true,
    val gainDb: Float = 0f
)

/** Measurements of one short window of audio. */
data class DiagnosticsWindow(
    val frames: Int = 0,
    val inputPeakDb: Float = SILENCE_DB,
    val inputRmsDb: Float = SILENCE_DB,
    val outputPeakDb: Float = SILENCE_DB,
    val outputRmsDb: Float = SILENCE_DB,
    val clippedSamples: Int = 0,
    val loadPercent: Int = 0
)

/** Collects measurements frame by frame and reports them per window. Not thread safe. */
class DiagnosticsAccumulator {
    private var frames = 0
    private var inPeak = 0
    private var outPeak = 0
    private var inSumSq = 0.0
    private var outSumSq = 0.0
    private var inCount = 0L
    private var outCount = 0L
    private var clipped = 0
    private var processingNs = 0L
    private var budgetNs = 0L

    /**
     * Record one frame: the microphone samples, the samples sent to the output, how long the
     * processing took and how long it is allowed to take (the frame's duration).
     */
    fun onFrame(
        input: ShortArray, inputCount: Int,
        output: ShortArray, outputCount: Int,
        processingNs: Long, frameBudgetNs: Long
    ) {
        frames++
        for (i in 0 until minOf(inputCount, input.size)) {
            val v = abs(input[i].toInt())
            if (v > inPeak) inPeak = v
            inSumSq += v.toDouble() * v
            inCount++
        }
        for (i in 0 until minOf(outputCount, output.size)) {
            val s = output[i].toInt()
            val v = abs(s)
            if (v > outPeak) outPeak = v
            if (s >= Short.MAX_VALUE.toInt() || s <= Short.MIN_VALUE.toInt()) clipped++
            outSumSq += v.toDouble() * v
            outCount++
        }
        this.processingNs += processingNs
        this.budgetNs += frameBudgetNs
    }

    /** Result of the window so far; starts a new window. */
    fun takeWindow(): DiagnosticsWindow {
        val window = DiagnosticsWindow(
            frames = frames,
            inputPeakDb = toDb(inPeak.toDouble()),
            inputRmsDb = toDb(if (inCount > 0) sqrt(inSumSq / inCount) else 0.0),
            outputPeakDb = toDb(outPeak.toDouble()),
            outputRmsDb = toDb(if (outCount > 0) sqrt(outSumSq / outCount) else 0.0),
            clippedSamples = clipped,
            loadPercent = if (budgetNs > 0) (processingNs * 100 / budgetNs).toInt() else 0
        )
        frames = 0; inPeak = 0; outPeak = 0; inSumSq = 0.0; outSumSq = 0.0
        inCount = 0; outCount = 0; clipped = 0; processingNs = 0; budgetNs = 0
        return window
    }

    companion object {
        /** Level of a sample value (0 to 32768) in dB relative to full scale, at least [SILENCE_DB]. */
        fun toDb(value: Double): Float {
            if (value <= 0.0) return SILENCE_DB
            return (20.0 * log10(value / 32768.0)).toFloat().coerceAtLeast(SILENCE_DB)
        }
    }
}

enum class FindingLevel { OK, INFO, WARNING, PROBLEM }

/** One plain-language result of the diagnostic. */
data class DiagnosticFinding(val level: FindingLevel, val text: String)

/** Turns the raw readings into plain-language advice. Pure, so it can be tested. */
object AudioDiagnosticsAdvisor {

    /** Output device types that are Bluetooth or hearing aid links. */
    private val bluetoothTypes = setOf(7, 8, 23, 26)

    fun deviceName(type: Int): String = when (type) {
        1 -> "phone earpiece"
        2 -> "phone speaker"
        3 -> "wired headset"
        4 -> "wired headphones"
        7 -> "Bluetooth call link"
        8 -> "Bluetooth headphones"
        22 -> "USB headset"
        23 -> "hearing aid"
        26 -> "Bluetooth LE headset"
        -1 -> "unknown"
        else -> "device type $type"
    }

    fun advise(
        d: AudioDiagnostics,
        nativeSampleRate: Int,
        nativeFramesPerBuffer: Int
    ): List<DiagnosticFinding> {
        if (!d.running) {
            return listOf(
                DiagnosticFinding(
                    FindingLevel.INFO,
                    "The amplifier is not running. Plug in headphones, switch it on, then check again."
                )
            )
        }
        val out = mutableListOf<DiagnosticFinding>()

        if (d.outputDeviceType == 2 || d.outputDeviceType == 1) {
            out += DiagnosticFinding(
                FindingLevel.PROBLEM,
                "The sound is going to the phone's own ${deviceName(d.outputDeviceType)}, " +
                    "not your headphones. It will squeal and sound poor. Reconnect the headphones."
            )
        }
        if (d.outputDeviceType == 7) {
            out += DiagnosticFinding(
                FindingLevel.PROBLEM,
                "The headphones are in phone-call mode, which is narrow and muffled. " +
                    "Other apps using the microphone can cause this. Close them, or try wired headphones."
            )
        } else if (d.outputDeviceType in bluetoothTypes) {
            out += DiagnosticFinding(
                FindingLevel.INFO,
                "Bluetooth adds its own delay and squeezes the sound. That is not the app. " +
                    "Test with wired headphones to hear the app on its own."
            )
        }
        if (d.clippedSamples > 0) {
            out += DiagnosticFinding(
                FindingLevel.PROBLEM,
                "The output is hitting its ceiling (${d.clippedSamples} samples just now). " +
                    "This sounds harsh and crackly. Lower the gain."
            )
        }
        if (d.inputPeakDb > -1f) {
            out += DiagnosticFinding(
                FindingLevel.WARNING,
                "The microphone signal is overloaded (peak ${fmt(d.inputPeakDb)} dB). " +
                    "Move away from the loud sound or lower the gain."
            )
        } else if (d.inputRmsDb < -65f) {
            out += DiagnosticFinding(
                FindingLevel.WARNING,
                "The microphone signal is almost silent (${fmt(d.inputRmsDb)} dB). " +
                    "The microphone may be blocked or in use by another app."
            )
        }
        if (d.underrunsRecent > 0) {
            out += DiagnosticFinding(
                FindingLevel.PROBLEM,
                "The output is running dry right now (${d.underrunsRecent} dropouts), which sounds like crackle. " +
                    "Raise the Delay setting."
            )
        } else if (d.underrunsTotal > 0) {
            out += DiagnosticFinding(
                FindingLevel.WARNING,
                "The output ran dry ${d.underrunsTotal} times since starting. " +
                    "If you hear crackle, raise the Delay setting."
            )
        }
        if (d.readProblems > 0) {
            out += DiagnosticFinding(
                FindingLevel.WARNING,
                "The microphone gave errors ${d.readProblems} times. Another app may be using it."
            )
        }
        if (d.maxLoadPercent > 60) {
            out += DiagnosticFinding(
                FindingLevel.WARNING,
                "The processing is using ${d.maxLoadPercent}% of each frame's time, so it can fall behind. " +
                    "Turn off noise reduction or voice focus, or use a longer Delay."
            )
        }
        if (nativeSampleRate > 0 && d.sampleRate != nativeSampleRate) {
            out += DiagnosticFinding(
                FindingLevel.INFO,
                "This phone natively plays at $nativeSampleRate Hz, but the amplifier uses ${d.sampleRate} Hz. " +
                    "The phone converts it, which can soften high sounds and add delay. Try $nativeSampleRate Hz if it is offered."
            )
        }
        if (d.rawMode && d.micSource.isNotEmpty() && d.micSource != "UNPROCESSED") {
            out += DiagnosticFinding(
                FindingLevel.INFO,
                "This phone gave the normal microphone (${d.micSource}), not the unprocessed one. " +
                    "The phone may still apply its own volume levelling to it, which is outside the app's control."
            )
        }
        if (nativeFramesPerBuffer > 0 && d.sampleRate > 0) {
            val nativeMs = nativeFramesPerBuffer * 1000f / d.sampleRate
            if (d.frameMs in 0.1f..nativeMs / 2f) {
                out += DiagnosticFinding(
                    FindingLevel.INFO,
                    "Each frame (${fmt(d.frameMs)} ms) is shorter than the phone's own audio block " +
                        "(about ${fmt(nativeMs)} ms). Very short frames can crackle on this phone."
                )
            }
        }

        if (out.none { it.level == FindingLevel.PROBLEM || it.level == FindingLevel.WARNING }) {
            out += DiagnosticFinding(
                FindingLevel.OK,
                "No problems found inside the app. If it still sounds bad, the cause is probably the " +
                    "microphone, the headphones or the Bluetooth link."
            )
        }
        return out
    }

    /** Plain text of the readings and findings, to copy and send. */
    fun report(
        d: AudioDiagnostics,
        findings: List<DiagnosticFinding>,
        nativeSampleRate: Int,
        nativeFramesPerBuffer: Int
    ): String = buildString {
        appendLine("Sound diagnostic")
        appendLine("Running: ${d.running}")
        appendLine("Sample rate: ${d.sampleRate} Hz (phone native: $nativeSampleRate Hz, block: $nativeFramesPerBuffer frames)")
        appendLine("Frame: ${fmt(d.frameMs)} ms, buffers: ${fmt(d.bufferMs)} ms")
        appendLine("Mode: ${if (d.rawMode) "raw" else "processed"}, gain: ${fmt(d.gainDb)} dB")
        appendLine("Microphone source: ${d.micSource.ifEmpty { "unknown" }}")
        appendLine("Output: ${deviceName(d.outputDeviceType)}")
        appendLine("Input peak/rms: ${fmt(d.inputPeakDb)} / ${fmt(d.inputRmsDb)} dB")
        appendLine("Output peak/rms: ${fmt(d.outputPeakDb)} / ${fmt(d.outputRmsDb)} dB")
        appendLine("Clipped samples: ${d.clippedSamples}")
        appendLine("Dropouts: ${d.underrunsTotal} (recent ${d.underrunsRecent}), mic errors: ${d.readProblems}")
        appendLine("Processing load: ${d.loadPercent}% (max ${d.maxLoadPercent}%)")
        appendLine("Findings:")
        findings.forEach { appendLine("- [${it.level}] ${it.text}") }
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)
}
