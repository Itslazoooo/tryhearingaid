package com.example.tryhearingaid.data

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Room TypeConverters for all non-primitive types used in the database.
 *
 * Covers:
 *  - UUID  ↔  String
 *  - Instant  ↔  Long (epoch millis)
 *  - LocalDate  ↔  String (ISO-8601)
 *  - Map<Int, Int>  ↔  String (JSON)
 *  - List<String>  ↔  String (JSON)
 *  - List<VocabTerm>  ↔  String (JSON)
 */
class HearingAidTypeConverters {

    private val gson = Gson()

    // ── UUID ─────────────────────────────────────────────────────────────────

    @TypeConverter
    fun uuidToString(uuid: UUID?): String? = uuid?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let { UUID.fromString(it) }

    // ── Instant ───────────────────────────────────────────────────────────────

    @TypeConverter
    fun instantToLong(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    // ── LocalDate ─────────────────────────────────────────────────────────────

    @TypeConverter
    fun localDateToString(date: LocalDate?): String? = date?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let { LocalDate.parse(it) }

    // ── Map<Int, Int> — used for audiogram ear maps ───────────────────────────

    @TypeConverter
    fun intIntMapToString(map: Map<Int, Int>?): String? {
        if (map == null) return null
        return gson.toJson(map)
    }

    @TypeConverter
    fun stringToIntIntMap(value: String?): Map<Int, Int>? {
        if (value == null) return null
        val type = object : TypeToken<Map<Int, Int>>() {}.type
        return gson.fromJson(value, type)
    }

    // ── List<String> — used for keyTopics ─────────────────────────────────────

    @TypeConverter
    fun stringListToString(list: List<String>?): String? {
        if (list == null) return null
        return gson.toJson(list)
    }

    @TypeConverter
    fun stringToStringList(value: String?): List<String>? {
        if (value == null) return null
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, type)
    }

    // ── List<VocabTerm> — used for StudyNotes.vocabularyTerms ─────────────────

    @TypeConverter
    fun vocabTermListToString(list: List<VocabTerm>?): String? {
        if (list == null) return null
        return gson.toJson(list)
    }

    @TypeConverter
    fun stringToVocabTermList(value: String?): List<VocabTerm>? {
        if (value == null) return null
        val type = object : TypeToken<List<VocabTerm>>() {}.type
        return gson.fromJson(value, type)
    }

    // ── List<EqBand> — used for HearingProfile.eqBands when stored inline ─────

    @TypeConverter
    fun eqBandListToString(list: List<EqBand>?): String? {
        if (list == null) return null
        return gson.toJson(list)
    }

    @TypeConverter
    fun stringToEqBandList(value: String?): List<EqBand>? {
        if (value == null) return null
        val type = object : TypeToken<List<EqBand>>() {}.type
        return gson.fromJson(value, type)
    }
}
