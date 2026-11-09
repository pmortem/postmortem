package app.postmortem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.postmortem.core.analysis.PositionEvals
import app.postmortem.core.board.MoveTarget
import app.postmortem.core.board.Moves
import app.postmortem.core.board.PieceColor
import app.postmortem.core.report.Drill
import app.postmortem.core.report.DrillItem
import app.postmortem.core.store.GameLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the practice screen is: [LOADING] (the game and the best move are being found), [ASKING], [CHECKING] an answer, or its verdict. */
enum class DrillPhase { LOADING, ASKING, CHECKING, RIGHT, GOOD, WRONG, SHOWN, DONE }

/** What the practice screen shows. [fen] is the board; [lastMove] its highlighted move; [best] is drawn as an arrow when set. */
data class DrillUi(
    val active: Boolean = false,
    val phase: DrillPhase = DrillPhase.LOADING,
    val item: DrillItem? = null,
    val fen: String = "",
    val lastMove: String? = null,
    val flipped: Boolean = false,
    val best: String? = null,
    /** The best move in words (SAN), once it is shown. */
    val bestSan: String? = null,
    val position: Int = 0,
    val total: Int = 0,
    val solved: Int = 0,
    val failed: Int = 0,
    val engineFailed: Boolean = false,
)

/**
 * "Learn from your mistakes": positions from the player's own games, one after another, to find a better move in. A move is checked
 * against the engine's best move; one other than that is evaluated with the analysis settings and is good enough when it loses less
 * than an inaccuracy. Every try and skip is written to the database, so the progress survives a restart. One engine process at most
 * (the evaluator of lines), and the live engine is not used here.
 */
