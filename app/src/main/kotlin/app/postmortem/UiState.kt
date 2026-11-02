package app.postmortem

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.GameReport
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.GameOverview
import app.postmortem.core.pgn.PgnError
import app.postmortem.core.pgn.overview

/** The views under the board on the Result screen. The selected move is shared, so switching tabs never changes the board. */
enum class ResultTab { Moves, Chart, Summary }

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
        /** The one source of the selected tab: the tab bar and the content below it both read it. Each new result starts on Moves. */
        val tab: ResultTab = ResultTab.Moves,
        /** Black at the bottom. A newly analysed game starts with White at the bottom; later features can set it. */
        val flipped: Boolean = false,
        /** How the evals were made (engine, nodes, hash), so a saved analysis can say. */
        val analysisSettings: String = "",
    ) : UiState {
        val canStepBack: Boolean get() = selectedPly > 0
        val canStepForward: Boolean get() = selectedPly < game.plies.size
        val selectedEval: Eval get() = evals[selectedPly]

        /** Accuracy, ACPL and the judged moves; worked out once, when the Summary or the chart first asks. */
        val report: GameReport by lazy(
            LazyThreadSafetyMode.NONE,
        ) { GameReport.of(evals, game.whiteMovesFirst, startEval = lichessStartEval) }
        val overview: GameOverview get() = game.overview

        /** Lichess scores the first move against 15 cp, whatever the start position evaluates to; so do we, for a normal game. */
        private val lichessStartEval: Eval? get() = if (game.startsFromStandardPosition) Eval.Cp(GameReport.LICHESS_START_CP) else null
    }
}
