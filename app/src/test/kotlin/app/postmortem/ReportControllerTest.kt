package app.postmortem

import app.postmortem.core.analysis.AnalysisProgress
import app.postmortem.core.analysis.Eval
import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Game
import app.postmortem.core.report.Period
import app.postmortem.core.report.ReportFilter
import app.postmortem.core.report.Speed
import app.postmortem.core.store.GameLibrary
import app.postmortem.core.store.GameSummary
import app.postmortem.core.store.MainLine
import app.postmortem.core.store.NewGame
import app.postmortem.core.store.ReportRow
import app.postmortem.core.store.SavedGame
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
class ReportControllerTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun row(
        id: Long,
        white: String,
        black: String,
        result: String,
        opening: String?,
        whiteAccuracy: Double? = 80.0,
        blackAccuracy: Double? = 70.0,
        timeControl: String? = null,
        date: String = "2025.01.0$id",
    ) = ReportRow(
        id,
        mapOf("White" to white, "Black" to black, "Result" to result, "Date" to date) +
            listOfNotNull(timeControl?.let { "TimeControl" to it }),
        opening,
        whiteAccuracy,
        blackAccuracy,
    )

    /** A library that only knows its report rows, which can change. */
    private class RowsLibrary(
        var rows: List<ReportRow>,
        var lines: Map<Long, MainLine> = emptyMap(),
    ) : StubLibrary() {
        var linesAsked = 0

        override fun reportRows(): List<ReportRow> = rows

        override fun mainLines(ids: List<Long>): Map<Long, MainLine> {
            linesAsked += ids.size
            return lines.filterKeys { it in ids }
        }
    }

    private val games =
        listOf(
            row(1, "Thomas", "Anna", "1-0", "Sicilian Defense: Najdorf Variation"),
            row(2, "thomas", "Ben", "1/2-1/2", "Sicilian Defense: Dragon Variation"),
            row(3, "Anna", "Thomas", "1-0", "Italian Game"),
        )

    private fun controller(
        library: GameLibrary? = RowsLibrary(games),
        setting: ReportPlayerSetting = InMemoryReportPlayerSetting(),
    ) = ReportController(kotlinx.coroutines.CoroutineScope(dispatcher), dispatcher, library, setting, today = { today })

    private val today = java.time.LocalDate.of(2025, 2, 1)

    @Test
    fun beforeTheTabIsOpenedNothingIsReadAndAfterwardsTheNamesAreListedWithTheirGames() {
        val report = controller()
        assertFalse(report.ui.loaded)
        report.refresh()
        assertTrue(report.ui.loaded)
        assertEquals(3, report.ui.games)
        assertEquals(listOf("Thomas" to 3, "Anna" to 2, "Ben" to 1), report.ui.names.map { it.name to it.games })
        assertNull(report.ui.view, "no player picked yet")
    }

    @Test
    fun anEmptyDatabaseHasNoGamesAndNoNames() {
        val report = controller(RowsLibrary(emptyList()))
        report.refresh()
        assertTrue(report.ui.loaded)
        assertEquals(0, report.ui.games)
        assertTrue(report.ui.names.isEmpty())
    }

    @Test
    fun pickingANameShowsItsRecordAndOpenings() {
        val report = controller()
        report.refresh()
        report.setPlayer("THOMAS ")
        val view = assertNotNull(report.ui.view)
        assertEquals("Thomas", view.name)
        assertEquals(3, view.record.games)
        assertEquals(1, view.record.wins)
        assertEquals(1, view.record.draws)
        assertEquals(1, view.record.losses)
        assertEquals(listOf("Sicilian Defense"), view.openings.white.map { it.name })
        assertEquals(listOf("Italian Game"), view.openings.black.map { it.name })
    }

    @Test
    fun aNameWithNoGamesHasNoView() {
        val report = controller()
        report.refresh()
        report.setPlayer("Nobody")
        assertNull(report.ui.view)
        assertEquals("Nobody", report.ui.player)
    }

    @Test
    fun theLastKnownNameIsRememberedAndComesBackAfterARestart() {
        val setting = InMemoryReportPlayerSetting()
        val first = controller(setting = setting)
        first.refresh()
        first.setPlayer("Nob")
        first.setPlayer("anna")
        first.setPlayer("Ann")
        val restarted = controller(setting = setting)
        restarted.refresh()
        assertEquals("Anna", restarted.ui.player, "only a name of a saved game is remembered, spelled as the games do")
        assertEquals(
            2,
            restarted.ui.view
                ?.record
                ?.games,
        )
    }

    @Test
    fun theReportIsReadAgainWhenTheTabIsOpenedAgain() {
        val library = RowsLibrary(games)
        val report = controller(library)
        report.refresh()
        report.setPlayer("Thomas")
        library.rows = games + row(4, "Thomas", "Ben", "0-1", "Italian Game")
        report.refresh()
        assertEquals(
            4,
            report.ui.view
                ?.record
                ?.games,
        )
        library.rows = emptyList()
        report.refresh()
        assertEquals(0, report.ui.games)
        assertNull(report.ui.view)
    }

    @Test
    fun familiesExpandAndCollapseAndAListOpensAndCloses() {
        val report = controller()
        report.refresh()
        report.setPlayer("Thomas")
        val key = FamilyKey(PieceColor.WHITE, "Sicilian Defense")
        report.toggleFamily(key)
        assertTrue(key in report.expanded)
        report.toggleFamily(key)
        assertFalse(key in report.expanded)
        assertFalse(report.closeList(), "nothing was listed")
        report.list(VariationKey(PieceColor.WHITE, "Sicilian Defense: Najdorf Variation"))
        assertNotNull(report.listed)
        assertTrue(report.closeList())
        assertNull(report.listed)
    }

    @Test
    fun aListOfGamesClosesWhenItsPlayerChangesAndTheExpandedFamiliesStayForTheSamePlayer() {
        val report = controller()
        report.refresh()
        report.setPlayer("Thomas")
        report.list(VariationKey(PieceColor.WHITE, "Sicilian Defense: Najdorf Variation"))
        report.setPlayer("Ben")
        assertNull(report.listed, "Ben has no such opening")
    }

    /** Thomas (Black in game 3, which he lost) was winning from position 6 to 11 and gave it up at ply 12. */
    private fun thrownAway(): Map<Long, MainLine> {
        val evals =
            List<Eval>(31) {
                if (it in 6..11) {
                    Eval.Cp(-400)
                } else if (it > 11) {
                    Eval.Cp(300)
                } else {
                    Eval.Cp(0)
                }
            }
        return mapOf(3L to MainLine(List(30) { "e4" }, evals, whiteMovesFirst = true))
    }

    @Test
    fun missedWinsAreWorkedOutWithTheReportAndTheMainLinesAreReadOnlyOnce() {
        val library = RowsLibrary(games, thrownAway())
        val report = controller(library)
        report.refresh()
        report.setPlayer("Thomas")
        val missed = assertNotNull(report.ui.missed)
        assertEquals(3, missed.decided)
        assertEquals(listOf(3L), missed.wins.map { it.game.gameId })
        assertEquals(12, missed.wins.single().turningPly)
        val asked = library.linesAsked
        report.setPlayer("thomas ")
        assertEquals(asked, library.linesAsked, "the lines of the same games are not read again")
        report.refresh()
        assertTrue(library.linesAsked > asked, "a refresh reads them again")
    }

    @Test
    fun noMissedReportWithoutAPlayerAndAnEmptyOneWhenThereAreNoStoredEvals() {
        val report = controller()
        report.refresh()
        assertNull(report.ui.missed)
        report.setPlayer("Thomas")
        assertTrue(assertNotNull(report.ui.missed).wins.isEmpty())
    }

    private fun canned(game: Game): Flow<AnalysisProgress> = flow { }

    @Test
    fun backClosesTheGameListBeforeLeavingTheReportTab() {
        val vm = MainViewModel(::canned, dispatcher, library = RowsLibrary(games))
        vm.selectStartTab(StartTab.Report)
        assertTrue(vm.report.ui.loaded)
        vm.report.setPlayer("Thomas")
        vm.report.list(VariationKey(PieceColor.WHITE, "Sicilian Defense: Najdorf Variation"))
        assertTrue(vm.back())
        assertNull(vm.report.listed)
        assertEquals(StartTab.Report, vm.startTab, "the first Back only closed the list")
        assertTrue(vm.back())
        assertEquals(StartTab.Analyse, vm.startTab)
        assertIs<UiState.Paste>(vm.state)
    }

    private val tagged =
        listOf(
            row(1, "Thomas", "Anna", "1-0", "Sicilian Defense: Najdorf Variation", timeControl = "180+2", date = "2025.01.20"),
            row(2, "Thomas", "Ben", "1/2-1/2", "Sicilian Defense: Dragon Variation", timeControl = "600+5", date = "2025.01.25"),
            row(3, "Anna", "Thomas", "1-0", "Italian Game", timeControl = "180+0", date = "2024.11.01"),
            row(4, "Thomas", "Ben", "0-1", "Italian Game", timeControl = "60+0", date = "2025.01.30"),
            row(5, "Thomas", "Ben", "1-0", null, timeControl = null, date = "2025.01.31"),
        )

    @Test
    fun theFiltersChangeEveryPartOfTheReportAndAreRememberedWithThePlayer() {
        val setting = InMemoryReportPlayerSetting()
        val report = controller(RowsLibrary(tagged), setting)
        report.refresh()
        report.setPlayer("Thomas")
        assertEquals(
            5,
            report.ui.view
                ?.record
                ?.games,
        )
        assertEquals(5, report.ui.unfiltered)
        report.setFilter(ReportFilter(speed = Speed.BLITZ))
        // blitz: games 1 (180+2 = 260 s) and 3 (180 s)
        assertEquals(
            2,
            report.ui.view
                ?.record
                ?.games,
        )
        assertEquals(
            listOf("Sicilian Defense"),
            report.ui.view
                ?.openings
                ?.white
                ?.map { it.name },
        )
        assertEquals(
            listOf("Italian Game"),
            report.ui.view
                ?.openings
                ?.black
                ?.map { it.name },
        )
        assertEquals(5, report.ui.unfiltered, "the player still has 5 games before the filters")
        report.setFilter(ReportFilter(Period.LAST_30_DAYS, Speed.BLITZ))
        assertEquals(
            1,
            report.ui.view
                ?.record
                ?.games,
            "game 3 is older than 30 days",
        )
        report.setFilter(ReportFilter(Period.LAST_30_DAYS))
        assertEquals(
            4,
            report.ui.view
                ?.record
                ?.games,
        )
        assertEquals(ReportFilter(Period.LAST_30_DAYS), kotlinx.coroutines.runBlocking { setting.filter() })
        val restarted = controller(RowsLibrary(tagged), setting)
        restarted.refresh()
        assertEquals(ReportFilter(Period.LAST_30_DAYS), restarted.ui.filter, "remembered across a restart")
    }

    @Test
    fun aFilterThatLeavesNoGamesShowsNoViewButTheCountBeforeTheFiltersKeepsTheMessageRight() {
        val report = controller(RowsLibrary(tagged))
        report.refresh()
        report.setPlayer("Thomas")
        report.setFilter(ReportFilter(speed = Speed.CLASSICAL))
        assertNull(report.ui.view)
        assertNull(report.ui.missed)
        assertEquals(5, report.ui.unfiltered)
        report.setPlayer("Nobody")
        assertEquals(0, report.ui.unfiltered)
    }

    @Test
    fun theTabIsKeptThroughAFilterAndTheMissedWinsFollowTheFilters() {
        val lines =
            mapOf(
                4L to
                    MainLine(
                        List(30) { "e4" },
                        List<Eval>(31) {
                            if (it in
                                6..12
                            ) {
                                Eval.Cp(400)
                            } else if (it > 12) {
                                Eval.Cp(-300)
                            } else {
                                Eval.Cp(0)
                            }
                        },
                        true,
                    ),
            )
        val report = controller(RowsLibrary(tagged, lines))
        report.refresh()
        report.setPlayer("Thomas")
        assertEquals(ReportTab.Overview, report.tab)
        report.selectTab(ReportTab.Mistakes)
        assertEquals(
            1,
            report.ui.missed
                ?.wins
                ?.size,
            "game 4 (bullet, lost) was winning for two moves",
        )
        report.setFilter(ReportFilter(speed = Speed.BLITZ))
        assertEquals(ReportTab.Mistakes, report.tab, "a filter does not change the tab")
        assertEquals(
            0,
            report.ui.missed
                ?.wins
                ?.size,
            "game 4 is bullet: its missed win is out",
        )
        assertEquals(
            0,
            report.ui.drill
                ?.items
                ?.size,
        )
    }
}
