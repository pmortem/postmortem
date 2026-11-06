package app.postmortem

import android.content.ClipboardManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.postmortem.core.analysis.Analyser
import app.postmortem.core.analysis.AnalysisConfig
import app.postmortem.core.analysis.LiveConfig
import app.postmortem.core.analysis.LiveSearch
import app.postmortem.core.analysis.PositionEvaluator
import app.postmortem.core.analysis.format
import app.postmortem.core.board.Moves
import app.postmortem.ui.AnalysingScreen
import app.postmortem.ui.BoardInput
import app.postmortem.ui.PasteScreen
import app.postmortem.ui.PromotionPrompt
import app.postmortem.ui.ResultScreen
import app.postmortem.ui.SettingsScreen
import app.postmortem.ui.SplashScreen
import app.postmortem.ui.theme.PostmortemTheme
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

private const val ANALYSIS_TAG = "Analysis"

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val config = AnalysisConfig.forCores(Runtime.getRuntime().availableProcessors())
                val analyser =
                    Analyser(config, EngineLocator.factory(applicationContext)) { result ->
                        Log.i(ANALYSIS_TAG, "search: ${result.nodes} nodes in ${result.timeMs} ms, ${result.nodesPerSecond} nodes/s")
                    }
                val positions =
                    PositionEvaluator(config, EngineLocator.factory(applicationContext)) { result ->
                        Log.i(ANALYSIS_TAG, "line search: ${result.nodes} nodes in ${result.timeMs} ms, ${result.nodesPerSecond} nodes/s")
                    }
                MainViewModel(
                    positions = positions,
                    live = LiveSearch(LiveConfig(), EngineLocator.factory(applicationContext)),
                    arrowsSetting = DataStoreEngineArrowsSetting(applicationContext),
                    analysisSettings = config.describe(BuildConfig.ENGINE_NAME),
                    analysis = { game ->
                        var started = 0L
                        analyser
                            .analyse(game)
                            .onStart { started = SystemClock.elapsedRealtime() }
                            .onEach { if (it.finished) Log.i(ANALYSIS_TAG, "evals: " + it.evals.joinToString(" ") { e -> e.format() }) }
                            .onCompletion {
                                Log.i(
                                    ANALYSIS_TAG,
                                    "analysis of ${game.positionCount} positions took ${SystemClock.elapsedRealtime() - started} ms with ${config.workers} workers",
                                )
                            }
                    },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) DebugInput.pgn(intent)?.let(viewModel::onPgnChange)
        val coldStart = savedInstanceState == null
        setContent {
            var showSplash by rememberSaveable { mutableStateOf(coldStart) }
            PostmortemTheme {
                if (showSplash) {
                    SplashScreen(onFinished = { showSplash = false })
                    return@PostmortemTheme
                }
                when (val state = viewModel.state) {
                    is UiState.Paste -> {
                        PasteScreen(state = state, onPgnChange = viewModel::onPgnChange, onAnalyse = viewModel::analyse)
                    }

                    is UiState.Analysing -> {
                        BackHandler { viewModel.back() }
                        AnalysingScreen(state)
                    }

                    is UiState.Result -> {
                        BackHandler { viewModel.back() }
                        if (viewModel.settingsOpen) {
                            SettingsScreen(onBack = { viewModel.back() })
                            return@PostmortemTheme
                        }
                        ResultScreen(
                            state = state,
                            onSelect = viewModel::selectPly,
                            onStep = viewModel::step,
                            onTab = viewModel::selectTab,
                            onFlip = viewModel::flip,
                            live = viewModel.liveUpdate,
                            engineOn = viewModel.engineOn,
                            onToggleEngine = { viewModel.showEngineArrows(!viewModel.engineOn) },
                            onSettings = viewModel::openSettings,
                            onSelectLineMove = viewModel::selectLineMove,
                            boardInput =
                                BoardInput(
                                    selected = viewModel.selectedSquare,
                                    targets = viewModel.moveTargets,
                                    promotion =
                                        viewModel.promotion?.let {
                                            PromotionPrompt(
                                                square = it.to,
                                                color = Moves.sideToMove(state.fen),
                                                onChoose = viewModel::choosePromotion,
                                                onCancel = viewModel::cancelPromotion,
                                            )
                                        },
                                    onTap = viewModel::tapSquare,
                                    onDrag = viewModel::dragMove,
                                ),
                        )
                    }
                }
            }
        }
    }

    /** The live engine runs only while the app is on screen: it stops when the app goes to the background or the screen turns off. */
    override fun onStart() {
        super.onStart()
        viewModel.onForeground(true)
    }

    override fun onStop() {
        viewModel.onForeground(false)
        super.onStop()
    }

    /** Android only lets the focused app read the clipboard, so this is the first moment we can. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text =
            clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
        viewModel.onClipboard(text)
    }
}
