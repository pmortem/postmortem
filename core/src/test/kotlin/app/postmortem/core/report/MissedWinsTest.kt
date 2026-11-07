package app.postmortem.core.report

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.Judgement
import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.store.GameStore
import app.postmortem.core.store.JdbcSqlDb
import app.postmortem.core.store.MainLine
import app.postmortem.core.store.NewGame
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Missed wins and missed mates on a real game (25 moves: 50 plies) with made-up evals, White's point of view, one per position. */
class MissedWinsTest {
    private val game = Fixtures.game("lichess-mate-castle")
    private val positions = game.positionCount

    private fun line(vararg changes: Pair<Int, Eval>): MainLine {
        val evals = MutableList<Eval>(positions) { Eval.Cp(0) }
        for ((position, eval) in changes) evals[position] = eval
        return MainLine(game.plies.map { it.san }, evals, whiteMovesFirst = true)
    }

    /** From [from] on, every position has [eval]. */
    private fun from(
        from: Int,
        eval: Eval,
    ): Array<Pair<Int, Eval>> = (from until positions).map { it to eval }.toTypedArray()

    private fun played(
        outcome: Outcome,
        color: PieceColor = PieceColor.WHITE,
        id: Long = 1,
        date: String? = "2025.01.01",
    ) = PlayerGame(id, color, "Opponent", outcome, null, 80.0, date)

    private fun report(
        outcome: Outcome,
        line: MainLine,
        color: PieceColor = PieceColor.WHITE,
    ) = MissedWins.of(listOf(played(outcome, color)), mapOf(1L to line))

    /** +4.00 after White's plies 5, 7 and 9 (and Black's in between), then thrown away by White's ply 11. */
    private val thrown =
        line(
            *(5 until 11).map { it to Eval.Cp(400) }.toTypedArray(),
            *from(11, Eval.Cp(-300)),
        )

    @Test
    fun winningHeldForTwoMovesAndThenLostIsAMissedWinAtTheMoveThatThrewItAway() {
        assertTrue(positions > 40)
        val missed = report(Outcome.LOSS, thrown).wins.single()
        // positions 5..10 are +4.0: White's plies 5, 7, 9 are all winning; Black's 6, 8, 10 are too; ply 11 (White) drops to -3.0
        assertEquals(11, missed.turningPly)
        assertEquals(game.plies[10].san, missed.san)
        assertEquals(Eval.Cp(400), missed.peak)
        assertEquals(Eval.Cp(-300), missed.after)
        assertEquals(Judgement.BLUNDER, missed.judgement)
        assertEquals(1, report(Outcome.LOSS, thrown).decided)
    }

    @Test
    fun aDrawFromAWinningPositionCountsToo() {
        assertEquals(1, report(Outcome.DRAW, thrown).wins.size)
    }

    @Test
    fun winningAndWinningTheGameIsNotAMissedWin() {
        val won = line(*(5 until 11).map { it to Eval.Cp(400) }.toTypedArray(), *from(11, Eval.Cp(600)))
        assertTrue(report(Outcome.WIN, won).wins.isEmpty())
        assertTrue(report(Outcome.WIN, thrown).wins.isEmpty(), "even if the evals say otherwise, a won game is not a missed win")
    }

    @Test
    fun winningAfterOnlyOneOfTheMovesIsNotEnough() {
        // +4.0 after ply 5 only; after ply 7 it is equal again
        val once = line(5 to Eval.Cp(400), 6 to Eval.Cp(200), 7 to Eval.Cp(0))
        assertTrue(report(Outcome.LOSS, once).wins.isEmpty())
    }

    @Test
    fun anUnfinishedGameIsLeftOutOfTheCountAndTheList() {
        val report = report(Outcome.UNFINISHED, thrown)
        assertEquals(0, report.decided)
        assertTrue(report.wins.isEmpty())
    }

    @Test
    fun aMateForThePlayerCountsAsWinning() {
        val mating = line(*(5 until 11).map { it to Eval.Mate(6) }.toTypedArray(), *from(11, Eval.Cp(-100)))
        assertEquals(1, report(Outcome.LOSS, mating).wins.size)
    }

