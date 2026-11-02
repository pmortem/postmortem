package app.postmortem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import app.postmortem.core.pgn.looksLikePgn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** [analysis] evaluates a game; it runs on [dispatcher] and is cancelled with the screen or on Back. */
class MainViewModel(
    private val analysis: (Game) -> Flow<AnalysisProgress>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    var state: UiState by mutableStateOf(UiState.Paste())
        private set

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

    private fun startAnalysis(pgn: String, game: Game) {
        state = UiState.Analysing(pgn, game, done = 0, total = game.positionCount)
        job = viewModelScope.launch {
            analysis(game)
                .flowOn(dispatcher)
                .catch { state = UiState.Paste(pgn = pgn, engineFailed = true) }
                .collect { progress ->
                    state = if (progress.finished) UiState.Result(pgn, game, progress.evals)
                    else UiState.Analysing(pgn, game, progress.done, progress.total)
                }
        }
    }

    fun selectPly(ply: Int) {
        val current = state as? UiState.Result ?: return
        state = current.copy(selectedPly = ply.coerceIn(0, current.game.plies.size))
    }

    fun step(delta: Int) {
        val current = state as? UiState.Result ?: return
        selectPly(current.selectedPly + delta)
    }

    /** Back stops a running analysis, or leaves the result; either way the game stays in the paste box. */
    fun back(): Boolean {
        val pgn = when (val current = state) {
            is UiState.Analysing -> current.pgn
            is UiState.Result -> current.pgn
            is UiState.Paste -> return false
        }
        job?.cancel()
        job = null
        state = UiState.Paste(pgn = pgn)
        return true
    }
}
