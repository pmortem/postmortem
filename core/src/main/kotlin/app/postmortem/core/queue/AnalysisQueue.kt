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

/**
 * How many games are analysed together and how many engines there are in all. A game needs an engine of its own, so no more games run
 * together than there are engines; the engines are shared between them, never multiplied (2 games with 4 engines get 2 each, not 4).
 */
data class QueuePlan(
    val gamesAtOnce: Int = 1,
    val engines: Int = 1,
) {
    /** The games that really run together. */
    val concurrent: Int get() = minOf(gamesAtOnce, engines).coerceAtLeast(1)
}

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

/**
 * A game that left the queue: analysed (and saved as [Finished.gameId], or not saved when the library failed, null then), or failed
 * with [Failed.message].
 */
sealed interface QueueEvent {
    val game: QueuedGame

    data class Finished(
        override val game: QueuedGame,
        val gameId: Long?,
        val whiteAccuracy: Double?,
        val blackAccuracy: Double?,
        /** The evals of every position, so a screen waiting for this game can show it at once. */
        val evals: List<app.postmortem.core.analysis.Eval>,
    ) : QueueEvent

    data class Failed(
        override val game: QueuedGame,
        val message: String,
    ) : QueueEvent
}

/**
 * Games waiting to be analysed, in the order they were added, by [analyse]. [plan] says how many run together and how many engines
 * there are in all, asked as each game starts (the evals are the same whatever the number; a change applies from the next game). A finished game is saved to [library] and leaves the queue; so does a
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
    private val plan: () -> QueuePlan,
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

    /** A game being analysed, with the job that does it and the engines it was given. */
    private class Run(
        val job: Job,
        val engines: Int,
    )

    private val runs = HashMap<Long, Run>()

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
                runs[id]?.job?.cancel()
            }
            withContext(dispatcher) { store.removeQueued(id) }
        }
    }

    private suspend fun work() {
        while (true) {
            if (hot.value) {
                // with games waiting and none running, say so and wait for the phone to cool down; with none waiting, just wait for one
                val waiting =
                    lock.withLock {
                        if (runs.isEmpty()) games.value.firstOrNull()?.also { setStateLocked(it.id, QueueState.PAUSED_HOT, 0) } else null
                    }
                if (waiting != null) {
                    hot.first { !it }
                    continue
                }
            }
            val launched = lock.withLock { launchNext() }
            if (!launched) wake.receive()
        }
    }

    /** Starts the next game that is not running, when a slot is free and the phone is not hot; false when nothing was started. */
    private fun launchNext(): Boolean {
        if (hot.value) return false
        val current = plan()
        if (runs.size >= current.concurrent) return false
        val next = games.value.firstOrNull { it.id !in runs } ?: return false
        // what is left of the engines is shared by the free slots, so the last slot takes the remainder
        val engines = ((current.engines - runs.values.sumOf { it.engines }) / (current.concurrent - runs.size)).coerceAtLeast(1)
        val job = scope.launch { runOne(next, engines) }
        runs[next.id] = Run(job, engines)
        job.invokeOnCompletion {
            scope.launch {
                lock.withLock { runs.remove(next.id) }
                wake.trySend(Unit)
            }
        }
        return true
    }

    /** Analyses [queued] to the end and saves it, unless it is cancelled; the heat stops it and it starts again when cool. */
    private suspend fun runOne(
        queued: QueuedGame,
        engines: Int,
    ) {
        withContext(dispatcher) { store.setQueuedState(queued.id, STATE_RUNNING) }
        setState(queued.id, QueueState.RUNNING, 0)
        var last: AnalysisProgress? = null
        var tooHot = false
        val failure =
            try {
                coroutineScope {
                    val job =
                        launch {
                            analyse(queued.game, engines).collect { progress ->
                                last = progress
                                // a report can arrive with an older count than one before it: the count never goes back
                                lock.withLock {
                                    games.update { list ->
                                        list.map {
                                            if (it.id ==
                                                queued.id
                                            ) {
                                                it.copy(state = QueueState.RUNNING, done = maxOf(it.done, progress.done))
                                            } else {
                                                it
                                            }
                                        }
                                    }
                                }
                            }
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
            }
        val stillQueued = lock.withLock { games.value.any { it.id == queued.id } }
        when {
            !stillQueued -> {
                Unit
            }

            tooHot -> {
                withContext(dispatcher) { store.setQueuedState(queued.id, STATE_WAITING) }
                setState(queued.id, QueueState.PAUSED_HOT, 0)
            }

            failure != null || last?.finished != true -> {
                events.emit(QueueEvent.Failed(queued, failure?.message ?: "the analysis stopped"))
                leave(queued)
            }

            else -> {
                val progress = last!!
                val new = NewGame.analysed(queued.pgn, queued.game, progress.evals, progress.bestMoves, analysisSettings)
                // a library that fails costs the save, never the analysis
                val id = runCatching { withContext(dispatcher) { library.save(new) } }.getOrNull()
                // the event goes out before the game leaves the list, so whoever watches the list for the end has heard it
                events.emit(QueueEvent.Finished(queued, id, new.whiteAccuracy, new.blackAccuracy, new.evals))
                leave(queued)
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
    ) = lock.withLock { setStateLocked(id, state, done) }

    private fun setStateLocked(
        id: Long,
        state: QueueState,
        done: Int,
    ) = games.update { list -> list.map { if (it.id == id) it.copy(state = state, done = done) else it } }

    private fun parse(pgn: String): Game? = (PgnParser.parse(pgn) as? PgnResult.Success)?.game

    private companion object {
        const val EVENT_BUFFER = 16
        const val STATE_WAITING = "waiting"
        const val STATE_RUNNING = "running"
    }
}

/** A [QueueStore] that lives in memory only: for a queue that need not survive the app (tests, a screen without a database). */
class InMemoryQueueStore : QueueStore {
    private val rows = ArrayList<QueuedRow>()
    private var next = 1L

    @Synchronized
    override fun queuedRows(): List<QueuedRow> = rows.sortedWith(compareBy({ it.added }, { it.id }))

    @Synchronized
    override fun addQueued(
        pgn: String,
        added: Long,
    ): Long = next++.also { rows += QueuedRow(it, pgn, added, "waiting") }

    @Synchronized
    override fun setQueuedState(
        id: Long,
        state: String,
    ) {
        rows.replaceAll { if (it.id == id) it.copy(state = state) else it }
    }

    @Synchronized
    override fun removeQueued(id: Long) {
        rows.removeAll { it.id == id }
    }
}
