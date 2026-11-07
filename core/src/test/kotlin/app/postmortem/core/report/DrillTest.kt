package app.postmortem.core.report

import app.postmortem.core.analysis.Eval
import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.store.DrillKey
import app.postmortem.core.store.DrillProgress
import app.postmortem.core.store.MainLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The queue on a real game (50 plies) with made-up evals, and the verdict on answers. */
class DrillTest {
    private val game = Fixtures.game("lichess-mate-castle")
    private val positions = game.positionCount

    private fun line(vararg changes: Pair<Int, Eval>): MainLine {
        val evals = MutableList<Eval>(positions) { Eval.Cp(0) }
        for ((position, eval) in changes) evals[position] = eval
        return MainLine(game.plies.map { it.san }, evals, whiteMovesFirst = true)
    }

    private fun played(
        id: Long,
        date: String = "2025.01.0$id",
        outcome: Outcome = Outcome.WIN,
        color: PieceColor = PieceColor.WHITE,
    ) = PlayerGame(id, color, "Opponent", outcome, null, 80.0, date)

    // White's plies 3 (a blunder: 0 -> -300) and 9 (a mistake: 0 -> -130) in the same game; Black's moves never count for White.
    private val twoMistakes =
        line(
            *(3 until 9).map { it to Eval.Cp(-300) }.toTypedArray(),
            *(9 until positions).map { it to Eval.Cp(-430) }.toTypedArray(),
        )

    @Test
    fun theQueueHoldsThePositionsBeforeTheMistakesAndBlundersOfThePlayersOwnMovesOnly() {
        val queue = Drill.queue(listOf(played(1, outcome = Outcome.LOSS)), mapOf(1L to twoMistakes), emptyMap())
        // ply 3 (position 2) is a blunder; ply 9 (position 8): -300 -> -430 is a drop of about 0.1: an inaccuracy, so left out
        assertEquals(listOf(2), queue.items.map { it.position })
        assertEquals(DrillKind.BLUNDER, queue.items.single().kind)
        assertEquals(3, queue.items.single().ply)
        assertEquals(game.plies[2].san, queue.items.single().san)
        assertEquals(Eval.Cp(0), queue.items.single().before)
        assertEquals(Eval.Cp(-300), queue.items.single().after)
        assertEquals(0, queue.solved)
    }

    @Test
    fun theOpponentsMistakesAreNotThePlayersToLearnFrom() {
        // the same line seen from Black: White's blunder at ply 3 is not Black's
        val queue = Drill.queue(listOf(played(1, color = PieceColor.BLACK)), mapOf(1L to twoMistakes), emptyMap())
        assertTrue(queue.items.none { it.ply % 2 == 1 })
    }

    @Test
    fun missedWinsAndMissedMatesComeFirstThenBlundersThenMistakesNewestGameFirst() {
        val blunder = line(*(3 until positions).map { it to Eval.Cp(-300) }.toTypedArray())
        val mistake = line(*(3 until positions).map { it to Eval.Cp(-140) }.toTypedArray())
        // White winning at +4.0 after plies 5, 7 (held), thrown away by ply 9: a missed win and a blunder at once
        val missedWin =
            line(*(5 until 9).map { it to Eval.Cp(400) }.toTypedArray(), *(9 until positions).map { it to Eval.Cp(-200) }.toTypedArray())
        val missedMate = line(6 to Eval.Mate(3), 7 to Eval.Cp(50), *(8 until positions).map { it to Eval.Cp(50) }.toTypedArray())
        val games = listOf(played(1), played(2), played(3, outcome = Outcome.LOSS), played(4), played(5))
        val lines = mapOf(1L to mistake, 2L to blunder, 3L to missedWin, 4L to missedMate, 5L to blunder)
        val queue = Drill.queue(games, lines, emptyMap())
        assertEquals(
            listOf(
                3L to DrillKind.MISSED_WIN,
                4L to DrillKind.MISSED_MATE,
                5L to DrillKind.BLUNDER,
                2L to DrillKind.BLUNDER,
                1L to DrillKind.MISTAKE,
            ),
            queue.items.map { it.game.gameId to it.kind },
            "newest game first within a kind (game 5 is newer than 2)",
        )
        assertEquals(1, queue.items.count { it.game.gameId == 3L }, "the turning move is one position, not two")
    }

