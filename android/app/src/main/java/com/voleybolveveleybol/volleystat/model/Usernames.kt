package com.voleybolveveleybol.volleystat.model

import java.text.Normalizer
import java.util.Locale

/** The server's rule for a username (docs/API_V1.md, section 10): 3 to 30 of a-z, 0-9, dot and underscore, a letter first. */
private val USERNAME = Regex("[a-z][a-z0-9._]{2,29}")

const val USERNAME_MAX_LENGTH = 30

/** The letters a suggestion starts with when the name itself starts with a digit: usernames start with a letter. */
const val USERNAME_PREFIX = "oyuncu"

fun usernameValid(username: String): Boolean = USERNAME.matches(username)

/**
 * The username as the app sends it: trimmed and lowercased with the root locale. In a Turkish
 * locale "I".lowercase() is the dotless ı, which the server refuses.
 */
fun normalizeUsername(typed: String): String = typed.trim().lowercase(Locale.ROOT)

/** Turkish letters that a plain Unicode decomposition does not reduce to ASCII (ı) or that must not keep a dot (İ). */
private val TURKISH = mapOf(
    'ç' to 'c', 'Ç' to 'c', 'ğ' to 'g', 'Ğ' to 'g', 'ı' to 'i', 'İ' to 'i',
    'ö' to 'o', 'Ö' to 'o', 'ş' to 's', 'Ş' to 's', 'ü' to 'u', 'Ü' to 'u',
)
private val MARKS = Regex("\\p{Mn}+")
private val SPACES = Regex("\\s+")
private val DOTS = Regex("\\.{2,}")

/**
 * The username the register screen fills in from the display name (design 10.1): Turkish letters
 * folded to ASCII, lowercased, spaces made dots, every character outside a-z, 0-9, dot and
 * underscore dropped, and a letter first. Empty when the name holds nothing usable. The user can
 * edit it; the suggestion may still be too short for the server's rule.
 */
fun suggestUsername(displayName: String): String {
    val folded = displayName.map { TURKISH[it] ?: it }.joinToString("")
    val plain = Normalizer.normalize(folded, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)
    val kept = plain.trim().replace(SPACES, ".").filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' }
    var name = kept.replace(DOTS, ".").trimStart('.', '_').trimEnd('.')
    if (name.isEmpty()) return ""
    if (name.first() !in 'a'..'z') name = USERNAME_PREFIX + name
    return name.take(USERNAME_MAX_LENGTH).trimEnd('.')
}

/**
 * The username the register screen offers after the server answered that [taken] is taken
 * (design 2.12, `username_taken`): its trailing digits replaced by two others, within 30
 * characters, and never [taken] itself.
 */
fun suggestAnotherUsername(taken: String, random: kotlin.random.Random = kotlin.random.Random.Default): String {
    val base = taken.trimEnd { it in '0'..'9' }.ifEmpty { USERNAME_PREFIX }.take(USERNAME_MAX_LENGTH - 2)
    while (true) {
        val next = base + random.nextInt(10, 100)
        if (next != taken) return next
    }
}
