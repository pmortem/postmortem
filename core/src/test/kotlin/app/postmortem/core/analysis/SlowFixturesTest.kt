package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.uci.EngineBinary
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The slow pass, run by lab-check and CI (-Ppostmortem.slow=true): every fixture game through the real engine. */
class SlowFixturesTest {
    private val config = AnalysisConfig(moveTimeMs = 60, threads = 2, hashMb = 32)
    private val lichessEval = Regex("""\[%eval (#?-?\d+(?:\.\d+)?)]""")
    private val decisiveCentipawns = 300
    private val quietCentipawns = 100
    private val quietToleranceCentipawns = 120

    private fun evalsOf(name: String): List<Eval> = runBlocking {
        Analyser(config, EngineBinary.requireSlow()).analyse(Fixtures.game(name)).last().evals
    }

    @Test
    fun everyFixtureGameIsAnalysedToTheEnd() {
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            val game = Fixtures.game(name)
            val evals = evalsOf(name)
            assertEquals(game.positionCount, evals.size, name)
            val last = game.plies.last()
            if (last.san.endsWith("#")) {
                val winner = if (game.plies.size % 2 == 1) PieceColor.WHITE else PieceColor.BLACK
                assertEquals(Eval.Checkmate(winner), evals.last(), name)
            }
        }
    }

    @Test
    fun theSignsAgreeWithLichessOwnEvaluations() {
        // lichess-clocks-evals-mate carries Lichess's engine evals after every ply, from White's point of view.
        val name = "lichess-clocks-evals-mate"
        val theirs = lichessEval.findAll(Fixtures.text(name)).map { it.groupValues[1] }.toList()
        val ours = evalsOf(name)
        assertEquals(Fixtures.game(name).plies.size, theirs.size)
        var compared = 0
        theirs.forEachIndexed { index, raw ->
            val mine = ours[index + 1]
            if (raw.startsWith("#")) {
                val moves = raw.drop(1).toInt()
                assertTrue(mine is Eval.Mate && (mine.moves > 0) == (moves > 0), "ply ${index + 1}: lichess $raw, ours $mine")
                compared++
            } else if (abs(raw.toDouble() * 100) >= decisiveCentipawns) {
                val white = raw.toDouble() > 0
                val agrees = when (mine) {
                    is Eval.Cp -> (mine.centipawns > 0) == white && abs(mine.centipawns) >= decisiveCentipawns / 2
                    is Eval.Mate -> (mine.moves > 0) == white
                    is Eval.Checkmate -> (mine.winner == PieceColor.WHITE) == white
                }
                assertTrue(agrees, "ply ${index + 1}: lichess $raw, ours $mine")
                compared++
            }
        }
        assertTrue(compared >= 4, "only $compared decisive plies to compare")

        // Quiet plies: the engines may differ, but not by much.
        val quiet = theirs.withIndex().filter { (_, raw) -> !raw.startsWith("#") && abs(raw.toDouble() * 100) < quietCentipawns }
        val close = quiet.count { (index, raw) ->
            val mine = ours[index + 1] as? Eval.Cp
            mine != null && abs(mine.centipawns - raw.toDouble() * 100) <= quietToleranceCentipawns
        }
        assertTrue(close >= quiet.size * 0.9, "only $close of ${quiet.size} quiet plies are within $quietToleranceCentipawns cp")
    }
}
