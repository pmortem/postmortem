package app.postmortem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import app.postmortem.core.pgn.looksLikePgn

class MainViewModel : ViewModel() {
    var state: UiState by mutableStateOf(UiState.Paste())
        private set

    private var clipboardOffered = false

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
        state = when (val result = PgnParser.parse(current.pgn)) {
            is PgnResult.Success -> UiState.Result(current.pgn, result.game)
            is PgnResult.Failure -> current.copy(error = result.error)
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

    /** Back from the result returns to the paste page with the game still in the box. */
    fun back(): Boolean {
        val current = state as? UiState.Result ?: return false
        state = UiState.Paste(pgn = current.pgn)
        return true
    }
}
