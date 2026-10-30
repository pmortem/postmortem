package app.postmortem

import app.postmortem.core.pgn.PgnError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainViewModelTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.getResource("/pgn/$name.pgn")) { "no fixture $name" }.readText()

    private fun MainViewModel.paste() = assertIs<UiState.Paste>(state)
    private fun MainViewModel.result() = assertIs<UiState.Result>(state)

    @Test
    fun aRealGameOpensTheResultAtTheStartPosition() {
        val vm = MainViewModel()
        vm.onPgnChange(fixture("chesscom-short-resign"))
        vm.analyse()
        val result = vm.result()
        assertEquals(57, result.game.plies.size)
        assertEquals(0, result.selectedPly)
        assertEquals(result.game.startFen, result.game.fenAt(result.selectedPly))
    }

    @Test
    fun anIllegalMoveStaysOnThePastePageWithTheError() {
        val vm = MainViewModel()
        vm.onPgnChange("1. e4 e5 2. Kh5")
        vm.analyse()
        assertEquals(PgnError.IllegalMove(3, "Kh5"), vm.paste().error)
    }

    @Test
    fun selectingAndSteppingStayInsideTheGame() {
        val vm = MainViewModel()
        vm.onPgnChange(fixture("lichess-short-resign"))
        vm.analyse()
        val last = vm.result().game.plies.size
        vm.selectPly(5)
        assertEquals(5, vm.result().selectedPly)
        vm.step(1)
        assertEquals(6, vm.result().selectedPly)
        vm.step(-100)
        assertEquals(0, vm.result().selectedPly)
        assertFalse(vm.result().canStepBack)
        vm.selectPly(last + 10)
        assertEquals(last, vm.result().selectedPly)
        assertFalse(vm.result().canStepForward)
        vm.step(-1)
        assertEquals(vm.result().game.plies[last - 2].fen, vm.result().game.fenAt(last - 1))
    }

    @Test
    fun backReturnsToThePastePageKeepingTheGame() {
        val vm = MainViewModel()
        vm.onPgnChange(fixture("lichess-draw"))
        vm.analyse()
        assertTrue(vm.back())
        assertEquals(fixture("lichess-draw"), vm.paste().pgn)
        assertFalse(vm.back())
    }

    @Test
    fun aGameWithoutMovesIsRejected() {
        val vm = MainViewModel()
        vm.onPgnChange(fixture("chesscom-no-moves"))
        vm.analyse()
        assertEquals(PgnError.NoMoves, vm.paste().error)
    }

    @Test
    fun editingClearsTheError() {
        val vm = MainViewModel()
        vm.onPgnChange("hello")
        vm.analyse()
        assertIs<PgnError.IllegalMove>(vm.paste().error)
        vm.onPgnChange("hello ")
        assertNull(vm.paste().error)
    }

    @Test
    fun theClipboardFillsAnEmptyBoxOnceWhenItHoldsAGame() {
        val vm = MainViewModel()
        vm.onClipboard(fixture("lichess-draw"))
        assertEquals(fixture("lichess-draw"), vm.paste().pgn)
        vm.onPgnChange("")
        vm.onClipboard(fixture("lichess-mate-castle"))
        assertEquals("", vm.paste().pgn)
    }

    @Test
    fun theClipboardIsIgnoredWhenItIsNotAGameOrTheBoxHasText() {
        val vm = MainViewModel()
        vm.onClipboard("shopping list")
        assertEquals("", vm.paste().pgn)

        val typed = MainViewModel()
        typed.onPgnChange("1. e4")
        typed.onClipboard(fixture("lichess-draw"))
        assertEquals("1. e4", typed.paste().pgn)
    }

    @Test
    fun analyseIsOnlyAvailableWhileTheBoxHasText() {
        val vm = MainViewModel()
        assertFalse(vm.paste().canAnalyse)
        vm.onPgnChange(fixture("lichess-draw"))
        assertTrue(vm.paste().canAnalyse)
        vm.onPgnChange("")
        assertFalse(vm.paste().canAnalyse)
        vm.onPgnChange(" \n ")
        assertFalse(vm.paste().canAnalyse)
    }
}
