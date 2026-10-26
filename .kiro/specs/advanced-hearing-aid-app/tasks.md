# Implementation Plan: Advanced Hearing Aid App

## Overview

Implement the Advanced Hearing Aid App incrementally, starting with project scaffolding and the core audio pipeline, then layering in AI processing, captions, sound classification, analytics, profiles, wearable integration, and finally polish and privacy. Each task builds on the previous ones and wires into the MVVM + `AudioProcessingService` architecture defined in the design.

All Kotlin code lives under `com.example.tryhearingaid`. Property-based tests use Kotest (`io.kotest:kotest-runner-junit4:5.9.1`, `io.kotest:kotest-property:5.9.1`). Unit tests use JUnit 4 + MockK (`io.mockk:mockk:1.13.11`).

---

## Tasks

- [x] 1. Configure Gradle dependencies and project scaffolding
  - Add to `libs.versions.toml` and `app/build.gradle.kts`: Jetpack Compose BOM + Material3, Lifecycle/ViewModel, Room + KSP, DataStore (Proto), Hilt, Coroutines, TFLite + Task API, Kotest (runner-junit4 + property), MockK, WorkManager, Accompanist permissions
  - Enable `buildFeatures { compose = true }` and set `kotlinCompilerExtensionVersion`
  - Add KSP plugin for Room code generation
  - Create the base package directory tree: `audio/`, `captions/`, `classification/`, `analytics/`, `profiles/`, `ai/`, `wearable/`, `ui/`, `data/`, `service/`, `di/`
  - Fix syntax errors in existing `MainActivity.kt` (typos: `cl title`, `+` operators, `TopAppBaarDefaults`, `current` reference, `PlaceholderScreen` param name)
  - _Requirements: 1.3_

- [x] 2. Define core data models and Room database
  - [x] 2.1 Create Kotlin data classes and Room entities: `HearingProfile`, `EqBand`/`EqBandEntity`, `Audiogram`/`AudiogramEntity`, `CaptionSegment`/`CaptionSegmentEntity`, `TranscriptSession`, `DailyExposure`/`DailyExposureEntity`, `VocabTerm`/`VocabTermEntity`, `SoundAlert`, `StudyNotes`, `SoundCategory` enum, `AlertPriority`/`AlertChannel`/`FlashlightPattern` enums
  - [x] 2.2 Create Room DAOs: `ProfileDao`, `TranscriptDao`, `AnalyticsDao`, `VocabularyDao` with suspend functions and `Flow`-returning queries
  - [x] 2.3 Create `HearingAidDatabase` (`RoomDatabase` subclass) with all entities and migrations
  - [x] 2.4 Create `DataStore` proto schema (`UserPreferences.proto`) and `PreferencesRepository` wrapping it
  - [x] 2.5 Create `DataRepository` that aggregates all DAOs and exposes `StateFlow`/`SharedFlow` streams consumed by ViewModels
  - _Requirements: 5.3, 7.4, 11.5, 12.4, 13.4, 16.4_

- [x] 3. Implement AudioEngine and AudioProcessingService skeleton
  - [x] 3.1 Define `AudioProcessingStage` interface and `AudioEngine`, `AudioConfig`, `AudioEngineState` as per the design interfaces
  - [x] 3.2 Implement `AudioEngineImpl` using `AudioRecord`/`AudioTrack` in EXCLUSIVE low-latency mode on a dedicated high-priority thread; include gain clamping to [0, 40] dB and quantization to 1 dB increments; detect output device disconnection (headset/Bluetooth) and pause output with user notification within 2 seconds
  - [x] 3.3 Create `AudioProcessingService` as a foreground service with `FOREGROUND_SERVICE_TYPE_MICROPHONE`; bind `AudioEngineImpl`; publish `StateFlow` for `AudioEngineState`; declare service + permissions (`RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`) in `AndroidManifest.xml`
  - [x] 3.4 Implement adaptive amplification loop: measure ambient noise floor every 2 s, compute SNR-maintaining gain, apply 500 ms smoothed transition, enforce 40 dB cap and 85 dB SPL clamp with loudness warning emission
  - [x] 3.5 Wire `AudioProcessingService` lifecycle into `MainActivity` via `ServiceConnection`; expose bound service reference through a `ServiceLocator` or Hilt binding
  - _Requirements: 1.1, 1.2, 1.3, 1.5, 6.1, 6.2, 6.3, 6.4_

  - [x]* 3.6 Write property test for gain clamping (Property 1)
    - **Property 1: Gain values are always clamped to the valid range**
    - Use `Arb.float(-200f, 200f)` to set gain; assert applied gain is in `[0f, 40f]` and rounded to nearest 1 dB
    - **Validates: Requirements 1.2, 6.3**

