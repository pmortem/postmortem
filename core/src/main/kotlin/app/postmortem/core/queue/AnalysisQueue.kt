package app.postmortem.core.queue

import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import app.postmortem.core.pgn.overview
import app.postmortem.core.store.GameLibrary
import app.postmortem.core.store.NewGame
import app.postmortem.core.store.QueueStore
import app.postmortem.core.store.QueuedRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where a game in the queue is: waiting its turn, being analysed, or held back because the phone is too hot. */
enum class QueueState { WAITING, RUNNING, PAUSED_HOT }

/** A game in the queue, with its players for the notification and the Library, and how far its analysis is ([done] of [total] positions). */
data class QueuedGame(
    val id: Long,
    val pgn: String,
    val game: Game,
    val state: QueueState,
    val done: Int = 0,
    val total: Int = game.positionCount,
) {
    val white: String? get() = game.overview.white
    val black: String? get() = game.overview.black
}

/** What [AnalysisQueue.enqueue] did with a PGN. */
sealed interface Enqueued {
    /** Added to the queue (or it was there already), as [id]. */
    data class Queued(
        val id: Long,
    ) : Enqueued

    /** The game is saved already: nothing is analysed; [gameId] opens it. */
    data class AlreadySaved(
        val gameId: Long,
    ) : Enqueued

    /** Not a game that can be analysed. */
    data object Invalid : Enqueued
}

/** A game that left the queue: saved as [gameId] with its accuracies, or failed with [message]. */
sealed interface QueueEvent {
    val game: QueuedGame

    data class Finished(
        override val game: QueuedGame,
        val gameId: Long,
        val whiteAccuracy: Double?,
        val blackAccuracy: Double?,
    ) : QueueEvent

    data class Failed(
        override val game: QueuedGame,
        val message: String,
    ) : QueueEvent
}

/**
 * Games waiting to be analysed, one at a time in the order they were added, by [analyse] with as many engines as [workers] says when
 * each game starts (the evals are the same whatever the number). A finished game is saved to [library] and leaves the queue; so does a
 * failed or cancelled one. The queue is kept in [store], so after the app was killed [start] picks it up again, a game that was half
 * done from its first position. While [hot] is true nothing is analysed: a running game stops and starts again from its first position
 * when the phone has cooled down.
 */
