# Requirements Document

## Introduction

An advanced hearing assistance Android app targeting users with hearing loss, hard-of-hearing users, and anyone needing enhanced audio awareness. The app goes far beyond simple amplification by combining real-time AI audio processing, live speech-to-text, sound recognition, health analytics, emergency detection, educational tools, wearable integration, and personalized hearing profiles — all in a single Kotlin/Compose application.

---

## Glossary

- **App**: The advanced hearing aid Android application.
- **Audio_Engine**: The component responsible for real-time audio capture, processing, and output.
- **Noise_Reducer**: The AI sub-component that identifies and suppresses non-speech background noise.
- **Voice_Isolator**: The AI sub-component that separates and amplifies the primary speaker's voice.
- **Directional_Filter**: The component that applies spatial audio filtering to emphasize sound from a selected direction.
- **Equalizer**: The component responsible for per-frequency band gain adjustment.
- **Caption_Engine**: The component that converts audio to real-time text captions.
- **Sound_Classifier**: The AI model that detects and classifies environmental sounds.
- **AI_Assistant**: The component that performs NLP tasks: summarization, translation, keyword extraction, and study-note generation.
- **Analytics_Engine**: The component that tracks, aggregates, and evaluates hearing exposure data.
- **Emergency_Detector**: The sub-component of Sound_Classifier focused exclusively on life-safety sounds.
- **Profile_Manager**: The component that stores, loads, and applies personalized hearing profiles.
- **Hearing_Test**: The in-app audiometric test used to determine a user's frequency response.
- **Wearable_Bridge**: The component that manages Bluetooth connections to hearing aids, earbuds, smartwatches, and smart glasses.
- **Notification_Dispatcher**: The component that routes alerts to the appropriate output channel (vibration, flashlight, smartwatch, on-screen).
- **Transcript_Store**: The persistent storage for conversation history and exported transcripts.
- **User**: A person using the app, typically someone with hearing loss or situational hearing difficulty.

---

## Requirements

### Requirement 1: Real-Time Audio Amplification

**User Story:** As a user with hearing loss, I want real-time audio amplification with low latency, so that I can hear sounds in my environment clearly without perceptible delay.

#### Acceptance Criteria

1. WHEN the User activates audio processing, THE Audio_Engine SHALL capture microphone input and route amplified audio to the connected output device within 20 milliseconds end-to-end latency.
2. THE Audio_Engine SHALL support a gain range of 0 dB to 40 dB in increments of 1 dB.
3. WHILE audio processing is active, THE Audio_Engine SHALL run as a foreground Android service to prevent interruption by the operating system.
4. IF the microphone permission is not granted, THEN THE App SHALL display a rationale screen and request the RECORD_AUDIO permission before activating the Audio_Engine.
5. WHEN the User disconnects the headset or Bluetooth output device, THE Audio_Engine SHALL pause output and notify the User within 2 seconds.

---

### Requirement 2: AI Noise Reduction

**User Story:** As a user in a noisy environment, I want AI-powered background noise suppression, so that I can focus on the sounds I care about.

#### Acceptance Criteria

1. WHEN noise reduction is enabled, THE Noise_Reducer SHALL attenuate stationary background noise (fans, traffic, HVAC) by at least 15 dB without degrading speech intelligibility.
2. WHEN noise reduction is enabled, THE Noise_Reducer SHALL process audio frames in real time with no more than 10 ms additional latency introduced by the model.
3. THE Noise_Reducer SHALL operate entirely on-device without requiring an internet connection.
4. WHERE the User selects a named environment preset (e.g., "Restaurant", "Classroom", "Outdoors"), THE Noise_Reducer SHALL apply a tuned suppression profile for that environment.
5. IF the on-device AI model fails to load, THEN THE App SHALL fall back to a classical spectral-subtraction filter and notify the User that reduced noise reduction is active.

---

### Requirement 3: Voice Isolation

**User Story:** As a user in a group setting, I want the app to isolate and amplify the primary speaker's voice, so that I can follow one conversation without distraction.

#### Acceptance Criteria

1. WHEN voice isolation is enabled, THE Voice_Isolator SHALL suppress non-target voices and non-speech sounds while preserving the dominant speech signal.
2. WHEN voice isolation is enabled, THE Voice_Isolator SHALL re-evaluate the dominant speaker every 3 seconds to handle speaker changes.
3. WHILE voice isolation is active, THE Audio_Engine SHALL mix the isolated voice signal with the amplification chain before output.
4. IF no speech is detected for more than 5 consecutive seconds, THEN THE Voice_Isolator SHALL pass through the full audio signal unmodified until speech is detected again.

---

### Requirement 4: Directional Hearing

