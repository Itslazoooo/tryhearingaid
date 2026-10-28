package com.example.tryhearingaid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.tryhearingaid.emergency.SosContacts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

// ---------------------------------------------------------------------------
// DataStore singleton extension on Context
// ---------------------------------------------------------------------------

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences"
)

// ---------------------------------------------------------------------------
// UserPreferences — aggregates all persisted settings in one snapshot
// ---------------------------------------------------------------------------

/**
 * Immutable snapshot of all user preferences read from DataStore.
 * Defaults mirror the spec requirements.
 */
data class UserPreferences(
    val activeProfileId: UUID? = null,
    val captionLanguageCode: String = "en",
    val translationTargetLanguage: String = "en",
    val enabledSoundCategories: Set<String> = SoundCategory.entries.map { it.name }.toSet(),
    val alertChannels: Set<String> = AlertChannel.entries.map { it.name }.toSet(),
    val flashlightPattern: String = FlashlightPattern.SHORT.name,
    val cloudAiEnabled: Boolean = false,
    val ttsEnabled: Boolean = false,
    val privacyAudioUploadConsent: Boolean = false,
    /** Emergency SOS phone numbers, at most [SosContacts.MAX_CONTACTS], in the order the user entered them. */
    val sosContacts: List<String> = emptyList(),
    /** Sample rate, noise reduction and voice focus choices for the amplifier. */
    val audioSettings: AudioSettings = AudioSettings(),
)

// ---------------------------------------------------------------------------
// PreferencesRepository interface
// ---------------------------------------------------------------------------

/**
 * Repository for user preferences. Backed by Jetpack DataStore (Preferences)
 * in production; an in-memory implementation is available for tests.
 */
interface PreferencesRepository {

    /** Combined flow of all preferences as a single [UserPreferences] snapshot. */
    val userPreferences: Flow<UserPreferences>

    // -- Active profile --
    suspend fun updateActiveProfileId(id: UUID?)

    // -- Captions / translation --
    suspend fun updateCaptionLanguageCode(code: String)
    suspend fun updateTranslationTargetLanguage(code: String)

    // -- Sound categories --
    suspend fun updateEnabledSoundCategories(categories: Set<String>)

    // -- Alert channels --
    suspend fun updateAlertChannels(channels: Set<String>)

    // -- Flashlight --
    suspend fun updateFlashlightPattern(pattern: String)

    // -- AI / TTS / Privacy --
    suspend fun updateCloudAiEnabled(enabled: Boolean)
    suspend fun updateTtsEnabled(enabled: Boolean)
    suspend fun updatePrivacyAudioUploadConsent(consent: Boolean)

    // -- Emergency SOS --
    /** Saves the SOS contacts after cleaning them with [SosContacts.normalize]. */
    suspend fun updateSosContacts(numbers: List<String>)

    /** Save the amplifier's sound-quality settings. */
    suspend fun updateAudioSettings(settings: AudioSettings)

    /** Resets every preference back to its default value. */
    suspend fun clearAll()
}

// ---------------------------------------------------------------------------
// DataStore-backed implementation
// ---------------------------------------------------------------------------

/**
 * Production implementation that persists preferences via
 * [androidx.datastore:datastore-preferences].
 *
 * Construct with a [Context] (typically the Application context) or inject
 * a pre-built [DataStore<Preferences>] for easier testing.
 */
class DataStorePreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : PreferencesRepository {

    constructor(context: Context) : this(context.dataStore)

    // -- Keys ----------------------------------------------------------------

    private object Keys {
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val CAPTION_LANGUAGE_CODE = stringPreferencesKey("caption_language_code")
        val TRANSLATION_TARGET_LANGUAGE = stringPreferencesKey("translation_target_language")
        val ENABLED_SOUND_CATEGORIES = stringSetPreferencesKey("enabled_sound_categories")
        val ALERT_CHANNELS = stringSetPreferencesKey("alert_channels")
        val FLASHLIGHT_PATTERN = stringPreferencesKey("flashlight_pattern")
        val CLOUD_AI_ENABLED = booleanPreferencesKey("cloud_ai_enabled")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val PRIVACY_AUDIO_UPLOAD_CONSENT = booleanPreferencesKey("privacy_audio_upload_consent")
        val SOS_CONTACTS = stringPreferencesKey("sos_contacts")
        val AUDIO_RAW_MODE = booleanPreferencesKey("audio_raw_mode")
        val AUDIO_LATENCY = stringPreferencesKey("audio_latency")
        val AUDIO_SAMPLE_RATE = intPreferencesKey("audio_sample_rate")
        val NOISE_PRESET = stringPreferencesKey("noise_preset")
        val NOISE_STRENGTH = floatPreferencesKey("noise_strength")
        val VOICE_FOCUS_ENABLED = booleanPreferencesKey("voice_focus_enabled")
        val VOICE_FOCUS_STRENGTH = floatPreferencesKey("voice_focus_strength")
    }

