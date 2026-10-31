package com.example.tryhearingaid.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The offline AI: a small language model that runs on the phone, with no internet. It answers one
 * request at a time. Throws [GeminiUnavailableException] if the model is not downloaded or fails.
 */
@Singleton
class LocalLlmClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: LlmModelStore
) : GeminiClient {

    // One worker thread: the model handles one request at a time, and it must not block the UI.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-llm").apply { isDaemon = true }
    }

    @Volatile private var engine: LlmInference? = null
    @Volatile private var enginePath: String? = null

    override suspend fun generate(prompt: String): String {
        val file = store.modelFile()
        if (file == null) {
            releaseEngine()
            throw GeminiUnavailableException("The offline AI model is not downloaded.")
        }
        return suspendCancellableCoroutine { continuation ->
            val future = executor.submit {
                try {
                    continuation.resume(run(file, prompt))
                } catch (t: Throwable) {
                    Log.w(TAG, "Offline AI failed: ${t.message}")
                    continuation.resumeWithException(
                        GeminiUnavailableException("The offline AI could not run: ${t.message}", t)
                    )
                }
            }
            // A request that has not started yet is dropped; one already running finishes quietly.
            continuation.invokeOnCancellation { future.cancel(false) }
        }
    }

    private fun run(file: File, prompt: String): String {
        val llm = engineFor(file)
        return ChatMl.clean(llm.generateResponse(ChatMl.format(prompt)))
    }

    private fun engineFor(file: File): LlmInference {
        engine?.let { if (enginePath == file.absolutePath) return it }
        releaseEngine()
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(LlmCatalog.CONTEXT_TOKENS)
            .build()
        return LlmInference.createFromOptions(context, options).also {
            engine = it
            enginePath = file.absolutePath
        }
    }

    private fun releaseEngine() {
        runCatching { engine?.close() }
        engine = null
        enginePath = null
    }

    private companion object {
        const val TAG = "LocalLlmClient"
    }
}
