package app.postmortem

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.GameReport
import app.postmortem.core.analysis.Judgement
import app.postmortem.core.analysis.PlayedMove
import app.postmortem.core.analysis.positionKey
import app.postmortem.core.board.Moves
import app.postmortem.core.board.PieceColor
import app.postmortem.core.line.Cursor
import app.postmortem.core.line.Line
import app.postmortem.core.line.Lines
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
        val fraction: Float get() = if (total == 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

        /** The whole percentage shown in the ring: 0 at the start, 100 when every position is done, rounded to the nearest. */
        val percent: Int get() = if (total == 0) 0 else ((100L * done + total / 2) / total).toInt().coerceIn(0, 100)
    }

    /**
     * [cursor] is where the board is: a position of the game (ply 0 is the start, ply n the position after the n-th half-move) or of
     * a [lines] entry the user played. [evals] has one entry per position of the game; the lines have none yet.
     */
    data class Result(
        val pgn: String,
        val game: Game,
        val evals: List<Eval>,
        val cursor: Cursor = Cursor.game(0),
        /** The lines played from the game; the game, its evals, chart and summary never change with them. */
        val lines: Lines = Lines(),
        /** The evals of line positions found so far, by [positionKey]; a position that is also in the game uses the game's eval. */
        val lineEvals: Map<String, Eval> = emptyMap(),
        /** Line positions waiting for an eval (queued or being searched), by [positionKey]. */
        val pendingEvals: Set<String> = emptySet(),
        /** The one source of the selected tab: the tab bar and the content below it both read it. Each new result starts on Moves. */
        val tab: ResultTab = ResultTab.Moves,
        /** Black at the bottom. A newly analysed game starts with White at the bottom; later features can set it. */
        val flipped: Boolean = false,
        /** How the evals were made (engine, nodes, hash), so a saved analysis can say. */
        val analysisSettings: String = "",
        /** The id of the game in the library, once it is saved. */
        val gameId: Long? = null,
        /** What is worked out from [game] and [evals], shared by this result's copies (see [ResultCache]). */
        val cache: ResultCache = ResultCache(),
    ) : UiState {
        /** The game position shown, or the one the line the board is on branches from (the chart marks it). */
        val selectedPly: Int get() = lines.gamePly(cursor)
        val fen: String get() = lines.fen(game, cursor)
        val lastMove: String? get() = lines.lastMove(game, cursor)
        val canStepBack: Boolean get() = lines.canStepBack(cursor)
        val canStepForward: Boolean get() = lines.canStepForward(game, cursor)

        /** The position after the final move, whatever the ending: the eval bar says RIP there. */
        val atEndOfGame: Boolean get() = cursor.lineId == null && game.plies.isNotEmpty() && cursor.ply == game.plies.size

        private val gameEvalsByKey: Map<String, Eval>
            get() =
                cache.evalsByKey(evals) {
                    evals.withIndex().associate { (position, eval) ->
                        positionKey(game.fenAt(position)) to eval
                    }
                }

        /** The eval of [fen] if the game or the lines' search has it. */
        fun evalOf(fen: String): Eval? = positionKey(fen).let { lineEvals[it] ?: gameEvalsByKey[it] }

        fun isPending(fen: String): Boolean = positionKey(fen) in pendingEvals

        /** The eval of the shown position; on a line whose eval is not known yet, the one of the position it branches from. */
        val selectedEval: Eval get() = if (cursor.lineId == null) evals[cursor.ply] else evalOf(fen) ?: evals[selectedPly]

        /**
         * The judgements of the moves of [line] (null for a move that is fine) as far as their evals are known, worked out the way
         * the game's are, from the position the line branches off. Accuracy and ACPL are for the game only.
         */
        fun judgementsOf(line: Line): List<Judgement?> = reportOf(line)?.judgements.orEmpty()

        /** Whether each move of [line] is brilliant (same rule as the game's), as far as their evals are known. */
        fun brilliantOf(line: Line): List<Boolean> = reportOf(line)?.brilliant.orEmpty()

        /** A line's report, worked out once for the line and the evals known for it: a line row asks for it on every recomposition. */
        private fun reportOf(line: Line): GameReport? {
            val known =
                line.plies
                    .map { evalOf(it.fen) }
                    .takeWhile { it != null }
                    .filterNotNull()
            if (known.isEmpty()) return null
            return cache.lineReport(evals, line, known) { lineReportOf(line, known) }
        }

        private fun lineReportOf(
            line: Line,
            known: List<Eval>,
        ): GameReport {
            val whiteToMove = Moves.sideToMove(game.fenAt(line.branchPly)) == PieceColor.WHITE
            val moves =
                known.indices.map { index ->
                    PlayedMove(
                        if (index ==
                            0
                        ) {
                            game.fenAt(line.branchPly)
                        } else {
                            line.plies[index - 1].fen
                        },
                        line.plies[index].uci,
                        line.plies[index].fen,
                    )
                }
            return GameReport.of(listOf(evals[line.branchPly]) + known, whiteToMove, moves = moves)
        }

        /**
         * Accuracy, ACPL, the judged and the brilliant moves; worked out once for the game, not once per copy. The ViewModel starts it
         * in the background as soon as the result exists, so the screen normally finds it ready instead of building it on the main thread.
         */
        val report: GameReport
            get() =
                cache.report(evals) {
                    val moves = game.plies.mapIndexed { index, ply -> PlayedMove(game.fenAt(index), ply.uci, ply.fen) }
                    GameReport.of(evals, game.whiteMovesFirst, startEval = lichessStartEval, moves = moves)
                }
        val overview: GameOverview get() = game.overview

        /** Lichess scores the first move against 15 cp, whatever the start position evaluates to; so do we, for a normal game. */
        private val lichessStartEval: Eval? get() = if (game.startsFromStandardPosition) Eval.Cp(GameReport.LICHESS_START_CP) else null
    }
}

/**
 * What a result works out from its game and its evals: the report, the evals by position and the reports of the lines. Every step,
 * tab change or line played makes a copy of the result, and none of this changes with them, so the copies share one cache instead of
 * working it out again. Each value is tied to the evals it was made from and made again for others. Results are equal whatever their
 * caches hold. Thread-safe: the report is started in the background while the screen may ask for it.
 */
class ResultCache {
    private var report: Pair<List<Eval>, GameReport>? = null
    private var byKey: Pair<List<Eval>, Map<String, Eval>>? = null
    private val lines = HashMap<Triple<List<Eval>, Line, List<Eval>>, GameReport>()

    @Synchronized
    fun report(
        evals: List<Eval>,
        make: () -> GameReport,
    ): GameReport = report?.takeIf { it.first === evals }?.second ?: make().also { report = evals to it }

    @Synchronized
    fun evalsByKey(
        evals: List<Eval>,
        make: () -> Map<String, Eval>,
    ): Map<String, Eval> = byKey?.takeIf { it.first === evals }?.second ?: make().also { byKey = evals to it }

    @Synchronized
    fun lineReport(
        evals: List<Eval>,
        line: Line,
        known: List<Eval>,
        make: () -> GameReport,
    ): GameReport {
        if (lines.size > MAX_LINE_REPORTS) lines.clear()
        return lines.getOrPut(Triple(evals, line, known), make)
    }

    override fun equals(other: Any?): Boolean = other is ResultCache

    override fun hashCode(): Int = 0

    private companion object {
        /** Old lines and old eval sets are dropped now and then; a few hundred reports are plenty for one game. */
        const val MAX_LINE_REPORTS = 256
    }
}
