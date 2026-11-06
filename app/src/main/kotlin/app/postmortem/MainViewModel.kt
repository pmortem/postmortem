package app.postmortem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.LiveConfig
import app.postmortem.core.analysis.LiveLines
import app.postmortem.core.analysis.LiveUpdate
import app.postmortem.core.analysis.PositionEvals
import app.postmortem.core.analysis.positionKey
import app.postmortem.core.board.MoveTarget
import app.postmortem.core.board.Moves
import app.postmortem.core.line.Cursor
import app.postmortem.core.line.Line
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import app.postmortem.core.pgn.looksLikePgn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [analysis] evaluates a game; it runs on [dispatcher] and is cancelled with the screen or on Back. */
class MainViewModel(
    private val analysis: (Game) -> Flow<AnalysisProgress>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** How the analysis is made, kept with each result. */
    private val analysisSettings: String = "",
    /** The live engine that shows the best moves of the shown position; none in tests that do not need it. */
    private val live: LiveLines? = null,
    private val arrowsSetting: EngineArrowsSetting = InMemoryEngineArrowsSetting(),
    /** Evaluates the positions of the lines the user plays; none in tests that do not need it. */
    private val positions: PositionEvals? = null,
    private val liveDebounceMs: Long = LiveConfig.DEFAULT_DEBOUNCE_MS,
) : ViewModel() {
    var state: UiState by mutableStateOf(UiState.Paste())
        private set

    /** Whether the best moves are searched and drawn as arrows (the saved setting). */
    var engineOn: Boolean by mutableStateOf(true)
        private set

    /** The live engine's best moves for the shown position, as far as they are known; null when there are none to show. */
    var liveUpdate: LiveUpdate? by mutableStateOf(null)
        private set

    /** The Settings page is open over the Result screen; the game, selected move, tab and flip stay in [state]. */
    var settingsOpen: Boolean by mutableStateOf(false)
        private set

    /** The piece the user picked up by tapping, and where it may go. It counts only while the board shows the position it was picked in. */
    private var selection: BoardSelection? by mutableStateOf(null)
    private var promotionAsked: PromotionAsked? by mutableStateOf(null)

    private val shownFen: String? get() = (state as? UiState.Result)?.fen

    /** The square of the picked-up piece on the shown position, or null. */
    val selectedSquare: String? get() = selection?.takeIf { it.fen == shownFen }?.square

    /** Where the picked-up piece may go. */
    val moveTargets: List<MoveTarget> get() = selection?.takeIf { it.fen == shownFen }?.targets.orEmpty()

    /** The pawn move waiting for a piece to be chosen, or null. */
    val promotion: PromotionAsked? get() = promotionAsked?.takeIf { it.fen == shownFen }

    /** The line being edited (swiped left): a move played on it cuts it there. */
    var editingLineId: Int? by mutableStateOf(null)
        private set

    /** The line deleted last, which Undo brings back, with where the board was. */
    var deletedLine: DeletedLine? by mutableStateOf(null)
        private set

    private var liveJob: Job? = null
    private var foreground = true

    // The positions of the user's lines wait here, in the order they were played, for the one engine that evaluates them.
    private var evalQueue = Channel<String>(Channel.UNLIMITED)
    private var evalJob: Job? = null
    private val appVisible = MutableStateFlow(true)

    init {
        arrowsSetting.enabled
            .onEach {
                engineOn = it
                restartLive()
            }.launchIn(viewModelScope)
    }

    private var clipboardOffered = false
    private var job: Job? = null

    fun onPgnChange(text: String) {
        state = UiState.Paste(pgn = text)
    }

    /** The clipboard is offered once per launch, and only into an empty box. */
    fun onClipboard(text: String?) {
        if (clipboardOffered) return
        clipboardOffered = true
        val current = state as? UiState.Paste ?: return
        if (current.pgn.isBlank() && text != null && looksLikePgn(text)) onPgnChange(text)
    }

    fun analyse() {
        val current = state as? UiState.Paste ?: return
        when (val result = PgnParser.parse(current.pgn)) {
            is PgnResult.Failure -> state = current.copy(error = result.error)
            is PgnResult.Success -> startAnalysis(current.pgn, result.game)
        }
    }

    private fun startAnalysis(
        pgn: String,
        game: Game,
    ) {
        stopLive()
        editingLineId = null
        deletedLine = null
        state = UiState.Analysing(pgn, game, done = 0, total = game.positionCount)
        var furthest = 0
        job =
            viewModelScope.launch {
                analysis(game)
                    .flowOn(dispatcher)
                    .catch { state = UiState.Paste(pgn = pgn, engineFailed = true) }
                    .collect { progress ->
                        state =
                            if (progress.finished) {
                                positions?.seed(game, progress.evals)
                                UiState.Result(pgn, game, progress.evals, analysisSettings = analysisSettings)
                            } else {
                                furthest = maxOf(furthest, progress.done)
                                UiState.Analysing(pgn, game, furthest, progress.total)
                            }
                        if (progress.finished) restartLive()
                    }
            }
    }

    fun selectPly(ply: Int) {
        val current = state as? UiState.Result ?: return
        editingLineId = null
        state = current.copy(cursor = Cursor.game(ply.coerceIn(0, current.game.plies.size)))
        restartLive()
    }

    /**
     * A tap on [square]: with a piece picked up, a square it may go to plays the move (a pawn reaching the last rank first asks for
     * the piece), another piece of the side to move picks that one up, and anything else puts the piece down. With none picked up, a
     * piece that can move is picked up.
     */
    fun tapSquare(square: String) {
        val fen = shownFen ?: return
        if (promotion != null) return
        val picked = selection?.takeIf { it.fen == fen }
        if (picked != null && picked.targets.any { it.square == square }) {
            attempt(picked.square, square)
            return
        }
        val targets = Moves.legalTargets(fen, square)
        selection = if (targets.isEmpty() || picked?.square == square) null else BoardSelection(fen, square, targets)
    }

    /** A piece dragged from [from] and dropped on [to]: played when legal; otherwise nothing changes and the piece snaps back. */
    fun dragMove(
        from: String,
        to: String,
    ) {
        val fen = shownFen ?: return
        if (promotion != null || from == to) return
        if (Moves.legalTargets(fen, from).none { it.square == to }) return
        attempt(from, to)
    }

    private fun attempt(
        from: String,
        to: String,
    ) {
        val fen = shownFen ?: return
        selection = null
        if (Moves.needsPromotion(fen, from, to)) {
            promotionAsked = PromotionAsked(fen, from, to)
        } else {
            playMove(from, to)
        }
    }

    /** The piece (`q`, `r`, `b` or `n`) chosen for the waiting promotion. */
    fun choosePromotion(piece: Char) {
        val asked = promotion ?: return
        promotionAsked = null
        playMove(asked.from, asked.to, piece)
    }

    fun cancelPromotion() {
        promotionAsked = null
    }

    /** Selects the [move]-th move of [lineId], counting from 1. */
    fun selectLineMove(
        lineId: Int,
        move: Int,
    ) {
        val current = state as? UiState.Result ?: return
        val line = current.lines.find(lineId) ?: return
        if (editingLineId != lineId) editingLineId = null
        state = current.copy(cursor = Cursor(lineId, move.coerceIn(1, line.plies.size)))
        restartLive()
    }

    /** Selects the whole line: the board shows its last position. */
    fun selectLine(lineId: Int) {
        val current = state as? UiState.Result ?: return
        val line = current.lines.find(lineId) ?: return
        selectLineMove(lineId, line.plies.size)
    }

    /** Deletes the line and remembers it for [undoDelete]; the board goes to the position it branched from if it was on it. */
    fun deleteLine(lineId: Int) {
        val current = state as? UiState.Result ?: return
        val line = current.lines.find(lineId) ?: return
        deletedLine = DeletedLine(line, current.cursor)
        if (editingLineId == lineId) editingLineId = null
        val cursor = if (current.cursor.lineId == lineId) Cursor.game(line.branchPly) else current.cursor
        state = current.copy(lines = current.lines.delete(lineId), cursor = cursor)
        restartLive()
    }

    /** Brings the last deleted line back as it was, with its evals, and the board to where it was if it was on that line. */
    fun undoDelete() {
        val current = state as? UiState.Result ?: return
        val deleted = deletedLine ?: return
        deletedLine = null
        val back = if (deleted.cursorBefore.lineId == deleted.line.id) deleted.cursorBefore else current.cursor
        state = current.copy(lines = current.lines.restore(deleted.line), cursor = back)
        restartLive()
    }

    /** The undo offer is over (its time ran out). */
    fun dismissUndo() {
        deletedLine = null
    }

    fun editLine(lineId: Int) {
        val current = state as? UiState.Result ?: return
        if (current.lines.find(lineId) == null) return
        editingLineId = lineId
    }

    fun doneEditing() {
        editingLineId = null
    }

    /**
     * Plays the move from [from] to [to] on the shown position. It only goes through when it is legal ([promotion] is `q`, `r`, `b`
     * or `n` for a pawn reaching the last rank). The game's own next move steps forward; any other move makes or extends a line.
     */
    fun playMove(
        from: String,
        to: String,
        promotion: Char? = null,
    ) {
        val current = state as? UiState.Result ?: return
        val ply = Moves.play(current.fen, from, to, promotion) ?: return
        val editing = editingLineId
        val edited = editing?.let { current.lines.edit(current.game, current.cursor, it, ply) }
        if (editing != null && edited == null) editingLineId = null
        val played = edited ?: current.lines.play(current.game, current.cursor, ply)
        selection = null
        state = current.copy(lines = played.lines, cursor = played.cursor)
        evaluateSoon(ply.fen)
        restartLive()
    }

    /**
     * Gets the eval of a position of a line: at once when the game or an earlier search has it, otherwise it is queued behind the
     * positions played before it, and shows as pending until the engine has answered.
     */
    private fun evaluateSoon(fen: String) {
        val evaluator = positions ?: return
        val current = state as? UiState.Result ?: return
        val key = positionKey(fen)
        if (current.evalOf(fen) != null || key in current.pendingEvals) return
        val known = evaluator.cached(fen)
        if (known != null) {
            state = current.copy(lineEvals = current.lineEvals + (key to known))
            return
        }
        state = current.copy(pendingEvals = current.pendingEvals + key)
        evalQueue.trySend(fen)
        if (evalJob == null) {
            evalJob =
                viewModelScope.launch {
                    for (queued in evalQueue) {
                        appVisible.first { it }
                        val found =
                            try {
                                withContext(dispatcher) { evaluator.evaluate(queued) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                        val now = state as? UiState.Result ?: continue
                        val queuedKey = positionKey(queued)
                        state =
                            now.copy(
                                lineEvals = if (found != null) now.lineEvals + (queuedKey to found) else now.lineEvals,
                                pendingEvals = now.pendingEvals - queuedKey,
                            )
                    }
                }
        }
    }

    /** Drops what waits for an eval and ends the engine that evaluates lines. */
    private fun stopEvaluating() {
        evalJob?.cancel()
        evalJob = null
        evalQueue.close()
        evalQueue = Channel(Channel.UNLIMITED)
        positions?.let { viewModelScope.launch { it.release() } }
    }

    fun showEngineArrows(on: Boolean) {
        viewModelScope.launch { arrowsSetting.set(on) }
    }

    fun openSettings() {
        if (state !is UiState.Result) return
        settingsOpen = true
        restartLive()
    }

    /** The app is on screen or not. In the background nothing is searched and the engine process ends. */
    fun onForeground(visible: Boolean) {
        foreground = visible
        appVisible.value = visible
        if (!visible) positions?.let { viewModelScope.launch { it.release() } }
        restartLive()
    }

    /**
     * Searches the shown position for its best moves after it has stayed shown for [liveDebounceMs], so stepping quickly starts no
     * search. A position searched before shows its lines at once. Only on the Result screen, with the engine on and the app visible.
     */
    private fun restartLive() {
        stopLive(release = false)
        val current = state as? UiState.Result
        val search = live
        if (search == null || current == null || !engineOn || !foreground || settingsOpen) {
            stopLive()
            return
        }
        val fen = current.fen
        search.cached(fen)?.let {
            liveUpdate = LiveUpdate(it, it.maxOfOrNull { line -> line.depth } ?: 0, null, finished = true)
            return
        }
        liveJob =
            viewModelScope.launch {
                delay(liveDebounceMs)
                search
                    .lines(fen)
                    .flowOn(dispatcher)
                    .catch { liveUpdate = null }
                    .collect { liveUpdate = it }
            }
    }

    /** Ends the running search and clears the arrows; with [release] the engine process ends too, unless a new search has begun. */
    private fun stopLive(release: Boolean = true) {
        val running = liveJob
        liveJob = null
        liveUpdate = null
        running?.cancel()
        val search = live ?: return
        if (release) {
            viewModelScope.launch {
                running?.join()
                if (liveJob == null) search.release()
            }
        }
    }

    override fun onCleared() {
        (live as? AutoCloseable)?.close()
        (positions as? AutoCloseable)?.close()
    }

    /** Turns the board half around; the selected move, the tab and the chart stay as they are. */
    fun flip() {
        val current = state as? UiState.Result ?: return
        state = current.copy(flipped = !current.flipped)
    }

    fun selectTab(tab: ResultTab) {
        val current = state as? UiState.Result ?: return
        state = current.copy(tab = tab)
    }

    fun step(delta: Int) {
        val current = state as? UiState.Result ?: return
        state = current.copy(cursor = current.lines.step(current.game, current.cursor, delta))
        restartLive()
    }

    /** Back stops a running analysis, or leaves the result; either way the game stays in the paste box. */
    fun back(): Boolean {
        if (settingsOpen) {
            settingsOpen = false
            restartLive()
            return true
        }
        if (promotion != null) {
            promotionAsked = null
            return true
        }
        if (editingLineId != null) {
            editingLineId = null
            return true
        }
        (state as? UiState.Result)?.let { current ->
            if (current.cursor.lineId != null) {
                state = current.copy(cursor = current.lines.leave(current.cursor))
                restartLive()
                return true
            }
        }
        val pgn =
            when (val current = state) {
                is UiState.Analysing -> current.pgn
                is UiState.Result -> current.pgn
                is UiState.Paste -> return false
            }
        job?.cancel()
        job = null
        stopLive()
        stopEvaluating()
        editingLineId = null
        deletedLine = null
        state = UiState.Paste(pgn = pgn)
        return true
    }
}

/** A piece picked up on [square] of the position [fen], with the squares it may go to. */
private data class BoardSelection(
    val fen: String,
    val square: String,
    val targets: List<MoveTarget>,
)

/** A pawn move from [from] to [to] on the position [fen] that needs a piece chosen. */
data class PromotionAsked(
    val fen: String,
    val from: String,
    val to: String,
)

/** A line the user deleted, and where the board was, so Undo can put both back. */
data class DeletedLine(
    val line: Line,
    val cursorBefore: Cursor,
)
