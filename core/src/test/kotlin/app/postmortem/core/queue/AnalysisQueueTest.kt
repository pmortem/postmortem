package app.postmortem.core.queue

import app.postmortem.core.analysis.Analyser
import app.postmortem.core.analysis.AnalysisConfig
import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.Eval
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.pgn.Game
import app.postmortem.core.store.GameStore
import app.postmortem.core.store.JdbcSqlDb
import app.postmortem.core.store.NewGame
import app.postmortem.core.uci.EngineBinary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AnalysisQueueTest {
    private val db = JdbcSqlDb.open()
    private val store = GameStore(db)

    @AfterTest
    fun close() = db.close()

    private val scholars = Fixtures.text("scholars-mate")
    private val fools = Fixtures.text("fools-mate")
    private val short = Fixtures.text("lichess-short-resign")

    /** A fake engine: each game's analysis waits for its gate (open by default), then gives 0.00 for every position. */
    private inner class FakeAnalysis {
        val started = ArrayList<Pair<Game, Int>>()
        val gates = HashMap<Game, CompletableDeferred<Unit>>()
        var failFor: Game? = null

        fun gate(game: Game) = gates.getOrPut(game) { CompletableDeferred() }

        fun open(pgn: String) = gate(Fixtures.game(nameOf(pgn))).complete(Unit)

        fun analyse(
            game: Game,
            workers: Int,
        ): Flow<AnalysisProgress> =
            flow {
                started += game to workers
                emit(AnalysisProgress(0, game.positionCount, emptyList()))
                gate(game).await()
                if (game == failFor) error("engine broke")
                val evals = List(game.positionCount) { Eval.Cp(0) }
                emit(AnalysisProgress(game.positionCount, game.positionCount, evals, List(game.positionCount) { null }))
            }
    }

    private fun nameOf(pgn: String) =
        when (pgn) {
            scholars -> "scholars-mate"
            fools -> "fools-mate"
            else -> "lichess-short-resign"
        }

    private fun TestScope.queue(
        fake: FakeAnalysis,
        hot: MutableStateFlow<Boolean> = MutableStateFlow(false),
        workers: Int = 2,
        scope: CoroutineScope = backgroundScope,
    ): AnalysisQueue {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return AnalysisQueue(scope, dispatcher, store, store, fake::analyse, "test", { workers }, hot) { testScheduler.currentTime }
    }

    @Test
    fun gamesAreAnalysedOneAtATimeInTheOrderTheyWereAddedAndSavedWithTheirAccuracies() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeAnalysis()
            val queue = queue(fake)
            val events = ArrayList<QueueEvent>()
            backgroundScope.launch { queue.finished.collect { events += it } }
            queue.start()
            val a = assertIs<Enqueued.Queued>(queue.enqueue(scholars)).id
            val b = assertIs<Enqueued.Queued>(queue.enqueue(fools)).id
            assertEquals(listOf(QueueState.RUNNING, QueueState.WAITING), queue.progress.value.map { it.state })
            assertEquals(1, fake.started.size, "only the first game runs")
            assertEquals(listOf(a, b), store.queuedRows().map { it.id })
            fake.open(scholars)
            assertEquals(listOf(b), queue.progress.value.map { it.id })
            assertEquals(2, fake.started.size)
            fake.open(fools)
            assertTrue(queue.progress.value.isEmpty())
            assertEquals(listOf(a, b), events.map { it.game.id })
            val finished = assertIs<QueueEvent.Finished>(events.first())
            val saved = store.load(finished.gameId)!!
            assertEquals(Fixtures.game("scholars-mate"), saved.game)
            val expected = NewGame.analysed(scholars, saved.game, saved.evals, saved.bestMoves, "test")
            assertEquals(expected.whiteAccuracy, finished.whiteAccuracy)
            assertEquals(expected.whiteAccuracy, saved.whiteAccuracy)
            assertTrue(store.queuedRows().isEmpty(), "finished games leave the table")
        }

    @Test
    fun aSavedGameIsNotQueuedAQueuedOneKeepsItsPlaceAndNonsenseIsRefused() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeAnalysis()
            val queue = queue(fake)
            queue.start()
            val savedId =
                store.save(
                    NewGame(
                        short,
                        Fixtures.game(
                            "lichess-short-resign",
                        ),
                        List(Fixtures.game("lichess-short-resign").positionCount) {
                            Eval.Cp(0)
                        },
                        "t",
                    ),
                )
            assertEquals(Enqueued.AlreadySaved(savedId), queue.enqueue(short))
            val first = queue.enqueue(scholars)
            assertEquals(first, queue.enqueue(scholars), "already queued")
            assertEquals(Enqueued.Invalid, queue.enqueue("this is not a game"))
            assertEquals(1, queue.progress.value.size)
        }

    @Test
    fun aWaitingGameAndTheRunningOneCanBeCancelledAndNothingIsSavedForThem() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeAnalysis()
            val queue = queue(fake)
            val events = ArrayList<QueueEvent>()
            backgroundScope.launch { queue.finished.collect { events += it } }
            queue.start()
            val a = (queue.enqueue(scholars) as Enqueued.Queued).id
            val b = (queue.enqueue(fools) as Enqueued.Queued).id
            queue.enqueue(short)
            queue.cancel(b)
            assertEquals(2, queue.progress.value.size)
            queue.cancel(a)
            assertEquals(2, fake.started.size, "the next game started at once")
            assertEquals(
                listOf("lichess-short-resign"),
                queue.progress.value.map {
                    Fixtures.names.first { n ->
                        Fixtures.text(n) == it.pgn
                    }
                },
            )
            fake.open(short)
            assertEquals(1, events.size)
            assertEquals(1, store.list().size, "only the game that was not cancelled is saved")
            assertTrue(store.queuedRows().isEmpty())
        }

    @Test
    fun afterTheAppWasKilledTheQueueCarriesOnAndAHalfDoneGameStartsAgainFromTheStart() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeAnalysis()
            val killed = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
            val before = queue(fake, scope = killed)
            before.start()
            before.enqueue(scholars)
            before.enqueue(fools)
            assertEquals(listOf("running", "waiting"), store.queuedRows().map { it.state })
            killed.cancel()
            val after = queue(fake)
            after.start()
            assertEquals(2, after.progress.value.size)
            assertEquals(
                listOf(Fixtures.game("scholars-mate"), Fixtures.game("scholars-mate")),
                fake.started.map {
                    it.first
                },
                "the half-done game started again",
            )
            fake.open(scholars)
            fake.open(fools)
            assertTrue(after.progress.value.isEmpty())
            assertEquals(2, store.list().size)
        }

    @Test
    fun nothingRunsWhileThePhoneIsHotAndARunningGameStartsAgainWhenItHasCooledDown() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeAnalysis()
            val hot = MutableStateFlow(true)
            val queue = queue(fake, hot)
            queue.start()
            queue.enqueue(scholars)
            assertEquals(
                QueueState.PAUSED_HOT,
                queue.progress.value
                    .single()
                    .state,
            )
            assertTrue(fake.started.isEmpty())
            hot.value = false
            assertEquals(
                QueueState.RUNNING,
                queue.progress.value
                    .single()
                    .state,
            )
            hot.value = true
            assertEquals(
                QueueState.PAUSED_HOT,
                queue.progress.value
                    .single()
                    .state,
            )
            assertEquals(
                0,
                queue.progress.value
                    .single()
                    .done,
            )
            hot.value = false
            assertEquals(2, fake.started.size, "started again from the first position")
            fake.open(scholars)
            assertTrue(queue.progress.value.isEmpty())
            assertEquals(1, store.list().size)
        }

    @Test
    fun aGameWhoseAnalysisFailsLeavesTheQueueWithAMessageAndTheNextOneRuns() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeAnalysis().apply { failFor = Fixtures.game("scholars-mate") }
            val queue = queue(fake)
            val events = ArrayList<QueueEvent>()
            backgroundScope.launch { queue.finished.collect { events += it } }
            queue.start()
            queue.enqueue(scholars)
            queue.enqueue(fools)
            fake.open(scholars)
            assertIs<QueueEvent.Failed>(events.single())
            assertEquals("engine broke", (events.single() as QueueEvent.Failed).message)
            fake.open(fools)
            assertIs<QueueEvent.Finished>(events.last())
            assertEquals(1, store.list().size)
        }

    @Test
    fun theNumberOfEnginesIsAskedWhenEachGameStarts() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeAnalysis()
            var engines = 2
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val queue = AnalysisQueue(backgroundScope, dispatcher, store, store, fake::analyse, "t", { engines }) { 0 }
            queue.start()
            queue.enqueue(scholars)
            engines = 4
            fake.open(scholars)
            queue.enqueue(fools)
            assertEquals(listOf(2, 4), fake.started.map { it.second })
        }

    @Test
    fun twoAndFourEnginesSaveExactlyTheSameEvals() =
        runBlocking {
            val config = AnalysisConfig(nodes = 20_000, workers = 1, hashMb = 16)

            suspend fun evalsWith(engines: Int): List<Eval> {
                val db = JdbcSqlDb.open()
                val store = GameStore(db)
                val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
                val queue =
                    AnalysisQueue(scope, kotlinx.coroutines.Dispatchers.IO, store, store, { game, workers ->
                        Analyser(config.copy(workers = workers), EngineBinary.require()).analyse(game)
                    }, "t", { engines })
                queue.start()
                val done = scope.launch { queue.finished.first() }
                queue.enqueue(short)
                withTimeout(TIMEOUT_MS) { done.join() }
                val evals = store.load(store.list().single().id)!!.evals
                scope.cancel()
                db.close()
                return evals
            }
            assertEquals(evalsWith(2), evalsWith(4))
            assertEquals(Fixtures.game("lichess-short-resign").positionCount, evalsWith(2).size)
        }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