**User Story:** As a user in a large space, I want to emphasize sounds from a specific direction, so that I can focus on sources in front of me or to my side.

#### Acceptance Criteria

1. WHEN the User selects a direction (Front, Left, Right, Rear, or a custom azimuth angle), THE Directional_Filter SHALL apply a beam-forming gain pattern that amplifies audio from the selected direction by at least 6 dB relative to other directions.
2. WHERE the device has two or more microphones, THE Directional_Filter SHALL use both microphone inputs to compute spatial filtering.
3. IF the device has only a single microphone, THEN THE App SHALL disable directional hearing and display a message informing the User that a multi-microphone device is required for this feature.
4. WHEN the User changes the selected direction, THE Directional_Filter SHALL transition to the new beam pattern within 500 ms.

---

### Requirement 5: Frequency Customization (Equalizer)

**User Story:** As a user with a specific hearing profile, I want to adjust amplification per frequency band, so that I can compensate for the exact frequencies I struggle to hear.

#### Acceptance Criteria

1. THE Equalizer SHALL provide at least 8 adjustable frequency bands spanning 250 Hz to 8000 Hz.
2. THE Equalizer SHALL allow per-band gain adjustment in the range of -20 dB to +40 dB.
3. WHEN the User saves an equalizer configuration, THE Profile_Manager SHALL persist the configuration and make it available for future sessions.
4. WHEN a Hearing_Test result is available, THE Equalizer SHALL automatically pre-populate band gains based on the audiogram data from the test.
5. THE Equalizer SHALL apply gain changes to the live audio stream within 100 ms of a User adjustment.

---

### Requirement 6: Adaptive Amplification

**User Story:** As a user moving between environments, I want the app to automatically adjust amplification, so that I do not experience sudden loudness changes.

#### Acceptance Criteria

1. WHEN adaptive amplification is enabled, THE Audio_Engine SHALL measure the ambient noise floor every 2 seconds and adjust the output gain to maintain a target speech-to-noise ratio of 15 dB.
2. WHEN the measured ambient level changes by more than 10 dB within a 2-second window, THE Audio_Engine SHALL apply a smoothed gain transition over 500 ms to avoid abrupt volume changes.
3. WHILE adaptive amplification is active, THE Audio_Engine SHALL cap output gain at 40 dB to prevent harmful loudness levels.
4. IF the output level would exceed 85 dB SPL at the earpiece, THEN THE Audio_Engine SHALL clamp the gain and display a loudness warning to the User.

---

### Requirement 7: Live Speech-to-Text Captions

**User Story:** As a deaf or hard-of-hearing user, I want real-time captions of spoken conversations, so that I can follow dialogue without relying solely on audio.

#### Acceptance Criteria

1. WHEN caption mode is active, THE Caption_Engine SHALL transcribe speech to text with a display latency of no more than 2 seconds from the moment speech ends.
2. THE Caption_Engine SHALL support a minimum of 11 languages including Filipino, English, Spanish, French, German, Arabic, Chinese (Mandarin), Japanese, Korean, Portuguese, and Hindi.
3. WHEN multiple speakers are detected, THE Caption_Engine SHALL label each caption segment with a speaker identifier (e.g., "Speaker 1:", "Speaker 2:").
4. THE Transcript_Store SHALL retain the conversation history for the current session and allow the User to export the transcript as a plain-text or PDF file.
5. WHEN the User exports a transcript, THE Transcript_Store SHALL generate the file within 5 seconds and offer a standard Android share sheet for distribution.
6. IF speech recognition confidence for a segment falls below 70%, THEN THE Caption_Engine SHALL mark that segment with a visual uncertainty indicator.

---

### Requirement 8: AI Conversation Assistant

**User Story:** As a user attending a meeting or lecture, I want AI to summarize and highlight key points from the conversation, so that I do not miss critical information.

#### Acceptance Criteria

1. WHEN the User requests a conversation summary, THE AI_Assistant SHALL generate a concise summary of the current session transcript within 5 seconds.
2. WHEN the AI_Assistant identifies a sentence containing a date, deadline, action item, or proper noun marked as important, THE AI_Assistant SHALL surface it as a highlighted "Important Point" card in the UI within 3 seconds of the sentence being transcribed.
3. WHERE the User has enabled speech translation, THE AI_Assistant SHALL translate each captioned segment into the User's selected target language and display both source and translated text.
4. WHEN the User taps an unfamiliar word in the caption view, THE AI_Assistant SHALL display a plain-language definition within 2 seconds.
5. WHEN the User requests meeting notes, THE AI_Assistant SHALL produce structured notes including a title, key points list, and action items within 10 seconds of the request.
6. IF an internet connection is unavailable, THEN THE AI_Assistant SHALL automatically switch to on-device AI processing for supported features and notify the User that cloud AI features are temporarily unavailable. WHEN an internet connection is restored, THE AI_Assistant SHALL automatically re-enable cloud-dependent features and use them for supported tasks without requiring the User to restart the application.

