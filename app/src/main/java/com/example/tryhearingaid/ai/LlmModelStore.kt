package com.example.tryhearingaid.ai

import android.content.Context
import android.util.Log
import com.example.tryhearingaid.captions.ModelState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The offline AI model that can be downloaded. */
object LlmCatalog {
    data class Entry(
        val name: String,
        val url: String,
        val fileName: String,
        val approxMb: Int,
        /** A finished download is never smaller than this; smaller means it is incomplete or wrong. */
        val minBytes: Long
    )

    /** Qwen2.5 0.5B Instruct (Apache 2.0), 8-bit, packaged for on-device use. */
    val DEFAULT = Entry(
        name = "Qwen2.5 0.5B Instruct",
        url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/" +
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        fileName = "qwen2.5-0.5b-instruct-q8.task",
        approxMb = 547,
        minBytes = 500_000_000L
    )

    /** The most text (prompt plus answer) the packaged model can handle at once, in tokens. */
    const val CONTEXT_TOKENS = 1280
}

/**
 * Downloads, keeps and removes the offline AI model. The download happens once, on request, and
 * carries on from where it stopped if it is interrupted.
 */
@Singleton
class LlmModelStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val entry = LlmCatalog.DEFAULT
    private val dir = File(context.filesDir, "llm")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ModelState>(
        if (modelFile() != null) ModelState.Ready else ModelState.NotDownloaded
    )
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** The downloaded model file, or null if it is not (completely) downloaded. */
    fun modelFile(): File? =
        File(dir, entry.fileName).takeIf { it.isFile && it.length() >= entry.minBytes }

    fun isReady(): Boolean = modelFile() != null

    fun startDownload() {
        if (_state.value is ModelState.Downloading) return
        _state.value = ModelState.Downloading(percentOf(partFile().length(), 0))
        scope.launch { download() }
    }

    fun delete() {
        File(dir, entry.fileName).delete()
        partFile().delete()
        _state.value = ModelState.NotDownloaded
    }

    private fun partFile() = File(dir, entry.fileName + ".part")

    private fun percentOf(done: Long, total: Long): Int =
        if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else 0

    private fun download() {
        val part = partFile()
        try {
            dir.mkdirs()
            var existing = if (part.exists()) part.length() else 0L

            val connection = URL(entry.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (existing > 0) connection.setRequestProperty("Range", "bytes=$existing-")

            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> existing = 0L          // the server sent it all again
                HttpURLConnection.HTTP_PARTIAL -> Unit               // carrying on
                416 -> {                                             // the partial file does not fit
                    part.delete()
                    throw IOException("The partial download was reset. Tap Download to start again.")
                }
                else -> throw IOException("The server answered ${connection.responseCode}.")
            }
            val total = connection.contentLengthLong.let { if (it > 0) it + existing else -1L }

            var done = existing
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(part, existing > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = percentOf(done, total)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            _state.value = ModelState.Downloading(percent)
                        }
                    }
                }
            }

            if (part.length() < entry.minBytes) {
                throw IOException("The download stopped early. Tap Download to continue.")
            }
            val target = File(dir, entry.fileName)
            target.delete()
            if (!part.renameTo(target)) throw IOException("The model could not be saved.")
            _state.value = ModelState.Ready
        } catch (e: Exception) {
            Log.w(TAG, "Model download failed: ${e.message}")
            _state.value = ModelState.Failed(e.message ?: "The download failed.")
        }
    }

    private companion object {
        const val TAG = "LlmModelStore"
    }
}
