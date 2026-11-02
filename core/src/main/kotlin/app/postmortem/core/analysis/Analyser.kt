package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Game
import app.postmortem.core.uci.EngineException
import app.postmortem.core.uci.ProcessFactory
import app.postmortem.core.uci.UciEngine
import com.github.bhlangonijr.chesslib.Board
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** [evals] holds the evaluation of positions 0 until [done]; position n is the position after ply n. */
data class AnalysisProgress(val done: Int, val total: Int, val evals: List<Eval>) {
    val finished: Boolean get() = done == total
}

class Analyser(
    private val config: AnalysisConfig,
    private val processFactory: ProcessFactory,
) {
    /**
     * Evaluates every position of [game] with one engine process, in White's point of view, and reports after each.
     * Checkmate and stalemate are scored without the engine. The process dies when the flow ends or is cancelled.
     */
    fun analyse(game: Game): Flow<AnalysisProgress> = flow {
        val total = game.positionCount
        val evals = ArrayList<Eval>(total)
        emit(AnalysisProgress(0, total, emptyList()))
        UciEngine.start(config, processFactory).use { engine ->
            for (position in 0 until total) {
                val fen = game.fenAt(position)
                evals += terminalEval(fen) ?: searchEval(engine, fen)
                emit(AnalysisProgress(position + 1, total, evals.toList()))
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

    private suspend fun searchEval(engine: UciEngine, fen: String): Eval {
        val result = engine.search(fen)
        val score = result.score ?: throw EngineException("the engine gave no score for $fen")
        return Eval.fromScore(score, isWhiteToMove(fen))
    }

    private fun isWhiteToMove(fen: String) = fen.split(' ').getOrNull(1) != "b"
}