- [x] 4. Implement permission request flow and `AudioViewModel`
  - [x] 4.1 Create `PermissionRationaleScreen` composable that explains each permission before requesting it; use Accompanist `rememberPermissionState` for `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `CAMERA`, `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`
  - [x] 4.2 Implement `PermissionsViewModel` that tracks which permissions are granted and disables dependent features when a permission is revoked; emit `UiEvent` for each revocation
  - [x] 4.3 Create `AudioViewModel` exposing `StateFlow<AudioUiState>` (gain, adaptiveEnabled, noisePreset, directionalDirection, isRunning, loudnessWarning); handle user intents (start/stop, setGain, toggleAdaptive)
  - [x]* 4.4 Write unit test: verify rationale screen appears before `RECORD_AUDIO` request; verify feature disabled and event emitted when permission revoked
  - _Requirements: 1.4, 16.1, 16.3_

- [x] 5. Implement NoiseReducer and VoiceIsolator processing stages
  - [x] 5.1 Implement `NoiseReducerImpl`: load TFLite model from assets with NNAPI delegate; implement `setEnvironmentPreset` that swaps suppression config; on model-load failure set `isModelLoaded = false` and activate classical spectral-subtraction fallback; emit snackbar notification to ViewModel via shared flow
  - [x] 5.2 Implement `VoiceIsolatorImpl`: load TFLite speaker-separation model; re-evaluate dominant speaker every 3 s; pass audio through unmodified when no speech detected for > 5 s; wire into `AudioProcessingService` pipeline after `NoiseReducer`
  - [x] 5.3 Write unit test: verify `isModelLoaded = false` and spectral subtraction active when TFLite load throws; verify environment preset stored and returned correctly
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4_

  - [x] 5.4 Write property test for noise reducer preset round-trip (Property 2)
    - **Property 2: Noise reducer environment preset is applied correctly**
    - Use `Arb.enum<EnvironmentPreset>()` to set preset; assert `getActivePreset()` returns the same value
    - **Validates: Requirements 2.4**

- [x] 6. Implement Equalizer and DirectionalFilter processing stages
  - [x] 6.1 Implement `EqualizerImpl`: 8-band IIR filter with center frequencies {250, 500, 1000, 2000, 3000, 4000, 6000, 8000} Hz; `setBandGain` clamps to [-20, +40] dB; `applyAudiogram` maps threshold dBHL values to band gains (higher threshold → proportionally higher gain); apply changes within 100 ms
  - [x] 6.2 Implement `DirectionalFilterImpl`: detect microphone count via `AudioManager`; if single-mic, set `isAvailable = false`; if dual-mic, apply beamforming delay-and-sum for selected `AudioDirection`; transition to new beam pattern within 500 ms
  - [x] 6.3 Write unit test: verify single-mic device sets `isAvailable = false`; verify `applyAudiogram` produces gains proportional to thresholds
  - _Requirements: 4.1, 4.2, 4.3, 4.4, 5.1, 5.2, 5.4, 5.5_

  - [x] 6.4 Write property tests for Equalizer (Properties 3, 4, 5, 6)
    - **Property 3: Equalizer always has at least 8 bands in required frequency range** — assert `bands.size >= 8` and every `centerHz` in `[250, 8000]`
    - **Property 4: Equalizer band gain is always within valid range** — use `Arb.float(-200f, 200f)` for gain; assert applied gain in `[-20f, 40f]`
    - **Property 5: Equalizer profile round-trip persistence** — generate random 8-band config; save/load via `ProfileManager`; assert gains identical
    - **Property 6: Audiogram maps to proportional EQ gains** — generate `Audiogram` with `Arb.int(0, 80)`; assert bands with higher thresholds get higher (or equal) gains
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 12.3, 12.4**

- [x] 7. Checkpoint — core audio pipeline complete
  - Ensure all tests pass. Verify `AudioProcessingService` starts with notification, `AudioEngineImpl` records/plays back audio in a test, `Equalizer` and `DirectionalFilter` are wired into the pipeline. Ask the user if any questions arise.

- [x] 8. Implement CaptionEngine and TranscriptStore
  - [x] 8.1 Implement `CaptionEngineImpl` wrapping Android `SpeechRecognizer` (ML Kit on-device); emit `CaptionSegment` with `isUncertain = confidence < 0.70`; attach `speakerLabel` via simple diarization heuristic (energy-based speaker change detection, incrementing "Speaker N" labels)
  - [x] 8.2 Implement `TranscriptStoreImpl` backed by `TranscriptDao`; implement `exportAsText` (write all segment texts line-by-line) and `exportAsPdf` (use `PdfDocument` API); both complete within 5 s; offer Android share sheet via `FileProvider` intent
  - [x] 8.3 Create `CaptionViewModel` exposing `StateFlow<CaptionUiState>` (captions list, isActive, selectedLanguage); handle start/stop intents, language selection from supported 11-language list
  - [x] 8.4 Write unit test: verify `CaptionEngine` emits segments with `isUncertain = true` for confidence < 0.70 and `false` for ≥ 0.70; verify speaker labels are non-empty when multi-speaker detected; verify fallback message shown when `SpeechRecognizer` unavailable
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [x] 8.5 Write property tests for CaptionEngine (Properties 7, 8, 9, 10)
    - **Property 7: Caption segments with low confidence are marked uncertain** — `Arb.float(0f, 1f)` confidence; assert `isUncertain == (confidence < 0.70f)`
    - **Property 8: Supported caption languages include required 11** — assert support set contains `{fil, en, es, fr, de, ar, zh, ja, ko, pt, hi}`
    - **Property 9: Caption segments have speaker labels in multi-speaker sessions** — simulate multi-speaker session; assert all segments have non-empty, distinct per-speaker labels
    - **Property 10: Transcript export round-trip** — save N random segments; export as text; assert all segment texts appear in output
    - **Validates: Requirements 7.2, 7.3, 7.4, 7.6**

- [x] 9. Implement SoundClassifier, EmergencyDetector, and NotificationDispatcher
  - [x] 9.1 Implement `SoundClassifierImpl` using YAMNet TFLite model; map 521 AudioSet classes to the 21 `SoundCategory` enum values; emit `SoundDetection` on a `SharedFlow`; respect `setEnabledCategories` filter (silently drop disabled categories); run on a background coroutine from resampled 16 kHz audio
  - [x] 9.2 Implement `EmergencyDetectorImpl` subscribing to `SoundClassifier.detections`; filter to the 6 life-safety categories; emit `EmergencyEvent` when confidence ≥ 0.85; remain active even when other features are paused (always-on subscription in `AudioProcessingService`)
  - [x] 9.3 Implement `NotificationDispatcherImpl`: map `SoundDetection` to `AlertPriority` (HIGH ≥ 0.80, LOW 0.50–0.79, ignore < 0.50); dispatch to enabled `AlertChannel`s (screen banner via `NotificationManager`, vibration via `Vibrator`, flashlight via `CameraManager`, smartwatch via `WearableBridge`); apply configured `FlashlightPattern`
  - [x] 9.4 Create `AlertViewModel` exposing `StateFlow<AlertUiState>` (recent detections, emergency event, enabled categories, alert channels); handle user intents (toggle category, change flashlight pattern, dismiss emergency)
  - [x] 9.5 Write unit test: verify categories not in enabled set produce no dispatch; verify confidence < 0.50 produces no dispatch; verify emergency event triggers full-screen alert within 500 ms; verify `SoundClassifier` emits no event on classification exception
  - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 10.1, 10.2, 10.5_

  - [x] 9.6 Write property tests for classification and dispatch (Properties 11, 12, 13, 14, 15)
    - **Property 11: SoundClassifier supports ≥ 20 required categories** — assert all 21 enum values in `SoundCategory` are detectable
    - **Property 12: Alert priority maps correctly from confidence** — `Arb.float(0f, 1f)`; assert HIGH when ≥ 0.80, LOW when in [0.50, 0.79), no dispatch when < 0.50
    - **Property 13: Flashlight pattern matches config** — `Arb.enum<FlashlightPattern>()`; set pattern; dispatch alert; assert dispatched pattern matches
    - **Property 14: Enabled categories filter determines dispatch** — random subset of categories enabled; assert only enabled categories produce dispatches
    - **Property 15: EmergencyDetector includes all 6 life-safety categories** — assert monitored set contains `{SMOKE_ALARM, CO_ALARM, FIRE_TRUCK_SIREN, AMBULANCE_SIREN, POLICE_SIREN, VERBAL_HELP}`
    - **Validates: Requirements 9.1, 9.2, 9.4, 9.5, 9.6, 10.1**

- [~] 10. Implement AnalyticsEngine and hearing health dashboard
  - [~] 10.1 Implement `AnalyticsEngineImpl`: subscribe to SPL measurements from `AudioEngine`; compute NIOSH dose (dose% = 100 × Σ(Cᵢ / Tᵢ), Tᵢ = 8h × 2^((85−Lᵢ)/3)); emit `dailyDosePercent` state flow; emit moderate warning at 50%, high warning at 100%; persist `DailyExposureEntity` records; purge records older than 90 days
  - [~] 10.2 Implement `generateTrendReport(days = 30)`: query last 30 `DailyExposureEntity` records; compute average daily dose, count of high-risk days, and max peak SPL across window
  - [x] 10.3 Create `AnalyticsViewModel` exposing `StateFlow<AnalyticsDashboard>` and `StateFlow<DoseWarningLevel>`; handle "generate trend report" intent
  - [~] 10.4 Write unit test: verify dose formula for known SPL/duration pairs; verify moderate warning emitted at 50% and high at 100%; verify trend report includes correct fields
  - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6_

  - [x] 10.5 Write property tests for AnalyticsEngine (Properties 16, 17, 18, 19, 20)
    - **Property 16: NIOSH dose computation is correct** — generate random SPL/duration sequences; assert computed dose equals manual formula result within floating-point tolerance
    - **Property 17: Dashboard always has all 4 required fields** — assert `currentSpl`, `dailyDose`, `weeklyTrend` (non-empty), `peakLevel` are all non-null for any dashboard snapshot
    - **Property 18: Risk warning level matches dose thresholds** — `Arb.float(0f, 200f)` dose; assert NO_WARNING < 50%, MODERATE 50%–99%, HIGH ≥ 100%
    - **Property 19: Exposure data retained for ≥ 90 days** — insert record with date = today - N days (N ≤ 90); assert record still present on query; assert records older than 90 days may be absent
    - **Property 20: Trend report aggregates correct 30-day window** — insert 30 known records; assert report average, high-risk count, and max peak match expected values
    - **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5, 11.6**

- [~] 11. Checkpoint — AI processing and analytics complete
  - Ensure all tests pass. Verify `SoundClassifier` emits detections on a real audio frame, `AnalyticsEngine` accumulates dose over time, and `CaptionEngine` produces segments. Ask the user if any questions arise.

- [x] 12. Implement ProfileManager and HearingTest
  - [x] 12.1 Implement `ProfileManagerImpl` backed by `ProfileDao`; `saveProfile` persists `HearingProfile` + `EqBandEntity` rows; `activateProfile` stores `activeProfileId` in DataStore and pushes EQ + adaptive settings to `AudioEngine` within 200 ms; `deleteProfile` removes profile and its child rows
  - [x] 12.2 Implement `HearingTestEngine`: play calibrated pure tones at {250, 500, 1000, 2000, 4000, 8000} Hz from 0 to 80 dBHL in 5 dB steps; record user threshold responses; on test completion build `Audiogram`, call `profileManager.saveProfile` with the audiogram, and call `equalizer.applyAudiogram`; if any threshold > 40 dBHL, set audiologist recommendation flag
  - [x] 12.3 Create `ProfileViewModel` exposing `StateFlow<ProfileUiState>` (profile list, active profile, hearing test state, audiologistRecommended); handle create/rename/delete/activate intents and test start/response intents
  - [x] 12.4 Write unit test: verify activating a profile applies EQ bands within 200 ms; verify audiologist flag set when any threshold > 40 dBHL; verify max 5+ profiles can be saved; verify profile switch applies new settings
  - _Requirements: 5.3, 5.4, 5.5, 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_

  - [x] 12.5 Write property tests for ProfileManager and HearingTest (Properties 21, 22, 23)
    - **Property 21: Hearing test frequencies and level steps are correct** — assert test frequencies == `{250, 500, 1000, 2000, 4000, 8000}` and levels == `{0, 5, ..., 80}` for any test instance
    - **Property 22: Hearing test threshold round-trip** — `Arb.int(0, 16)` (index × 5 = dBHL); record threshold; retrieve audiogram; assert same value
    - **Property 23: Profiles with threshold > 40 dBHL set audiologist recommendation** — `Arb.int(41, 80)` threshold for one frequency; assert `audiologistRecommended == true`
    - **Validates: Requirements 5.3, 5.4, 12.1, 12.2, 12.3, 12.4, 12.6**

- [x] 13. Implement AIAssistant with on-device and cloud Gemini
  - [x] 13.1 Implement `AIAssistantImpl` backed by Gemini Nano (Android AI Core / on-device inference API) for offline operations and Gemini REST API for cloud operations; detect network availability via `ConnectivityManager`; automatically switch `isOnlineMode`; re-enable cloud features on connectivity restore without app restart
  - [x] 13.2 Implement `summarize`, `translate`, `define`, `generateStudyNotes`, and `describeSound` suspend functions; apply 5 s / 10 s timeouts; cloud API 5xx errors trigger one retry after 2 s then fall back to on-device
  - [x] 13.3 Implement vocabulary tracking: `AIAssistantImpl` compares each transcribed word against `VocabularyDao`; insert new `VocabTermEntity` with AI-generated definition if word not found; expose `importantPoints: SharedFlow<ImportantPoint>` by scanning each new segment for dates, deadlines, action items, and proper nouns
  - [x] 13.4 Write unit test: verify `AIAssistant` switches to offline when network lost and re-enables when restored; verify word definition added for new vocabulary; verify `define` returns within 2 s for cached words; verify meeting notes contain title + key points + action items
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 13.3_

  - [x] 13.5 Write property tests for vocabulary (Properties 24, 25)
    - **Property 24: New vocabulary words added with non-empty definitions** — generate random novel words; assert vocabulary list contains entry with non-empty definition after processing
    - **Property 25: Vocabulary list clear is idempotent** — clear empty list; assert still empty; clear non-empty list twice; assert same empty result both times
    - **Validates: Requirements 13.3, 13.4**

- [x] 14. Implement classroom mode and educational features
  - [x] 14.1 Add `classroomMode` flow to `CaptionViewModel`: a single `activateClassroomMode()` call atomically enables `CaptionEngine.start()`, `AIAssistant` summarization subscription, and `TranscriptStore` session recording; on session end, call `aiAssistant.generateStudyNotes(transcript)` and persist the result
  - [x] 14.2 Implement keyword context lookup in `CaptionViewModel`: given a keyword from study notes, query `TranscriptStore` for surrounding segments and return them within 1 s
  - [x] 14.3 Implement audio recording alongside text transcript using `MediaRecorder` (when classroom mode active); save audio file path alongside `TranscriptSession` in Room
  - [x] 14.4 Write unit test: verify classroom mode atomically enables all three components; verify study notes generated contain all required fields; verify keyword lookup returns surrounding context
  - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5, 13.6_

- [ ] 15. Implement WearableBridge and Bluetooth integration
  - [x] 15.1 Implement `WearableBridgeImpl`: scan and connect to A2DP/HFP devices via `BluetoothAdapter`; connect to BLE devices via `BluetoothLeScanner`; read battery level via GATT `0x2A19` characteristic (set `batteryPercent = null` on read failure); start 30-s reconnect loop on unexpected disconnection; notify user after timeout
  - [ ] 15.2 Wire `AudioEngine` to route output to connected A2DP device automatically; wire `NotificationDispatcher.sendAlert` to `WearableBridge.sendAlert` for smartwatch vibration
  - [-] 15.3 Implement `sendCaptions` to transmit `CaptionSegment.text` to display-capable wearables (`supportsDisplay == true`) only; subscribe to `CaptionViewModel.captions` flow in `AudioProcessingService`
  - [~] 15.4 Write unit test: verify `batteryPercent` is non-null and in [0, 100] when GATT characteristic present; verify null when absent; verify no caption transmitted to non-display device; verify alert forwarded to smartwatch
  - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6_

  - [~] 15.5 Write property tests for WearableBridge (Properties 26, 27)
    - **Property 26: Battery level non-null iff device exposes it** — `Arb.boolean()` for `exposesBattery`; if true, assert `batteryPercent in [0, 100]`; if false, assert `batteryPercent == null`
    - **Property 27: Caption text forwarded only to display-capable wearables** — generate random `supportsDisplay` boolean; assert transmission occurs iff `supportsDisplay == true`
    - **Validates: Requirements 14.5, 14.6**

- [ ] 16. Implement Emergency SOS and spatial sound narration
  - [~] 16.1 Implement SOS function in `AlertViewModel`: on user activation, retrieve GPS via `FusedLocationProviderClient`; if unavailable, use last-known location with staleness timestamp; compose and send SMS to up to 5 saved contacts via `SmsManager`; retry 3 times on failure; show error if contacts not configured
  - [~] 16.2 Implement environmental awareness mode in `AlertViewModel`: subscribe to `SoundClassifier.detections`; for each detection call `aiAssistant.describeSound`; maintain a `description queue` (max depth 5, dequeue oldest when full); merge same-category events within 3 s into one card; auto-dismiss cards after 5 s; if TTS enabled, read description via `TextToSpeech`
  - [~] 16.3 Wire `EmergencyDetector.emergencyEvents` to trigger full-screen `EmergencyAlertDialog` composable within 500 ms; wire to `WearableBridge.sendAlert` for smartwatch emergency regardless of screen state; ensure detector stays active when screen is off by holding `WakeLock` in `AudioProcessingService`
  - [~] 16.4 Write unit test: verify SOS sends last-known location with staleness note when GPS unavailable; verify no SOS sent when contacts list empty; verify description queue never exceeds 5; verify same-category events within 3 s merged into one card
  - _Requirements: 10.2, 10.3, 10.4, 10.5, 10.6, 15.1, 15.2, 15.3, 15.4, 15.5_

  - [~] 16.5 Write property tests for spatial narration queue (Properties 28, 29, 30)
    - **Property 28: Every detected sound event produces a non-empty description** — emit random `SoundDetection` while awareness mode active; assert `describeSound` result is non-empty and contains category name
    - **Property 29: Description queue depth never exceeds 5** — `Arb.int(5, 50)` events; assert queue size is always ≤ 5 after each addition
    - **Property 30: Same-category events within 3 s are merged** — emit two events of same category ≤ 3000 ms apart; assert queue contains exactly one merged card
    - **Validates: Requirements 15.1, 15.3, 15.5**

- [~] 17. Checkpoint — all backend components wired and tested
  - Ensure all unit and property tests pass. Verify `AudioProcessingService` orchestrates the full pipeline (DirectionalFilter → NoiseReducer → VoiceIsolator → Equalizer → AdaptiveGain → AudioTrack) and publishes state flows consumed by all ViewModels. Ask the user if any questions arise.

- [ ] 18. Build Jetpack Compose UI screens
  - [~] 18.1 Build `AmplifierScreen`: gain slider (0–40 dB), adaptive amplification toggle, environment preset selector, directional hearing control (direction picker, disabled state for single-mic), loudness warning banner; bind to `AudioViewModel`
  - [~] 18.2 Build `CaptionsScreen`: scrollable caption list (each segment shows speaker label, text, uncertainty indicator), language selector dropdown, start/stop button, export button with share sheet, classroom mode FAB; bind to `CaptionViewModel`
  - [~] 18.3 Build `AlertsScreen`: sound category toggle list (enable/disable each of 20+ categories), alert channel checkboxes, flashlight pattern selector, recent detections feed, emergency alert full-screen overlay; bind to `AlertViewModel`
  - [~] 18.4 Build `AnalyticsScreen`: current SPL gauge, daily dose progress bar with color-coded risk level, 7-day trend `LineChart` (using Compose Canvas or Vico), peak level indicator, "Generate Report" button; bind to `AnalyticsViewModel`
  - [~] 18.5 Build `ProfilesScreen`: profile list with activate/delete actions, "New Profile" dialog, hearing test flow (tone playback UI with "I can hear it" button per frequency), audiologist recommendation card; bind to `ProfileViewModel`
  - [~] 18.6 Build `SettingsScreen`: privacy settings (data retention info, "Delete All Data" button), cloud AI opt-in toggle, TTS toggle, Bluetooth device list with battery indicators; bind to `PermissionsViewModel` and `PreferencesRepository`
  - [~] 18.7 Update `MainActivity` navigation to include all 6 screens (Amplifier, Captions, Alerts, Analytics, Profiles, Settings) with bottom nav bar; add top-level permission gate that shows `PermissionRationaleScreen` on first launch
  - _Requirements: 1.1, 1.2, 4.1, 5.1, 5.2, 6.4, 7.1, 7.2, 7.5, 8.2, 9.3, 9.4, 9.5, 11.2, 11.3, 11.4, 12.1, 14.5, 15.2, 15.4, 16.1, 16.4_

- [ ] 19. Implement privacy controls and data deletion
  - [~] 19.1 Implement `deleteAllData()` in `DataRepository`: run in a single Room transaction deleting all rows from `CaptionSegmentEntity`, `TranscriptSession`, `EqBandEntity`, `AudiogramEntity`, `HearingProfile`, `DailyExposureEntity`, `VocabTermEntity`; then clear all DataStore preferences; complete within 5 s; emit a confirmation event to `SettingsViewModel`
  - [~] 19.2 Implement runtime permission revocation handling in `PermissionsViewModel`: observe permission state changes via `AppOpsManager` or broadcast receiver; when a permission is revoked, pause dependent features and emit `PermissionRevokedEvent` with affected feature list; display impact message in UI
  - [~] 19.3 Write unit test: verify all Room tables empty and DataStore cleared after `deleteAllData()`; verify features disabled and message shown on permission revocation
  - _Requirements: 16.2, 16.3, 16.4, 16.5_

  - [~] 19.4 Write property tests for privacy (Properties 31, 32)
    - **Property 31: Disabling a permission disables all dependent features** — `Arb.enum<Permission>()`; revoke permission; assert all features depending on it are in disabled state
    - **Property 32: Data deletion leaves no retrievable user data** — insert random records; call `deleteAllData()`; query all stores; assert all return empty/null
    - **Validates: Requirements 16.3, 16.5**

- [~] 20. Wire Hilt dependency injection across all components
  - Create `@Module` classes: `AudioModule` (binds `AudioEngine`, `NoiseReducer`, `VoiceIsolator`, `DirectionalFilter`, `Equalizer`), `ProcessingModule` (binds `CaptionEngine`, `SoundClassifier`, `EmergencyDetector`, `AnalyticsEngine`, `AIAssistant`), `DataModule` (provides `HearingAidDatabase`, all DAOs, `DataRepository`), `WearableModule` (binds `WearableBridge`, `NotificationDispatcher`)
  - Annotate `AudioProcessingService` with `@AndroidEntryPoint`; inject all processing components
  - Annotate all ViewModels with `@HiltViewModel`; inject repositories and service-bound interfaces
  - _Requirements: 1.3_

- [~] 21. Final checkpoint — full integration
  - Ensure all unit and property tests pass (32 property tests, all unit tests). Verify the complete audio pipeline runs end-to-end in `AudioProcessingService`, all 6 UI screens render and respond to ViewModel state, `EmergencyDetector` stays active when screen is off, and `deleteAllData()` leaves all stores empty. Ask the user if any questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP iteration
- Each task references specific requirements for traceability
- Kotest property tests run a minimum of 100 iterations via `PropTestConfig(iterations = 100)`
- All property tests must include the comment `// Feature: advanced-hearing-aid-app, Property N: <property_text>`
- The existing `MainActivity.kt` has syntax errors that must be fixed in Task 1 before any Compose work can compile
- TFLite model files (YAMNet, noise suppression, voice isolation) must be placed in `app/src/main/assets/` and referenced by filename in the respective Impl classes
- Gemini Nano availability depends on the device running Android 14+ with Google Play Services; always guard with `isGeminiAvailable()` check before calling on-device inference

