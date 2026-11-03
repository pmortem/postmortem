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
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import app.postmortem.core.pgn.looksLikePgn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** [analysis] evaluates a game; it runs on [dispatcher] and is cancelled with the screen or on Back. */
class MainViewModel(
    private val analysis: (Game) -> Flow<AnalysisProgress>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** How the analysis is made, kept with each result. */
    private val analysisSettings: String = "",
    /** The live engine that shows the best moves of the shown position; none in tests that do not need it. */
    private val live: LiveLines? = null,
    private val arrowsSetting: EngineArrowsSetting = InMemoryEngineArrowsSetting(),
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

    private var liveJob: Job? = null
    private var foreground = true

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
        state = current.copy(selectedPly = ply.coerceIn(0, current.game.plies.size))
        restartLive()
    }

    fun showEngineArrows(on: Boolean) {
        viewModelScope.launch { arrowsSetting.set(on) }
    }

    /** The app is on screen or not. In the background nothing is searched and the engine process ends. */
    fun onForeground(visible: Boolean) {
        foreground = visible
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
        if (search == null || current == null || !engineOn || !foreground) {
            stopLive()
            return
        }
        val fen = current.game.fenAt(current.selectedPly)
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
        selectPly(current.selectedPly + delta)
    }

    /** Back stops a running analysis, or leaves the result; either way the game stays in the paste box. */
    fun back(): Boolean {
        val pgn =
            when (val current = state) {
                is UiState.Analysing -> current.pgn
                is UiState.Result -> current.pgn
                is UiState.Paste -> return false
            }
        job?.cancel()
        job = null
        stopLive()
        state = UiState.Paste(pgn = pgn)
        return true
    }
}