    // -- Default sets (computed once) ----------------------------------------

    // A short first-run list so the phone is not alerting for every bird and voice. Life-safety
    // sounds are always listened for regardless (see AlertSettings.classifierCategories).
    private val defaultSoundCategories: Set<String> =
        DefaultAlerts.CATEGORIES.map { it.name }.toSet()

    private val defaultAlertChannels: Set<String> =
        setOf(AlertChannel.SCREEN_BANNER, AlertChannel.VIBRATION).map { it.name }.toSet()

    // -- Flow ----------------------------------------------------------------

    override val userPreferences: Flow<UserPreferences> = dataStore.data.map { prefs ->
        val rawId = prefs[Keys.ACTIVE_PROFILE_ID]
        UserPreferences(
            activeProfileId = rawId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            captionLanguageCode = prefs[Keys.CAPTION_LANGUAGE_CODE] ?: "en",
            translationTargetLanguage = prefs[Keys.TRANSLATION_TARGET_LANGUAGE] ?: "en",
            enabledSoundCategories = prefs[Keys.ENABLED_SOUND_CATEGORIES] ?: defaultSoundCategories,
            alertChannels = prefs[Keys.ALERT_CHANNELS] ?: defaultAlertChannels,
            flashlightPattern = prefs[Keys.FLASHLIGHT_PATTERN] ?: FlashlightPattern.SHORT.name,
            cloudAiEnabled = prefs[Keys.CLOUD_AI_ENABLED] ?: false,
            ttsEnabled = prefs[Keys.TTS_ENABLED] ?: false,
            privacyAudioUploadConsent = prefs[Keys.PRIVACY_AUDIO_UPLOAD_CONSENT] ?: false,
            sosContacts = SosContacts.decode(prefs[Keys.SOS_CONTACTS]),
            audioSettings = AudioSettings(
                rawMode = prefs[Keys.AUDIO_RAW_MODE] ?: true,
                latency = runCatching { LatencyMode.valueOf(prefs[Keys.AUDIO_LATENCY] ?: "") }
                    .getOrDefault(LatencyMode.BALANCED),
                sampleRate = prefs[Keys.AUDIO_SAMPLE_RATE] ?: AudioSettings.DEFAULT_SAMPLE_RATE,
                noisePreset = runCatching { EnvironmentPreset.valueOf(prefs[Keys.NOISE_PRESET] ?: "") }
                    .getOrDefault(EnvironmentPreset.DEFAULT),
                noiseStrength = prefs[Keys.NOISE_STRENGTH] ?: AudioSettings.DEFAULT_STRENGTH,
                voiceFocusEnabled = prefs[Keys.VOICE_FOCUS_ENABLED] ?: false,
                voiceFocusStrength = prefs[Keys.VOICE_FOCUS_STRENGTH] ?: AudioSettings.DEFAULT_STRENGTH
            ).sanitized(),
        )
    }

    // -- Update functions ----------------------------------------------------

    override suspend fun updateActiveProfileId(id: UUID?) {
        dataStore.edit { prefs ->
            if (id != null) prefs[Keys.ACTIVE_PROFILE_ID] = id.toString()
            else prefs.remove(Keys.ACTIVE_PROFILE_ID)
        }
    }

    override suspend fun updateCaptionLanguageCode(code: String) {
        dataStore.edit { it[Keys.CAPTION_LANGUAGE_CODE] = code }
    }

    override suspend fun updateTranslationTargetLanguage(code: String) {
        dataStore.edit { it[Keys.TRANSLATION_TARGET_LANGUAGE] = code }
    }

