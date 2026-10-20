package org.hostapptv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.hostapptv.player.core.EditionParser
import org.hostapptv.player.core.Redactor
import org.hostapptv.player.core.TextNormalizer
import org.hostapptv.player.data.repo.SearchQuery

class RedactorTest {
    @Test fun `strips credentials from api query`() {
        val out = Redactor.redact("http://h:8080/player_api.php?username=alice&password=s3cret&action=get_live_streams")
        assertFalse(out.contains("alice")); assertFalse(out.contains("s3cret")); assertTrue(out.contains("action=get_live_streams"))
    }
    @Test fun `strips credentials from stream paths`() {
        listOf("live", "movie", "series").forEach { kind ->
            val out = Redactor.redact("http://h/$kind/alice/s3cret/123.ts failed")
            assertEquals("http://h/$kind/***/***/123.ts failed", out)
        }
    }
    @Test fun `strips userinfo and explicit secrets`() {
        assertEquals("https://***@host/x", Redactor.redact("https://bob:pw@host/x"))
        assertEquals("token *** seen", Redactor.redact("token hunter2 seen", "hunter2"))
    }
}

class TextNormalizerTest {
    @Test fun `folds accents case and punctuation`() {
        assertEquals("cafe societe unicode", TextNormalizer.normalize("Café Société: Ünïcödé!"))
        assertEquals("spider man 2", TextNormalizer.normalize("Spider-Man   2"))
    }
    @Test fun `unifies arabic letter variants and removes harakat`() {
        assertEquals(TextNormalizer.normalize("احمد"), TextNormalizer.normalize("أَحْمَد"))
        assertEquals(TextNormalizer.normalize("مدرسه"), TextNormalizer.normalize("مدرسة"))
        assertEquals("قناه 2", TextNormalizer.normalize("قناة 2"))
    }
    @Test fun `blank input is empty`() { assertEquals("", TextNormalizer.normalize("  ...  ")) }
}

class SearchQueryTest {
    @Test fun `builds token and prefix patterns`() {
        val q = SearchQuery.from("The  Dark Knight")!!
        assertEquals("the dark knight", q.q)
        assertEquals("%the%dark%knight%", q.pattern)
        assertEquals("the dark knight%", q.prefix)
        assertEquals("% the dark knight%", q.wordPrefix)
    }
    @Test fun `like wildcards never reach the pattern`() {
        // Normalisation turns % and _ into word breaks, so user text cannot act as a LIKE wildcard.
        assertEquals("100 x%", SearchQuery.from("100_x")!!.prefix)
        assertEquals("%50%off%", SearchQuery.from("50% off")!!.pattern)
    }
    @Test fun `punctuation only is no query`() { assertNull(SearchQuery.from("!!!")) }
}

class EditionParserTest {
    @Test fun `parses language prefix, year and quality`() {
        val e = EditionParser.parse("EN - The Film (2019) 4K")
        assertEquals("The Film", e.title); assertEquals("EN", e.language); assertEquals("4K", e.quality); assertEquals(2019, e.year)
    }
    @Test fun `parses bracketed forms`() {
        val e = EditionParser.parse("|FR| Le Film [FHD]")
        assertEquals("Le Film", e.title); assertEquals("FR", e.language); assertEquals("1080p", e.quality)
    }
    @Test fun `unknown stays unknown`() {
        val e = EditionParser.parse("Inception")
        assertEquals("Inception", e.title); assertNull(e.language); assertNull(e.quality); assertNull(e.year)
    }
    @Test fun `title words are not mistaken for languages`() {
        assertNull(EditionParser.parse("It: Chapter Two").language)
        assertNull(EditionParser.parse("Up - A Pixar Film").language)
    }
    @Test fun `provider year wins and arabic titles survive`() {
        val e = EditionParser.parse("AR - فيلم اختبار", "2021-05-01")
        assertEquals("فيلم اختبار", e.title); assertEquals("AR", e.language); assertEquals(2021, e.year)
    }
    @Test fun `groups only by media type plus valid tmdb id`() {
        assertEquals("movie:tmdb:603", EditionParser.groupKey("movie", "603", 1))
        assertEquals("movie:tmdb:603", EditionParser.groupKey("movie", " 603 ", 2))
        assertEquals("series:tmdb:603", EditionParser.groupKey("series", "603", 3))
        assertEquals("movie:id:4", EditionParser.groupKey("movie", "", 4))
        assertEquals("movie:id:5", EditionParser.groupKey("movie", "0", 5))
        assertEquals("movie:id:6", EditionParser.groupKey("movie", "tt0133093", 6))
        assertEquals("movie:id:7", EditionParser.groupKey("movie", null, 7))
    }
}
