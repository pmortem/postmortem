package app.postmortem

import app.postmortem.core.analysis.Eval
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnError

sealed interface UiState {
    /** [error] is why the last Analyse was refused; [engineFailed] says the engine broke during it. */
    data class Paste(
        val pgn: String = "",
        val error: PgnError? = null,
        val engineFailed: Boolean = false,
    ) : UiState {
        val canAnalyse: Boolean get() = pgn.isNotBlank()
    }

    /** [done] of [total] positions are evaluated. */
    data class Analysing(
        val pgn: String,
        val game: Game,
        val done: Int,
        val total: Int,
    ) : UiState {
        val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
    }

    /** [selectedPly] 0 is the start position; ply n is the position after the n-th half-move. [evals] has one entry per position. */
    data class Result(
        val pgn: String,
        val game: Game,
        val evals: List<Eval>,
        val selectedPly: Int = 0,
    ) : UiState {
        val canStepBack: Boolean get() = selectedPly > 0
        val canStepForward: Boolean get() = selectedPly < game.plies.size
        val selectedEval: Eval get() = evals[selectedPly]
    }
}
