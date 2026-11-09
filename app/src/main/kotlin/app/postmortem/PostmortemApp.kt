package app.postmortem

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.postmortem.core.analysis.Analyser
import app.postmortem.core.analysis.AnalysisConfig
import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.format
import app.postmortem.core.pgn.Game
import app.postmortem.core.queue.AnalysisQueue
import app.postmortem.data.ActiveDatabaseName
import app.postmortem.data.DatabaseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val ANALYSIS_TAG = "Analysis"

/**
 * What lives as long as the app's process, not as long as a screen: the database and the background analysis queue. Games keep being
 * analysed when the screen is gone; [AnalysisService] keeps the process alive meanwhile, with a notification.
 */
class PostmortemApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val config: AnalysisConfig by lazy { AnalysisConfig.forCores(Runtime.getRuntime().availableProcessors()) }

    /** The one database manager of the app: the screens and the queue share it, and its lock. */
    val databases: DatabaseManager by lazy { DatabaseManager(this, ActiveDatabaseName(this)) }

    /** Postmortem is on screen: games are analysed at full speed then, gently otherwise. */
    val onScreen = MutableStateFlow(false)

    /** Android's daily limit for background work was reached: the queue waits until the app is opened again. */
    val held = MutableStateFlow(false)

    /** The phone is too hot (Android's thermal status is severe or worse): the queue waits until it cools down. */
    private val hot = MutableStateFlow(false)

    /** A game was being analysed while Postmortem was not on screen: the moment to ask for notifications has come. */
    @Volatile
    var analysedInBackground = false

    val analysisSettings: String get() = config.describe(BuildConfig.ENGINE_NAME)

    val queue: AnalysisQueue by lazy {
        AnalysisQueue(
            scope = scope,
            dispatcher = Dispatchers.IO,
            store = databases,
            library = databases,
            analyse = ::analyse,
            analysisSettings = analysisSettings,
            workers = { if (onScreen.value) config.workers else minOf(AnalysisConfig.BACKGROUND_WORKERS, config.workers) },
            hot = combine(hot, held) { tooHot, limited -> tooHot || limited }.stateIn(scope, SharingStarted.Eagerly, false),
        )
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()
        watchHeat()
    }

    /**
     * Starts the queue (once): it reads back what was waiting when the app was killed, and from then on the service runs whenever a game
     * waits. Called by the screen after the debug build has had its say about the data, and by the service.
     */
    fun startQueue() {
        if (started) return
        started = true
        queue.start()
        Notifications.channels(this)
        scope.launch {
            queue.progress.collect { games ->
                if (games.isEmpty()) return@collect
                if (!onScreen.value) analysedInBackground = true
                startService()
            }
        }
        scope.launch { queue.finished.collect { Notifications.finished(this@PostmortemApp, it) } }
    }

    /** Starts [AnalysisService]; Android may refuse it while the app is in the background, and then it starts with the app. */
    fun startService() {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, AnalysisService::class.java)) }
            .onFailure { Log.i(ANALYSIS_TAG, "service not started: ${it.message}") }
    }

    private fun analyse(
        game: Game,
        workers: Int,
    ): Flow<AnalysisProgress> {
        var begun = 0L
        val analyser =
            Analyser(config.copy(workers = workers), EngineLocator.factory(this)) { result ->
                Log.i(ANALYSIS_TAG, "search: ${result.nodes} nodes in ${result.timeMs} ms, ${result.nodesPerSecond} nodes/s")
            }
        return analyser
            .analyse(game)
            .onStart { begun = SystemClock.elapsedRealtime() }
            .onEach { if (it.finished) Log.i(ANALYSIS_TAG, "evals: " + it.evals.joinToString(" ") { e -> e.format() }) }
            .onCompletion {
                Log.i(
                    ANALYSIS_TAG,
                    "analysis of ${game.positionCount} positions took ${SystemClock.elapsedRealtime() - begun} ms with $workers workers",
                )
            }
    }

    private fun watchHeat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val power = getSystemService(PowerManager::class.java) ?: return
        hot.value = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        power.addThermalStatusListener(ContextCompat.getMainExecutor(this)) { status ->
            hot.value = status >= PowerManager.THERMAL_STATUS_SEVERE
        }
    }
}