class DrillController(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val library: GameLibrary?,
    private val positions: PositionEvals?,
    private val report: ReportController,
) {
    var ui: DrillUi by mutableStateOf(DrillUi())
        private set

    /** Moves played by hand, only on the position asked and only while it is asked. */
    private val input = MoveInput({ asked.takeIf { ui.phase == DrillPhase.ASKING } }) { from, to, promotion -> play(from, to, promotion) }

    /** The picked-up piece and where it may go, and a promotion waiting for its piece, on the position asked. */
    val selected: String? get() = input.selected
    val targets: List<MoveTarget> get() = input.targets
    val promotion: PromotionAsked? get() = input.promotion

    private var session: List<DrillItem> = emptyList()
    private var failed = mutableListOf<DrillItem>()
    private var asked = ""
    private var work: Job? = null

    /** Starts from [queue], by default the one the Report shows; nothing happens when it is empty. */
    fun start(
        queue: List<DrillItem> =
            report.ui.drill
                ?.items
                .orEmpty(),
    ) {
        if (queue.isEmpty()) return
        begin(queue)
    }

    private fun begin(items: List<DrillItem>) {
        session = items
        failed = mutableListOf()
        ui = DrillUi(active = true, total = items.size)
        load(0)
    }

    /** Practises the positions that were failed or skipped in this round again. */
    fun startOver() {
        if (failed.isEmpty()) return
        begin(failed.toList())
    }

    private fun load(index: Int) {
        work?.cancel()
        clearInput()
        if (index >= session.size) {
            ui = ui.copy(phase = DrillPhase.DONE, item = null, best = null, bestSan = null)
            return
        }
        val item = session[index]
        ui = ui.copy(phase = DrillPhase.LOADING, item = item, position = index, best = null, bestSan = null, engineFailed = false)
        work =
            scope.launch {
                val loaded = withContext(dispatcher) { runCatching { library?.load(item.game.gameId) }.getOrNull() }
                if (loaded == null) {
                    // the game is gone (deleted meanwhile): its position cannot be asked
                    load(index + 1)
                    return@launch
                }
                val fen = loaded.game.fenAt(item.position)
                val best = bestMoveOf(item, fen)
                bestUci = best
                asked = fen
                ui =
                    ui.copy(
                        phase = DrillPhase.ASKING,
                        fen = fen,
                        lastMove =
                            loaded.game.plies
                                .getOrNull(item.position - 1)
                                ?.uci,
                        flipped = item.game.color == PieceColor.BLACK,
                        best = null,
                        bestSan = best?.let { san(fen, it) },
                    )
            }
    }

    private var bestUci: String? = null

    /** The stored best move, or one found now with the analysis settings and written back for next time. */
    private suspend fun bestMoveOf(
        item: DrillItem,
        fen: String,
    ): String? {
        val stored = withContext(dispatcher) { runCatching { library?.bestMove(item.game.gameId, item.position) }.getOrNull() }
        if (stored != null) return stored
        val found =
            try {
                positions?.bestMove(fen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui = ui.copy(engineFailed = true)
                null
            }
        if (found != null) withContext(dispatcher) { runCatching { library?.setBestMove(item.game.gameId, item.position, found) } }
        return found
    }

    private fun san(
        fen: String,
        uci: String,
    ): String? = Moves.play(fen, uci.substring(0, 2), uci.substring(2, 4), uci.getOrNull(4))?.san

    private fun clearInput() = input.clear()

    /** A tap on a square of the board (see [MoveInput.tap]). */
    fun tapSquare(square: String) = input.tap(square)

    fun dragMove(
        from: String,
        to: String,
    ) = input.drag(from, to)

    fun choosePromotion(piece: Char) = input.choose(piece)

    fun cancelPromotion() {
        input.cancelPromotion()
    }

    private fun play(
        from: String,
        to: String,
        piece: Char?,
    ) {
        val item = ui.item ?: return
        val ply = Moves.play(asked, from, to, piece) ?: return
        ui = ui.copy(phase = DrillPhase.CHECKING, engineFailed = false)
        work?.cancel()
        work =
            scope.launch {
                val mover = item.game.color
                val verdict =
                    if (!Drill.needsEval(bestUci, ply.uci)) {
                        Drill.Verdict.RIGHT
                    } else {
                        val after =
                            try {
                                positions?.evaluate(ply.fen)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                        if (after == null) {
                            ui = ui.copy(phase = DrillPhase.ASKING, engineFailed = true)
                            return@launch
                        }
                        Drill.verdict(bestUci, ply.uci, item.before, after, mover)
                    }
                when (verdict) {
                    Drill.Verdict.RIGHT -> finish(DrillPhase.RIGHT, ply.fen, ply.uci, showBest = false)
                    Drill.Verdict.GOOD -> finish(DrillPhase.GOOD, ply.fen, ply.uci, showBest = true)
                    Drill.Verdict.WRONG -> ui = ui.copy(phase = DrillPhase.WRONG)
                }
            }
    }

    private fun finish(
        phase: DrillPhase,
        fen: String,
        lastMove: String,
        showBest: Boolean,
    ) {
        val item = ui.item ?: return
        record(item, solved = true)
        ui = ui.copy(phase = phase, fen = fen, lastMove = lastMove, best = if (showBest) bestUci else null, solved = ui.solved + 1)
    }

    /** After a wrong answer: the move is taken back and the same position asked again. */
    fun tryAgain() {
        if (ui.phase != DrillPhase.WRONG) return
        clearInput()
        ui = ui.copy(phase = DrillPhase.ASKING)
    }

    /** After a wrong answer: shows the best move with an arrow and its eval; the position counts as failed for now. */
    fun show() {
        val item = ui.item ?: return
        if (ui.phase != DrillPhase.WRONG && ui.phase != DrillPhase.ASKING) return
        clearInput()
        record(item, solved = false)
        failed += item
        ui = ui.copy(phase = DrillPhase.SHOWN, best = bestUci, failed = ui.failed + 1)
    }

    /** Leaves this position for later: a try that is not solved. */
    fun skip() {
        val item = ui.item ?: return
        if (ui.phase != DrillPhase.ASKING && ui.phase != DrillPhase.WRONG) return
        record(item, solved = false)
        failed += item
        ui = ui.copy(failed = ui.failed + 1)
        load(ui.position + 1)
    }

    /** After a verdict: the next position, or the end. */
    fun next() {
        if (ui.phase != DrillPhase.RIGHT && ui.phase != DrillPhase.GOOD && ui.phase != DrillPhase.SHOWN) return
        load(ui.position + 1)
    }

    private fun record(
        item: DrillItem,
        solved: Boolean,
    ) {
        scope.launch { withContext(dispatcher) { runCatching { library?.recordDrill(item.game.gameId, item.position, solved) } } }
    }

    /** Leaves the practice and has the Report count again, with the progress made. */
    fun close() {
        work?.cancel()
        clearInput()
        ui = DrillUi()
        scope.launch {
            runCatching { positions?.release() }
            report.refresh()
        }
    }
}
