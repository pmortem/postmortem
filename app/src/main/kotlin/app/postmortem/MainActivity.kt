package app.postmortem

import android.content.ClipboardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.postmortem.core.analysis.AnalysisConfig
import app.postmortem.core.analysis.Analyser
import app.postmortem.ui.AnalysingScreen
import app.postmortem.ui.PasteScreen
import app.postmortem.ui.ResultScreen
import app.postmortem.ui.theme.PostmortemTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val config = AnalysisConfig.forCores(Runtime.getRuntime().availableProcessors())
                val analyser = Analyser(config, EngineLocator.factory(applicationContext))
                MainViewModel(analysis = analyser::analyse)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) DebugInput.pgn(intent)?.let(viewModel::onPgnChange)
        setContent {
            PostmortemTheme {
                when (val state = viewModel.state) {
                    is UiState.Paste ->
                        PasteScreen(state = state, onPgnChange = viewModel::onPgnChange, onAnalyse = viewModel::analyse)
                    is UiState.Analysing -> {
                        BackHandler { viewModel.back() }
                        AnalysingScreen(state)
                    }
                    is UiState.Result -> {
                        BackHandler { viewModel.back() }
                        ResultScreen(state = state, onSelect = viewModel::selectPly, onStep = viewModel::step)
                    }
                }
            }
        }
    }

    /** Android only lets the focused app read the clipboard, so this is the first moment we can. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        viewModel.onClipboard(text)
    }
}
