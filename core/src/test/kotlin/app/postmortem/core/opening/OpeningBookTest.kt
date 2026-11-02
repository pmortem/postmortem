package app.postmortem.core.opening

import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpeningBookTest {
    private val book: OpeningBook by lazy { OpeningBook.bundled() }

    private fun detect(moves: String): Opening? = book.detect((PgnParser.parse(moves) as PgnResult.Success).game)

    @Test
    fun theBundledTableHasThousandsOfOpenings() {
        assertTrue(book.size > 3000, "only ${book.size} positions")
    }

    @Test
    fun theItalianGame() {
        assertEquals(Opening("C50", "Italian Game"), detect("1. e4 e5 2. Nf3 Nc6 3. Bc4"))
    }

    @Test
    fun theSicilianNajdorfAndItsDepthWins() {
        val najdorf = detect("1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6")
        assertEquals("Sicilian Defense: Najdorf Variation", najdorf?.name)
        assertTrue(najdorf!!.eco.startsWith("B9"))
        // A longer line of the same variation is found, and moves played after the book ends do not hide it.
        val adams = detect("1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6 6. h3")
        assertTrue(adams!!.name.startsWith("Sicilian Defense: Najdorf Variation"), adams.name)
        assertEquals(adams, detect("1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6 6. h3 e6 7. g4 h6 8. Bg2 Nc6 9. Qe2 Be7 10. O-O"))
    }

    @Test
    fun aTranspositionIsFoundByPosition() {
        // The table's line is 1.d4 d5 2.c4 e6; the same position reached as 1.c4 e6 2.d4 d5 or 1.d4 e6 2.c4 d5 has the same name.
        val qgd = Opening("D30", "Queen's Gambit Declined")
        assertEquals(qgd, detect("1. d4 d5 2. c4 e6"))
        assertEquals(qgd, detect("1. c4 e6 2. d4 d5"))
        assertEquals(qgd, detect("1. d4 e6 2. c4 d5"))
    }

    @Test
    fun scholarsMateIsNamedByItsFirstMoves() {
        val opening = detect("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#")
        assertNotNull(opening)
        assertTrue(opening.eco.startsWith("C"), opening.toString())
    }

    @Test
    fun aGameThatLeavesTheBookAtOnceHasNoOpening() {
        val small = OpeningBook.parse("B00\tKing's Pawn\t1. e4\n")
        assertEquals("King's Pawn", small.detect((PgnParser.parse("1. e4 e5") as PgnResult.Success).game)?.name)
        assertNull(small.detect((PgnParser.parse("1. d4 d5") as PgnResult.Success).game))
    }

    @Test
    fun aSetUpPositionHasNoOpening() {
        val pgn = "[SetUp \"1\"]\n[FEN \"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1\"]\n\n1... e5 2. Nf3 Nc6 3. Bc4"
        assertNull(book.detect((PgnParser.parse(pgn) as PgnResult.Success).game))
    }

    @Test
    fun badLinesAreSkipped() {
        val small = OpeningBook.parse("eco\tname\tpgn\nX00\tNonsense\t1. e9\nnot a line\nB00\tKing's Pawn\t1. e4\n")
        assertEquals(1, small.size)
    }

    @Test
    fun detectionAgreesWithLichessOwnOpeningTagsOnRealGames() {
        var compared = 0
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            val game = Fixtures.game(name)
            val tag = game.headers["Opening"] ?: continue
            val detected = book.detect(game)
            // Lichess names games from this same table, so a game's tag is the name of an entry on its path.
            assertNotNull(detected, "$name has the tag '$tag' but nothing was detected")
            assertTrue(
                tag.startsWith(detected.name) || detected.name.startsWith(tag.substringBefore(':')),
                "$name: tag '$tag', detected '${detected.name}'",
            )
            compared++
        }
        assertTrue(compared >= 3, "only $compared fixture games carry an Opening tag")
    }
}
