package com.example.tryhearingaid.captions

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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

sealed class ModelState {
    object NotDownloaded : ModelState()
    data class Downloading(val percent: Int) : ModelState()
    object Ready : ModelState()
    data class Failed(val message: String) : ModelState()
}

/** Downloads, keeps and removes the offline speech models. The download happens once, on request. */
@Singleton
class VoskModelStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val baseDir = File(context.filesDir, "vosk")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow(
        VoskCatalog.entries.associate { it.language to initialState(it.language) }
    )

    /** The state of each language's model, keyed by language code. */
    val states: StateFlow<Map<String, ModelState>> = _states.asStateFlow()

    private fun initialState(language: String): ModelState =
        if (modelRoot(language) != null) ModelState.Ready else ModelState.NotDownloaded

    /** The folder to load the model from, or null if it is not downloaded. */
    fun modelRoot(language: String): File? {
        val entry = VoskCatalog.forLanguage(language) ?: return null
        return findModelRoot(File(baseDir, entry.language))
    }

    fun isReady(language: String): Boolean = modelRoot(language) != null

    fun startDownload(language: String) {
        val entry = VoskCatalog.forLanguage(language) ?: return
        if (_states.value[entry.language] is ModelState.Downloading) return
        setState(entry.language, ModelState.Downloading(0))
        scope.launch { download(entry) }
    }

    fun delete(language: String) {
        val entry = VoskCatalog.forLanguage(language) ?: return
        File(baseDir, entry.language).deleteRecursively()
        setState(entry.language, ModelState.NotDownloaded)
    }

    private fun download(entry: VoskCatalog.Entry) {
        val temp = File(baseDir, entry.language + ".tmp")
        val target = File(baseDir, entry.language)
        try {
            baseDir.mkdirs()
            temp.deleteRecursively()
            temp.mkdirs()

            val connection = URL(entry.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("The server answered ${connection.responseCode}.")
            }
            val total = connection.contentLengthLong
            var lastPercent = -1
            val input = CountingInputStream(connection.inputStream) { bytes ->
                if (total > 0) {
                    val percent = (bytes * 100 / total).toInt().coerceIn(0, 99)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        setState(entry.language, ModelState.Downloading(percent))
                    }
                }
            }
            input.use { ZipExtractor.extract(it, temp) }

            if (findModelRoot(temp) == null) throw IOException("The download did not contain a speech model.")
            target.deleteRecursively()
            if (!temp.renameTo(target)) throw IOException("The model could not be saved.")
            setState(entry.language, ModelState.Ready)
        } catch (e: Exception) {
            Log.w(TAG, "Model download failed: ${e.message}")
            temp.deleteRecursively()
            setState(entry.language, ModelState.Failed(e.message ?: "The download failed."))
        }
    }

    private fun setState(language: String, state: ModelState) {
        _states.value = _states.value + (language to state)
    }

    private companion object {
        const val TAG = "VoskModelStore"
    }
}
