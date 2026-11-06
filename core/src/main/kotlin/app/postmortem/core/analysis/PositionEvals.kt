package app.postmortem.core.analysis

import app.postmortem.core.pgn.Game
import app.postmortem.core.uci.ProcessFactory
import app.postmortem.core.uci.SearchResult
import app.postmortem.core.uci.UciEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The position without its move counters: placement, side to move, castling and en passant square. Two positions with the same key
 * are the same position for the eval cache. The engine's eval depends on the halfmove clock only through the 50-move rule, so a
 * transposition that arrives at another count shares the eval; a position that differs only in how its en passant square is
 * written is a cache miss, which costs a search and nothing else.
 */
fun positionKey(fen: String): String = fen.split(' ').take(KEY_FIELDS).joinToString(" ")

private const val KEY_FIELDS = 4

/** Evaluations of single positions on request, for the lines the user plays. */
interface PositionEvals {
    /** The eval of [fen] if it is known (from the game, an earlier search or the end of the game), else null. */
    fun cached(fen: String): Eval?

    /** The eval of [fen]: the known one at once, else a search; searches run one at a time, in the order they are asked. */
    suspend fun evaluate(fen: String): Eval

    /** Remembers the evals of the positions of [game] (they were found with the same settings), so a transposition needs no search. */
    fun seed(
        game: Game,
        evals: List<Eval>,
    )

    /** Ends the engine process; the next [evaluate] starts one again. The known evals stay. */
    suspend fun release()
}

/**
 * [PositionEvals] with one engine process, started on first use. It searches with the settings of the game's analysis
 * ([AnalysisConfig]: fixed nodes, one thread, cleared hash), so a line's +0.8 means what the game's +0.8 means and the same
 * position always gets the same number. Checkmate and stalemate are scored without the engine.
 */
class PositionEvaluator(
    private val config: AnalysisConfig,
    private val processFactory: ProcessFactory,
    /** Called after every search that used the engine, for logging how fast it runs. */
    private val onSearch: (SearchResult) -> Unit = {},
) : PositionEvals,
    AutoCloseable {
    private val cache = HashMap<String, Eval>()
    private val lock = Mutex()
    private var engine: UciEngine? = null

    override fun cached(fen: String): Eval? = synchronized(cache) { cache[positionKey(fen)] }

    override fun seed(
        game: Game,
        evals: List<Eval>,
    ) {
        synchronized(cache) {
            for ((position, eval) in evals.withIndex()) cache.putIfAbsent(positionKey(game.fenAt(position)), eval)
        }
    }

    override suspend fun evaluate(fen: String): Eval {
        cached(fen)?.let { return it }
        return lock.withLock {
            cached(fen)?.let { return it }
            val eval =
                terminalEval(fen) ?: run {
                    val started = engine ?: UciEngine.start(config, processFactory).also { engine = it }
                    try {
                        searchEval(started, fen, onSearch)
                    } catch (e: Exception) {
                        // A broken engine is not kept; the next request starts a new one.
                        engine = null
                        started.close()
                        throw e
                    }
                }
            synchronized(cache) { cache[positionKey(fen)] = eval }
            eval
        }
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
}
