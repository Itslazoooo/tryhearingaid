package com.example.tryhearingaid.emergency

/**
 * Rules and storage format for the emergency contact list (Requirement 10.3).
 *
 * Pure Kotlin so it can be unit tested on the JVM.
 */
object SosContacts {

    /** The SOS message goes to at most this many contacts. */
    const val MAX_CONTACTS = 5

    private const val SEPARATOR = "\n"

    /**
     * Clean a list of phone numbers: trim spaces, drop blanks and duplicates, keep the first
     * [MAX_CONTACTS]. Order is preserved.
     */
    fun normalize(numbers: List<String>): List<String> =
        numbers.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_CONTACTS)

    /** Turn a list into one string for storage. The list is [normalize]d first. */
    fun encode(numbers: List<String>): String = normalize(numbers).joinToString(SEPARATOR)

    /** Read a stored string back into a list. A null or empty string gives an empty list. */
    fun decode(stored: String?): List<String> =
        if (stored.isNullOrEmpty()) emptyList() else normalize(stored.split(SEPARATOR))
}
