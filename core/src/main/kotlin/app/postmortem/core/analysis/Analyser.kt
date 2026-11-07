package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Game
import app.postmortem.core.uci.BestMove
import app.postmortem.core.uci.EngineException
import app.postmortem.core.uci.ProcessFactory
import app.postmortem.core.uci.SearchResult
import app.postmortem.core.uci.UciEngine
import com.github.bhlangonijr.chesslib.Board
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [evals] holds the evaluation of positions 0 until [done]; position n is the position after ply n. [bestMoves] is the engine's best
 * move (UCI) for each of those positions, null where there is none (checkmate, stalemate); empty when nobody asked for them.
 */
data class AnalysisProgress(
    val done: Int,
    val total: Int,
    val evals: List<Eval>,
    val bestMoves: List<String?> = emptyList(),
) {
    val finished: Boolean get() = done == total
}

class Analyser(
    private val config: AnalysisConfig,
    private val processFactory: ProcessFactory,
    /** Called after every search that used the engine, for logging how fast it runs. */
    private val onSearch: (SearchResult) -> Unit = {},
) {
    /**
     * Evaluates every position of [game], in White's point of view, with [AnalysisConfig.workers] single-threaded engines that
     * take positions from one queue, and reports as each finishes. A position's evaluation depends only on the position and the
     * settings, so the result is the same whatever the number of workers or the order they pick positions in. Checkmate and
     * stalemate are scored without the engine. The processes die when the flow ends or is cancelled.
     *
     * [AnalysisProgress.done] counts finished positions; [AnalysisProgress.evals] holds the evaluations of the positions from 0
     * on that are all finished.
     */
    fun analyse(game: Game): Flow<AnalysisProgress> =
        channelFlow {
            val total = game.positionCount
            send(AnalysisProgress(0, total, emptyList()))
            val results = arrayOfNulls<Eval>(total)
            val bestMoves = arrayOfNulls<String>(total)
            val queue = Channel<Int>(Channel.UNLIMITED)
            for (position in 0 until total) queue.trySend(position)
            queue.close()
            val lock = Mutex()
            var done = 0
            coroutineScope {
                repeat(minOf(config.workers, total)) {
                    launch {
                        var engine: UciEngine? = null
                        try {
                            for (position in queue) {
                                val fen = game.fenAt(position)
                                val found =
                                    terminalEval(fen)?.let { PositionResult(it, null) } ?: run {
                                        val started = engine ?: UciEngine.start(config, processFactory).also { engine = it }
                                        searchPosition(started, fen, onSearch)
                                    }
                                lock.withLock {
                                    results[position] = found.eval
                                    bestMoves[position] = found.bestMove
                                    done++
                                    val finished = results.takeWhile { it != null }.filterNotNull()
                                    send(AnalysisProgress(done, total, finished, bestMoves.take(finished.size)))
                                }
                            }
                        } finally {
                            engine?.close()
                        }
                    }
                }
            }
        }
}

/** The score of a position that is over (checkmate, stalemate), without the engine; null while the game goes on. */
internal fun terminalEval(fen: String): Eval? {
    val board = Board().apply { loadFromFen(fen) }
    val whiteToMove = isWhiteToMove(fen)
    return when {
        board.isMated -> Eval.Checkmate(if (whiteToMove) PieceColor.BLACK else PieceColor.WHITE)
        board.isStaleMate -> Eval.Cp(0)
        else -> null
    }
}

/** What a search of one position found: the evaluation (White's point of view) and the engine's best move (UCI), null when it has none. */
internal data class PositionResult(
    val eval: Eval,
    val bestMove: String?,
)

/** The engine's evaluation and best move of [fen] under the engine's settings. */
internal suspend fun searchPosition(
    engine: UciEngine,
    fen: String,
    onSearch: (SearchResult) -> Unit,
): PositionResult {
    val result = engine.search(fen)
    onSearch(result)
    val score = result.score ?: throw EngineException("the engine gave no score for $fen")
    return PositionResult(Eval.fromScore(score, isWhiteToMove(fen)), (result.bestMove as? BestMove.Move)?.uci)
}

/** The engine's evaluation of [fen] under the engine's settings, in White's point of view. */
internal suspend fun searchEval(
    engine: UciEngine,
    fen: String,
    onSearch: (SearchResult) -> Unit,
): Eval = searchPosition(engine, fen, onSearch).eval

private fun isWhiteToMove(fen: String) = fen.split(' ').getOrNull(1) != "b"
