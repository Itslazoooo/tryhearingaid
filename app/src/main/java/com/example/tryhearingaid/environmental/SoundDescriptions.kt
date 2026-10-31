package com.example.tryhearingaid.environmental

import com.example.tryhearingaid.data.SoundCategory

/**
 * Plain-language descriptions of detected sounds, written ahead of time so they need no AI model,
 * no download and no internet (Requirement 15.1). Every description names the sound it is about.
 */
object SoundDescriptions {

    /** Below this confidence the description says the detection is not certain. */
    const val CERTAIN_AT = 0.7f

    private val text: Map<SoundCategory, String> = mapOf(
        SoundCategory.DOORBELL to "Doorbell: someone is at the door.",
        SoundCategory.BABY_CRYING to "Baby crying: a baby is crying nearby.",
        SoundCategory.FIRE_ALARM to "Fire alarm: a loud alarm is sounding. Check for fire and be ready to leave.",
        SoundCategory.CAR_HORN to "Car horn: a vehicle is sounding its horn nearby.",
        SoundCategory.DOG_BARKING to "Dog barking: a dog is barking nearby.",
        SoundCategory.NAME_CALLED to "Name called: someone may be calling your name.",
        SoundCategory.KNOCK to "Knock: someone is knocking.",
        SoundCategory.PHONE_RINGING to "Phone ringing: a phone is ringing nearby.",
        SoundCategory.WATER_RUNNING to "Water running: a tap or running water nearby.",
        SoundCategory.GLASS_BREAKING to "Glass breaking: something made of glass has smashed nearby.",
        SoundCategory.SIREN to "Siren: an emergency vehicle siren is sounding.",
        SoundCategory.SMOKE_ALARM to "Smoke alarm: an alarm is sounding. Check for smoke or fire.",
        SoundCategory.CROWD_CHEERING to "Crowd cheering: a crowd is cheering nearby.",
        SoundCategory.BIRDS to "Birds: birds are singing nearby.",
        SoundCategory.THUNDER to "Thunder: thunder was heard nearby.",
        SoundCategory.SPEECH to "Speech: someone is talking nearby.",
        SoundCategory.CO_ALARM to "CO alarm: the carbon monoxide alarm is sounding. Get to fresh air.",
        SoundCategory.FIRE_TRUCK_SIREN to "Fire truck siren: a fire engine is nearby.",
        SoundCategory.AMBULANCE_SIREN to "Ambulance siren: an ambulance is nearby.",
        SoundCategory.POLICE_SIREN to "Police siren: a police vehicle is nearby.",
        SoundCategory.VERBAL_HELP to "Verbal help: someone may be calling for help."
    )

    /** The description for [category]. Never blank. Adds "Not certain." below [CERTAIN_AT]. */
    fun describe(category: SoundCategory, confidence: Float): String {
        val base = text[category] ?: "${SoundNarrator.categoryLabel(category)}: a sound was detected nearby."
        return if (confidence < CERTAIN_AT) "$base Not certain." else base
    }

    /** Reads the "confidence NN percent" text the narrator sends; returns [default] if it is missing. */
    fun confidenceFrom(context: String, default: Float = 1f): Float {
        val percent = Regex("(\\d{1,3})\\s*percent").find(context)?.groupValues?.get(1)?.toIntOrNull()
        return percent?.let { (it / 100f).coerceIn(0f, 1f) } ?: default
    }
}