---

### Requirement 9: Sound Recognition and Alerts

**User Story:** As a user who cannot hear important environmental sounds, I want the app to detect and notify me of critical sounds, so that I can stay aware of my surroundings.

#### Acceptance Criteria

1. THE Sound_Classifier SHALL detect a minimum of 20 sound categories including: doorbell, baby crying, fire alarm, car horn, dog barking, name called, knock on door, phone ringing, water running, glass breaking, siren, smoke alarm, crowd cheering, birds, thunder, and speech.
2. WHEN a target sound is detected with a confidence score of 80% or above, THE Notification_Dispatcher SHALL deliver an alert within 1 second of detection.
3. THE Notification_Dispatcher SHALL support the following alert channels: on-screen banner, vibration pattern, device flashlight, and connected smartwatch notification.
4. WHERE the User enables the flashlight alert channel, THE Notification_Dispatcher SHALL flash the device torch in a configurable pattern (short, long, or SOS) for each detected sound event.
5. THE User SHALL be able to enable or disable individual sound categories independently.
6. IF the Sound_Classifier confidence score for an event is between 50% and 79%, THEN THE Notification_Dispatcher SHALL deliver a low-priority notification distinguishable from high-confidence alerts.

---

### Requirement 10: Emergency Detection

**User Story:** As a user who cannot hear emergency signals, I want the app to detect life-safety sounds and trigger emergency actions, so that I can respond to emergencies immediately.

#### Acceptance Criteria

1. THE Emergency_Detector SHALL detect the following life-safety sounds: smoke alarm, CO alarm, fire truck siren, ambulance siren, police siren, and verbal calls for help ("help", "fire", "emergency").
2. WHEN an emergency sound is detected with a confidence score of 85% or above, THE Emergency_Detector SHALL trigger a high-priority full-screen alert with vibration within 500 ms of detection.
3. WHEN the User activates the SOS function from the emergency screen, THE App SHALL send a pre-configured emergency SMS to up to 5 saved contacts containing the User's current GPS coordinates.
4. IF the GPS location is unavailable when the SOS is triggered, THEN THE App SHALL send the SMS with the last known location and a timestamp, noting that the location may be outdated.
5. THE Emergency_Detector SHALL operate continuously while the App is running, including when the screen is off and other features are paused.
6. WHERE the User has linked a smartwatch, THE Emergency_Detector SHALL send emergency alerts to the smartwatch regardless of the phone's screen state.

---

### Requirement 11: Hearing Health Analytics

**User Story:** As a user concerned about my hearing health, I want a dashboard showing my daily sound exposure, so that I can make informed decisions to protect my hearing.

#### Acceptance Criteria

1. WHILE audio processing is active, THE Analytics_Engine SHALL continuously measure the output SPL and accumulate a daily noise dose using the NIOSH dose formula (85 dB SPL reference, 3 dB exchange rate).
2. THE Analytics_Engine SHALL display a dashboard showing: current noise level (dB SPL), daily dose percentage, 7-day noise trend chart, and detected peak levels.
3. WHEN the daily noise dose reaches 50%, THE Analytics_Engine SHALL display a moderate-risk warning to the User.
4. WHEN the daily noise dose reaches 100%, THE Analytics_Engine SHALL display a high-risk warning and recommend reducing listening time or lowering volume.
5. THE Analytics_Engine SHALL store daily aggregated exposure data for a minimum of 90 days.
6. WHEN the User requests a hearing trend report, THE Analytics_Engine SHALL generate a summary of the past 30 days including average daily dose, number of high-risk days, and loudest recorded event.

---

### Requirement 12: Personalized Hearing Test and Profile Calibration

**User Story:** As a new user, I want to take an in-app hearing test, so that the app automatically configures amplification for my specific hearing loss pattern.

#### Acceptance Criteria

1. THE Hearing_Test SHALL present pure tones at frequencies 250 Hz, 500 Hz, 1 kHz, 2 kHz, 4 kHz, and 8 kHz at calibrated levels from 0 dBHL to 80 dBHL in 5 dB steps.
2. WHEN the User indicates they can hear a tone, THE Hearing_Test SHALL record the threshold level for that frequency and ear.
3. WHEN the Hearing_Test is complete, THE Profile_Manager SHALL store the audiogram and immediately apply corresponding Equalizer band gains to compensate for the User's measured thresholds.
4. THE Profile_Manager SHALL allow the User to save, name, and switch between multiple hearing profiles (minimum 5 profiles).
5. WHEN the User switches the active profile, THE Audio_Engine SHALL apply the new profile's Equalizer and adaptive settings within 200 ms.
6. IF the hearing test detects thresholds exceeding 40 dBHL at any frequency, THEN THE App SHALL display a recommendation to consult a licensed audiologist.