class AnalysisQueue(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val store: QueueStore,
    private val library: GameLibrary,
    private val analyse: (Game, Int) -> Flow<AnalysisProgress>,
    private val analysisSettings: String,
    private val workers: () -> Int,
    private val hot: StateFlow<Boolean> = MutableStateFlow(false),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val games = MutableStateFlow<List<QueuedGame>>(emptyList())

    /** The games still in the queue, in order: the running (or paused) one first. */
    val progress: StateFlow<List<QueuedGame>> = games.asStateFlow()

    private val events = MutableSharedFlow<QueueEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Each game that leaves the queue by finishing or failing (a cancelled one says nothing). */
    val finished: SharedFlow<QueueEvent> = events.asSharedFlow()

    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null
    private var running: Job? = null
    private var runningId: Long? = null

    /** Reads the queue back from [store] (a game that was running waits again) and starts working through it. Once is enough. */
    fun start() {
        if (loop != null) return
        loop =
            scope.launch {
                val rows = withContext(dispatcher) { store.queuedRows() }
                val waiting =
                    rows.mapNotNull { row ->
                        parse(row.pgn)?.let { QueuedGame(row.id, row.pgn, it, QueueState.WAITING) }
                            ?: dropBroken(row)
                    }
                lock.withLock { games.value = waiting + games.value.filter { queued -> waiting.none { it.id == queued.id } } }
                work()
            }
    }

    private suspend fun dropBroken(row: QueuedRow): QueuedGame? {
        withContext(dispatcher) { store.removeQueued(row.id) }
        return null
    }

    /** Adds the game of [pgn] at the end of the queue, unless it is saved already or queued already (then its place is kept). */
    suspend fun enqueue(pgn: String): Enqueued {
        val game = parse(pgn) ?: return Enqueued.Invalid
        val saved = withContext(dispatcher) { runCatching { library.findSaved(game) }.getOrNull() }
        if (saved != null) return Enqueued.AlreadySaved(saved.id)
        return lock.withLock {
            games.value.firstOrNull { it.game == game }?.let { return@withLock Enqueued.Queued(it.id) }
            val id = withContext(dispatcher) { store.addQueued(pgn, clock()) }
            games.update { it + QueuedGame(id, pgn, game, QueueState.WAITING) }
            wake.trySend(Unit)
            Enqueued.Queued(id)
        }
    }

    /** Takes [id] out of the queue; when it is the one running, its analysis stops. */
    fun cancel(id: Long) {
        scope.launch {
            lock.withLock {
                games.update { list -> list.filter { it.id != id } }
                if (runningId == id) running?.cancel()
            }
            withContext(dispatcher) { store.removeQueued(id) }
        }
    }

    private suspend fun work() {
        while (true) {
            val next = lock.withLock { games.value.firstOrNull() }
            if (next == null) {
                wake.receive()
                continue
            }
            if (hot.value) {
                setState(next.id, QueueState.PAUSED_HOT, 0)
                hot.first { !it }
            }
            runOne(next)
        }
    }

    /** Analyses [queued] to the end and saves it, unless it is cancelled; the heat stops it and it starts again when cool. */
    private suspend fun runOne(queued: QueuedGame) {
        withContext(dispatcher) { store.setQueuedState(queued.id, STATE_RUNNING) }
        setState(queued.id, QueueState.RUNNING, 0)
        var last: AnalysisProgress? = null
        var tooHot = false
        val failure =
            try {
                coroutineScope {
                    val job =
                        launch {
                            analyse(queued.game, workers()).collect { progress ->
                                last = progress
                                setState(queued.id, QueueState.RUNNING, progress.done)
                            }
                        }
                    lock.withLock {
                        running = job
                        runningId = queued.id
                    }
                    val watch =
                        launch {
                            hot.first { it }
                            tooHot = true
                            job.cancel()
                        }
                    job.join()
                    watch.cancel()
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            } finally {
                lock.withLock {
                    running = null
                    runningId = null
                }
            }
        val stillQueued = lock.withLock { games.value.any { it.id == queued.id } }
        when {
            !stillQueued -> {
                Unit
            }

            tooHot -> {
                withContext(dispatcher) { store.setQueuedState(queued.id, STATE_WAITING) }
                setState(queued.id, QueueState.PAUSED_HOT, 0)
                hot.first { !it }
            }

            failure != null || last?.finished != true -> {
                leave(queued)
                events.emit(QueueEvent.Failed(queued, failure?.message ?: "the analysis stopped"))
            }

            else -> {
                val progress = last!!
                val saved =
                    runCatching {
                        withContext(dispatcher) {
                            val new = NewGame.analysed(queued.pgn, queued.game, progress.evals, progress.bestMoves, analysisSettings)
                            new to library.save(new)
                        }
                    }
                leave(queued)
                saved
                    .onSuccess { (new, id) -> events.emit(QueueEvent.Finished(queued, id, new.whiteAccuracy, new.blackAccuracy)) }
                    .onFailure { events.emit(QueueEvent.Failed(queued, it.message ?: "the game could not be saved")) }
            }
        }
    }

    private suspend fun leave(queued: QueuedGame) {
        lock.withLock { games.update { list -> list.filter { it.id != queued.id } } }
        withContext(dispatcher) { store.removeQueued(queued.id) }
    }

    private suspend fun setState(
        id: Long,
        state: QueueState,
        done: Int,
    ) = lock.withLock { games.update { list -> list.map { if (it.id == id) it.copy(state = state, done = done) else it } } }

    private fun parse(pgn: String): Game? = (PgnParser.parse(pgn) as? PgnResult.Success)?.game

    private companion object {
        const val EVENT_BUFFER = 16
        const val STATE_WAITING = "waiting"
        const val STATE_RUNNING = "running"
    }
}
