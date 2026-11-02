package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.uci.EngineBinary
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalyserTest {
    private val quick = AnalysisConfig(nodes = 20_000, workers = 2, hashMb = 16)

    private fun analyse(name: String) =
        runBlocking {
            Analyser(quick, EngineBinary.require()).analyse(Fixtures.game(name)).toList()
        }

    @Test
    fun scholarsMateIsScoredEndToEnd() {
        val progress = analyse("scholars-mate")
        val game = Fixtures.game("scholars-mate")
        assertEquals(game.positionCount + 1, progress.size)
        assertEquals(0, progress.first().done)
        assertTrue(progress.last().finished)
        val evals = progress.last().evals
        assertEquals(game.positionCount, evals.size)
        // After 3...Nf6 White mates in one, and the last position is mate on the board.
        assertEquals(Eval.Mate(1), evals[6])
        assertEquals(Eval.Checkmate(PieceColor.WHITE), evals[7])
        assertIs<Eval.Cp>(evals[0])
    }

    @Test
    fun blackMatingShowsAsNegative() {
        val evals = analyse("fools-mate").last().evals
        assertEquals(Eval.Mate(-1), evals[4 - 1])
        assertEquals(Eval.Checkmate(PieceColor.BLACK), evals[4])
    }

    @Test
    fun progressCountsUpOneByOne() {
        val progress = analyse("fools-mate")
        assertEquals((0..5).toList(), progress.map { it.done })
        // The evals reported are the finished positions from 0 on, so never more than are done, and all of them at the end.
        assertTrue(progress.all { it.evals.size <= it.done })
        assertEquals(5, progress.last().evals.size)
    }

    @Test
    fun theSameGameGivesIdenticalEvalsWhateverTheNumberOfWorkers() =
        runBlocking {
            val game = Fixtures.game("lichess-short-resign")

            suspend fun run(workers: Int) = Analyser(quick.copy(workers = workers), EngineBinary.require()).analyse(game).last().evals
            val one = run(1)
            assertEquals(game.positionCount, one.size)
            assertEquals<List<Eval>>(one, run(1), "the same settings twice")
            assertEquals<List<Eval>>(one, run(3), "three engines at once")
            assertEquals(GameReport.of(one), GameReport.of(run(2)), "so the judgements are the same too")
        }

    @Test
    fun stoppingTheFlowEarlyKillsTheEngine() =
        runBlocking {
            val factory = EngineBinary.require()
            val processes = java.util.Collections.synchronizedList(mutableListOf<Process>())
            val progress =
                Analyser(quick, processFactory = { factory().also { processes += it } })
                    .analyse(Fixtures.game("scholars-mate"))
                    .take(3)
                    .toList()
            assertEquals(listOf(0, 1, 2), progress.map { it.done })
            assertTrue(processes.isNotEmpty() && processes.all { it.waitFor(5, TimeUnit.SECONDS) }, "an engine process is still running")
        }
}
