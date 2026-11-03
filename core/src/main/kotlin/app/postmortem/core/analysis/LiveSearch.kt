package app.postmortem.core.analysis

import app.postmortem.core.uci.ProcessFactory
import app.postmortem.core.uci.PvInfo
import app.postmortem.core.uci.Score
import app.postmortem.core.uci.UciEngine
import com.github.bhlangonijr.chesslib.Board
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** One of the best moves of a position: its [rank] (1 is the best), the move in UCI, and its [score] for the side to move. */
data class LiveLine(
    val rank: Int,
    val move: String,
    val score: Score,
    val depth: Int,
    val pv: List<String>,
) {
    /** `+0.8`, `−1.3`, `#3`, `#−3`: from the point of view of the side to move, whose move this is. */
    fun scoreText(): String =
        when (score) {
            is Score.Cp -> {
                val pawns = String.format(Locale.ROOT, "%.1f", kotlin.math.abs(score.centipawns) / CENTIPAWNS_PER_PAWN)
                val sign =
                    if (pawns == "0.0") {
                        ""
                    } else if (score.centipawns < 0) {
                        "$MINUS"
                    } else {
                        "+"
                    }
                sign + pawns
            }

            is Score.Mate -> {
                if (score.moves >= 0) "#${score.moves}" else "#$MINUS${-score.moves}"
            }
        }

    private companion object {
        const val CENTIPAWNS_PER_PAWN = 100.0
        const val MINUS = '−'
    }
}

/** What a live search knows so far: the best [lines] by rank, how deep and how fast the engine is, and whether it is done. */
data class LiveUpdate(
    val lines: List<LiveLine>,
    val depth: Int,
    val nodesPerSecond: Long?,
    val finished: Boolean,
)

/** What the screen needs from the live engine, so it can be replaced in tests. */
interface LiveLines {
    /** The finished lines of [fen] if it has been searched to the end, else null. */
    fun cached(fen: String): List<LiveLine>?

    /** Updates for [fen] (see [LiveSearch.lines]); cancelling the collection stops the search. */
    fun lines(fen: String): Flow<LiveUpdate>

    /** Ends the engine process once no search runs; the next [lines] starts one again. The cache stays. */
    suspend fun release()
}

/**
 * The live engine: one separate process that searches the shown position for its best moves, one position at a time. Collecting
 * [lines] of another position while one runs needs the first collection to be cancelled; the engine is stopped and the new
 * search starts once it has answered. Finished results are kept by position, so a revisited position needs no search.
 */
class LiveSearch(
    private val config: LiveConfig,
    private val processFactory: ProcessFactory,
    private val clock: () -> Long = System::nanoTime,
) : LiveLines,
    AutoCloseable {
    private val cache = HashMap<String, List<LiveLine>>()
    private val lock = Mutex()
    private var engine: UciEngine? = null

    override fun cached(fen: String): List<LiveLine>? = synchronized(cache) { cache[fen] }

    /**
     * Updates for [fen]: the cached lines at once, or partial lines as the search finds them (at most one per
     * [LiveConfig.updateIntervalMs]), then a last one with `finished = true`. A position that is mated or stalemated has no lines and
     * starts no engine. Cancelling the collection stops the search; only a search that ran to its end is cached.
     */
    override fun lines(fen: String): Flow<LiveUpdate> =
        channelFlow {
            cached(fen)?.let {
                send(LiveUpdate(it, it.firstOrNull()?.depth ?: 0, null, true))
                return@channelFlow
            }
            val board = Board().apply { loadFromFen(fen) }
            if (board.isMated || board.isStaleMate) {
                send(LiveUpdate(emptyList(), 0, null, true))
                return@channelFlow
            }
            lock.withLock {
                val live = engine ?: startEngine().also { engine = it }
                val found = HashMap<Int, PvInfo>()
                var lastSent: Long? = null
                var latest: PvInfo? = null

                fun snapshot(finished: Boolean) =
                    LiveUpdate(
                        found.toSortedMap().values.map { LiveLine(it.multiPv, it.pv.first(), it.score, it.depth, it.pv) },
                        found.values.maxOfOrNull { it.depth } ?: 0,
                        latest?.nodesPerSecond,
                        finished,
                    )
                try {
                    live.searchLive(fen, config.maxSearchMs) { info ->
                        if (info.multiPv > config.lines) return@searchLive
                        if ((found[info.multiPv]?.depth ?: 0) <= info.depth) found[info.multiPv] = info
                        latest = info
                        val now = clock()
                        if (lastSent.let { it == null || now - it >= config.updateIntervalMs * NANOS_PER_MILLI }) {
                            lastSent = now
                            trySend(snapshot(false))
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        engine = null
                        live.close()
                    }
                    throw e
                }
                val done = snapshot(true)
                synchronized(cache) { cache[fen] = done.lines }
                send(done)
            }
        }

    private suspend fun startEngine(): UciEngine {
        val started = UciEngine.start(AnalysisConfig(hashMb = config.hashMb), processFactory)
        try {
            started.setOption("Threads", config.threads)
            started.setOption("MultiPV", config.lines)
        } catch (e: Throwable) {
            started.close()
            throw e
        }
        return started
    }

    override suspend fun release() =
        lock.withLock {
            engine?.close()
            engine = null
        }

    override fun close() {
        engine?.close()
        engine = null
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
