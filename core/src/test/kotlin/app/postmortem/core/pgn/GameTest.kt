package app.postmortem.core.pgn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    @Test
    fun theLastMoveIsTheSelectedPliesMoveAndNoneAtTheStart() {
        val game = Fixtures.game("scholars-mate")
        assertNull(game.lastMoveAt(0))
        assertEquals("e2e4", game.lastMoveAt(1))
        assertEquals(game.plies.last().uci, game.lastMoveAt(game.plies.size))
    }

    @Test
    fun theLastMoveOfCastlingAndPromotionNamesTheKingsAndThePawnsSquares() {
        val castles = Fixtures.game("lichess-mate-castle")
        assertTrue(castles.lastMoveAt(castles.plies.indexOfFirst { it.san == "O-O" } + 1) in setOf("e1g1", "e8g8"))

        val long = Fixtures.game("lichess-promotion-long-castle")
        assertTrue(long.lastMoveAt(long.plies.indexOfFirst { it.san == "O-O-O" } + 1) in setOf("e1c1", "e8c8"))
        val promotion = long.plies.indexOfFirst { '=' in it.san }
        assertEquals(long.plies[promotion].uci, long.lastMoveAt(promotion + 1))
        assertEquals(5, long.plies[promotion].uci.length)
    }

    @Test
    fun theLastMoveOfAnEnPassantCaptureIsThePawnsDiagonalStep() {
        val game = Fixtures.game("chesscom-en-passant")
        val index =
            game.plies.indices.first { i ->
                val move = game.plies[i].uci
                val before = game.fenAt(i).substringBefore(' ').split('/')
                val target = before[8 - (move[3] - '0')].flatMap { if (it.isDigit()) List(it - '0') { '.' } else listOf(it) }
                'x' in game.plies[i].san && game.plies[i].san[0].isLowerCase() && target[move[2] - 'a'] == '.'
            }
        assertEquals(game.plies[index].uci, game.lastMoveAt(index + 1))
        assertEquals(game.plies[index].san[0], game.plies[index].uci[0])
    }
}
