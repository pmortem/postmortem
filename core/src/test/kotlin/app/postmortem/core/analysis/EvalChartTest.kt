package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvalChartTest {
    private val tolerance = 1e-9

    @Test
    fun zeroCentipawnsIsTheMiddle() {
        assertEquals(0.0, Eval.Cp(0).winningChances(), tolerance)
    }

    @Test
    fun theCurveIsSymmetricAroundZero() {
        for (cp in listOf(1, 35, 120, 300, 900)) {
            assertEquals(-Eval.Cp(cp).winningChances(), Eval.Cp(-cp).winningChances(), tolerance, "cp $cp")
        }
    }

    @Test
    fun largeEvalsStayInsideTheRange() {
        for (cp in listOf(1000, -1000, 5000, -5000, Int.MAX_VALUE / 2, Int.MIN_VALUE / 2)) {
            val y = Eval.Cp(cp).winningChances()
            assertTrue(y in -1.0..1.0, "cp $cp gave $y")
        }
        assertTrue(Eval.Cp(1000).winningChances() > 0.95)
    }

    @Test
    fun matesAndCheckmatesAreTheExtremes() {
        assertEquals(1.0, Eval.Mate(3).winningChances(), tolerance)
        assertEquals(-1.0, Eval.Mate(-1).winningChances(), tolerance)
        assertEquals(1.0, Eval.Checkmate(PieceColor.WHITE).winningChances(), tolerance)
        assertEquals(-1.0, Eval.Checkmate(PieceColor.BLACK).winningChances(), tolerance)
    }

    @Test
    fun theLichessCurveHasItsKnownValueAtOnePawn() {
        // 2 / (1 + exp(-0.00368208 * 100)) - 1 = 0.18 (Lichess shows about 59% winning chances for +1.00).
        assertEquals(0.1820, Eval.Cp(100).winningChances(), 0.001)
    }

    @Test
    fun aRealGamesEvalsStayInRangeAndKeepTheirSign() {
        // lichess-clocks-evals-mate carries Lichess's own evaluation after every ply, from White's point of view.
        val raw =
            Regex(
                """\[%eval (#?-?\d+(?:\.\d+)?)]""",
            ).findAll(Fixtures.text("lichess-clocks-evals-mate")).map { it.groupValues[1] }.toList()
        assertTrue(raw.size > 20, "expected a long real game, got ${raw.size} evals")
        val evals =
            raw.map {
                if (it.startsWith("#")) Eval.Mate(it.drop(1).toInt()) else Eval.Cp((it.toDouble() * 100).toInt())
            }
        val chances = evals.map { it.winningChances() }
        assertTrue(chances.all { it in -1.0..1.0 })
        evals.zip(chances).forEach { (eval, y) ->
            if (eval is Eval.Cp && eval.centipawns != 0) assertEquals(eval.centipawns > 0, y > 0, "$eval gave $y")
        }
        assertTrue(chances.any { abs(it) > 0.5 }, "the game is decisive, so the chart leaves the middle")
    }

    @Test
    fun touchPositionsMapToTheNearestPosition() {
        // 5 positions across 400 px sit at x = 0, 100, 200, 300, 400.
        assertEquals(0, nearestPly(0f, 400f, 5))
        assertEquals(1, nearestPly(100f, 400f, 5))
        assertEquals(1, nearestPly(149f, 400f, 5))
        assertEquals(2, nearestPly(151f, 400f, 5))
        assertEquals(4, nearestPly(400f, 400f, 5))
    }

    @Test
    fun touchesOutsideTheChartClamp() {
        assertEquals(0, nearestPly(-50f, 400f, 5))
        assertEquals(4, nearestPly(900f, 400f, 5))
    }

    @Test
    fun emptyAndOneMoveGames() {
        assertEquals(0, nearestPly(10f, 400f, 0))
        assertEquals(0, nearestPly(10f, 400f, 1))
        assertEquals(0, nearestPly(0f, 400f, 2))
        assertEquals(1, nearestPly(400f, 400f, 2))
        assertEquals(0, nearestPly(10f, 0f, 5))
    }
}