    @Test
    fun blackIsReadFromBlacksSide() {
        // Black is winning (-4.0) after its plies 6, 8 and 10, and its ply 12 lets White back to +3.0
        val black = line(*(6 until 12).map { it to Eval.Cp(-400) }.toTypedArray(), *from(12, Eval.Cp(300)))
        val missed = report(Outcome.LOSS, black, PieceColor.BLACK).wins.single()
        assertEquals(12, missed.turningPly, "ply 12 is Black's move that lets White back from -4.0 to +3.0")
        assertEquals("+4.0", missed.peak.formatShortFor(PieceColor.BLACK))
        assertEquals("-3.0", missed.after.formatShortFor(PieceColor.BLACK))
    }

    @Test
    fun aMateInThreeThatWasThrownAwayIsAMissedMateAndAMateInEightIsNot() {
        // White to move at position 6 (before ply 7): mate in 3, and the move played leaves +1.0
        val threw = line(6 to Eval.Mate(3), 7 to Eval.Cp(100))
        val mate = report(Outcome.WIN, threw).mates.single()
        assertEquals(7, mate.ply)
        assertEquals(3, mate.mateIn)
        assertEquals(game.plies[6].san, mate.san)
        assertTrue(report(Outcome.WIN, line(6 to Eval.Mate(8), 7 to Eval.Cp(100))).mates.isEmpty(), "too long to call missed")
        assertEquals(1, report(Outcome.WIN, line(6 to Eval.Mate(5), 7 to Eval.Cp(100))).mates.size, "five is the longest")
    }

    @Test
    fun aMateThatIsKeptOrDeliveredIsNotMissedAndTheOpponentsMateIsNotTheirs() {
        assertTrue(report(Outcome.WIN, line(6 to Eval.Mate(3), 7 to Eval.Mate(2))).mates.isEmpty(), "still mating")
        assertTrue(report(Outcome.WIN, line(6 to Eval.Mate(1), 7 to Eval.Checkmate(PieceColor.WHITE))).mates.isEmpty(), "mate delivered")
        assertTrue(report(Outcome.WIN, line(6 to Eval.Mate(-3), 7 to Eval.Cp(100))).mates.isEmpty(), "Black had the mate, not White")
        // Black missing its own mate (position 7, before Black's ply 8)
        val black = report(Outcome.WIN, line(7 to Eval.Mate(-2), 8 to Eval.Cp(-100)), PieceColor.BLACK).mates.single()
        assertEquals(8, black.ply)
        assertEquals(2, black.mateIn)
    }

    @Test
    fun gamesAreListedNewestFirst() {
        val a = played(Outcome.LOSS, id = 1, date = "2025.01.01")
        val b = played(Outcome.LOSS, id = 2, date = "2025.03.01")
        val report = MissedWins.of(listOf(a, b), mapOf(1L to thrown, 2L to thrown))
        assertEquals(listOf(2L, 1L), report.wins.map { it.game.gameId })
        assertNull(report.wins.firstOrNull { it.game.gameId == 3L })
    }

    @Test
    fun aGameWithoutStoredEvalsIsSkipped() {
        val report = MissedWins.of(listOf(played(Outcome.LOSS)), emptyMap())
        assertEquals(1, report.decided)
        assertTrue(report.wins.isEmpty())
    }

    // The query behind it: the main lines of saved games, read without anything else.
    private val db = JdbcSqlDb.open()
    private val store = GameStore(db)

    @AfterTest
    fun close() = db.close()

    @Test
    fun theStoreGivesTheMainLineOfEachRequestedGameWithItsMovesAndEvals() {
        val ids = mutableMapOf<String, Long>()
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            val g = Fixtures.game(name)
            val evals = List(g.positionCount) { Eval.Cp(it * 3) }
            ids[name] = store.save(NewGame(Fixtures.text(name), g, evals, "t"))
        }
        val read = store.mainLines(ids.values.toList())
        assertEquals(ids.size, read.size)
        for ((name, id) in ids) {
            val g = Fixtures.game(name)
            assertEquals(MainLine(g.plies.map { it.san }, List(g.positionCount) { Eval.Cp(it * 3) }, g.whiteMovesFirst), read[id], name)
        }
        assertEquals(setOf(ids.values.first()), store.mainLines(listOf(ids.values.first())).keys)
        assertEquals(emptyMap(), store.mainLines(emptyList()))
        assertEquals(emptyMap(), store.mainLines(listOf(99_999L)))
    }
}
