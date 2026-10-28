package com.example.tryhearingaid.emergency

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.tryhearingaid.data.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Reads sound descriptions aloud when the user has switched text-to-speech on
 * (Requirement 15.4).
 *
 * The setting is followed live: turning it off stops any speech at once, and while it is off
 * [speak] does nothing and the speech engine is never started. The engine starts on first use,
 * and text asked for while it is still starting is spoken as soon as it is ready.
 *
 * The speech goes out on the normal media stream, so it is heard on whatever output is in use,
 * including a connected Bluetooth hearing aid or earbuds.
 */
@Singleton
class SpeechOutput @Inject constructor(
    @ApplicationContext private val context: Context,
    preferences: PreferencesRepository
) {
    @Volatile
    private var enabled = false

    // The next three are only touched on the main thread.
    private var engine: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            preferences.userPreferences
                .map { it.ttsEnabled }
                .distinctUntilChanged()
                .collect { isOn ->
                    enabled = isOn
                    if (!isOn) stop()
                }
        }
    }

    /** Speak [text] after anything already being spoken. Does nothing if speech is switched off. */
    fun speak(text: String) {
        if (!enabled || text.isBlank()) return
        mainHandler.post {
            if (ready) {
                engine?.speak(text, TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
            } else {
                pending.addLast(text)
                if (engine == null) startEngine()
            }
        }
    }

    /** Stop speaking now and forget text that was waiting. */
    fun stop() {
        mainHandler.post {
            engine?.stop()
            pending.clear()
        }
    }

    private fun startEngine() {
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.setLanguage(Locale.getDefault())
                ready = true
                while (pending.isNotEmpty()) {
                    engine?.speak(pending.removeFirst(), TextToSpeech.QUEUE_ADD, null, UTTERANCE_ID)
                }
            } else {
                Log.w(TAG, "Text-to-speech could not start (status $status).")
                engine = null // allow a later attempt
                pending.clear()
            }
        }
    }

    private companion object {
        const val TAG = "SpeechOutput"
        const val UTTERANCE_ID = "sound-description"
    }
}
