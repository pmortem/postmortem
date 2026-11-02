package app.postmortem.core.pgn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameOverviewTest {
    @Test
    fun aLichessExportFillsTheOverview() {
        val overview = Fixtures.game("lichess-clocks-evals-mate").overview
        assertEquals("alpov", overview.white)
        assertEquals("2478", overview.whiteElo)
        assertEquals("Angelicanmade", overview.black)
        assertEquals("2474", overview.blackElo)
        assertEquals("2026.10.30", overview.date)
        assertEquals("rated blitz game", overview.event)
        assertEquals("1-0", overview.result)
        assertEquals("Normal", overview.termination)
        assertEquals("Queen's Pawn Game", overview.opening)
        assertEquals("A41", overview.eco)
    }

    @Test
    fun aChessComExportFillsTheOverview() {
        val overview = Fixtures.game("chesscom-promotion-long").overview
        assertTrue(!overview.isEmpty)
        assertTrue(overview.white != null && overview.black != null)
    }

    @Test
    fun aBareMoveListHasNothingToShow() {
        val game = (PgnParser.parse("1. e4 e5 2. Nf3 Nc6") as PgnResult.Success).game
        assertTrue(game.overview.isEmpty)
    }

    @Test
    fun placeholderValuesAreHiddenNotShownAsQuestionMarks() {
        val overview =
            GameOverview.of(
                mapOf(
                    "White" to "?",
                    "Black" to "Kasparov",
                    "BlackElo" to "?",
                    "Date" to "????.??.??",
                    "Event" to "?",
                    "Site" to "?",
                    "Round" to "-",
                    "Result" to "*",
                ),
            )
        assertNull(overview.white)
        assertEquals("Kasparov", overview.black)
        assertNull(overview.blackElo)
        assertNull(overview.date)
        assertNull(overview.event)
        assertNull(overview.site)
        assertNull(overview.result)
    }

    @Test
    fun aPartialDateKeepsTheKnownPart() {
        assertEquals("2024", GameOverview.of(mapOf("Date" to "2024.??.??")).date)
        assertEquals("2024.03", GameOverview.of(mapOf("Date" to "2024.03.??")).date)
        assertEquals("2024.03.05", GameOverview.of(mapOf("Date" to "2024.03.05")).date)
    }

    @Test
    fun eitherOpeningTagAloneIsKept() {
        assertEquals("C50", GameOverview.of(mapOf("ECO" to "C50")).eco)
        assertNull(GameOverview.of(mapOf("ECO" to "C50")).opening)
        assertEquals("Italian Game", GameOverview.of(mapOf("Opening" to "Italian Game")).opening)
    }
}
