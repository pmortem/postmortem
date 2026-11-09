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
import app.postmortem.core.pgn.overview
import app.postmortem.core.store.GameLibrary
import app.postmortem.core.store.GameSummary
import app.postmortem.core.store.NewGame
import app.postmortem.core.store.SavedGame
import app.postmortem.data.DatabaseControl
import app.postmortem.data.DbFileInfo
import app.postmortem.data.DbOutcome
import app.postmortem.data.DbProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    /** The saved games: every analysed game and what is done to it is kept; none in tests that do not need it. */
    private val library: GameLibrary? = null,
    /** Says what the library did (saved, failed), for the log. */
    private val libraryLog: (String) -> Unit = {},
    /** The database files (rename, open, export, import); none in tests that do not need them. */
    private val databases: DatabaseControl? = null,
    private val liveDebounceMs: Long = LiveConfig.DEFAULT_DEBOUNCE_MS,
    reportPlayer: ReportPlayerSetting = InMemoryReportPlayerSetting(),
) : ViewModel() {
    /** The Report tab of the start page: the player, their openings, and what is expanded or listed. */
    val report = ReportController(viewModelScope, dispatcher, library, reportPlayer)

    /** "Learn from your mistakes", opened from the Report tab. */
    val drill = DrillController(viewModelScope, dispatcher, library, positions, report)

    var state: UiState by mutableStateOf(UiState.Paste())
        private set

    /** Whether the best moves are searched and drawn as arrows (the saved setting). */
    var engineOn: Boolean by mutableStateOf(true)
        private set

    /** The live engine's best moves for the shown position, as far as they are known; null when there are none to show. */
    var liveUpdate: LiveUpdate? by mutableStateOf(null)
        private set

    /** The page open over the current screen (Settings from the Result's cog); [state] stays as it is under it. */
    var overlay: Overlay? by mutableStateOf(null)
        private set

    val settingsOpen: Boolean get() = overlay == Overlay.Settings

    /** The tab of the start page (the Paste state): it stays while a game is open, so Back from a game returns to it. */
    var startTab: StartTab by mutableStateOf(StartTab.Analyse)
        private set

    /** The saved games for the Library page, newest worked on first; null while they are being read. */
    var libraryGames: List<GameSummary>? by mutableStateOf(null)
        private set

    /** The game deleted from the Library last, which Undo brings back. */
    var deletedGame: SavedGame? by mutableStateOf(null)
        private set

    /** What the Database section of the Settings page shows. */
    var databaseUi: DatabaseUi by mutableStateOf(DatabaseUi())
        private set

    /** A complete copy of the active database waiting to be handed to the share sheet; the screen takes it with [exportShared]. */
    var pendingExport: java.io.File? by mutableStateOf(null)
        private set

    private val shownFen: String? get() = (state as? UiState.Result)?.fen

    /** Moves played by hand on the board; what is picked up counts only while the board shows the position it was picked on. */
    private val input = MoveInput({ shownFen }) { from, to, promotion -> playMove(from, to, promotion) }

    /** The square of the picked-up piece on the shown position, or null. */
    val selectedSquare: String? get() = input.selected

    /** Where the picked-up piece may go. */
    val moveTargets: List<MoveTarget> get() = input.targets

    /** The pawn move waiting for a piece to be chosen, or null. */
    val promotion: PromotionAsked? get() = input.promotion

    /** The line deleted last, which Undo brings back, with where the board was. */
    var deletedLine: DeletedLine? by mutableStateOf(null)
        private set

    /** The Result was opened from the library instead of analysed (shown once, then cleared with [noticeShown]). */
    var openedFromLibrary: Boolean by mutableStateOf(false)
        private set

    // Saves wait here, in the order the changes were made, and run one at a time off the main thread.
    private var saveQueue = Channel<() -> Unit>(Channel.UNLIMITED)
    private var saveJob: Job? = null

    /** The id the game on screen has in the library, once it is saved; each game on screen has its own. */
    private class GameHandle {
        @Volatile
        var id: Long? = null
    }

    private var handle = GameHandle()

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

    /** A game that is already saved opens from the library, with its lines and no analysis; any other is analysed. */
    private fun startAnalysis(
        pgn: String,
        game: Game,
    ) {
        val saved = library
        if (saved == null) {
            analyseGame(pgn, game)
            return
        }
        if (job?.isActive == true) return
        job =
            viewModelScope.launch {
                val found = withContext(dispatcher) { runCatching { saved.findSaved(game) }.getOrNull() }
                if (state !is UiState.Paste) return@launch
                if (found != null) openSaved(found, pgn, fromLibrary = true) else analyseGame(pgn, game)
            }
    }

    /** Opens a saved game on the Result screen, at position [ply] (0 is the start), with its lines and their evals; nothing is analysed. */
    fun openGame(
        id: Long,
        ply: Int = 0,
    ) {
        val saved = library ?: return
        if (job?.isActive == true) return
        job =
            viewModelScope.launch {
                val found = withContext(dispatcher) { runCatching { saved.load(id) }.getOrNull() } ?: return@launch
                openSaved(found, found.pgn, fromLibrary = true, ply = ply)
            }
    }

    private fun openSaved(
        saved: SavedGame,
        pgn: String,
        fromLibrary: Boolean,
        ply: Int = 0,
    ) {
        stopLive()
        deletedLine = null
        overlay = null
        handle = GameHandle().also { it.id = saved.id }
        positions?.seed(saved.game, saved.evals)
        val opened =
            UiState.Result(
                pgn,
                saved.game,
                saved.evals,
                analysisSettings = saved.analysisSettings,
                lines = saved.lines,
                lineEvals = saved.lineEvals,
                gameId = saved.id,
                cursor = Cursor.game(ply.coerceIn(0, saved.game.plies.size)),
            )
        state = opened
        openedFromLibrary = fromLibrary
        // a line position whose eval was never found (the app was closed meanwhile) is evaluated now
        saved.lines.all
            .flatMap { it.plies }
            .forEach { evaluateSoon(it.fen) }
        restartLive()
    }

    fun noticeShown() {
        openedFromLibrary = false
    }

    private fun analyseGame(
        pgn: String,
        game: Game,
    ) {
        stopLive()
        deletedLine = null
        handle = GameHandle()
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
                                UiState
                                    .Result(
                                        pgn,
                                        game,
                                        progress.evals,
                                        analysisSettings = analysisSettings,
                                    ).also { saveGame(it, progress.bestMoves) }
                            } else {
                                furthest = maxOf(furthest, progress.done)
                                UiState.Analysing(pgn, game, furthest, progress.total)
                            }
                        if (progress.finished) restartLive()
                    }
            }
    }

    /** Runs [task] after the saves queued before it, off the main thread; a failing save never takes the app down. */
    private fun saveSoon(task: () -> Unit) {
        if (library == null) return
        saveQueue.trySend(task)
        if (saveJob == null) {
            saveJob =
                viewModelScope.launch(dispatcher) {
                    for (queued in saveQueue) runCatching(queued).onFailure { libraryLog("save failed: ${it.message}") }
                }
        }
    }

    /** Saves a game whose analysis has just finished, with its accuracies, and tells the screen its id. */
    private fun saveGame(
        result: UiState.Result,
        bestMoves: List<String?>,
    ) {
        val saved = library ?: return
        val mine = handle
        val report = result.report
        val new =
            NewGame(
                pgn = result.pgn,
                game = result.game,
                evals = result.evals,
                analysisSettings = result.analysisSettings,
                whiteAccuracy = report.white.accuracy,
                blackAccuracy = report.black.accuracy,
                opening = result.game.overview.opening,
                bestMoves = bestMoves,
            )
        saveSoon {
            mine.id = saved.save(new)
            libraryLog("saved game ${mine.id}")
            viewModelScope.launch {
                val now = state as? UiState.Result
                if (handle === mine && now != null) state = now.copy(gameId = mine.id)
            }
        }
    }

    /** Saves the lines of the game on screen and the evals found for them, as they are now. */
    private fun saveLinesSoon() {
        val saved = library ?: return
        val current = state as? UiState.Result ?: return
        val mine = handle
        val lines = current.lines
        val lineEvals = current.lineEvals
        saveSoon {
            mine.id?.let {
                saved.saveLines(it, lines, lineEvals)
                libraryLog("saved ${lines.all.size} lines of game $it")
            }
        }
    }

    fun selectPly(ply: Int) {
        val current = state as? UiState.Result ?: return
        state = current.copy(cursor = Cursor.game(ply.coerceIn(0, current.game.plies.size)))
        restartLive()
    }

    /** A tap on a square of the board (see [MoveInput.tap]). */
    fun tapSquare(square: String) = input.tap(square)

    /** A piece dragged from [from] and dropped on [to]: played when legal; otherwise nothing changes and the piece snaps back. */
    fun dragMove(
        from: String,
        to: String,
    ) = input.drag(from, to)

    /** The piece (`q`, `r`, `b` or `n`) chosen for the waiting promotion. */
    fun choosePromotion(piece: Char) = input.choose(piece)

    fun cancelPromotion() {
        input.cancelPromotion()
    }

    /** Opens the game of the position being practised, at the move that was played there; the practice ends. */
    fun openDrillGame() {
        val item = drill.ui.item ?: return
        drill.close()
        openGame(item.game.gameId, item.ply)
    }

    /** Selects the [move]-th move of [lineId], counting from 1. */
    fun selectLineMove(
        lineId: Int,
        move: Int,
    ) {
        val current = state as? UiState.Result ?: return
        val line = current.lines.find(lineId) ?: return
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
        val cursor = if (current.cursor.lineId == lineId) Cursor.game(line.branchPly) else current.cursor
        state = current.copy(lines = current.lines.delete(lineId), cursor = cursor)
        saveLinesSoon()
        restartLive()
    }

    /** Brings the last deleted line back as it was, with its evals, and the board to where it was if it was on that line. */
    fun undoDelete() {
        val current = state as? UiState.Result ?: return
        val deleted = deletedLine ?: return
        deletedLine = null
        val back = if (deleted.cursorBefore.lineId == deleted.line.id) deleted.cursorBefore else current.cursor
        state = current.copy(lines = current.lines.restore(deleted.line), cursor = back)
        saveLinesSoon()
        restartLive()
    }

    /** The undo offer is over (its time ran out). */
    fun dismissUndo() {
        deletedLine = null
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
        val played = current.lines.play(current.game, current.cursor, ply)
        input.putDown()
        state = current.copy(lines = played.lines, cursor = played.cursor)
        saveLinesSoon()
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
            saveLinesSoon()
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
                        if (found != null) saveLinesSoon()
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

    /** Opens the Settings page over the Result (its cog); the start page has Settings as a tab. */
    fun openSettings() {
        if (state !is UiState.Result) return
        overlay = Overlay.Settings
        refreshDatabases()
        restartLive()
    }

    /** Reads the database files again (name, sizes, games). */
    fun refreshDatabases() {
        val control = databases ?: return
        viewModelScope.launch {
            val (name, files) =
                withContext(dispatcher) { runCatching { control.activeName() to control.files() }.getOrNull() }
                    ?: return@launch
            databaseUi = databaseUi.copy(activeName = name, files = files)
        }
    }

    /** The saves made so far are on disk: what is queued before this has run. */
    private suspend fun savesDone() {
        if (library == null) return
        val done = CompletableDeferred<Unit>()
        saveSoon { done.complete(Unit) }
        done.await()
    }

    private fun databaseAction(action: (DatabaseControl) -> DbOutcome) {
        val control = databases ?: return
        if (databaseUi.busy) return
        viewModelScope.launch {
            databaseUi = databaseUi.copy(busy = true, message = null)
            savesDone()
            val outcome = withContext(dispatcher) { runCatching { action(control) }.getOrElse { DbOutcome.Failed(DbProblem.FAILED) } }
            when (outcome) {
                is DbOutcome.Opened -> {
                    // the game on screen belongs to the database that was left, so it is not saved into the new one
                    handle = GameHandle()
                    deletedGame = null
                    libraryGames = null
                    DbMessage.Opened(outcome.fileName)
                }

                is DbOutcome.Renamed -> {
                    DbMessage.Renamed(outcome.fileName)
                }

                is DbOutcome.Imported -> {
                    DbMessage.Imported(outcome.result.added, outcome.result.skipped)
                }

                is DbOutcome.Exported -> {
                    pendingExport = outcome.file
                    null
                }

                is DbOutcome.Failed -> {
                    DbMessage.Failed(outcome.problem)
                }
            }.let { databaseUi = databaseUi.copy(busy = false, message = it) }
            refreshDatabases()
        }
    }

    fun renameDatabase(input: String) = databaseAction { it.rename(input) }

    fun openDatabaseFile(fileName: String) = databaseAction { it.open(fileName) }

    fun exportDatabase() = databaseAction { it.export() }

    /** The share sheet has the exported copy. */
    fun exportShared() {
        pendingExport = null
    }

    fun openPickedDatabase(
        displayName: String?,
        source: () -> java.io.InputStream?,
    ) = databaseAction { it.openPicked(displayName, source) }

    fun importPickedDatabase(source: () -> java.io.InputStream?) = databaseAction { it.importPicked(source) }

    fun dismissDatabaseMessage() {
        databaseUi = databaseUi.copy(message = null)
    }

    /** Shows [tab] on the start page; the Library's games and the database files are read again when their tab opens. */
    fun selectStartTab(tab: StartTab) {
        if (state !is UiState.Paste) return
        if (startTab == StartTab.Library && tab != StartTab.Library) dismissDeletedGame()
        startTab = tab
        when (tab) {
            StartTab.Library -> {
                if (library == null) return
                deletedGame = null
                refreshLibrary()
            }

            StartTab.Settings -> {
                refreshDatabases()
            }

            StartTab.Report -> {
                report.refresh()
            }

            StartTab.Analyse -> {
                Unit
            }
        }
    }

    private fun refreshLibrary() {
        val saved = library ?: return
        viewModelScope.launch {
            val games = withContext(dispatcher) { runCatching { saved.list() }.getOrDefault(emptyList()) }
            libraryGames = games
        }
    }

    /** Deletes a saved game and keeps it for [undoDeleteGame]. */
    fun deleteSavedGame(id: Long) {
        val saved = library ?: return
        viewModelScope.launch {
            val found = withContext(dispatcher) { runCatching { saved.load(id)?.also { saved.delete(id) } }.getOrNull() } ?: return@launch
            deletedGame = found
            refreshLibrary()
        }
    }

    fun undoDeleteGame() {
        val saved = library ?: return
        val found = deletedGame ?: return
        deletedGame = null
        viewModelScope.launch {
            withContext(dispatcher) { runCatching { saved.restore(found) } }
            refreshLibrary()
        }
    }

    /** The undo offer for the deleted game is over. */
    fun dismissDeletedGame() {
        deletedGame = null
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
        if (search == null || current == null || !engineOn || !foreground || overlay != null) {
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
        if (overlay != null) {
            overlay = null
            restartLive()
            return true
        }
        if (promotion != null && input.cancelPromotion()) return true
        (state as? UiState.Result)?.let { current ->
            if (current.cursor.lineId != null) {
                state = current.copy(cursor = current.lines.leave(current.cursor))
                restartLive()
                return true
            }
        }
        val pgn =
            when (val current = state) {
                is UiState.Analysing -> {
                    current.pgn
                }

                is UiState.Result -> {
                    current.pgn
                }

                is UiState.Paste -> {
                    if (startTab == StartTab.Report && drill.ui.active) {
                        drill.close()
                        return true
                    }
                    if (startTab == StartTab.Report && report.closeList()) return true
                    if (startTab == StartTab.Analyse) return false
                    selectStartTab(StartTab.Analyse)
                    return true
                }
            }
        job?.cancel()
        job = null
        stopLive()
        stopEvaluating()
        deletedLine = null
        openedFromLibrary = false
        handle = GameHandle()
        state = UiState.Paste(pgn = pgn)
        if (startTab == StartTab.Library) refreshLibrary()
        if (startTab == StartTab.Report) report.refresh()
        return true
    }
}

/** A line the user deleted, and where the board was, so Undo can put both back. */
data class DeletedLine(
    val line: Line,
    val cursorBefore: Cursor,
)

/** A page that opens over the current screen: Settings from the Result's cog. */
enum class Overlay { Settings, }

/** The tabs of the start page, left to right. */
enum class StartTab { Analyse, Library, Report, Settings }

/** The Database section of the Settings page: the active file, the files in the folder, and whether something is going on. */
data class DatabaseUi(
    val activeName: String = "",
    val files: List<DbFileInfo> = emptyList(),
    val busy: Boolean = false,
    val message: DbMessage? = null,
)

/** What the last database action said. */
sealed interface DbMessage {
    data class Renamed(
        val fileName: String,
    ) : DbMessage

    data class Opened(
        val fileName: String,
    ) : DbMessage

    data class Imported(
        val added: Int,
        val skipped: Int,
    ) : DbMessage

    data class Failed(
        val problem: DbProblem,
    ) : DbMessage
}
