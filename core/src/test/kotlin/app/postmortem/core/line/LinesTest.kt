package app.postmortem.core.line

import app.postmortem.core.board.Moves
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.Ply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinesTest {
    // 1.e4 e5 2.Qh5 Nc6 3.Bc4 Nf6 4.Qxf7#
    private val game: Game = Fixtures.game("scholars-mate")

    private fun move(
        fen: String,
        uci: String,
    ): Ply = assertNotNull(Moves.play(fen, uci.substring(0, 2), uci.substring(2, 4), uci.getOrNull(4)), uci)

    private fun Lines.play(
        cursor: Cursor,
        uci: String,
    ) = play(game, cursor, move(fen(game, cursor), uci))

    @Test
    fun theGamesOwnNextMoveStepsForwardAndMakesNoLine() {
        val played = Lines().play(Cursor.game(2), game.plies[2].uci)
        assertEquals(Cursor.game(3), played.cursor)
        assertTrue(played.lines.all.isEmpty())
    }

    @Test
    fun anotherMoveStartsALineThatBranchesAtThatPlyAndIsSelected() {
        val played = Lines().play(Cursor.game(4), "d2d4")
        val line = played.lines.all.single()
        assertEquals(Line(1, null, 4, listOf(move(game.fenAt(4), "d2d4"))), line)
        assertEquals(Cursor(1, 1), played.cursor)
        assertEquals(line.plies.single().fen, played.lines.fen(game, played.cursor))
        assertEquals("d2d4", played.lines.lastMove(game, played.cursor))
        assertEquals(4, played.lines.gamePly(played.cursor))
    }

    @Test
    fun aMoveAtTheEndOfALineExtendsIt() {
        val first = Lines().play(Cursor.game(4), "d2d4")
        val second = first.lines.play(first.cursor, "d7d5")
        val third = second.lines.play(second.cursor, "c2c4")
        assertEquals(
            listOf("d2d4", "d7d5", "c2c4"),
            third.lines.all
                .single()
                .plies
                .map { it.uci },
        )
        assertEquals(Cursor(1, 3), third.cursor)
    }

    @Test
    fun aDifferentMoveInTheMiddleOfALineStartsAnotherLineWithTheMovesUpToThereCopied() {
        var played = Lines().play(Cursor.game(4), "d2d4")
        played = played.lines.play(played.cursor, "d7d5")
        played = played.lines.play(played.cursor, "c2c4")
        val other = played.lines.play(Cursor(1, 2), "g1f3")
        assertEquals(2, other.lines.all.size)
        assertEquals(
            listOf("d2d4", "d7d5", "c2c4"),
            other.lines
                .find(1)!!
                .plies
                .map { it.uci },
            "the first line is unchanged",
        )
        assertEquals(
            listOf("d2d4", "d7d5", "g1f3"),
            other.lines
                .find(2)!!
                .plies
                .map { it.uci },
        )
        assertEquals(4, other.lines.find(2)!!.branchPly)
        assertEquals(Cursor(2, 3), other.cursor)
    }

    @Test
    fun theLinesNextMoveInTheMiddleOnlyStepsForward() {
        var played = Lines().play(Cursor.game(4), "d2d4")
        played = played.lines.play(played.cursor, "d7d5")
        val stepped = played.lines.play(Cursor(1, 1), "d7d5")
        assertEquals(Cursor(1, 2), stepped.cursor)
        assertEquals(1, stepped.lines.all.size)
    }

    @Test
    fun aSecondDifferentMoveAtTheSameGamePositionAddsALineBesideTheFirstInTheOrderMade() {
        val first = Lines().play(Cursor.game(4), "d2d4")
        val second = first.lines.play(Cursor.game(4), "g1f3")
        assertEquals(listOf(1, 2), second.lines.branchingAt(4).map { it.id })
        assertTrue(second.lines.branchingAt(3).isEmpty())
    }

    @Test
    fun theSameMoveAgainSelectsTheExistingLineInsteadOfCopyingIt() {
        val first = Lines().play(Cursor.game(4), "d2d4")
        val again = first.lines.play(Cursor.game(4), "d2d4")
        assertEquals(first.lines, again.lines)
        assertEquals(Cursor(1, 1), again.cursor)
    }

    @Test
    fun steppingGoesAlongTheLineBackToItsBranchAndThenDownTheGame() {
        var played = Lines().play(Cursor.game(4), "d2d4")
        played = played.lines.play(played.cursor, "d7d5")
        val lines = played.lines
        assertEquals(Cursor(1, 1), lines.step(game, played.cursor, -1))
        assertEquals(Cursor.game(4), lines.step(game, Cursor(1, 1), -1))
        assertEquals(Cursor.game(3), lines.step(game, Cursor.game(4), -1))
        assertEquals(Cursor(1, 2), lines.step(game, Cursor(1, 1), 1))
        assertEquals(Cursor(1, 2), lines.step(game, Cursor(1, 2), 1), "the end of a line stays")
        assertFalse(lines.canStepForward(game, Cursor(1, 2)))
        assertTrue(lines.canStepForward(game, Cursor(1, 1)))
        assertTrue(lines.canStepBack(Cursor(1, 1)))
        assertFalse(lines.canStepBack(Cursor.game(0)))
        assertEquals(Cursor.game(game.plies.size), lines.step(game, Cursor.game(game.plies.size), 1))
    }

    @Test
    fun leavingALineGoesToItsBranchPositionAndTheGameStaysPut() {
        val played = Lines().play(Cursor.game(4), "d2d4")
        assertEquals(Cursor.game(4), played.lines.leave(played.cursor))
        assertEquals(Cursor.game(2), played.lines.leave(Cursor.game(2)))
    }

    @Test
    fun theGameIsNeverChangedByLines() {
        val before = Fixtures.game("scholars-mate")
        var played = Lines().play(Cursor.game(2), "g1f3")
        played = played.lines.play(played.cursor, "b8c6")
        assertEquals(before, game)
        assertNull(played.lines.find(99))
    }
}
