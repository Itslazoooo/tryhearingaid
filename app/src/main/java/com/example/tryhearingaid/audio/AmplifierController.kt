package com.example.tryhearingaid.audio

import com.example.tryhearingaid.data.AudioSettings
import com.example.tryhearingaid.data.PreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the controller should do to the audio engine right now. */
enum class AmplifierAction { NONE, START, STOP, RESTART }

/** The decision rules, separate from Android so they can be tested. */
object AmplifierPolicy {
    /**
     * The amplifier runs only while the user wants it on AND headphones are connected, because
     * through the phone's own speaker the microphone hears the output and howls. When the sample
     * settings (sample rate, latency, raw mode) change while running, the engine restarts.
     */
    fun decide(
        wanted: Boolean,
        headphones: Boolean,
        engineActive: Boolean,
        configured: Any,
        running: Any?
    ): AmplifierAction {
        val shouldRun = wanted && headphones
        return when {
            shouldRun && !engineActive -> AmplifierAction.START
            !shouldRun && engineActive -> AmplifierAction.STOP
            shouldRun && engineActive && configured != running -> AmplifierAction.RESTART
            else -> AmplifierAction.NONE
        }
    }
}

/**
 * Owns when the amplifier runs and with which sound-quality settings. The service calls [begin]
 * once its audio stages are in place and [end] when it shuts down; the screen calls [setWanted].
 */
@Singleton
class AmplifierController @Inject constructor(
    private val engine: AudioEngine,
    private val headphones: HeadphoneDetector,
    private val preferences: PreferencesRepository,
    private val noiseReducer: NoiseReducerImpl,
    private val voiceIsolator: VoiceIsolatorImpl
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var runningConfig: AudioConfig? = null

    private val _wanted = MutableStateFlow(true)

    /** Whether the user has the amplifier switched on (it still needs headphones to run). */
    val wanted: StateFlow<Boolean> = _wanted.asStateFlow()

    val headphonesConnected: StateFlow<Boolean> get() = headphones.isConnected

    val settings: StateFlow<AudioSettings> = preferences.userPreferences
        .map { it.audioSettings }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, AudioSettings())

    fun setWanted(on: Boolean) {
        _wanted.value = on
    }

    suspend fun updateSettings(transform: (AudioSettings) -> AudioSettings) {
        preferences.updateAudioSettings(transform(settings.value))
    }

    /** Start following the settings and headphones. Does nothing if already started. */
    fun begin() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch {
                settings.collect { applyToStages(it) }
            }
            combine(_wanted, headphones.isConnected, settings) { wanted, plugged, s ->
                Triple(wanted, plugged, s.toAudioConfig())
            }.collect { (wanted, plugged, config) -> reconcile(wanted, plugged, config) }
        }
    }

    /** Stop following and switch the engine off. */
    fun end() {
        job?.cancel()
        job = null
        engine.stop()
        runningConfig = null
    }

    private fun applyToStages(s: AudioSettings) {
        noiseReducer.setEnvironmentPreset(s.noisePreset)
        noiseReducer.setStrength(s.noiseStrength)
        voiceIsolator.setStrength(if (s.voiceFocusEnabled) s.voiceFocusStrength else 0f)
    }

    private suspend fun reconcile(wanted: Boolean, plugged: Boolean, config: AudioConfig) {
        val active = engine.state.value == AudioEngineState.RUNNING ||
            engine.state.value == AudioEngineState.STARTING
        when (AmplifierPolicy.decide(wanted, plugged, active, config, runningConfig)) {
            AmplifierAction.START -> startEngine(config)
            AmplifierAction.STOP -> {
                engine.stop()
                runningConfig = null
            }
            AmplifierAction.RESTART -> {
                engine.stop()
                runningConfig = null
                delay(RESTART_PAUSE_MS) // let the old audio thread finish before reopening the mic
                startEngine(config)
            }
            AmplifierAction.NONE -> Unit
        }
    }

    private fun startEngine(config: AudioConfig) {
        runningConfig = config
        engine.start(config)
    }

    private companion object {
        const val RESTART_PAUSE_MS = 250L
    }
}
