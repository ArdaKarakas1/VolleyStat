package com.voleybolveveleybol.volleystat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The username the register screen fills in from the display name (design 10.1). The user can still edit it. */
class UsernameSuggestionTest {

    @Test fun turkishLettersFoldToAsciiAndTheSpaceBecomesADot() {
        assertEquals("cagri.ozturk", suggestUsername("Çağrı Öztürk"))
        assertEquals("ilker.isik", suggestUsername("İlker Işık"))
        assertEquals("sule.gunes", suggestUsername("ŞULE GÜNEŞ"))
    }

    @Test fun lettersWithOtherMarksLoseTheirMarks() {
        assertEquals("rene.aydin", suggestUsername("René Aydın"))
        assertEquals("hakan", suggestUsername("Hâkan"))
    }

    @Test fun digitsDotsAndUnderscoresAreKept() {
        assertEquals("ali.veli.10", suggestUsername("Ali.Veli 10"))
        assertEquals("ayse_7", suggestUsername("Ayşe_7"))
    }

    @Test fun everyOtherCharacterIsDroppedAndNoDotIsDoubled() {
        assertEquals("ali.veli", suggestUsername("  Ali  -  Veli!  "))
        assertEquals("deniz", suggestUsername("Deniz 😀"))
        assertEquals("oreilly", suggestUsername("O'Reilly"))
    }

    @Test fun aLeadingDigitGetsALetterInFront() {
        assertEquals("oyuncu7.numara", suggestUsername("7 Numara"))
        assertEquals("oyuncu23", suggestUsername("23"))
        assertTrue(usernameValid(suggestUsername("23")))
        // Dots and underscores can not stand first either.
        assertEquals("emre", suggestUsername("..Emre"))
        assertEquals("oyuncu9", suggestUsername("_9"))
    }

    @Test fun anEmptyOrSymbolOnlyNameSuggestsNothing() {
        assertEquals("", suggestUsername(""))
        assertEquals("", suggestUsername("   "))
        assertEquals("", suggestUsername("!? -"))
    }

    @Test fun aLongNameIsCutToThirtyCharactersWithoutATrailingDot() {
        val suggestion = suggestUsername("Abdurrahmangazi Kılıçarslanoğlu Yıldırım")
        assertEquals("abdurrahmangazi.kilicarslanogl", suggestion)
        assertEquals(30, suggestion.length)
        assertEquals("abcdefghijklmnopqrstuvwxyzabc", suggestUsername("Abcdefghijklmnopqrstuvwxyzabc Ek"))
        assertTrue(usernameValid(suggestion))
    }

    @Test fun theValidityCheckIsTheServersRule() {
        assertTrue(usernameValid("ali"))
        assertTrue(usernameValid("a.b_c9"))
        assertFalse(usernameValid("al"))
        assertFalse(usernameValid("9ali"))
        assertFalse(usernameValid("Ali"))
        assertFalse(usernameValid("ali veli"))
        assertFalse(usernameValid("a".repeat(31)))
        assertTrue(usernameValid("a".repeat(30)))
    }

    @Test fun aTakenUsernameGetsTwoDigitsInPlaceOfItsOwnTrailingDigits() {
        repeat(200) { seed ->
            val random = kotlin.random.Random(seed)
            val next = suggestAnotherUsername("cagri.ozturk", random)
            assertTrue(next, Regex("cagri\\.ozturk[1-9][0-9]").matches(next))
            assertTrue(usernameValid(next))
            val again = suggestAnotherUsername(next, random)
            assertTrue(again, Regex("cagri\\.ozturk[1-9][0-9]").matches(again))
            assertFalse("a suggestion never repeats the taken name", again == next)
        }
        assertTrue(Regex("ali[1-9][0-9]").matches(suggestAnotherUsername("ali7", kotlin.random.Random(1))))
    }

    @Test fun aTakenUsernameOfThirtyCharactersStaysWithinThirty() {
        val taken = "a".repeat(30)
        val next = suggestAnotherUsername(taken, kotlin.random.Random(3))
        assertEquals(30, next.length)
        assertTrue(next.startsWith("a".repeat(28)))
        assertTrue(usernameValid(next))
        // A name of digits only keeps a letter in front.
        assertTrue(usernameValid(suggestAnotherUsername("123", kotlin.random.Random(4))))
    }

    @Test fun aTypedUsernameIsTrimmedAndLowercasedTheSameWayInATurkishLocale() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            // In the Turkish locale "I".lowercase() would be the dotless ı, which the server refuses.
            assertEquals("ilker", normalizeUsername("  ILKER "))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
