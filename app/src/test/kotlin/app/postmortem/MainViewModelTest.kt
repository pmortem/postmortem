package app.postmortem

import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.Eval
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnError
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import app.postmortem.core.uci.EngineException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun fixture(name: String) =
        checkNotNull(javaClass.getResource("/pgn/$name.pgn")) { "no fixture $name" }.readText()

    private fun positionCount(name: String) =
        (PgnParser.parse(fixture(name)) as PgnResult.Success).game.positionCount

    /** Stands in for the engine: one canned evaluation per position, reported one by one. */
    private fun canned(game: Game): Flow<AnalysisProgress> = flow {
        val evals = ArrayList<Eval>()
        emit(AnalysisProgress(0, game.positionCount, emptyList()))
        for (position in 0 until game.positionCount) {
            evals += Eval.Cp(position * 10)
            emit(AnalysisProgress(position + 1, game.positionCount, evals.toList()))
        }
    }

    private fun viewModel(analysis: (Game) -> Flow<AnalysisProgress> = ::canned) = MainViewModel(analysis, dispatcher)

    private fun MainViewModel.paste() = assertIs<UiState.Paste>(state)
    private fun MainViewModel.result() = assertIs<UiState.Result>(state)

    @Test
    fun aRealGameIsAnalysedAndOpensTheResultAtTheStartPosition() {
        val vm = viewModel()
        vm.onPgnChange(fixture("chesscom-short-resign"))
        vm.analyse()
        val result = vm.result()
        assertEquals(57, result.game.plies.size)
        assertEquals(result.game.positionCount, result.evals.size)
        assertEquals(0, result.selectedPly)
        assertEquals(Eval.Cp(0), result.selectedEval)
    }

    @Test
    fun theProgressIsShownWhileTheEngineWorks() {
        val vm = viewModel { game ->
            flow {
                emit(AnalysisProgress(3, game.positionCount, List(3) { Eval.Cp(0) }))
                awaitCancellation()
            }
        }
        vm.onPgnChange(fixture("lichess-draw"))
        vm.analyse()
        val analysing = assertIs<UiState.Analysing>(vm.state)
        assertEquals(3, analysing.done)
        assertEquals(positionCount("lichess-draw"), analysing.total)
    }

    @Test
    fun anEngineFailureReturnsToThePastePageWithTheGameKept() {
        val vm = viewModel { flow { throw EngineException("boom") } }
        vm.onPgnChange(fixture("lichess-draw"))
        vm.analyse()
        val paste = vm.paste()
        assertTrue(paste.engineFailed)
        assertEquals(fixture("lichess-draw"), paste.pgn)
    }

    @Test
    fun backDuringTheAnalysisStopsItAndKeepsTheGame() {
        var cancelled = false
        val vm = viewModel { flow<AnalysisProgress> { awaitCancellation() }.onCompletion { cancelled = true } }
        vm.onPgnChange(fixture("lichess-draw"))
        vm.analyse()
        assertIs<UiState.Analysing>(vm.state)
        assertTrue(vm.back())
        assertTrue(cancelled)
        assertEquals(fixture("lichess-draw"), vm.paste().pgn)
        assertFalse(vm.paste().engineFailed)
    }

    @Test
    fun anIllegalMoveStaysOnThePastePageWithTheError() {
        var started = false
        val vm = viewModel { started = true; canned(it) }
        vm.onPgnChange("1. e4 e5 2. Kh5")
        vm.analyse()
        assertEquals(PgnError.IllegalMove(3, "Kh5"), vm.paste().error)
        assertFalse(started)
    }

    @Test
    fun aGameWithoutMovesIsRejected() {
        val vm = viewModel()
        vm.onPgnChange(fixture("chesscom-no-moves"))
        vm.analyse()
        assertEquals(PgnError.NoMoves, vm.paste().error)
    }

    @Test
    fun editingClearsTheError() {
        val vm = viewModel()
        vm.onPgnChange("hello")
        vm.analyse()
        assertIs<PgnError.IllegalMove>(vm.paste().error)
        vm.onPgnChange("hello ")
        assertNull(vm.paste().error)
    }

    @Test
    fun selectingAndSteppingStayInsideTheGame() {
        val vm = viewModel()
        vm.onPgnChange(fixture("lichess-short-resign"))
        vm.analyse()
        val last = vm.result().game.plies.size
        vm.selectPly(5)
        assertEquals(5, vm.result().selectedPly)
        assertEquals(Eval.Cp(50), vm.result().selectedEval)
        vm.step(1)
        assertEquals(6, vm.result().selectedPly)
        vm.step(-100)
        assertEquals(0, vm.result().selectedPly)
        assertFalse(vm.result().canStepBack)
        vm.selectPly(last + 10)
        assertEquals(last, vm.result().selectedPly)
        assertFalse(vm.result().canStepForward)
    }

    @Test
    fun backReturnsToThePastePageKeepingTheGame() {
        val vm = viewModel()
        vm.onPgnChange(fixture("lichess-draw"))
        vm.analyse()
        assertTrue(vm.back())
        assertEquals(fixture("lichess-draw"), vm.paste().pgn)
        assertFalse(vm.back())
    }

    @Test
    fun analyseIsOnlyAvailableWhileTheBoxHasText() {
        val vm = viewModel()
        assertFalse(vm.paste().canAnalyse)
        vm.onPgnChange(fixture("lichess-draw"))
        assertTrue(vm.paste().canAnalyse)
        vm.onPgnChange("")
        assertFalse(vm.paste().canAnalyse)
        vm.onPgnChange(" \n ")
        assertFalse(vm.paste().canAnalyse)
    }

    @Test
    fun theClipboardFillsAnEmptyBoxOnceWhenItHoldsAGame() {
        val vm = viewModel()
        vm.onClipboard(fixture("lichess-draw"))
        assertEquals(fixture("lichess-draw"), vm.paste().pgn)
        vm.onPgnChange("")
        vm.onClipboard(fixture("lichess-mate-castle"))
        assertEquals("", vm.paste().pgn)
    }

    @Test
    fun theClipboardIsIgnoredWhenItIsNotAGameOrTheBoxHasText() {
        val vm = viewModel()
        vm.onClipboard("shopping list")
        assertEquals("", vm.paste().pgn)

        val typed = viewModel()
        typed.onPgnChange("1. e4")
        typed.onClipboard(fixture("lichess-draw"))
        assertEquals("1. e4", typed.paste().pgn)
    }
}
