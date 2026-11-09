package app.postmortem

import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.Eval
import app.postmortem.core.line.Cursor
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun positionCount(name: String) = (PgnParser.parse(TestPgn.text(name)) as PgnResult.Success).game.positionCount

    /** Stands in for the engine: one canned evaluation per position, reported one by one. */
    private fun canned(game: Game): Flow<AnalysisProgress> =
        flow {
            val evals = ArrayList<Eval>()
            emit(AnalysisProgress(0, game.positionCount, emptyList()))
            for (position in 0 until game.positionCount) {
                evals += Eval.Cp(position * 10)
                emit(AnalysisProgress(position + 1, game.positionCount, evals.toList()))
            }
        }

    private fun viewModel(analysis: (Game) -> Flow<AnalysisProgress> = ::canned) =
        MainViewModel(analysis, dispatcher, analysisSettings = "test engine, 123 nodes")

    private fun MainViewModel.paste() = assertIs<UiState.Paste>(state)

    private fun MainViewModel.result() = assertIs<UiState.Result>(state)

    @Test
    fun aRealGameIsAnalysedAndOpensTheResultAtTheStartPosition() {
        val vm = viewModel()
        vm.onPgnChange(TestPgn.text("chesscom-short-resign"))
        vm.analyse()
        val result = vm.result()
        assertEquals(57, result.game.plies.size)
        assertEquals(result.game.positionCount, result.evals.size)
        assertEquals(0, result.selectedPly)
        assertEquals(Eval.Cp(0), result.selectedEval)
    }

    @Test
    fun theProgressIsShownWhileTheEngineWorks() {
        val vm =
            viewModel { game ->
                flow {
                    emit(AnalysisProgress(3, game.positionCount, List(3) { Eval.Cp(0) }))
                    awaitCancellation()
                }
            }
        vm.onPgnChange(TestPgn.text("lichess-draw"))
        vm.analyse()
        val analysing = assertIs<UiState.Analysing>(vm.state)
        assertEquals(3, analysing.done)
        assertEquals(positionCount("lichess-draw"), analysing.total)
    }

    @Test
    fun anEngineFailureReturnsToThePastePageWithTheGameKept() {
        val vm = viewModel { flow { throw EngineException("boom") } }
        vm.onPgnChange(TestPgn.text("lichess-draw"))
        vm.analyse()
        val paste = vm.paste()
        assertTrue(paste.engineFailed)
        assertEquals(TestPgn.text("lichess-draw"), paste.pgn)
    }

    @Test
    fun backDuringTheAnalysisLeavesItRunningInTheQueueAndKeepsTheGameAndCancelStopsIt() {
        var cancelled = false
        val vm = viewModel { flow<AnalysisProgress> { awaitCancellation() }.onCompletion { cancelled = true } }
        vm.onPgnChange(TestPgn.text("lichess-draw"))
        vm.analyse()
        assertIs<UiState.Analysing>(vm.state)
        assertTrue(vm.back())
        assertFalse(cancelled, "the game goes on in the queue")
        assertEquals(TestPgn.text("lichess-draw"), vm.paste().pgn)
        assertFalse(vm.paste().engineFailed)
        val queued = vm.queued.value.single()
        vm.cancelQueued(queued.id)
        assertTrue(cancelled)
        assertTrue(vm.queued.value.isEmpty())
    }

    @Test
    fun anAnalysisThatStopsWithoutFinishingSaysTheEngineFailed() {
        val vm = viewModel { flow<AnalysisProgress> { } }
        vm.onPgnChange(TestPgn.text("lichess-draw"))
        vm.analyse()
        assertTrue(vm.paste().engineFailed)
    }

    @Test
    fun anIllegalMoveStaysOnThePastePageWithTheError() {
        var started = false
        val vm =
            viewModel {
                started = true
                canned(it)
            }
        vm.onPgnChange("1. e4 e5 2. Kh5")
        vm.analyse()
        assertEquals(PgnError.IllegalMove(3, "Kh5"), vm.paste().error)
        assertFalse(started)
    }

    @Test
    fun aGameWithoutMovesIsRejected() {
        val vm = viewModel()
        vm.onPgnChange(TestPgn.text("chesscom-no-moves"))
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
        vm.onPgnChange(TestPgn.text("lichess-short-resign"))
        vm.analyse()
        val last =
            vm
                .result()
                .game.plies.size
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
    fun aNewAnalysisStartsOnTheMovesTabAndTheTabIsPartOfTheResult() {
        val vm = viewModel()
        vm.onPgnChange(TestPgn.text("lichess-short-resign"))
        vm.analyse()
        assertEquals(ResultTab.Moves, vm.result().tab)
        vm.selectTab(ResultTab.Summary)
        assertEquals(ResultTab.Summary, vm.result().tab)
        // The tab is separate from the move: stepping and selecting leave it alone.
        vm.step(1)
        vm.selectPly(3)
        assertEquals(ResultTab.Summary, vm.result().tab)
        assertEquals(3, vm.result().selectedPly)
        // Back to the paste page and a new analysis (the same game or another one): a fresh result on Moves.
        vm.back()
        vm.analyse()
        assertEquals(ResultTab.Moves, vm.result().tab)
        assertEquals(0, vm.result().selectedPly)
        vm.selectTab(ResultTab.Chart)
        vm.back()
        vm.onPgnChange(TestPgn.text("lichess-draw"))
        vm.analyse()
        assertEquals(ResultTab.Moves, vm.result().tab)
    }

    @Test
    fun flippingTurnsTheBoardAndKeepsTheMoveAndTheTabAndAFreshGameStartsUnflipped() {
        val vm = viewModel()
        vm.onPgnChange(TestPgn.text("lichess-short-resign"))
        vm.analyse()
        assertFalse(vm.result().flipped)
        vm.selectPly(5)
        vm.selectTab(ResultTab.Chart)
        vm.flip()
        assertTrue(vm.result().flipped)
        assertEquals(5, vm.result().selectedPly)
        assertEquals(ResultTab.Chart, vm.result().tab)
        // Stepping leaves the board flipped and moves only the selection.
        vm.step(1)
        assertTrue(vm.result().flipped)
        assertEquals(6, vm.result().selectedPly)
        vm.flip()
        assertFalse(vm.result().flipped)
        vm.flip()
        vm.back()
        vm.analyse()
        assertFalse(vm.result().flipped)
    }

    @Test
    fun backReturnsToThePastePageKeepingTheGame() {
        val vm = viewModel()
        vm.onPgnChange(TestPgn.text("lichess-draw"))
        vm.analyse()
        assertTrue(vm.back())
        assertEquals(TestPgn.text("lichess-draw"), vm.paste().pgn)
        assertFalse(vm.back())
    }

    @Test
    fun analyseIsOnlyAvailableWhileTheBoxHasText() {
        val vm = viewModel()
        assertFalse(vm.paste().canAnalyse)
        vm.onPgnChange(TestPgn.text("lichess-draw"))
        assertTrue(vm.paste().canAnalyse)
        vm.onPgnChange("")
        assertFalse(vm.paste().canAnalyse)
        vm.onPgnChange(" \n ")
        assertFalse(vm.paste().canAnalyse)
    }

    @Test
    fun theClipboardFillsAnEmptyBoxOnceWhenItHoldsAGame() {
        val vm = viewModel()
        vm.onClipboard(TestPgn.text("lichess-draw"))
        assertEquals(TestPgn.text("lichess-draw"), vm.paste().pgn)
        vm.onPgnChange("")
        vm.onClipboard(TestPgn.text("lichess-mate-castle"))
        assertEquals("", vm.paste().pgn)
    }

    @Test
    fun theClipboardIsIgnoredWhenItIsNotAGameOrTheBoxHasText() {
        val vm = viewModel()
        vm.onClipboard("shopping list")
        assertEquals("", vm.paste().pgn)

        val typed = viewModel()
        typed.onPgnChange("1. e4")
        typed.onClipboard(TestPgn.text("lichess-draw"))
        assertEquals("1. e4", typed.paste().pgn)
    }

    @Test
    fun theSettingsAreKeptWithTheResult() {
        val vm = viewModel()
        vm.onPgnChange(TestPgn.text("lichess-short-resign"))
        vm.analyse()
        assertEquals("test engine, 123 nodes", vm.result().analysisSettings)
    }

    @Test
    fun theEvalBarSaysRipOnTheLastPositionOnly() {
        val vm = viewModel()
        vm.onPgnChange(TestPgn.text("lichess-draw"))
        vm.analyse()
        val last =
            vm
                .result()
                .game.plies.size
        assertFalse(vm.result().atEndOfGame, "the start position")
        vm.selectPly(last - 1)
        assertFalse(vm.result().atEndOfGame, "the one before last")
        vm.step(1)
        assertTrue(vm.result().atEndOfGame, "the last position, a draw")
        vm.step(-1)
        assertFalse(vm.result().atEndOfGame)
    }

    @Test
    fun theLastPositionIsTheEndWhateverTheEndingIsMateOrResignation() {
        for (name in listOf("scholars-mate", "chesscom-short-resign")) {
            val vm = viewModel()
            vm.onPgnChange(TestPgn.text(name))
            vm.analyse()
            vm.selectPly(Int.MAX_VALUE)
            assertTrue(vm.result().atEndOfGame, name)
            assertEquals(
                vm
                    .result()
                    .game.plies.size,
                vm.result().selectedPly,
                name,
            )
        }
    }

    @Test
    fun thePercentageIsRoundedStartsAtZeroEndsAtHundredAndNeverGoesDown() {
        val game = (PgnParser.parse(TestPgn.text("lichess-draw")) as PgnResult.Success).game

        fun percent(
            done: Int,
            total: Int,
        ) = UiState.Analysing("", game, done, total).percent
        assertEquals(0, percent(0, 81))
        assertEquals(100, percent(81, 81))
        assertEquals(42, percent(34, 81))
        assertEquals(1, percent(1, 165), "0.6 % rounds to 1")
        assertEquals(0, percent(0, 0), "a game without positions")
        val all = (0..165).map { percent(it, 165) }
        assertEquals(all.sorted(), all, "more positions done never gives a lower percentage")
    }

    @Test
    fun theProgressShownNeverMovesBackwardsEvenIfAReportArrivesWithAnOlderCount() {
        val vm =
            viewModel { game ->
                flow {
                    emit(AnalysisProgress(5, game.positionCount, List(5) { Eval.Cp(0) }))
                    emit(AnalysisProgress(3, game.positionCount, List(3) { Eval.Cp(0) }))
                    awaitCancellation()
                }
            }
        vm.onPgnChange(TestPgn.text("lichess-draw"))
        vm.analyse()
        assertEquals(5, assertIs<UiState.Analysing>(vm.state).done)
    }

    @Test
    fun settingsOpensOverTheResultAndBackReturnsToTheSameGameMoveTabAndFlip() {
        val vm = viewModel()
        vm.onPgnChange(TestPgn.text("chesscom-short-resign"))
        vm.analyse()
        vm.selectPly(7)
        vm.selectTab(ResultTab.Chart)
        vm.flip()
        val before = vm.result()
        vm.openSettings()
        assertEquals(Overlay.Settings, vm.overlay)
        assertEquals(before, vm.result())
        assertTrue(vm.back())
        assertNull(vm.overlay)
        assertEquals(before, vm.result())
        assertEquals(7, vm.result().selectedPly)
    }

    @Test
    fun backOnTheSettingsPageDoesNotLeaveTheResultButTheNextBackDoes() {
        val vm = viewModel()
        vm.onPgnChange(TestPgn.text("lichess-draw"))
        vm.analyse()
        vm.openSettings()
        vm.back()
        assertIs<UiState.Result>(vm.state)
        vm.back()
        assertIs<UiState.Paste>(vm.state)
    }

    @Test
    fun theSettingsCogOnlyOpensOverAResultAndTheStartPageHasTabsInstead() {
        val vm = viewModel()
        vm.openSettings()
        assertNull(vm.overlay, "no Settings overlay over the start page: it is a tab")
        for (tab in StartTab.entries) {
            vm.selectStartTab(tab)
            assertEquals(tab, vm.startTab)
            assertNull(vm.overlay)
        }
        assertTrue(vm.back(), "Back from a tab goes to Analyse")
        assertEquals(StartTab.Analyse, vm.startTab)
        assertFalse(vm.back())
    }

    private fun scholars(): MainViewModel =
        viewModel().also {
            it.onPgnChange(TestPgn.text("scholars-mate"))
            it.analyse()
        }

    @Test
    fun aMoveThatIsNotTheGamesStartsALineAndSelectsIt() {
        val vm = scholars()
        val before = vm.result()
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        val result = vm.result()
        val line = result.lines.all.single()
        assertEquals(4, line.branchPly)
        assertEquals(Cursor(line.id, 1), result.cursor)
        assertEquals(line.plies.single().fen, result.fen)
        assertEquals("d2d4", result.lastMove)
        assertEquals(4, result.selectedPly, "the chart marks the branch")
        assertEquals(before.evals, result.evals)
        assertEquals(before.game, result.game)
        assertEquals(before.report, result.report, "accuracy and judgements stay the game's")
    }

    @Test
    fun theGamesOwnNextMoveStepsForwardAndMakesNoLine() {
        val vm = scholars()
        vm.selectPly(4)
        vm.playMove("f1", "c4")
        assertTrue(
            vm
                .result()
                .lines.all
                .isEmpty(),
        )
        assertEquals(Cursor.game(5), vm.result().cursor)
    }

    @Test
    fun anIllegalMoveChangesNothing() {
        val vm = scholars()
        vm.selectPly(4)
        val before = vm.result()
        vm.playMove("d2", "d5")
        vm.playMove("e7", "e5")
        vm.playMove("a1", "a8")
        assertEquals(before, vm.result())
    }

    @Test
    fun movesAtTheEndOfALineExtendItAndAnotherMoveMakesASecondLine() {
        val vm = scholars()
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        vm.playMove("d7", "d5")
        vm.playMove("c2", "c4")
        assertEquals(
            3,
            vm
                .result()
                .lines.all
                .single()
                .plies.size,
        )
        vm.selectPly(4)
        vm.playMove("g1", "f3")
        assertEquals(
            listOf(1, 2),
            vm
                .result()
                .lines
                .branchingAt(4)
                .map { it.id },
        )
        assertEquals(Cursor(2, 1), vm.result().cursor)
    }

    @Test
    fun arrowsStepAlongTheLineBackToItsBranchAndThenDownTheGame() {
        val vm = scholars()
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        vm.playMove("d7", "d5")
        assertTrue(vm.result().canStepBack)
        assertFalse(vm.result().canStepForward, "the end of a line")
        vm.step(-1)
        assertEquals(Cursor(1, 1), vm.result().cursor)
        assertTrue(vm.result().canStepForward)
        vm.step(-1)
        assertEquals(Cursor.game(4), vm.result().cursor)
        vm.step(-1)
        assertEquals(Cursor.game(3), vm.result().cursor)
        vm.selectLineMove(1, 2)
        assertEquals(Cursor(1, 2), vm.result().cursor)
    }

    @Test
    fun backFromALineGoesToItsBranchPositionAndTheNextBackLeavesTheResult() {
        val vm = scholars()
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        assertTrue(vm.back())
        assertEquals(Cursor.game(4), vm.result().cursor)
        assertEquals(
            1,
            vm
                .result()
                .lines.all.size,
            "the line stays",
        )
        assertTrue(vm.back())
        assertIs<UiState.Paste>(vm.state)
    }

    @Test
    fun theEndOfTheGameIsOnlyTheEndWhenTheBoardIsOnTheGame() {
        val vm = scholars()
        val last =
            vm
                .result()
                .game.plies.size
        vm.selectPly(last - 1)
        vm.playMove("h5", "h4")
        assertFalse(vm.result().atEndOfGame)
        vm.selectPly(last)
        assertTrue(vm.result().atEndOfGame)
    }

    @Test
    fun tappingAPieceShowsItsTargetsAndTappingATargetPlaysTheMove() {
        val vm = scholars()
        vm.selectPly(4)
        vm.tapSquare("d2")
        assertEquals("d2", vm.selectedSquare)
        assertEquals(listOf("d3", "d4"), vm.moveTargets.map { it.square })
        vm.tapSquare("d4")
        assertNull(vm.selectedSquare)
        assertEquals("d2d4", vm.result().lastMove)
        assertEquals(
            1,
            vm
                .result()
                .lines.all.size,
        )
    }

    @Test
    fun tappingElsewherePutsThePieceDownAndAnotherOwnPiecePicksThatOneUp() {
        val vm = scholars()
        vm.selectPly(4)
        vm.tapSquare("d2")
        vm.tapSquare("g1")
        assertEquals("g1", vm.selectedSquare)
        vm.tapSquare("e5")
        assertNull(vm.selectedSquare, "an empty square that is not a target")
        vm.tapSquare("e7")
        assertNull(vm.selectedSquare, "the other side's piece")
        vm.tapSquare("g1")
        vm.tapSquare("g1")
        assertNull(vm.selectedSquare, "tapping the picked piece again puts it down")
        assertTrue(
            vm
                .result()
                .lines.all
                .isEmpty(),
        )
    }

    @Test
    fun aPickedPieceIsDroppedWhenTheBoardShowsAnotherPosition() {
        val vm = scholars()
        vm.selectPly(4)
        vm.tapSquare("d2")
        vm.step(-1)
        assertNull(vm.selectedSquare)
        assertTrue(vm.moveTargets.isEmpty())
        vm.step(1)
        assertEquals("d2", vm.selectedSquare, "back on the position it was picked on, it is still picked")
    }

    @Test
    fun aDragPlaysALegalMoveAndDoesNothingForAnIllegalOne() {
        val vm = scholars()
        vm.selectPly(4)
        vm.dragMove("g1", "f3")
        assertEquals("g1f3", vm.result().lastMove)
        val before = vm.result()
        vm.dragMove("g1", "g5")
        vm.dragMove("e7", "e6")
        vm.dragMove("f3", "f3")
        assertEquals(before, vm.result())
    }

    @Test
    fun aPromotionAsksForThePieceAndPlaysIt() {
        val vm = viewModel()
        vm.onPgnChange("[FEN \"8/4P3/8/8/8/8/k7/4K3 w - - 0 1\"]\n[SetUp \"1\"]\n\n1. e8=Q")
        vm.analyse()
        vm.tapSquare("e7")
        assertTrue(vm.moveTargets.single().promotion)
        vm.dragMove("e7", "e8")
        assertEquals(PromotionAsked(vm.result().fen, "e7", "e8"), vm.promotion)
        assertEquals(Cursor.game(0), vm.result().cursor, "nothing is played until a piece is chosen")
        vm.tapSquare("a2")
        assertNotNull(vm.promotion, "taps are ignored while the picker is open")
        vm.choosePromotion('n')
        assertNull(vm.promotion)
        assertEquals("e7e8n", vm.result().lastMove)
        assertEquals(
            1,
            vm
                .result()
                .lines.all.size,
            "the game's own move is a queen, so a knight is a line",
        )
    }

    @Test
    fun backClosesThePromotionPickerBeforeAnythingElse() {
        val vm = viewModel()
        vm.onPgnChange("[FEN \"8/4P3/8/8/8/8/k7/4K3 w - - 0 1\"]\n[SetUp \"1\"]\n\n1. e8=Q")
        vm.analyse()
        vm.dragMove("e7", "e8")
        assertTrue(vm.back())
        assertNull(vm.promotion)
        assertIs<UiState.Result>(vm.state)
    }

    private fun threeMoveLine(): MainViewModel {
        val vm = scholars()
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        vm.playMove("d7", "d5")
        vm.playMove("c2", "c4")
        return vm
    }

    @Test
    fun tappingAWholeLineShowsItsLastPosition() {
        val vm = threeMoveLine()
        vm.selectPly(2)
        vm.selectLine(1)
        assertEquals(Cursor(1, 3), vm.result().cursor)
        vm.selectLineMove(1, 2)
        assertEquals(Cursor(1, 2), vm.result().cursor)
    }

    @Test
    fun deletingALineGoesToItsBranchAndUndoBringsItBackAsItWas() {
        val vm = threeMoveLine()
        val line =
            vm
                .result()
                .lines.all
                .single()
        vm.deleteLine(1)
        assertTrue(
            vm
                .result()
                .lines.all
                .isEmpty(),
        )
        assertEquals(Cursor.game(4), vm.result().cursor)
        assertEquals(line, vm.deletedLine?.line)
        vm.undoDelete()
        assertEquals(listOf(line), vm.result().lines.all)
        assertEquals(Cursor(1, 3), vm.result().cursor, "the board is back on the line")
        assertNull(vm.deletedLine)
    }

    @Test
    fun deletingALineTheBoardIsNotOnLeavesTheBoardWhereItIs() {
        val vm = threeMoveLine()
        vm.selectPly(2)
        vm.deleteLine(1)
        assertEquals(Cursor.game(2), vm.result().cursor)
        vm.undoDelete()
        assertEquals(Cursor.game(2), vm.result().cursor)
        assertEquals(
            1,
            vm
                .result()
                .lines.all.size,
        )
    }

    @Test
    fun theUndoOfAnOlderDeletionIsGoneAfterAnotherDeletionOrWhenItTimesOut() {
        val vm = threeMoveLine()
        vm.selectPly(4)
        vm.playMove("g1", "f3")
        vm.deleteLine(1)
        vm.deleteLine(2)
        assertEquals(2, vm.deletedLine?.line?.id)
        vm.undoDelete()
        assertEquals(
            listOf(2),
            vm
                .result()
                .lines.all
                .map { it.id },
        )
        vm.deleteLine(2)
        vm.dismissUndo()
        vm.undoDelete()
        assertTrue(
            vm
                .result()
                .lines.all
                .isEmpty(),
        )
    }

    @Test
    fun aDifferentMoveInTheMiddleOfALineMakesAnotherLineAndLeavesTheFirstAlone() {
        val vm = threeMoveLine()
        vm.selectLineMove(1, 2)
        vm.playMove("g1", "f3")
        val lines = vm.result().lines.all
        assertEquals(2, lines.size)
        assertEquals(listOf("d2d4", "d7d5", "c2c4"), lines.first().plies.map { it.uci })
        assertEquals(listOf("d2d4", "d7d5", "g1f3"), lines.last().plies.map { it.uci })
        assertEquals(Cursor(2, 3), vm.result().cursor)
    }
}
