package com.example.tryhearingaid.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

sealed class TranslationResult {
    data class Text(val text: String) : TranslationResult()
    data class Unavailable(val reason: String) : TranslationResult()
}

/** Translates caption text on the phone (Requirement 7: show both the original and the translation). */
interface TextTranslator {
    /** Translates [text] from language code [from] to [to]. Never throws. */
    suspend fun translate(text: String, from: String, to: String): TranslationResult
}

/** Used where no translator is available. */
object NoTranslation : TextTranslator {
    override suspend fun translate(text: String, from: String, to: String): TranslationResult =
        TranslationResult.Unavailable("Translation is not available.")
}

/** Maps the app's language codes to the codes the translation library uses. */
object TranslationLanguages {
    private val supported = setOf("en", "es", "fr", "de", "ar", "zh", "ja", "ko", "pt", "hi", "tl")

    /** The library's code for an app language code, or null if it cannot translate that language. */
    fun mlKitTag(code: String): String? {
        val base = code.substringBefore('-').substringBefore('_').lowercase()
        val tag = if (base == "fil") "tl" else base
        return tag.takeIf { it in supported }
    }
}

/**
 * Google ML Kit on-device translation. Each language's pack is about 30 MB and is downloaded the
 * first time it is needed, over Wi-Fi only. After that, translating works without internet.
 */
@Singleton
class MlKitTextTranslator @Inject constructor() : TextTranslator {

    private val translators = mutableMapOf<String, Translator>()
    private val ready = mutableSetOf<String>()

    override suspend fun translate(text: String, from: String, to: String): TranslationResult {
        val source = TranslationLanguages.mlKitTag(from)
            ?: return TranslationResult.Unavailable("Translation does not support this language.")
        val target = TranslationLanguages.mlKitTag(to)
            ?: return TranslationResult.Unavailable("Translation does not support the chosen language.")
        if (source == target) return TranslationResult.Text(text)

        val key = "$source>$target"
        val translator = synchronized(translators) {
            translators.getOrPut(key) {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()
                )
            }
        }
        return try {
            if (key !in ready) {
                withTimeout(DOWNLOAD_WAIT_MS) {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()
                }
                synchronized(ready) { ready.add(key) }
            }
            TranslationResult.Text(translator.translate(text).await())
        } catch (e: TimeoutCancellationException) {
            TranslationResult.Unavailable("Waiting for Wi-Fi to download the translation pack (about 30 MB per language).")
        } catch (e: Exception) {
            TranslationResult.Unavailable("Translation failed: ${e.message ?: "unknown error"}")
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
    }

    private companion object {
        const val DOWNLOAD_WAIT_MS = 20_000L
    }
}
