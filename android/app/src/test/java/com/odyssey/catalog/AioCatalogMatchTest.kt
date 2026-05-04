package com.odyssey.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AioCatalogMatchTest {

    private val clutter = AioCatalogEpisode(
        name = "Clutter",
        shortName = "#657: Clutter",
        thumbnailSmall = "https://cdn.example/clutter_sm.jpg",
        thumbnailMedium = "https://cdn.example/clutter_md.jpg",
    )
    private val warOfTheWords = AioCatalogEpisode(
        name = "War of the Words",
        shortName = "#658: War of the Words",
        thumbnailSmall = "https://cdn.example/wotw_sm.jpg",
    )
    private val bonusEpisode = AioCatalogEpisode(
        // No canonical # — common for club-only / bonus content.
        name = "Knox on Money",
        shortName = "",
    )
    private val sample = AioCatalog(
        scrapedAtMs = 1_700_000_000_000L,
        albumCount = 2,
        albums = listOf(
            AioAlbum(
                albumNumber = "51",
                name = "#51: Take It From the Top",
                imageUrl = "https://cdn.example/album-51.png",
                episodes = listOf(clutter, warOfTheWords),
            ),
            AioAlbum(
                albumNumber = "81",
                name = "#81: Never a Dull Moment",
                imageUrl = "https://cdn.example/album-81.png",
                episodes = listOf(bonusEpisode),
            ),
        ),
    )

    // ----- normalize -----

    @Test
    fun `normalize strips number prefix and lowercases`() {
        assertEquals("clutter", normalizeTitle("#657: Clutter"))
        assertEquals("clutter", normalizeTitle("Clutter"))
        assertEquals("clutter", normalizeTitle("CLUTTER"))
    }

    @Test
    fun `normalize strips fractional number prefix (78_5)`() {
        // Some albums are #78½ / #78.5 — same logic should still strip.
        assertEquals("clutter", normalizeTitle("#78.5: Clutter"))
    }

    @Test
    fun `normalize strips curly quotes oneplace sometimes uses`() {
        assertEquals("knox on money", normalizeTitle("“Knox on Money”"))
        assertEquals("knox on money", normalizeTitle("‘Knox on Money’"))
    }

    @Test
    fun `normalize collapses repeated whitespace`() {
        assertEquals("war of the words", normalizeTitle("War  of\tthe\nWords"))
    }

    @Test
    fun `normalize on blank or whitespace returns empty`() {
        assertEquals("", normalizeTitle(""))
        assertEquals("", normalizeTitle("   "))
    }

    // ----- stripNumberPrefix -----

    @Test
    fun `stripNumberPrefix on un-prefixed title is identity`() {
        assertEquals("Clutter", stripNumberPrefix("Clutter"))
    }

    @Test
    fun `stripNumberPrefix handles colon and space variants`() {
        assertEquals("Clutter", stripNumberPrefix("#657: Clutter"))
        assertEquals("Clutter", stripNumberPrefix("#657 Clutter"))
        assertEquals("Clutter", stripNumberPrefix("# 657 :  Clutter"))
    }

    // ----- findMatchByTitle -----

    @Test
    fun `finds episode by exact name (oneplace - title to catalog name)`() {
        val match = findMatchByTitle(sample, "Clutter")
        assertEquals("Clutter", match?.episode?.name)
        assertEquals("51", match?.album?.albumNumber)
        assertEquals("#657: Clutter", match?.displayName)
        assertEquals("https://cdn.example/clutter_md.jpg", match?.thumbnailUrl)
    }

    @Test
    fun `finds episode case-insensitively`() {
        val match = findMatchByTitle(sample, "CLUTTER")
        assertEquals("Clutter", match?.episode?.name)
    }

    @Test
    fun `finds episode when oneplace title arrived with smart quotes`() {
        // oneplace's "Knox on Money" came from a club episode without
        // a canonical #, but if it did appear with quotes we still match.
        val match = findMatchByTitle(sample, "“Knox on Money”")
        assertEquals("Knox on Money", match?.episode?.name)
    }

    @Test
    fun `bonus episode without a canonical number falls back to name in displayName`() {
        val match = findMatchByTitle(sample, "Knox on Money")
        assertEquals("Knox on Money", match?.displayName)
        // No episode thumbnail, but the album's imageUrl is the fallback.
        assertEquals("https://cdn.example/album-81.png", match?.thumbnailUrl)
    }

    @Test
    fun `match falls back to episode short_name when name is empty`() {
        // Defensive: catalog rows with name="" but shortName present.
        val cat = AioCatalog(
            scrapedAtMs = 0,
            albumCount = 1,
            albums = listOf(
                AioAlbum(
                    albumNumber = "1",
                    episodes = listOf(AioCatalogEpisode(name = "", shortName = "#1: Whit's Flop")),
                ),
            ),
        )
        val match = findMatchByTitle(cat, "Whit's Flop")
        assertEquals("#1: Whit's Flop", match?.episode?.shortName)
    }

    @Test
    fun `unknown title returns null`() {
        assertNull(findMatchByTitle(sample, "Episode That Doesn't Exist"))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(findMatchByTitle(sample, ""))
        assertNull(findMatchByTitle(sample, "   "))
    }

    @Test
    fun `match prefers medium thumbnail over small over album cover`() {
        // Episode with both → medium wins.
        val cat = AioCatalog(
            scrapedAtMs = 0, albumCount = 1,
            albums = listOf(AioAlbum(albumNumber = "X", imageUrl = "alb.png", episodes = listOf(clutter))),
        )
        assertEquals("https://cdn.example/clutter_md.jpg", findMatchByTitle(cat, "Clutter")?.thumbnailUrl)

        // Episode with only small → small wins.
        val cat2 = AioCatalog(
            scrapedAtMs = 0, albumCount = 1,
            albums = listOf(AioAlbum(albumNumber = "X", imageUrl = "alb.png", episodes = listOf(warOfTheWords))),
        )
        assertEquals("https://cdn.example/wotw_sm.jpg", findMatchByTitle(cat2, "War of the Words")?.thumbnailUrl)
    }
}