## Task Dependency Graph

```json
{
  "waves": [
    {
      "id": 0,
      "tasks": ["2.1", "2.2", "2.3", "2.4", "2.5"]
    },
    {
      "id": 1,
      "tasks": ["3.1", "3.2", "3.3", "3.4", "3.5"]
    },
    {
      "id": 2,
      "tasks": ["3.6", "4.1", "4.2", "4.3"]
    },
    {
      "id": 3,
      "tasks": ["4.4", "5.1", "5.2"]
    },
    {
      "id": 4,
      "tasks": ["5.3", "5.4", "6.1", "6.2"]
    },
    {
      "id": 5,
      "tasks": ["6.3", "6.4", "8.1", "8.2", "8.3"]
    },
    {
      "id": 6,
      "tasks": ["8.4", "8.5", "9.1", "9.2", "9.3", "9.4"]
    },
    {
      "id": 7,
      "tasks": ["9.5", "9.6", "10.1", "10.2", "10.3"]
    },
    {
      "id": 8,
      "tasks": ["10.4", "10.5", "12.1", "12.2", "12.3"]
    },
    {
      "id": 9,
      "tasks": ["12.4", "12.5", "13.1", "13.2", "13.3"]
    },
    {
      "id": 10,
      "tasks": ["13.4", "13.5", "14.1", "14.2", "14.3"]
    },
    {
      "id": 11,
      "tasks": ["14.4", "15.1", "15.2", "15.3"]
    },
    {
      "id": 12,
      "tasks": ["15.4", "15.5", "16.1", "16.2", "16.3"]
    },
    {
      "id": 13,
      "tasks": ["16.4", "16.5", "18.1", "18.2", "18.3", "18.4", "18.5", "18.6"]
    },
    {
      "id": 14,
      "tasks": ["18.7", "19.1", "19.2"]
    },
    {
      "id": 15,
      "tasks": ["19.3", "19.4"]
    }
  ]
}
```
