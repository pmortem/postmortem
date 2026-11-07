package app.postmortem

import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.PositionEvals
import app.postmortem.core.analysis.positionKey
import app.postmortem.core.line.Cursor
import app.postmortem.core.line.Lines
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.GameOverview
import app.postmortem.core.store.GameLibrary
import app.postmortem.core.store.GameSummary
import app.postmortem.core.store.NewGame
import app.postmortem.core.store.SavedGame
import app.postmortem.core.store.gameKey
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/pgn/$name.pgn")) { "no fixture $name" }.readText()

    /** The saved games in memory, as the real store keeps them. [broken] makes every call fail, like a full disk. */
    private class FakeLibrary : GameLibrary {
        val games = LinkedHashMap<Long, SavedGame>()
        var broken = false
        private var next = 1L

        private fun check() {
            if (broken) error("disk full")
        }

        override fun findSaved(game: Game): SavedGame? {
            check()
            return games.values.firstOrNull { it.key == gameKey(game) }
        }

        override fun save(new: NewGame): Long {
            check()
            val id = next++
            games[id] =
                SavedGame(
                    id,
                    gameKey(new.game),
                    new.pgn,
                    new.game,
                    new.evals,
                    new.analysisSettings,
                    Lines(),
                    emptyMap(),
                    new.whiteAccuracy,
                    new.blackAccuracy,
                    new.opening,
                    1L,
                    1L,
                )
            return id
        }

        override fun saveLines(
            id: Long,
            lines: Lines,
            lineEvals: Map<String, Eval>,
        ) {
            check()
            games[id] = games.getValue(id).copy(lines = lines, lineEvals = lineEvals)
        }

        override fun setOpening(
            id: Long,
            opening: String?,
        ) {
            games[id] = games.getValue(id).copy(opening = opening)
        }

        override fun load(id: Long): SavedGame? {
            check()
            return games[id]
        }

        override fun list(): List<GameSummary> =
            games.values.reversed().map {
                GameSummary(
                    it.id,
                    GameOverview.of(it.game.headers),
                    it.opening,
                    it.whiteAccuracy,
                    it.blackAccuracy,
                    it.lines.all.size,
                    it.created,
                    it.updated,
                )
            }

        override fun delete(id: Long) {
            games.remove(id)
        }

        override fun restore(saved: SavedGame) {
            games[saved.id] = saved
        }
    }

    private class FakePositions : PositionEvals {
        val asked = ArrayList<String>()

        override fun cached(fen: String): Eval? = null

        override suspend fun evaluate(fen: String): Eval {
            asked += fen
            return Eval.Cp(asked.size * 10)
        }

        override fun seed(
            game: Game,
            evals: List<Eval>,
        ) = Unit

        override suspend fun release() = Unit
    }

    private var analysed = 0

    private fun canned(game: Game): Flow<AnalysisProgress> =
        flow {
            analysed++
            emit(AnalysisProgress(game.positionCount, game.positionCount, List(game.positionCount) { Eval.Cp(it * 10) }))
        }

    private fun viewModel(
        library: FakeLibrary,
        positions: FakePositions? = null,
    ) = MainViewModel(::canned, dispatcher, positions = positions, library = library)

    private fun MainViewModel.analyse(name: String) {
        onPgnChange(fixture(name))
        analyse()
    }

    private fun MainViewModel.result() = assertIs<UiState.Result>(state)

    @Test
    fun aFinishedAnalysisIsSavedWithItsEvalsAndTheScreenKnowsItsId() {
        val library = FakeLibrary()
        val vm = viewModel(library)
        vm.analyse("scholars-mate")
        val saved = library.games.values.single()
        assertEquals(vm.result().evals, saved.evals)
        assertEquals(vm.result().game, saved.game)
        assertEquals(vm.result().pgn, saved.pgn)
        assertEquals(vm.result().analysisSettings, saved.analysisSettings)
        assertNotNull(saved.whiteAccuracy)
        assertEquals(saved.id, vm.result().gameId)
    }

    @Test
    fun everyNewLineMoveEvalAndDeleteIsSavedAsItHappens() {
        val library = FakeLibrary()
        val vm = viewModel(library, FakePositions())
        vm.analyse("scholars-mate")
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        vm.playMove("d7", "d5")
        val saved = library.games.values.single()
        assertEquals(vm.result().lines, saved.lines)
        assertEquals(2, saved.lineEvals.size, "the evals that arrived are saved too")
        vm.deleteLine(1)
        assertTrue(
            library.games.values
                .single()
                .lines.all
                .isEmpty(),
        )
        vm.undoDelete()
        assertEquals(
            1,
            library.games.values
                .single()
                .lines.all.size,
        )
    }

    @Test
    fun pastingASavedGameAgainOpensItWithItsLinesAndNoAnalysis() {
        val library = FakeLibrary()
        val first = viewModel(library, FakePositions())
        first.analyse("scholars-mate")
        first.selectPly(4)
        first.playMove("d2", "d4")
        val savedLines = first.result().lines
        analysed = 0
        val second = viewModel(library)
        second.analyse("scholars-mate")
        assertEquals(0, analysed, "no analysis ran")
        assertEquals(savedLines, second.result().lines)
        assertEquals(first.result().evals, second.result().evals)
        assertEquals(1L, second.result().gameId)
        assertTrue(second.openedFromLibrary)
        second.noticeShown()
        assertFalse(second.openedFromLibrary)
        assertEquals(first.result().pgn, second.result().pgn)
        assertEquals(Cursor.game(0), second.result().cursor)
    }

    @Test
    fun aGameThatIsNotSavedIsAnalysedAndAChangedTagIsAnotherGame() {
        val library = FakeLibrary()
        val vm = viewModel(library)
        vm.analyse("scholars-mate")
        vm.back()
        vm.onPgnChange(fixture("scholars-mate").replace("Scholar's mate", "Scholar's mate, again"))
        vm.analyse()
        assertEquals(2, analysed)
        assertEquals(2, library.games.size)
        assertFalse(vm.openedFromLibrary)
    }

    @Test
    fun aSavedGameOpensByIdForTheLibraryScreen() {
        val library = FakeLibrary()
        val vm = viewModel(library)
        vm.analyse("lichess-short-resign")
        vm.back()
        analysed = 0
        vm.openGame(1L)
        assertEquals(0, analysed)
        assertEquals(1L, vm.result().gameId)
        assertTrue(vm.openedFromLibrary)
        vm.openGame(99L)
        assertEquals(1L, vm.result().gameId, "a game that is not there changes nothing")
    }

    @Test
    fun aLinePositionWithoutAnEvalWhenTheGameOpensIsEvaluatedAgain() {
        val library = FakeLibrary()
        val first = viewModel(library)
        first.analyse("scholars-mate")
        first.selectPly(4)
        first.playMove("d2", "d4")
        first.back()
        first.back()
        val positions = FakePositions()
        val second = viewModel(library, positions)
        second.analyse("scholars-mate")
        val fen =
            second
                .result()
                .lines.all
                .single()
                .plies
                .single()
                .fen
        assertEquals(listOf(fen), positions.asked)
        assertTrue(second.result().evalOf(fen) != null)
        assertTrue(positionKey(fen) in second.result().lineEvals)
    }

    @Test
    fun aLibraryThatFailsNeverStopsTheAnalysisOrThePlay() {
        val library = FakeLibrary()
        library.broken = true
        val vm = viewModel(library)
        vm.analyse("scholars-mate")
        assertNull(vm.result().gameId)
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        assertEquals(
            1,
            vm
                .result()
                .lines.all.size,
        )
        assertTrue(library.games.isEmpty())
    }

    @Test
    fun theLibraryTabListsTheGamesNewestFirstAndBackReturnsToTheAnalyseTabWithTheTextKept() {
        val library = FakeLibrary()
        val vm = viewModel(library)
        vm.analyse("scholars-mate")
        vm.back()
        vm.analyse("lichess-short-resign")
        vm.back()
        vm.onPgnChange("keep this text")
        vm.selectStartTab(StartTab.Library)
        assertEquals(StartTab.Library, vm.startTab)
        assertEquals(listOf(2L, 1L), vm.libraryGames?.map { it.id })
        assertTrue(vm.back())
        assertEquals(StartTab.Analyse, vm.startTab)
        assertEquals("keep this text", assertIs<UiState.Paste>(vm.state).pgn)
        assertFalse(vm.back(), "on the Analyse tab with nothing open, Back is the system's")
    }

    @Test
    fun aGameOpenedFromTheLibraryTabReturnsToTheLibraryTabWithTheListRefreshed() {
        val library = FakeLibrary()
        val vm = viewModel(library, FakePositions())
        vm.analyse("scholars-mate")
        vm.back()
        vm.analyse("lichess-short-resign")
        vm.back()
        vm.selectStartTab(StartTab.Library)
        vm.openGame(1L)
        assertIs<UiState.Result>(vm.state)
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        assertTrue(vm.back())
        assertTrue(vm.back())
        assertIs<UiState.Paste>(vm.state)
        assertEquals(StartTab.Library, vm.startTab, "Back from the game returns to where it was opened")
        assertEquals(1, vm.libraryGames?.first { it.id == 1L }?.lineCount, "the list was read again, with the new line")
    }

    @Test
    fun openingAGameFromTheLibraryClosesItAndShowsTheGameWithoutAnalysis() {
        val library = FakeLibrary()
        val vm = viewModel(library, FakePositions())
        vm.analyse("scholars-mate")
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        vm.back()
        vm.back()
        analysed = 0
        vm.selectStartTab(StartTab.Library)
        vm.openGame(vm.libraryGames!!.first().id)
        assertNull(vm.overlay)
        assertEquals(0, analysed)
        assertEquals(
            1,
            vm
                .result()
                .lines.all.size,
        )
        assertEquals(1, library.list().first().lineCount)
    }

    @Test
    fun aSavedGameCanBeOpenedAtAGivenPositionAndAnOutOfRangeOneIsClamped() {
        val library = FakeLibrary()
        val vm = viewModel(library, FakePositions())
        vm.analyse("scholars-mate")
        vm.back()
        vm.openGame(1L, ply = 3)
        assertEquals(Cursor.game(3), vm.result().cursor)
        vm.back()
        vm.openGame(1L, ply = 99)
        assertEquals(
            Cursor.game(
                vm
                    .result()
                    .game.plies.size,
            ),
            vm.result().cursor,
        )
        vm.back()
        vm.openGame(1L)
        assertEquals(Cursor.game(0), vm.result().cursor)
    }

    @Test
    fun theStartTabsCannotBeChangedWhileAnalysing() {
        val vm = MainViewModel({ flow { } }, dispatcher, library = FakeLibrary())
        vm.onPgnChange(fixture("scholars-mate"))
        vm.analyse()
        assertIs<UiState.Analysing>(vm.state)
        vm.selectStartTab(StartTab.Library)
        assertEquals(StartTab.Analyse, vm.startTab)
    }

    @Test
    fun deletingAGameFromTheLibraryAndUndoingItKeepsTheListAndTheLinesRight() {
        val library = FakeLibrary()
        val vm = viewModel(library, FakePositions())
        vm.analyse("scholars-mate")
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        vm.back()
        vm.back()
        vm.selectStartTab(StartTab.Library)
        vm.deleteSavedGame(1L)
        assertTrue(library.games.isEmpty())
        assertEquals(emptyList(), vm.libraryGames)
        assertEquals(1L, vm.deletedGame?.id)
        vm.undoDeleteGame()
        assertNull(vm.deletedGame)
        assertEquals(listOf(1L), vm.libraryGames?.map { it.id })
        assertEquals(
            1,
            library.games
                .getValue(1L)
                .lines.all.size,
            "the lines came back with it",
        )
        vm.deleteSavedGame(1L)
        vm.dismissDeletedGame()
        vm.undoDeleteGame()
        assertTrue(library.games.isEmpty(), "an expired undo does nothing")
    }
}
