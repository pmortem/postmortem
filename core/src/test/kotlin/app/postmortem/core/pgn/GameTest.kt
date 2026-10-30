package app.postmortem.core.pgn

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
