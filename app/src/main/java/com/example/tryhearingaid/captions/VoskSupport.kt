package com.example.tryhearingaid.captions

import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** One finished sentence from the speech model. */
data class Utterance(val text: String, val confidence: Float)

object VoskResults {
    /** Confidence used when the model gives no per-word scores. */
    const val DEFAULT_CONFIDENCE = 0.8f

    /**
     * Reads the JSON the model returns, for example `{"text":"hello there","result":[{"conf":0.9,...}]}`.
     * Returns null for silence (blank text) or text that cannot be read.
     */
    fun parse(json: String): Utterance? = try {
        val obj = JsonParser.parseString(json).asJsonObject
        val text = obj.get("text")?.asString?.trim().orEmpty()
        if (text.isEmpty()) {
            null
        } else {
            val scores = obj.getAsJsonArray("result")
                ?.mapNotNull { it.asJsonObject.get("conf")?.asFloat }
                .orEmpty()
            Utterance(text, if (scores.isEmpty()) DEFAULT_CONFIDENCE else scores.average().toFloat())
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Guesses when a different person starts talking: a pause of at least 1.5 s together with a change in
 * loudness of more than 30 percent. The same rule the built-in recognizer path uses.
 */
class SpeakerTracker {
    private var index = 1
    private var lastEnergy = 0f
    private var lastTimeMs = 0L

    fun labelFor(energy: Float, nowMs: Long): String {
        if (lastEnergy > 0f && lastTimeMs > 0L &&
            nowMs - lastTimeMs >= MIN_GAP_MS &&
            abs(energy - lastEnergy) / lastEnergy > ENERGY_CHANGE
        ) {
            index++
        }
        lastEnergy = energy
        lastTimeMs = nowMs
        return "Speaker $index"
    }

    fun reset() {
        index = 1
        lastEnergy = 0f
        lastTimeMs = 0L
    }

    private companion object {
        const val MIN_GAP_MS = 1_500L
        const val ENERGY_CHANGE = 0.30f
    }
}

/** Unpacks a downloaded model while it arrives, refusing files that would land outside the folder. */
object ZipExtractor {
    fun extract(input: InputStream, destination: File) {
        val root = destination.canonicalFile
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(root, entry.name).canonicalFile
                if (target != root && !target.path.startsWith(root.path + File.separator)) {
                    throw IOException("The download contains an unsafe file path.")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}

/** Finds the folder that holds the model (the one containing "am"), inside [dir] or one level below. */
fun findModelRoot(dir: File): File? {
    if (!dir.isDirectory) return null
    if (File(dir, "am").isDirectory) return dir
    return dir.listFiles()?.firstOrNull { it.isDirectory && File(it, "am").isDirectory }
}

/** Reports how many bytes have been read so far. */
class CountingInputStream(
    source: InputStream,
    private val onBytes: (Long) -> Unit
) : FilterInputStream(source) {
    private var total = 0L

    override fun read(): Int = super.read().also { if (it >= 0) add(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

    private fun add(n: Long) {
        total += n
        onBytes(total)
    }
}
