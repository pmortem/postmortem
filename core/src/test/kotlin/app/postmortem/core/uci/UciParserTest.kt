package app.postmortem.core.uci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lines captured from the real Stockfish sf_19 (src/test/resources/uci). */
class UciParserTest {
    private fun capture(name: String) =
        checkNotNull(javaClass.getResource("/uci/$name.txt")) { "no capture $name" }.readText().lines().filter { it.isNotBlank() }

    private fun lastScore(name: String) = capture(name).mapNotNull(UciParser::score).last()

    private fun bestMove(name: String) = capture(name).firstNotNullOf(UciParser::bestMove)

    @Test
    fun mateInOneForTheSideToMove() {
        assertEquals(Score.Mate(1), lastScore("white-mates-in-1"))
        assertEquals(BestMove.Move("h5f7"), bestMove("white-mates-in-1"))
        assertEquals(Score.Mate(1), lastScore("black-mates-in-1"))
        assertEquals(BestMove.Move("d8h4"), bestMove("black-mates-in-1"))
    }

    @Test
    fun aMatedPositionScoresMateZeroAndHasNoBestMove() {
        assertEquals(Score.Mate(0), lastScore("checkmated"))
        assertEquals(BestMove.None, bestMove("checkmated"))
    }

    @Test
    fun aStalematedPositionScoresZeroAndHasNoBestMove() {
        assertEquals(Score.Cp(0), lastScore("stalemate"))
        assertEquals(BestMove.None, bestMove("stalemate"))
    }

    @Test
    fun boundsAreIgnored() {
        val lines = capture("with-bounds")
        val bounded = lines.filter { " lowerbound" in it || " upperbound" in it }
        assertTrue(bounded.isNotEmpty())
        for (line in bounded) assertNull(UciParser.score(line), line)
        assertNotNull(lines.mapNotNull(UciParser::score).lastOrNull())
    }

    @Test
    fun theStartPositionIsAboutEqual() {
        val score = lastScore("startpos-short") as Score.Cp
        assertTrue(score.centipawns in -80..120, "start position scored ${score.centipawns}")
    }

    @Test
    fun otherLinesGiveNothing() {
        for (line in capture("startpos-short").filter { it.startsWith("info string") }) assertNull(UciParser.score(line), line)
        assertNull(UciParser.score("readyok"))
        assertNull(UciParser.score("bestmove e2e4"))
        assertNull(UciParser.bestMove("info depth 1 score cp 10"))
    }

    @Test
    fun aPonderMoveIsLeftOut() {
        assertEquals(BestMove.Move("e2e4"), UciParser.bestMove("bestmove e2e4 ponder e7e5"))
    }
}
