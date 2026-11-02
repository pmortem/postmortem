package app.postmortem.core.pgn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameTest {
    @Test
    fun moveCountRoundsUpToWholeMoves() {
        val game = Fixtures.game("chesscom-short-resign")
        assertEquals(57, game.plies.size)
        assertEquals(29, game.moveCount)
        assertEquals(12, Fixtures.game("lichess-short-resign").moveCount)
    }

    @Test
    fun plyLabelsNumberBothSides() {
        val plies = Fixtures.game("lichess-mate-castle").plies
        assertEquals("1.${plies[0].san}", plyLabel(1, plies[0].san))
        assertEquals("1...${plies[1].san}", plyLabel(2, plies[1].san))
        assertEquals("12.${plies[22].san}", plyLabel(23, plies[22].san))
        assertEquals("12...${plies[23].san}", plyLabel(24, plies[23].san))
    }

    @Test
    fun whoMovesFirstFollowsTheStartPosition() {
        assertTrue(Fixtures.game("scholars-mate").whiteMovesFirst)
        val blackFirst = "[FEN \"rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1\"]\n[SetUp \"1\"]\n\n1... e5 2. Nf3"
        assertFalse((PgnParser.parse(blackFirst) as PgnResult.Success).game.whiteMovesFirst)
    }
}