    @Test
    fun solvedOnesLeaveTheQueueAndAreCountedAndTriedOnesComeBackAfterTheFreshOnesTheLongestAgoFirst() {
        val blunder = line(*(3 until positions).map { it to Eval.Cp(-300) }.toTypedArray())
        val mistakes =
            line(
                *(3 until positions).map { it to Eval.Cp(-300) }.toTypedArray(),
                *(5 until positions).map { it to Eval.Cp(-600) }.toTypedArray(),
            )
        val games = listOf(played(1), played(2), played(3))
        val lines = mapOf(1L to blunder, 2L to blunder, 3L to mistakes)
        val progress =
            mapOf(
                DrillKey(1, 2) to DrillProgress(solved = true, attempts = 1, lastTried = 10),
                DrillKey(2, 2) to DrillProgress(solved = false, attempts = 2, lastTried = 30),
                DrillKey(3, 2) to DrillProgress(solved = false, attempts = 1, lastTried = 20),
            )
        val queue = Drill.queue(games, lines, progress)
        assertEquals(1, queue.solved)
        // the fresh ones first (game 3's second blunder), then the tried ones: game 3 (20) before game 2 (30)
        assertEquals(
            listOf(3L to 4, 3L to 2, 2L to 2),
            queue.items.map { it.game.gameId to it.position }.let { all ->
                all.filter {
                    it.second ==
                        4
                } +
                    all.filter { it.second == 2 }
            },
        )
        assertEquals(listOf(3L, 2L), queue.items.filter { it.position == 2 }.map { it.game.gameId })
        assertTrue(queue.items.none { it.game.gameId == 1L })
    }

    @Test
    fun aGameWithoutStoredEvalsAddsNothing() {
        assertEquals(DrillQueue(emptyList(), 0), Drill.queue(listOf(played(1)), emptyMap(), emptyMap()))
    }

    @Test
    fun theBestMoveIsRightAndAMoveNearlyAsGoodIsGoodAndAWorseOneIsWrong() {
        // +1.00 is 0.182 winning chances; +0.50 is a drop of 0.090 and +0.35 a drop of 0.118, either side of the 0.1 limit
        val before = Eval.Cp(100)
        assertEquals(
            Drill.Verdict.RIGHT,
            Drill.verdict("e2e4", "e2e4", before, Eval.Cp(-900), PieceColor.WHITE),
            "the best move needs no evaluation",
        )
        assertEquals(Drill.Verdict.GOOD, Drill.verdict("e2e4", "d2d4", before, Eval.Cp(50), PieceColor.WHITE))
        assertEquals(Drill.Verdict.WRONG, Drill.verdict("e2e4", "d2d4", before, Eval.Cp(35), PieceColor.WHITE))
        assertEquals(Drill.Verdict.WRONG, Drill.verdict("e2e4", "d2d4", before, Eval.Cp(-100), PieceColor.WHITE))
    }

    @Test
    fun aMoveThatKeepsOrImprovesTheEvalIsGoodAndBlacksDropIsReadFromBlacksSide() {
        assertEquals(Drill.Verdict.GOOD, Drill.verdict("e7e5", "c7c5", Eval.Cp(-100), Eval.Cp(-100), PieceColor.BLACK))
        assertEquals(
            Drill.Verdict.GOOD,
            Drill.verdict("e7e5", "c7c5", Eval.Cp(-100), Eval.Cp(-300), PieceColor.BLACK),
            "Black's eval got better",
        )
        assertEquals(Drill.Verdict.WRONG, Drill.verdict("e7e5", "c7c5", Eval.Cp(-100), Eval.Cp(300), PieceColor.BLACK))
        assertEquals(Drill.Verdict.WRONG, Drill.verdict("g1f3", "a2a3", Eval.Mate(3), Eval.Cp(150), PieceColor.WHITE), "a mate thrown away")
        assertEquals(
            Drill.Verdict.GOOD,
            Drill.verdict("g1f3", "d1h5", Eval.Mate(1), Eval.Checkmate(PieceColor.WHITE), PieceColor.WHITE),
            "another mate",
        )
    }

    @Test
    fun onlyAMoveOtherThanTheBestOneNeedsTheEngine() {
        assertTrue(!Drill.needsEval("e2e4", "e2e4"))
        assertTrue(Drill.needsEval("e2e4", "d2d4"))
        assertTrue(Drill.needsEval(null, "d2d4"))
    }

    @Test
    fun theLimitIsTheSameNumberAsTheInaccuracyMark() {
        assertEquals(0.1, Drill.GOOD_ENOUGH_DROP)
    }
}
