package app.postmortem.core.uci

import app.postmortem.core.analysis.AnalysisConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UciEngineTest {
    private val quick = AnalysisConfig(nodes = 20_000, hashMb = 16)
    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val mateIn1 = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"

    @Test
    fun handshakeSearchAndShutdown() =
        runBlocking {
            val factory = EngineBinary.require()
            var process: Process? = null
            val engine = UciEngine.start(quick) { factory().also { process = it } }
            assertTrue(engine.isAlive)
            val result = engine.search(startFen)
            val move = (result.bestMove as BestMove.Move).uci
            assertTrue(Regex("[a-h][1-8][a-h][1-8]").matches(move), move)
            val cp = (result.score as Score.Cp).centipawns
            assertTrue(cp in -80..120, "start position scored $cp")
            engine.close()
            assertFalse(process!!.isAlive)
        }

    @Test
    fun findsMateInOne() =
        runBlocking {
            UciEngine.start(quick, EngineBinary.require()).use { engine ->
                val result = engine.search(mateIn1)
                assertEquals(Score.Mate(1), result.score)
                assertEquals(BestMove.Move("h5f7"), result.bestMove)
            }
        }

    @Test
    fun aMatedPositionHasNoBestMove() =
        runBlocking {
            UciEngine.start(quick, EngineBinary.require()).use { engine ->
                val result = engine.search("r1bqkb1r/pppp1Qpp/2n2n2/4p3/2B1P3/8/PPPP1PPP/RNB1K1NR b KQkq - 0 4")
                assertEquals(BestMove.None, result.bestMove)
            }
        }

    @Test
    fun cancellingASearchLeavesNoProcessBehind() =
        runBlocking {
            val factory = EngineBinary.require()
            var process: Process? = null
            val engine = UciEngine.start(quick) { factory().also { process = it } }
            val search = async(start = CoroutineStart.UNDISPATCHED) { engine.search(startFen, nodes = 100_000_000_000L) }
            delay(300)
            search.cancelAndJoin()
            engine.close()
            assertTrue(process!!.waitFor(5, TimeUnit.SECONDS))
        }

    @Test
    fun anEngineThatNeverAnswersTimesOutAndIsKilled() {
        var process: Process? = null
        val config = quick.copy(handshakeTimeoutMs = 300)
        assertFailsWith<EngineException> {
            runBlocking { UciEngine.start(config) { ProcessBuilder("sleep", "60").start().also { process = it } } }
        }
        assertFalse(process!!.isAlive)
    }

    @Test
    fun anEngineThatExitsIsReportedNotWaitedFor() {
        assertFailsWith<EngineException> {
            runBlocking { UciEngine.start(quick) { ProcessBuilder("true").start() } }
        }
    }

    @Test
    fun anEngineThatCannotBeStartedIsReported() {
        assertFailsWith<EngineException> {
            runBlocking { UciEngine.start(quick) { ProcessBuilder("/no/such/engine").start() } }
        }
    }

    @Test
    fun theSameFixedNodeSearchGivesTheSameResultEveryTimeAndInAnyOrder() =
        runBlocking {
            val fens =
                listOf(
                    startFen,
                    mateIn1,
                    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1",
                    "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
                    "2r3k1/pp3ppp/8/3p4/3P4/2P2N2/PP3PPP/6K1 w - - 0 1",
                )
            val factory = EngineBinary.require()
            val config = AnalysisConfig(nodes = 200_000, hashMb = 16)

            suspend fun run(order: List<Int>): Map<Int, Pair<Score?, BestMove>> =
                UciEngine.start(config, factory).use { engine ->
                    order.associateWith { engine.search(fens[it]).let { r -> r.score to r.bestMove } }
                }
            val forward = run(fens.indices.toList())
            assertEquals(forward, run(fens.indices.toList()), "the same positions again")
            assertEquals(forward, run(fens.indices.toList().reversed()), "the positions in another order")
            assertEquals(forward, run(listOf(2, 0, 4, 1, 3)), "and a third order")
        }

    @Test
    fun aSearchReportsItsNodesAndTime() =
        runBlocking {
            UciEngine.start(quick, EngineBinary.require()).use { engine ->
                val result = engine.search(startFen)
                assertTrue(result.nodes >= 20_000, "searched ${result.nodes} nodes")
                assertTrue(result.nodesPerSecond!! > 0)
            }
        }
}