---

### Requirement 13: Educational Features

**User Story:** As a student using the app in class, I want tools to capture, organize, and learn from lecture content, so that I can study from accessible materials.

#### Acceptance Criteria

1. WHEN classroom mode is activated, THE App SHALL enable Caption_Engine, AI_Assistant summarization, and Transcript_Store recording simultaneously with a single action.
2. WHEN a lecture recording session ends, THE AI_Assistant SHALL generate structured study notes including: title, key topic list, vocabulary terms with definitions, and a brief summary within 30 seconds of session end.
3. WHEN the AI_Assistant identifies a word used in the lecture that does not appear in the User's seen-vocabulary list, THE AI_Assistant SHALL add it to the User's vocabulary list with a definition.
4. THE User SHALL be able to review, export, and clear the vocabulary list at any time.
5. WHERE lecture recording is active, THE Transcript_Store SHALL save a timestamped audio recording alongside the text transcript for later review.
6. WHEN the User selects a keyword from the study notes, THE AI_Assistant SHALL display the surrounding transcript context for that keyword within 1 second.

---

### Requirement 14: Wearable and Bluetooth Integration

**User Story:** As a user who relies on wearable devices, I want the app to connect with my Bluetooth hearing aids, earbuds, and smartwatch, so that I can receive audio and alerts hands-free.

#### Acceptance Criteria

1. THE Wearable_Bridge SHALL support Bluetooth Classic (A2DP, HFP) and Bluetooth Low Energy (BLE) connections for hearing aids, earbuds, and smartwatches.
2. WHEN a Bluetooth audio device is connected, THE Audio_Engine SHALL route processed audio output to that device automatically.
3. WHEN a smartwatch is connected via the Wearable_Bridge, THE Notification_Dispatcher SHALL forward sound alerts and emergency notifications to the watch with a vibration pattern.
4. IF a connected Bluetooth device disconnects unexpectedly, THEN THE Wearable_Bridge SHALL attempt to reconnect automatically for up to 30 seconds before notifying the User.
5. THE Wearable_Bridge SHALL display the battery level of connected Bluetooth devices on the App's main dashboard where the device exposes battery information via GATT or HFP.
6. WHERE a connected wearable supports display output (e.g., smart glasses or smartwatch), THE Wearable_Bridge SHALL transmit live caption text to the wearable display.

---

### Requirement 15: AI Environmental Awareness (Spatial Sound Narration)

**User Story:** As a user with severe hearing loss, I want the app to describe sounds in my environment in plain language, so that I can understand what is happening around me.

#### Acceptance Criteria

1. WHEN environmental awareness mode is active, THE Sound_Classifier SHALL classify detected sounds and THE AI_Assistant SHALL generate a natural-language description for each detected event (e.g., "Motorcycle approaching from behind", "Someone is knocking on the door").
2. WHEN a sound event is described, THE App SHALL display the description as an on-screen notification card that auto-dismisses after 5 seconds.
3. THE App SHALL queue multiple simultaneous sound descriptions and display them in order, with a maximum queue depth of 5 items, discarding older items when the queue is full.
4. WHERE the User has enabled text-to-speech output, THE App SHALL read the sound description aloud through the connected audio output device.
5. WHEN two sound events of the same category occur within 3 seconds of each other, THE App SHALL merge them into a single description rather than creating duplicate cards.

---

### Requirement 16: Permissions and Privacy

**User Story:** As a user concerned about privacy, I want the app to be transparent about data use and obtain explicit permissions, so that I can trust the app with sensitive audio data.

#### Acceptance Criteria

1. THE App SHALL request RECORD_AUDIO, ACCESS_FINE_LOCATION (emergency SOS only), CAMERA (flashlight alerts only), BLUETOOTH_CONNECT, POST_NOTIFICATIONS, and FOREGROUND_SERVICE permissions individually with a clear rationale displayed before each request.
2. THE App SHALL process all audio on-device by default and SHALL NOT transmit raw audio to any remote server without explicit User opt-in.
3. WHEN the User disables a permission after it has been granted, THE App SHALL disable the features that depend on that permission and notify the User of the impact.
4. THE App SHALL provide a privacy settings screen listing all data collected, the retention period, and an option to delete all stored data (transcripts, audiograms, analytics).
5. WHEN the User requests data deletion, THE App SHALL irreversibly remove all locally stored User data within 5 seconds and confirm deletion.
