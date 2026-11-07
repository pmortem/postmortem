package app.postmortem

import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.Judgement
import app.postmortem.core.analysis.PositionEvals
import app.postmortem.core.analysis.positionKey
import app.postmortem.core.board.Moves
import app.postmortem.core.pgn.Game
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
class LineEvalViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/pgn/$name.pgn")) { "no fixture $name" }.readText()

    /** One canned eval per position: Cp(10 * position). */
    private fun canned(game: Game): Flow<AnalysisProgress> =
        flow {
            val evals = List(game.positionCount) { Eval.Cp(it * 10) }
            emit(AnalysisProgress(game.positionCount, game.positionCount, evals))
        }

    /** Evaluates positions when told to: [hold] keeps every answer back, and the positions asked are noted in order. */
    private class FakePositions(
        private val answer: (String) -> Eval = { Eval.Cp(it.length) },
    ) : PositionEvals {
        val asked = ArrayList<String>()
        var seeded: List<Eval>? = null
        var released = 0
        var hold: CompletableDeferred<Unit>? = null
        val known = HashMap<String, Eval>()

        override fun cached(fen: String): Eval? = known[positionKey(fen)]

        override suspend fun evaluate(fen: String): Eval {
            asked += fen
            hold?.await()
            return answer(fen)
        }

        override fun seed(
            game: Game,
            evals: List<Eval>,
        ) {
            seeded = evals
        }

        override suspend fun release() {
            released++
        }
    }

    private fun viewModel(positions: FakePositions) =
        MainViewModel(::canned, dispatcher, positions = positions).also {
            it.onPgnChange(fixture("scholars-mate"))
            it.analyse()
            it.selectPly(4)
        }

    private fun MainViewModel.result() = assertIs<UiState.Result>(state)

    @Test
    fun theGameEvalsSeedTheCacheWhenTheAnalysisIsDone() {
        val positions = FakePositions()
        viewModel(positions)
        assertEquals(List(8) { Eval.Cp(it * 10) }, positions.seeded)
    }

    @Test
    fun aLineMoveGetsItsEvalAndTheEvalBarFollows() {
        val positions = FakePositions { Eval.Cp(123) }
        val vm = viewModel(positions)
        vm.playMove("d2", "d4")
        val result = vm.result()
        val line = result.lines.all.single()
        assertEquals(Eval.Cp(123), result.evalOf(line.plies[0].fen))
        assertEquals(Eval.Cp(123), result.selectedEval)
        assertTrue(result.pendingEvals.isEmpty())
        assertEquals(listOf(line.plies[0].fen), positions.asked)
    }

    @Test
    fun movesMadeQuicklyAreAllEvaluatedInTheOrderPlayed() {
        val positions = FakePositions()
        val hold = CompletableDeferred<Unit>()
        positions.hold = hold
        val vm = viewModel(positions)
        vm.playMove("d2", "d4")
        vm.playMove("d7", "d5")
        vm.playMove("c2", "c4")
        val line =
            vm
                .result()
                .lines.all
                .single()
        assertEquals(3, vm.result().pendingEvals.size)
        assertTrue(vm.result().isPending(line.plies[2].fen))
        assertEquals(Eval.Cp(40), vm.result().selectedEval)
        hold.complete(Unit)
        assertEquals(line.plies.map { it.fen }, positions.asked)
        assertTrue(vm.result().pendingEvals.isEmpty())
        assertTrue(line.plies.all { vm.result().evalOf(it.fen) != null })
    }

    @Test
    fun aPositionTheGameOrAnEarlierSearchHasIsNotSearched() {
        val positions = FakePositions()
        val vm = viewModel(positions)
        // an earlier search (here of another game) already evaluated the position after d2d4
        val afterD4 = assertNotNull(Moves.play(vm.result().game.fenAt(4), "d2", "d4"))
        positions.known[positionKey(afterD4.fen)] = Eval.Cp(7)
        vm.playMove("d2", "d4")
        assertEquals(Eval.Cp(7), vm.result().evalOf(afterD4.fen))
        assertTrue(positions.asked.isEmpty())
        assertTrue(vm.result().pendingEvals.isEmpty())
    }

    @Test
    fun aTranspositionToAGamePositionUsesTheGameEval() {
        val positions = FakePositions()
        val vm = viewModel(positions)
        // 3.Bc4 is the game's own move here, so it makes no line; but a line position equal to a game position shares its eval
        val gameFen = vm.result().game.fenAt(5)
        assertEquals(Eval.Cp(50), vm.result().evalOf(gameFen.substringBeforeLast(' ').substringBeforeLast(' ') + " 9 99"))
    }

    @Test
    fun aBlunderInALineGetsTheSameMarkAsInTheGame() {
        val positions = FakePositions { Eval.Cp(-900) }
        val vm = viewModel(positions)
        vm.playMove("d2", "d4")
        val result = vm.result()
        assertEquals(listOf(Judgement.BLUNDER), result.judgementsOf(result.lines.all.single()))
        assertEquals(result.report, vm.result().report, "accuracy and ACPL stay the game's")
    }

    @Test
    fun aSoundSacrificeInALineIsBrilliantAndALosingOneIsNot() {
        // 3.Qxe5 takes a pawn the knight on c6 guards: the queen is lost for a pawn, and if the evals say that is fine it is brilliant
        val sound = viewModel(FakePositions { Eval.Cp(40) })
        sound.playMove("h5", "e5")
        val result = sound.result()
        assertEquals(listOf(true), result.brilliantOf(result.lines.all.single()))
        assertEquals(listOf<Judgement?>(null), result.judgementsOf(result.lines.all.single()))

        val losing = viewModel(FakePositions { Eval.Cp(-900) })
        losing.playMove("h5", "e5")
        val lost = losing.result()
        assertEquals(listOf(false), lost.brilliantOf(lost.lines.all.single()))
        assertEquals(listOf(Judgement.BLUNDER), lost.judgementsOf(lost.lines.all.single()))
    }

    @Test
    fun noJudgementsWhileTheEvalsAreStillWaiting() {
        val positions = FakePositions()
        positions.hold = CompletableDeferred()
        val vm = viewModel(positions)
        vm.playMove("d2", "d4")
        assertTrue(
            vm
                .result()
                .judgementsOf(
                    vm
                        .result()
                        .lines.all
                        .single(),
                ).isEmpty(),
        )
    }

    @Test
    fun inTheBackgroundTheQueuePausesAndTheEngineEndsAndItResumesOnReturn() {
        val positions = FakePositions()
        val vm = viewModel(positions)
        vm.onForeground(false)
        assertEquals(1, positions.released)
        vm.playMove("d2", "d4")
        assertTrue(positions.asked.isEmpty())
        assertEquals(1, vm.result().pendingEvals.size)
        vm.onForeground(true)
        assertEquals(1, positions.asked.size)
        assertTrue(vm.result().pendingEvals.isEmpty())
    }

    @Test
    fun aPositionAlreadyQueuedIsNotQueuedTwiceAndLeavingTheResultEndsTheEngine() {
        val positions = FakePositions()
        positions.hold = CompletableDeferred()
        val vm = viewModel(positions)
        vm.playMove("d2", "d4")
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        assertEquals(1, positions.asked.size)
        vm.back()
        vm.back()
        assertNull((vm.state as? UiState.Result))
        assertNotNull(positions.released.takeIf { it > 0 })
    }
}
