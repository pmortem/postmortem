package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.uci.EngineBinary
import app.postmortem.core.uci.ProcessFactory
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PositionEvaluatorTest {
    private val quick = AnalysisConfig(nodes = 20_000, workers = 1, hashMb = 16)
    private val noEngine: ProcessFactory = { error("no engine process should start") }

    @Test
    fun evaluatingAFenGivesExactlyTheEvalTheAnalysisGaveForThatPosition() =
        runBlocking {
            val game = Fixtures.game("lichess-short-resign")
            val analysed = Analyser(quick, EngineBinary.require()).analyse(game).last().evals
            PositionEvaluator(quick, EngineBinary.require()).use { evaluator ->
                // asked out of order, one process, each position on its own
                for (position in listOf(9, 0, 4, 11, 2)) {
                    assertEquals(analysed[position], evaluator.evaluate(game.fenAt(position)), "position $position")
                }
            }
        }

    @Test
    fun aPositionOfTheGameIsKnownWithoutSearchEvenWithOtherMoveCounters() =
        runBlocking {
            val game = Fixtures.game("scholars-mate")
            val evals = Analyser(quick, EngineBinary.require()).analyse(game).last().evals
            PositionEvaluator(quick, noEngine).use { evaluator ->
                assertNull(evaluator.cached(game.fenAt(3)))
                evaluator.seed(game, evals)
                val fen = game.fenAt(3)
                assertEquals(evals[3], evaluator.cached(fen))
                val transposed = fen.substringBeforeLast(' ').substringBeforeLast(' ') + " 7 31"
                assertEquals(evals[3], evaluator.evaluate(transposed), "a transposition at other counters needs no search")
            }
        }

    @Test
    fun theBestMoveOfAPositionIsFoundOnceAndAnEndedPositionHasNone() =
        runBlocking {
            val game = Fixtures.game("scholars-mate")
            var started = 0
            val counting: ProcessFactory = {
                started++
                EngineBinary.require().invoke()
            }
            PositionEvaluator(quick, counting).use { evaluator ->
                assertEquals("h5f7", evaluator.bestMove(game.fenAt(6)), "mate in one")
                assertEquals("h5f7", evaluator.bestMove(game.fenAt(6)))
                assertNull(evaluator.bestMove(game.fenAt(7)), "checkmate on the board has no move")
                assertEquals(1, started)
                assertEquals(Eval.Mate(1), evaluator.cached(game.fenAt(6)), "the eval of the same search is kept too")
            }
        }

    @Test
    fun anEvalFoundOnceIsRememberedForTheNextAsk() =
        runBlocking {
            val game = Fixtures.game("scholars-mate")
            var started = 0
            val counting: ProcessFactory = {
                started++
                EngineBinary.require().invoke()
            }
            PositionEvaluator(quick, counting).use { evaluator ->
                val first = evaluator.evaluate(game.fenAt(2))
                val second = evaluator.evaluate(game.fenAt(2))
                evaluator.evaluate(game.fenAt(4))
                assertEquals(first, second)
                assertEquals(1, started, "one engine process for all positions")
            }
        }

    @Test
    fun checkmateAndStalemateAreScoredWithoutStartingTheEngine() =
        runBlocking {
            val game = Fixtures.game("scholars-mate")
            PositionEvaluator(quick, noEngine).use { evaluator ->
                assertEquals(Eval.Checkmate(PieceColor.WHITE), evaluator.evaluate(game.fenAt(game.plies.size)))
                assertEquals(Eval.Cp(0), evaluator.evaluate("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"))
            }
        }

    @Test
    fun theKeyIgnoresOnlyTheMoveCounters() {
        assertEquals(
            positionKey("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"),
            positionKey("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 12 40"),
        )
        assertEquals(
            false,
            positionKey("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1") ==
                positionKey("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e3 0 1"),
        )
    }
}
