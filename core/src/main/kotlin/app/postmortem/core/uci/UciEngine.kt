package app.postmortem.core.uci

import app.postmortem.core.analysis.AnalysisConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Starts the engine process. On the phone and in the JVM tests this is `ProcessBuilder(path).start()`. */
typealias ProcessFactory = () -> Process

class EngineException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** What a search found: the last exact score and the move the engine chose. */
data class SearchResult(
    val score: Score?,
    val bestMove: BestMove,
)

/**
 * A UCI engine on a [java.lang.Process]. Output is read on its own thread and handed over through a channel, so
 * waiting for it can be cancelled; after a cancellation the engine's state is unknown, so close it.
 */
class UciEngine private constructor(
    private val process: Process,
    private val config: AnalysisConfig,
) : AutoCloseable {
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val input = process.outputStream.bufferedWriter()

    init {
        thread(isDaemon = true, name = "uci-out") {
            try {
                process.inputStream.bufferedReader().forEachLine { lines.trySend(it) }
            } catch (_: IOException) {
                // the process was closed under us
            } finally {
                lines.close()
            }
        }
        // Unread error output would block a chatty process.
        thread(isDaemon = true, name = "uci-err") {
            try {
                process.errorStream.copyTo(OutputStream.nullOutputStream())
            } catch (_: IOException) {
            }
        }
    }

    private fun send(command: String) {
        try {
            input.write(command)
            input.newLine()
            input.flush()
        } catch (e: IOException) {
            throw EngineException("the engine does not accept commands", e)
        }
    }

    /** Reads lines until [done] returns a value for one; [timeoutMs] bounds the wait. */
    private suspend fun <T : Any> readUntil(
        timeoutMs: Long,
        what: String,
        done: (String) -> T?,
    ): T {
        try {
            return withTimeout(timeoutMs) {
                var found: T? = null
                while (found == null) {
                    val line =
                        lines.receiveCatching().getOrNull()
                            ?: throw EngineException("the engine exited while we waited for $what")
                    found = done(line)
                }
                found
            }
        } catch (e: TimeoutCancellationException) {
            throw EngineException("the engine did not send $what within $timeoutMs ms", e)
        }
    }

    private suspend fun handshake() {
        send("uci")
        readUntil(config.handshakeTimeoutMs, "uciok") { if (it.trim() == "uciok") Unit else null }
        send("setoption name Threads value ${config.threads}")
        send("setoption name Hash value ${config.hashMb}")
        send("isready")
        readUntil(config.handshakeTimeoutMs, "readyok") { if (it.trim() == "readyok") Unit else null }
    }

    /** Searches [fen] for [moveTimeMs] and returns the last exact score the engine reported. */
    suspend fun search(
        fen: String,
        moveTimeMs: Long = config.moveTimeMs,
    ): SearchResult {
        send("position fen $fen")
        send("go movetime $moveTimeMs")
        var score: Score? = null
        val best =
            readUntil(moveTimeMs + config.searchMarginMs, "bestmove") { line ->
                UciParser.score(line)?.let { score = it }
                UciParser.bestMove(line)
            }
        return SearchResult(score, best)
    }

    val isAlive: Boolean get() = process.isAlive

    /** Asks the engine to quit, then kills it. Safe to call twice and from any thread. */
    override fun close() {
        try {
            send("quit")
            input.close()
        } catch (_: IOException) {
        }
        if (!process.waitFor(config.quitGraceMs, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        process.waitFor()
        lines.close()
    }

    companion object {
        /** Starts the process and finishes the `uci`/`isready` handshake with the settings of [config]. */
        suspend fun start(
            config: AnalysisConfig,
            factory: ProcessFactory,
        ): UciEngine {
            val process =
                try {
                    factory()
                } catch (e: IOException) {
                    throw EngineException("the engine could not be started", e)
                }
            val engine = UciEngine(process, config)
            try {
                engine.handshake()
            } catch (e: Throwable) {
                engine.close()
                throw e
            }
            return engine
        }
    }
}
