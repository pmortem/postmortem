package app.postmortem

import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.Eval
import app.postmortem.core.line.Lines
import app.postmortem.core.pgn.Game
import app.postmortem.core.store.GameLibrary
import app.postmortem.core.store.GameSummary
import app.postmortem.core.store.MergeResult
import app.postmortem.core.store.NewGame
import app.postmortem.core.store.SavedGame
import app.postmortem.data.DatabaseControl
import app.postmortem.data.DbFileInfo
import app.postmortem.data.DbOutcome
import app.postmortem.data.DbProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.io.File
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A library that only counts what is saved to it, so a test can see what was on disk when a file action ran. */
    private class CountingLibrary : StubLibrary() {
        var games = 0
        var lineSaves = 0

        override fun save(new: NewGame): Long {
            games++
            return games.toLong()
        }

        override fun saveLines(
            id: Long,
            lines: Lines,
            lineEvals: Map<String, Eval>,
        ) {
            lineSaves++
        }
    }

    private class FakeDatabases(
        private val library: CountingLibrary,
    ) : DatabaseControl {
        var name = "postmortem.sqlite"
        var files = listOf(DbFileInfo("postmortem.sqlite", 1000, 3, true), DbFileInfo("club.sqlite", 500, 0, false))
        var next: DbOutcome = DbOutcome.Renamed("club.sqlite")
        val calls = ArrayList<String>()
        var lineSavesWhenCalled = -1

        private fun run(call: String): DbOutcome {
            calls += call
            lineSavesWhenCalled = library.lineSaves
            return next
        }

        override fun activeName() = name

        override fun files() = files

        override fun rename(input: String) = run("rename $input")

        override fun open(fileName: String) = run("open $fileName")

        override fun export() = run("export")

        override fun openPicked(
            displayName: String?,
            source: () -> InputStream?,
        ) = run("openPicked $displayName")

        override fun importPicked(source: () -> InputStream?) = run("importPicked")
    }

    private fun canned(game: Game): Flow<AnalysisProgress> =
        flow {
            emit(AnalysisProgress(game.positionCount, game.positionCount, List(game.positionCount) { Eval.Cp(it) }))
        }

    private val library = CountingLibrary()
    private val databases = FakeDatabases(library)

    private fun viewModel() = MainViewModel(::canned, dispatcher, library = library, databases = databases)

    private fun MainViewModel.analyseScholars() {
        onPgnChange(TestPgn.text("scholars-mate"))
        analyse()
    }

    @Test
    fun settingsIsATabOfTheStartPageAndBackLeavesItForTheAnalyseTab() {
        val vm = viewModel()
        vm.onPgnChange("keep this")
        vm.selectStartTab(StartTab.Settings)
        assertEquals(StartTab.Settings, vm.startTab)
        assertNull(vm.overlay, "a tab is not an overlay")
        assertEquals("postmortem.sqlite", vm.databaseUi.activeName)
        assertTrue(vm.back())
        assertEquals(StartTab.Analyse, vm.startTab)
        assertEquals("keep this", assertIs<UiState.Paste>(vm.state).pgn)
        assertFalse(vm.back(), "on the Analyse tab Back is the system's")
    }

    @Test
    fun settingsCannotBeOpenedWhileAGameIsBeingAnalysed() {
        val vm = MainViewModel({ flow { } }, dispatcher, databases = databases)
        vm.analyseScholars()
        assertIs<UiState.Analysing>(vm.state)
        vm.openSettings()
        assertNull(vm.overlay)
    }

    @Test
    fun openingSettingsReadsTheActiveNameAndTheFiles() {
        val vm = viewModel()
        vm.selectStartTab(StartTab.Settings)
        assertEquals("postmortem.sqlite", vm.databaseUi.activeName)
        assertEquals(databases.files, vm.databaseUi.files)
    }

    @Test
    fun renamingReportsTheNewNameAndAFailureShowsItsReasonWithNothingElseChanged() {
        val vm = viewModel()
        vm.selectStartTab(StartTab.Settings)
        vm.renameDatabase("club")
        assertEquals(listOf("rename club"), databases.calls)
        assertEquals(DbMessage.Renamed("club.sqlite"), vm.databaseUi.message)
        databases.next = DbOutcome.Failed(DbProblem.EXISTS)
        vm.renameDatabase("club")
        assertEquals(DbMessage.Failed(DbProblem.EXISTS), vm.databaseUi.message)
        assertTrue(!vm.databaseUi.busy)
    }

    @Test
    fun anImportSaysHowManyWereAddedAndSkipped() {
        val vm = viewModel()
        vm.selectStartTab(StartTab.Settings)
        databases.next = DbOutcome.Imported(MergeResult(added = 4, skipped = 2))
        vm.importPickedDatabase { null }
        assertEquals(DbMessage.Imported(4, 2), vm.databaseUi.message)
    }

    @Test
    fun openingAnotherDatabaseStopsSavingTheGameOnScreenAndEmptiesTheLibraryList() {
        val vm = viewModel()
        vm.analyseScholars()
        assertEquals(1, library.games)
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        val savesBefore = library.lineSaves
        databases.next = DbOutcome.Opened("club.sqlite")
        vm.openSettings()
        vm.openDatabaseFile("club.sqlite")
        assertEquals(DbMessage.Opened("club.sqlite"), vm.databaseUi.message)
        assertNull(vm.libraryGames)
        vm.playMove("d7", "d5")
        assertEquals(savesBefore, library.lineSaves, "the game on screen belongs to the database that was left")
    }

    @Test
    fun everySaveMadeBeforeAFileActionIsOnDiskWhenItRuns() {
        val vm = viewModel()
        vm.analyseScholars()
        vm.selectPly(4)
        vm.playMove("d2", "d4")
        vm.openSettings()
        vm.exportDatabase()
        assertTrue(databases.lineSavesWhenCalled >= 1, "the line was saved before the export ran")
    }

    @Test
    fun anExportWaitsForTheShareSheetAndGivesItTheCopyOnce() {
        val vm = viewModel()
        vm.selectStartTab(StartTab.Settings)
        val copy = File("export/postmortem.sqlite")
        databases.next = DbOutcome.Exported(copy)
        vm.exportDatabase()
        assertEquals(copy, vm.pendingExport)
        assertNull(vm.databaseUi.message)
        vm.exportShared()
        assertNull(vm.pendingExport)
    }

    @Test
    fun pickedFilesAreOpenedByTheirDisplayName() {
        val vm = viewModel()
        vm.selectStartTab(StartTab.Settings)
        databases.next = DbOutcome.Opened("friends games.sqlite")
        vm.openPickedDatabase("friends games.sqlite") { null }
        assertEquals(listOf("openPicked friends games.sqlite"), databases.calls)
        assertEquals(DbMessage.Opened("friends games.sqlite"), vm.databaseUi.message)
    }
}
