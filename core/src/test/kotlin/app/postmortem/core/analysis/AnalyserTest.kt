package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.uci.EngineBinary
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnalyserTest {
    private val quick = AnalysisConfig(moveTimeMs = 100, threads = 2, hashMb = 16)

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
        assertEquals(progress.map { it.done }, progress.map { it.evals.size })
    }

    @Test
    fun stoppingTheFlowEarlyKillsTheEngine() =
        runBlocking {
            val factory = EngineBinary.require()
            var process: Process? = null
            val progress =
                Analyser(quick) { factory().also { process = it } }
                    .analyse(Fixtures.game("scholars-mate"))
                    .take(3)
                    .toList()
            assertEquals(listOf(0, 1, 2), progress.map { it.done })
            assertTrue(process!!.waitFor(5, TimeUnit.SECONDS), "the engine process is still running")
        }
}
