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

    @Test
    fun aMultiPvLineGivesItsRankDepthScoreAndMoves() {
        val info =
            UciParser.pv(
                "info depth 18 seldepth 25 multipv 2 score cp -34 nodes 123456 nps 410000 hashfull 12 tbhits 0 time 301 pv e7e5 g1f3 b8c6",
            )
        assertEquals(PvInfo(2, 18, Score.Cp(-34), 123456, 301, listOf("e7e5", "g1f3", "b8c6")), info)
        assertEquals(410_152L, info?.nodesPerSecond)
    }

    @Test
    fun aMateLineAndALineWithoutMultiPvAreLineOne() {
        assertEquals(Score.Mate(3), UciParser.pv("info depth 9 score mate 3 nodes 10 time 5 pv h5f7")?.score)
        assertEquals(1, UciParser.pv("info depth 9 score mate 3 nodes 10 time 5 pv h5f7")?.multiPv)
    }

    @Test
    fun boundsAndLinesWithoutMovesAreNotPvLines() {
        assertNull(UciParser.pv("info depth 9 multipv 1 score cp 20 lowerbound nodes 10 time 5 pv e2e4"))
        assertNull(UciParser.pv("info depth 9 multipv 1 score cp 20 nodes 10 time 5"))
        assertNull(UciParser.pv("info string NNUE evaluation using nn.nnue"))
        assertNull(UciParser.pv("info depth 9 currmove e2e4 currmovenumber 1"))
        assertNull(UciParser.pv("bestmove e2e4"))
    }

    @Test
    fun aRealMultiPvSearchGivesLinesOneToThreeAtEveryDepthExceptTheBoundedOne() {
        val infos = capture("multipv-3").mapNotNull { UciParser.pv(it) }
        // 11 info lines, one of them an upper bound (line 2 of the last depth), which is not a score to show
        assertEquals(10, infos.size)
        assertEquals(listOf(1, 2, 3), infos.take(3).map { it.multiPv }.sorted(), "the engine reports its lines in any order")
        assertEquals(listOf(12, 12, 13, 13), infos.take(4).map { it.depth })
        assertTrue(infos.all { it.pv.size >= 2 && it.nodes > 0 })
        assertEquals(Score.Cp(23), infos.last { it.multiPv == 1 }.score)
    }
}
