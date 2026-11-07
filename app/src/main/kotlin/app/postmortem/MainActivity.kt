package app.postmortem

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
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
import app.postmortem.data.ActiveDatabaseName
import app.postmortem.data.DatabaseManager
import app.postmortem.ui.AnalysingScreen
import app.postmortem.ui.BoardInput
import app.postmortem.ui.DrillScreen
import app.postmortem.ui.LibraryScreen
import app.postmortem.ui.LineActions
import app.postmortem.ui.PasteScreen
import app.postmortem.ui.PromotionPrompt
import app.postmortem.ui.ReportScreen
import app.postmortem.ui.ResultScreen
import app.postmortem.ui.SettingsActions
import app.postmortem.ui.SettingsScreen
import app.postmortem.ui.SplashScreen
import app.postmortem.ui.StartScreen
import app.postmortem.ui.theme.PostmortemTheme
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import java.io.File

private const val ANALYSIS_TAG = "Analysis"
private const val LIBRARY_TAG = "Library"
private const val SQLITE_MIME_TYPE = "application/vnd.sqlite3"
private val ANY_FILE = arrayOf("*/*")

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val config = AnalysisConfig.forCores(Runtime.getRuntime().availableProcessors())
                val analyser =
                    Analyser(config, EngineLocator.factory(applicationContext)) { result ->
                        Log.i(ANALYSIS_TAG, "search: ${result.nodes} nodes in ${result.timeMs} ms, ${result.nodesPerSecond} nodes/s")
                    }
                val databases = DatabaseManager(applicationContext, ActiveDatabaseName(applicationContext))
                val positions =
                    PositionEvaluator(config, EngineLocator.factory(applicationContext)) { result ->
                        Log.i(ANALYSIS_TAG, "line search: ${result.nodes} nodes in ${result.timeMs} ms, ${result.nodesPerSecond} nodes/s")
                    }
                MainViewModel(
                    positions = positions,
                    library = databases,
                    databases = databases,
                    libraryLog = { Log.i(LIBRARY_TAG, it) },
                    live = LiveSearch(LiveConfig(), EngineLocator.factory(applicationContext)),
                    arrowsSetting = DataStoreEngineArrowsSetting(applicationContext),
                    reportPlayer = DataStoreReportPlayerSetting(applicationContext),
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

    /** The file picker, used twice: to open a database as the active one, and to merge one into it. It needs no permission. */
    private val browseDatabase =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.openPickedDatabase(displayName(it)) { contentResolver.openInputStream(it) } }
        }
    private val importDatabase =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.importPickedDatabase { contentResolver.openInputStream(it) } }
        }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /** Hands the exported copy to the share sheet (OneDrive, Drive, Files, mail, ...). */
    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(SQLITE_MIME_TYPE)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.db_share_title)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && DebugInput.pgn(intent) != null) DebugInput.resetData(this)
        if (savedInstanceState == null) DebugInput.pgn(intent)?.let(viewModel::onPgnChange)
        val coldStart = savedInstanceState == null
        setContent {
            var showSplash by rememberSaveable { mutableStateOf(coldStart) }
            val pending = viewModel.pendingExport
            LaunchedEffect(pending) {
                if (pending != null) {
                    viewModel.exportShared()
                    share(pending)
                }
            }
            PostmortemTheme {
                if (showSplash) {
                    SplashScreen(onFinished = { showSplash = false })
                    return@PostmortemTheme
                }
                val databaseActions =
                    SettingsActions(
                        onRename = viewModel::renameDatabase,
                        onExport = viewModel::exportDatabase,
                        onOpenFile = viewModel::openDatabaseFile,
                        onBrowse = { browseDatabase.launch(ANY_FILE) },
                        onImport = { importDatabase.launch(ANY_FILE) },
                    )
                BackHandler(enabled = viewModel.overlay != null) { viewModel.back() }
                if (viewModel.overlay == Overlay.Settings) {
                    SettingsScreen(database = viewModel.databaseUi, actions = databaseActions, onBack = { viewModel.back() })
                    return@PostmortemTheme
                }
                when (val state = viewModel.state) {
                    is UiState.Paste -> {
                        BackHandler(enabled = viewModel.startTab != StartTab.Analyse) { viewModel.back() }
                        StartScreen(selected = viewModel.startTab, onSelect = viewModel::selectStartTab) { tab ->
                            when (tab) {
                                StartTab.Analyse -> {
                                    PasteScreen(state = state, onPgnChange = viewModel::onPgnChange, onAnalyse = viewModel::analyse)
                                }

                                StartTab.Library -> {
                                    LibraryScreen(
                                        games = viewModel.libraryGames,
                                        deletedOffered = viewModel.deletedGame != null,
                                        onOpen = viewModel::openGame,
                                        onDelete = viewModel::deleteSavedGame,
                                        onUndo = viewModel::undoDeleteGame,
                                        onUndoExpired = viewModel::dismissDeletedGame,
                                    )
                                }

                                StartTab.Report -> {
                                    if (viewModel.drill.ui.active) {
                                        DrillScreen(viewModel.drill, onOpenGame = viewModel::openDrillGame)
                                    } else {
                                        ReportScreen(
                                            viewModel.report,
                                            onOpenGame = { id, ply -> viewModel.openGame(id, ply) },
                                            onPractise = { viewModel.drill.start() },
                                        )
                                    }
                                }

                                StartTab.Settings -> {
                                    SettingsScreen(
                                        database = viewModel.databaseUi,
                                        actions = databaseActions.copy(onLibrary = { viewModel.selectStartTab(StartTab.Library) }),
                                    )
                                }
                            }
                        }
                    }

                    is UiState.Analysing -> {
                        BackHandler { viewModel.back() }
                        AnalysingScreen(state)
                    }

                    is UiState.Result -> {
                        BackHandler { viewModel.back() }
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
                            openedFromLibrary = viewModel.openedFromLibrary,
                            onNoticeShown = viewModel::noticeShown,
                            lineActions =
                                LineActions(
                                    deleted = viewModel.deletedLine,
                                    onSelectMove = viewModel::selectLineMove,
                                    onSelectLine = viewModel::selectLine,
                                    onDelete = viewModel::deleteLine,
                                    onUndo = viewModel::undoDelete,
                                    onUndoExpired = viewModel::dismissUndo,
                                ),
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
