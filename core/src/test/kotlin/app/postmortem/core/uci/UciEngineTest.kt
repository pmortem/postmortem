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
    private val quick = AnalysisConfig(moveTimeMs = 100, threads = 1, hashMb = 16)
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
            val search = async(start = CoroutineStart.UNDISPATCHED) { engine.search(startFen, moveTimeMs = 60_000) }
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
}
