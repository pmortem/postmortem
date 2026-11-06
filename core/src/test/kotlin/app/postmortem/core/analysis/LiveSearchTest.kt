package app.postmortem.core.analysis

import app.postmortem.core.uci.EngineBinary
import app.postmortem.core.uci.Score
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveSearchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val otherFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    private val mateIn1 = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
    private val mated = "r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4"
    private val config = LiveConfig(maxSearchMs = 2_000, updateIntervalMs = 100_000)

    private fun fake(
        waitForStop: Boolean = false,
        infoLines: List<String>? = null,
    ) = FakeLiveEngine(tmp.newFile(), waitForStop, infoLines)

    @Test
    fun linesArriveByRankKeepingTheDeepestEvenWhenTheyComeOutOfOrder() =
        runBlocking {
            val engine = fake()
            LiveSearch(config, engine.factory).use { live ->
                val last = live.lines(startFen).last()
                assertTrue(last.finished)
                assertEquals(listOf(1, 2, 3), last.lines.map { it.rank })
                // line 1: the depth 6 report wins over the depth 5 one; line 2: the later depth 4 report is ignored
                assertEquals(listOf("g1f3", "d2d4", "c2c4"), last.lines.map { it.move })
                assertEquals(listOf<Score>(Score.Cp(50), Score.Cp(30), Score.Mate(-2)), last.lines.map { it.score })
                assertEquals(6, last.depth)
            }
        }

    @Test
    fun aSearchStoppedMidDepthNeverShowsLinesOfDifferentDepthsTogether() =
        runBlocking {
            // Depth 7 changed the best move to d2d4, which was line 2 at depth 6; lines 2 and 3 of depth 7 had not been printed yet.
            val engine =
                fake(
                    infoLines =
                        listOf(
                            "info depth 6 multipv 1 score cp 50 nodes 1 time 1 pv g1f3",
                            "info depth 6 multipv 2 score cp 30 nodes 1 time 1 pv d2d4",
                            "info depth 6 multipv 3 score cp 20 nodes 1 time 1 pv c2c4",
                            "info depth 7 multipv 1 score cp 55 nodes 1 time 1 pv d2d4",
                        ),
                )
            LiveSearch(config, engine.factory).use { live ->
                val last = live.lines(startFen).last()
                assertEquals(listOf("g1f3", "d2d4", "c2c4"), last.lines.map { it.move })
                assertEquals(6, last.depth)
                assertEquals(
                    3,
                    last.lines
                        .map { it.move }
                        .toSet()
                        .size,
                )
            }
        }

    @Test
    fun aDepthWhereTwoRanksShowTheSameMoveIsSkippedForTheDeepestDepthWithDistinctMoves() =
        runBlocking {
            // As captured from the real engine at the time cap: ranks 2 and 3 printed as the same line at the deepest depth.
            val engine =
                fake(
                    infoLines =
                        listOf(
                            "info depth 13 multipv 1 score cp 30 nodes 1 time 1 pv e2e4 c7c5",
                            "info depth 13 multipv 2 score cp 24 nodes 1 time 1 pv g1f3 d7d5",
                            "info depth 13 multipv 3 score cp 20 nodes 1 time 1 pv d2d4 d7d5",
                            "info depth 14 multipv 1 score cp 32 nodes 1 time 1 pv e2e4 c7c5",
                            "info depth 14 multipv 2 score cp 25 nodes 1 time 1 pv g1f3 d7d5",
                            "info depth 14 multipv 3 score cp 25 nodes 1 time 1 pv g1f3 d7d5",
                        ),
                )
            LiveSearch(config, engine.factory).use { live ->
                val last = live.lines(startFen).last()
                assertEquals(listOf("e2e4", "g1f3", "d2d4"), last.lines.map { it.move })
                assertEquals(13, last.depth)
            }
        }

    @Test
    fun withoutAnyDistinctDepthTheFirstLineOfEachMoveIsKept() =
        runBlocking {
            val engine =
                fake(
                    infoLines =
                        listOf(
                            "info depth 14 multipv 1 score cp 32 nodes 1 time 1 pv e2e4 c7c5",
                            "info depth 14 multipv 2 score cp 25 nodes 1 time 1 pv g1f3 d7d5",
                            "info depth 14 multipv 3 score cp 25 nodes 1 time 1 pv g1f3 d7d5",
                        ),
                )
            LiveSearch(config, engine.factory).use { live ->
                val last = live.lines(startFen).last()
                assertEquals(listOf("e2e4", "g1f3"), last.lines.map { it.move })
                assertEquals(listOf(1, 2), last.lines.map { it.rank })
            }
        }

    @Test
    fun theEngineIsSetUpForThreeLinesOnTwoThreadsWith64MbOfHash() =
        runBlocking {
            val engine = fake()
            LiveSearch(LiveConfig(), engine.factory).use { it.lines(startFen).last() }
            val commands = engine.commands
            assertTrue("setoption name Hash value 64" in commands, commands.toString())
            assertTrue("setoption name Threads value 2" in commands)
            assertTrue("setoption name MultiPV value 3" in commands)
            assertTrue("go movetime 20000" in commands)
        }

    @Test
    fun aRevisitedPositionIsAnsweredFromTheCacheWithoutASearch() =
        runBlocking {
            val engine = fake()
            LiveSearch(config, engine.factory).use { live ->
                val first = live.lines(startFen).last()
                assertNull(live.cached(otherFen))
                assertEquals(first.lines, live.cached(startFen))
                val again = live.lines(startFen).toList()
                assertEquals(listOf(first.lines), again.map { it.lines })
                assertTrue(again.single().finished)
                assertEquals(1, engine.commands.count { it.startsWith("go") })
            }
        }

    @Test
    fun updatesWhileSearchingAreThrottledAndTheLastOneIsFinished() =
        runBlocking {
            val engine = fake()
            LiveSearch(config, engine.factory).use { live ->
                val updates = live.lines(startFen).toList()
                // five info lines arrive at once, but with a long interval only the first one and the final snapshot get through
                assertEquals(listOf(false, true), updates.map { it.finished })
            }
        }

    @Test
    fun cancellingTheCollectionStopsTheSearchAndTheEngineSearchesTheNextPosition() =
        runBlocking {
            val engine = fake(waitForStop = true)
            LiveSearch(config, engine.factory).use { live ->
                val first = async(start = CoroutineStart.UNDISPATCHED) { live.lines(startFen).last() }
                while ("go movetime 2000" !in engine.commands) delay(20)
                first.cancelAndJoin()
                assertNull(live.cached(startFen), "a search that was stopped is not cached")
                val second = launch { live.lines(otherFen).last() }
                while (engine.commands.count { it.startsWith("go") } < 2) delay(20)
                delay(100)
                second.cancelAndJoin()
                val commands = engine.commands.filter { it.startsWith("go") || it == "stop" || it.startsWith("position") }
                assertEquals(
                    listOf("position fen $startFen", "go movetime 2000", "stop", "position fen $otherFen", "go movetime 2000", "stop"),
                    commands,
                )
                assertEquals(1, engine.started, "one engine process for all positions")
            }
        }

    @Test
    fun aPositionWithoutMovesNeedsNoEngine() =
        runBlocking {
            val engine = fake()
            LiveSearch(config, engine.factory).use { live ->
                val update = live.lines(mated).last()
                assertTrue(update.finished)
                assertTrue(update.lines.isEmpty())
                assertEquals(0, engine.started)
            }
        }

    @Test
    fun closingEndsTheProcess() =
        runBlocking {
            var process: Process? = null
            val engine = fake()
            val live = LiveSearch(config, processFactory = { engine.factory().also { process = it } })
            live.lines(startFen).last()
            live.close()
            assertTrue(process!!.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
        }

    @Test
    fun theRealEngineGivesThreeDifferentBestMovesAtTheStart() =
        runBlocking {
            LiveSearch(LiveConfig(maxSearchMs = 1_000), EngineBinary.require()).use { live ->
                val last = live.lines(startFen).last()
                assertEquals(3, last.lines.size, last.toString())
                assertEquals(
                    3,
                    last.lines
                        .map { it.move }
                        .toSet()
                        .size,
                    last.toString(),
                )
                assertTrue(last.lines.all { Regex("[a-h][1-8][a-h][1-8]").matches(it.move) })
                // the lines are not all at the same depth when the time is up, so their order by score is not asserted
                assertTrue(last.lines.all { (it.score as Score.Cp).centipawns in -200..200 }, last.lines.toString())
            }
        }

    @Test
    fun theRealEngineSeesTheMateInOneAsTheBestLine() =
        runBlocking {
            LiveSearch(LiveConfig(maxSearchMs = 1_000), EngineBinary.require()).use { live ->
                val best =
                    live
                        .lines(mateIn1)
                        .last()
                        .lines
                        .first()
                assertEquals("h5f7", best.move)
                assertEquals("#1", best.scoreText())
            }
        }

    @Test
    fun scoresAreTextFromTheSideToMovesPointOfView() {
        fun text(score: Score) = LiveLine(1, "e2e4", score, 10, listOf("e2e4")).scoreText()
        assertEquals("+0.8", text(Score.Cp(80)))
        assertEquals("−1.3", text(Score.Cp(-130)))
        assertEquals("0.0", text(Score.Cp(0)))
        assertEquals("0.0", text(Score.Cp(-3)))
        assertEquals("#3", text(Score.Mate(3)))
        assertEquals("#−3", text(Score.Mate(-3)))
    }
}