    override suspend fun updateEnabledSoundCategories(categories: Set<String>) {
        dataStore.edit { it[Keys.ENABLED_SOUND_CATEGORIES] = categories }
    }

    override suspend fun updateAlertChannels(channels: Set<String>) {
        dataStore.edit { it[Keys.ALERT_CHANNELS] = channels }
    }

    override suspend fun updateFlashlightPattern(pattern: String) {
        dataStore.edit { it[Keys.FLASHLIGHT_PATTERN] = pattern }
    }

    override suspend fun updateCloudAiEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CLOUD_AI_ENABLED] = enabled }
    }

    override suspend fun updateTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.TTS_ENABLED] = enabled }
    }

    override suspend fun updatePrivacyAudioUploadConsent(consent: Boolean) {
        dataStore.edit { it[Keys.PRIVACY_AUDIO_UPLOAD_CONSENT] = consent }
    }

    override suspend fun updateSosContacts(numbers: List<String>) {
        dataStore.edit { it[Keys.SOS_CONTACTS] = SosContacts.encode(numbers) }
    }

    override suspend fun updateAudioSettings(settings: AudioSettings) {
        val clean = settings.sanitized()
        dataStore.edit {
            it[Keys.AUDIO_RAW_MODE] = clean.rawMode
            it[Keys.AUDIO_LATENCY] = clean.latency.name
            it[Keys.AUDIO_SAMPLE_RATE] = clean.sampleRate
            it[Keys.NOISE_PRESET] = clean.noisePreset.name
            it[Keys.NOISE_STRENGTH] = clean.noiseStrength
            it[Keys.VOICE_FOCUS_ENABLED] = clean.voiceFocusEnabled
            it[Keys.VOICE_FOCUS_STRENGTH] = clean.voiceFocusStrength
        }
    }

    override suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}

// ---------------------------------------------------------------------------
// In-memory implementation (for unit tests / stubs)
// ---------------------------------------------------------------------------

/**
 * Thread-safe, in-memory implementation of [PreferencesRepository].
 * No Android dependencies — safe to use in plain JVM unit tests.
 */
class InMemoryPreferencesRepository : PreferencesRepository {

    private val _state = MutableStateFlow(UserPreferences())
    override val userPreferences: Flow<UserPreferences> = _state

    override suspend fun updateActiveProfileId(id: UUID?) {
        _state.value = _state.value.copy(activeProfileId = id)
    }

    override suspend fun updateCaptionLanguageCode(code: String) {
        _state.value = _state.value.copy(captionLanguageCode = code)
    }

    override suspend fun updateTranslationTargetLanguage(code: String) {
        _state.value = _state.value.copy(translationTargetLanguage = code)
    }

    override suspend fun updateEnabledSoundCategories(categories: Set<String>) {
        _state.value = _state.value.copy(enabledSoundCategories = categories)
    }

    override suspend fun updateAlertChannels(channels: Set<String>) {
        _state.value = _state.value.copy(alertChannels = channels)
    }

    override suspend fun updateFlashlightPattern(pattern: String) {
        _state.value = _state.value.copy(flashlightPattern = pattern)
    }

    override suspend fun updateCloudAiEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(cloudAiEnabled = enabled)
    }

    override suspend fun updateTtsEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(ttsEnabled = enabled)
    }

    override suspend fun updatePrivacyAudioUploadConsent(consent: Boolean) {
        _state.value = _state.value.copy(privacyAudioUploadConsent = consent)
    }

    override suspend fun updateSosContacts(numbers: List<String>) {
        _state.value = _state.value.copy(sosContacts = SosContacts.normalize(numbers))
    }

    override suspend fun updateAudioSettings(settings: AudioSettings) {
        _state.value = _state.value.copy(audioSettings = settings.sanitized())
    }

    override suspend fun clearAll() {
        _state.value = UserPreferences()
    }
}

/** First-run alert settings shared by the saved preferences and the Alerts screen. */
object DefaultAlerts {
    val CATEGORIES: Set<SoundCategory> = setOf(
        SoundCategory.DOORBELL, SoundCategory.KNOCK, SoundCategory.BABY_CRYING,
        SoundCategory.FIRE_ALARM, SoundCategory.SMOKE_ALARM, SoundCategory.CO_ALARM
    )
}
