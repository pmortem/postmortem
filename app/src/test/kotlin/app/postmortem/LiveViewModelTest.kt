package app.postmortem

import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.LiveLine
import app.postmortem.core.analysis.LiveLines
import app.postmortem.core.analysis.LiveUpdate
import app.postmortem.core.pgn.Game
import app.postmortem.core.uci.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun canned(game: Game): Flow<AnalysisProgress> =
        flow {
            emit(AnalysisProgress(game.positionCount, game.positionCount, List(game.positionCount) { Eval.Cp(0) }))
        }

    /** A live engine that gives one line per position, searches until cancelled, and notes what happened. */
    private class FakeLive : LiveLines {
        val searched = ArrayList<String>()
        val cancelled = ArrayList<String>()
        var released = 0
        private val cache = HashMap<String, List<LiveLine>>()

        fun line(fen: String) = listOf(LiveLine(1, "e2e4", Score.Cp(searched.size * 10), 12, listOf("e2e4")))

        override fun cached(fen: String) = cache[fen]

        override fun lines(fen: String): Flow<LiveUpdate> =
            flow {
                searched += fen
                emit(LiveUpdate(line(fen), 12, 1000, finished = false))
                try {
                    awaitCancellation()
                } finally {
                    cancelled += fen
                }
            }

        fun finish(fen: String) {
            cache[fen] = line(fen)
        }

        override suspend fun release() {
            released++
        }
    }

    private fun viewModel(
        live: FakeLive,
        setting: EngineArrowsSetting = InMemoryEngineArrowsSetting(),
        debounceMs: Long = 300,
    ) = MainViewModel(::canned, dispatcher, live = live, arrowsSetting = setting, liveDebounceMs = debounceMs)

    private fun MainViewModel.analyseGame(name: String = "lichess-draw") {
        onPgnChange(TestPgn.text(name))
        analyse()
    }

    private fun MainViewModel.fen() = assertIs<UiState.Result>(state).let { it.fen }

    @Test
    fun theLiveEngineSearchesTheShownPositionOnlyAfterItStayedShown() {
        val live = FakeLive()
        val vm = viewModel(live)
        vm.analyseGame()
        assertTrue(live.searched.isEmpty(), "not before the debounce")
        dispatcher.scheduler.advanceTimeBy(299)
        dispatcher.scheduler.runCurrent()
        assertTrue(live.searched.isEmpty())
        dispatcher.scheduler.advanceTimeBy(2)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(vm.fen()), live.searched)
        assertNotNull(vm.liveUpdate)
    }

    @Test
    fun steppingQuicklyStartsNoSearchPerMoveAndLeavesNoArrowsOfTheOldMove() {
        val live = FakeLive()
        val vm = viewModel(live)
        vm.analyseGame()
        dispatcher.scheduler.advanceTimeBy(301)
        dispatcher.scheduler.runCurrent()
        assertNotNull(vm.liveUpdate)
        repeat(5) {
            vm.step(1)
            assertNull(vm.liveUpdate, "the old move's arrows are gone at once")
            dispatcher.scheduler.advanceTimeBy(100)
            dispatcher.scheduler.runCurrent()
        }
        assertEquals(1, live.searched.size, "only the first position was searched so far")
        dispatcher.scheduler.advanceTimeBy(301)
        dispatcher.scheduler.runCurrent()
        assertEquals(listOf(vm.fen()), live.searched.takeLast(1))
        assertEquals(2, live.searched.size)
    }

    @Test
    fun holdingAnArrowStartsNoSearchUntilItIsReleasedAndThenExactlyOneForTheFinalPosition() {
        val live = FakeLive()
        val vm = viewModel(live)
        vm.analyseGame("chesscom-short-resign")
        dispatcher.scheduler.advanceTimeBy(301)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, live.searched.size)
        // a hold: the first step after 400 ms, then every 120 ms, and every 60 ms after two seconds
        val steps = ArrayList<Long>()
        var held = 400L
        repeat(30) {
            steps += held
            held += if (held >= 2000) 60 else 120
        }
        var now = 0L
        for (at in steps) {
            dispatcher.scheduler.advanceTimeBy(at - now)
            dispatcher.scheduler.runCurrent()
            now = at
            vm.step(1)
            assertNull(vm.liveUpdate, "no arrows of an old position during a hold")
        }
        assertEquals(1, live.searched.size, "no search while the button is held")
        val last = vm.fen()
        dispatcher.scheduler.advanceTimeBy(301)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, live.searched.size, "one search for the position the hold ended on")
        assertEquals(last, live.searched.last())
    }

    @Test
    fun aPositionSearchedBeforeShowsItsLinesAtOnceWithoutASearch() {
        val live = FakeLive()
        val vm = viewModel(live)
        vm.analyseGame()
        val start = vm.fen()
        live.finish(start)
        vm.step(1)
        vm.step(-1)
        assertEquals(live.line(start), vm.liveUpdate?.lines)
        assertTrue(vm.liveUpdate!!.finished)
        assertTrue(live.searched.isEmpty())
    }

    @Test
    fun turningTheEngineOffStopsTheSearchAndEndsTheProcessAndTheSettingIsKept() {
        val live = FakeLive()
        val setting = InMemoryEngineArrowsSetting()
        val vm = viewModel(live, setting)
        vm.analyseGame()
        dispatcher.scheduler.advanceTimeBy(301)
        dispatcher.scheduler.runCurrent()
        vm.showEngineArrows(false)
        dispatcher.scheduler.runCurrent()
        assertEquals(false, vm.engineOn)
        assertNull(vm.liveUpdate)
        assertEquals(1, live.cancelled.size)
        assertTrue(live.released >= 1, "no engine process runs while it is off")
        vm.step(1)
        dispatcher.scheduler.advanceTimeBy(1000)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, live.searched.size, "no search while it is off")
        // a new view model (an app restart) reads the saved setting
        val restarted = viewModel(FakeLive(), setting)
        assertEquals(false, restarted.engineOn)
    }

    @Test
    fun leavingTheResultOrGoingToTheBackgroundStopsTheSearch() {
        val live = FakeLive()
        val vm = viewModel(live)
        vm.analyseGame()
        dispatcher.scheduler.advanceTimeBy(301)
        dispatcher.scheduler.runCurrent()
        vm.onForeground(false)
        dispatcher.scheduler.runCurrent()
        assertNull(vm.liveUpdate)
        assertEquals(1, live.cancelled.size)
        assertTrue(live.released >= 1)
        vm.onForeground(true)
        dispatcher.scheduler.advanceTimeBy(301)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, live.searched.size, "it searches again when the app is back")
        vm.back()
        dispatcher.scheduler.runCurrent()
        assertNull(vm.liveUpdate)
        assertEquals(2, live.cancelled.size)
    }

    @Test
    fun noLiveSearchDuringTheAnalysis() {
        val live = FakeLive()
        val vm =
            MainViewModel(
                { game ->
                    flow { emit(AnalysisProgress(3, game.positionCount, List(3) { Eval.Cp(0) })) }.onCompletion { awaitCancellation() }
                },
                dispatcher,
                live = live,
                liveDebounceMs = 0,
            )
        vm.analyseGame()
        assertIs<UiState.Analysing>(vm.state)
        dispatcher.scheduler.advanceTimeBy(1000)
        dispatcher.scheduler.runCurrent()
        assertTrue(live.searched.isEmpty())
    }
}
