package app.postmortem

import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnError

sealed interface UiState {
    /** [error] is why the last Analyse was refused, if it was. */
    data class Paste(val pgn: String = "", val error: PgnError? = null) : UiState {
        val canAnalyse: Boolean get() = pgn.isNotBlank()
    }

    /** [selectedPly] 0 is the start position; ply n is the position after the n-th half-move. */
    data class Result(val pgn: String, val game: Game, val selectedPly: Int = 0) : UiState {
        val canStepBack: Boolean get() = selectedPly > 0
        val canStepForward: Boolean get() = selectedPly < game.plies.size
    }
}
