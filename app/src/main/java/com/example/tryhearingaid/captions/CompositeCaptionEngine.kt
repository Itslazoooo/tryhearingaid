package com.example.tryhearingaid.captions

import com.example.tryhearingaid.data.CaptionSegment
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * The caption engine the app uses. It picks the offline speech model when one is downloaded for the
 * chosen language, and the phone's built-in recognizer otherwise (and for Filipino and Arabic).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CompositeCaptionEngine @Inject constructor(
    private val builtIn: CaptionEngineImpl,
    private val offline: VoskCaptionEngine,
    private val store: VoskModelStore
) : CaptionEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val current = MutableStateFlow<CaptionEngine>(builtIn)

    override val captions: StateFlow<List<CaptionSegment>> =
        current.flatMapLatest { it.captions }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val isActive: StateFlow<Boolean> =
        current.flatMapLatest { it.isActive }.stateIn(scope, SharingStarted.Eagerly, false)

    override val isUnavailable: StateFlow<Boolean> =
        current.flatMapLatest { it.isUnavailable }.stateIn(scope, SharingStarted.Eagerly, false)

    override val lastError: StateFlow<String?> =
        current.flatMapLatest { it.lastError }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun start(languageCode: String) {
        val chosen: CaptionEngine = if (store.isReady(languageCode)) offline else builtIn
        val other: CaptionEngine = if (chosen === offline) builtIn else offline
        other.stop()
        current.value = chosen
        chosen.start(languageCode)
    }

    override fun stop() {
        builtIn.stop()
        offline.stop()
    }

    override fun setConfidenceThreshold(threshold: Float) {
        builtIn.setConfidenceThreshold(threshold)
        offline.setConfidenceThreshold(threshold)
    }
}
