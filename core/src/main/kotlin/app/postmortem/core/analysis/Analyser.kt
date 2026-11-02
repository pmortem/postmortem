package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Game
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

/** [evals] holds the evaluation of positions 0 until [done]; position n is the position after ply n. */
data class AnalysisProgress(
    val done: Int,
    val total: Int,
    val evals: List<Eval>,
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
                                val eval =
                                    terminalEval(fen) ?: run {
                                        val started = engine ?: UciEngine.start(config, processFactory).also { engine = it }
                                        searchEval(started, fen)
                                    }
                                lock.withLock {
                                    results[position] = eval
                                    done++
                                    send(AnalysisProgress(done, total, results.takeWhile { it != null }.filterNotNull()))
                                }
                            }
                        } finally {
                            engine?.close()
                        }
                    }
                }
            }
        }

    private fun terminalEval(fen: String): Eval? {
        val board = Board().apply { loadFromFen(fen) }
        val whiteToMove = isWhiteToMove(fen)
        return when {
            board.isMated -> Eval.Checkmate(if (whiteToMove) PieceColor.BLACK else PieceColor.WHITE)
            board.isStaleMate -> Eval.Cp(0)
            else -> null
        }
    }

    private suspend fun searchEval(
        engine: UciEngine,
        fen: String,
    ): Eval {
        val result = engine.search(fen)
        onSearch(result)
        val score = result.score ?: throw EngineException("the engine gave no score for $fen")
        return Eval.fromScore(score, isWhiteToMove(fen))
    }

    private fun isWhiteToMove(fen: String) = fen.split(' ').getOrNull(1) != "b"
}
