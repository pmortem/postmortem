package app.postmortem

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
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
private const val PREFS = "app"
private const val NOTIFICATIONS_ASKED = "notifications_asked"
private const val LIBRARY_TAG = "Library"
private const val SQLITE_MIME_TYPE = "application/vnd.sqlite3"
private val ANY_FILE = arrayOf("*/*")

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as PostmortemApp
                app.startQueue()
                val positions =
                    PositionEvaluator(app.config, EngineLocator.factory(applicationContext)) { result ->
                        Log.i(ANALYSIS_TAG, "line search: ${result.nodes} nodes in ${result.timeMs} ms, ${result.nodesPerSecond} nodes/s")
                    }
                MainViewModel(
                    positions = positions,
                    library = app.databases,
                    databases = app.databases,
                    libraryLog = { Log.i(LIBRARY_TAG, it) },
                    live = LiveSearch(LiveConfig(), EngineLocator.factory(applicationContext)),
                    arrowsSetting = DataStoreEngineArrowsSetting(applicationContext),
                    reportPlayer = DataStoreReportPlayerSetting(applicationContext),
                    analysisSettings = app.analysisSettings,
                    sharedQueue = app.queue,
                )
            }
        }
    }

    private val app: PostmortemApp get() = application as PostmortemApp

    /** Asks once for notifications, after a game was analysed while the app was in the background (never at launch). */
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun maybeAskForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !app.analysedInBackground) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(NOTIFICATIONS_ASKED, false)) return
        prefs.edit().putBoolean(NOTIFICATIONS_ASKED, true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** A tap on a finished game's notification opens that game. */
    private fun openFrom(intent: Intent?) {
        val id = intent?.getLongExtra(Notifications.EXTRA_OPEN_GAME, -1L) ?: -1L
        if (id > 0) {
            intent?.removeExtra(Notifications.EXTRA_OPEN_GAME)
            viewModel.openGame(id)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
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
        if (savedInstanceState == null) openFrom(intent)
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
                                        queued = viewModel.queued.collectAsState().value,
                                        onCancelQueued = viewModel::cancelQueued,
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
        app.onScreen.value = true
        // opening the app lifts Android's daily hold on background work, and the queue goes on
        if (app.held.value) {
            app.held.value = false
            if (app.queue.progress.value
                    .isNotEmpty()
            ) {
                app.startService()
            }
        }
        maybeAskForNotifications()
    }

    override fun onStop() {
        viewModel.onForeground(false)
        app.onScreen.value = false
        if (app.queue.progress.value
                .isNotEmpty()
        ) {
            app.analysedInBackground = true
        }
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
