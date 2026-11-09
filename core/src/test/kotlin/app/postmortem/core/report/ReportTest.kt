package app.postmortem.core.report

import app.postmortem.core.analysis.Eval
import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.store.GameStore
import app.postmortem.core.store.JdbcSqlDb
import app.postmortem.core.store.NewGame
import app.postmortem.core.store.ReportRow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The player's openings on a small database made of the real fixture games, with the players, results and openings set by hand
 * so every number can be counted on paper:
 *
 *  | id | White  | Black  | Result  | Opening                              | acc W | acc B | date       |
 *  | 1  | Thomas | Anna   | 1-0     | Sicilian Defense: Najdorf Variation  | 90    | 70    | 2025.01.10 |
 *  | 2  | thomas | Ben    | 1/2-1/2 | Sicilian Defense: Dragon Variation   | 80    | 60    | 2025.02.01 |
 *  | 3  | Anna   | Thomas | 1-0     | Sicilian Defense: Najdorf Variation  | 85    | 50    | 2025.02.15 |
 *  | 4  | Thomas | Ben    | *       | Italian Game                         | null  | null  | 2025.03.01 |
 *  | 5  |  Thomas | ?     | 0-1     | (none)                               | 40    | null  | 2025.03.05 |
 *  | 6  | Ben    | Anna   | 1-0     | Italian Game                         | 75    | 65    | 2025.03.10 |
 */
class ReportTest {
    private val db = JdbcSqlDb.open()
    private val store = GameStore(db)

    @AfterTest
    fun close() = db.close()

    private fun add(
        fixture: String,
        white: String,
        black: String,
        result: String,
        opening: String?,
        whiteAccuracy: Double?,
        blackAccuracy: Double?,
        date: String,
    ) {
        val base = Fixtures.game(fixture)
        val game =
            base.copy(
                headers =
                    base.headers + mapOf("White" to white, "Black" to black, "Result" to result, "Date" to date, "Site" to date),
            )
        store.save(
            NewGame(Fixtures.text(fixture), game, List(game.positionCount) { Eval.Cp(0) }, "t", whiteAccuracy, blackAccuracy, opening),
        )
    }

    private val rows: List<ReportRow> by lazy {
        add("scholars-mate", "Thomas", "Anna", "1-0", "Sicilian Defense: Najdorf Variation", 90.0, 70.0, "2025.01.10")
        add("fools-mate", "thomas", "Ben", "1/2-1/2", "Sicilian Defense: Dragon Variation", 80.0, 60.0, "2025.02.01")
        add("lichess-short-resign", "Anna", "Thomas", "1-0", "Sicilian Defense: Najdorf Variation", 85.0, 50.0, "2025.02.15")
        add("lichess-draw", "Thomas", "Ben", "*", "Italian Game", null, null, "2025.03.01")
        add("lichess-mate-castle", " Thomas ", "?", "0-1", null, 40.0, null, "2025.03.05")
        add("chesscom-short-resign", "Ben", "Anna", "1-0", "Italian Game", 75.0, 65.0, "2025.03.10")
        store.reportRows()
    }

    @Test
    fun theRowsComeFromTheGameTableWithTheirTagsOpeningsAndAccuracies() {
        assertEquals(6, rows.size)
        assertEquals("Thomas", rows[0].headers["White"])
        assertEquals("Sicilian Defense: Najdorf Variation", rows[0].opening)
        assertEquals(90.0, rows[0].whiteAccuracy)
        assertNull(rows[4].opening)
        assertNull(rows[4].blackAccuracy)
    }

    @Test
    fun namesAreListedMostGamesFirstMatchedWithoutCaseAndSpacesAndLeaveOutPlaceholders() {
        val names = PlayerGames.names(rows)
        assertEquals(listOf("Thomas" to 5, "Anna" to 3, "Ben" to 3), names.map { it.name to it.games })
        assertTrue(names.none { it.name == "?" })
    }

    @Test
    fun aPlayersGamesAreFoundWithTheirColourOutcomeOpeningAndOwnAccuracy() {
        val games = PlayerGames.of(rows, "  THOMAS ")
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), games.map { it.gameId })
        assertEquals(
            listOf(PieceColor.WHITE, PieceColor.WHITE, PieceColor.BLACK, PieceColor.WHITE, PieceColor.WHITE),
            games.map { it.color },
        )
        assertEquals(
            listOf(Outcome.WIN, Outcome.DRAW, Outcome.LOSS, Outcome.UNFINISHED, Outcome.LOSS),
            games.map { it.outcome },
            "1-0 as White wins, 1-0 against him loses, * is unfinished, 0-1 as White loses",
        )
        assertEquals(listOf(90.0, 80.0, 50.0, null, 40.0), games.map { it.accuracy })
        assertEquals(listOf("Anna", "Ben", "Anna", "Ben", null), games.map { it.opponent })
        assertEquals(emptyList(), PlayerGames.of(rows, "nobody"))
        assertEquals(emptyList(), PlayerGames.of(rows, "  "))
    }

    @Test
    fun openingsAreGroupedByColourFamilyAndVariationWithResultsPercentagesAndAccuracy() {
        val report = OpeningStats.of(PlayerGames.of(rows, "Thomas"))
        // as White: Sicilian (games 1, 2), Italian (game 4, unfinished), unknown (game 5)
        assertEquals(listOf("Sicilian Defense", "Italian Game", null), report.white.map { it.name })
        val sicilian = report.white[0]
        assertEquals(2, sicilian.record.games)
        assertEquals(1, sicilian.record.wins)
        assertEquals(1, sicilian.record.draws)
        assertEquals(0, sicilian.record.losses)
        assertEquals(50.0, sicilian.record.winPercent)
        assertEquals(50.0, sicilian.record.drawPercent)
        assertEquals(0.0, sicilian.record.lossPercent)
        assertEquals(85.0, sicilian.record.accuracy)
        assertEquals(
            listOf("Sicilian Defense: Dragon Variation", "Sicilian Defense: Najdorf Variation"),
            sicilian.variations.map { it.name },
        )
        // an unfinished game is a game but never in the percentages or the accuracy
        val italian = report.white[1].record
        assertEquals(1, italian.games)
        assertEquals(0, italian.decided)
        assertNull(italian.winPercent)
        assertNull(italian.accuracy)
        // a game with no opening goes under unknown
        assertEquals(1, report.white[2].record.losses)
        assertEquals(40.0, report.white[2].record.accuracy)
        // as Black he played one game: the Najdorf, lost with 50 %
        assertEquals(listOf("Sicilian Defense"), report.black.map { it.name })
        assertEquals(
            100.0,
            report.black
                .single()
                .record.lossPercent,
        )
        assertEquals(
            50.0,
            report.black
                .single()
                .record.accuracy,
        )
    }

    @Test
    fun aPlayerWhoOnlyPlayedBlackHasNothingAsWhiteAndTheGamesAreListedNewestFirst() {
        val report = OpeningStats.of(PlayerGames.of(rows, "Anna"))
        // Anna: White in game 3 (Najdorf), Black in games 1 (Najdorf) and 6 (Italian)
        assertEquals(listOf("Sicilian Defense"), report.white.map { it.name })
        assertEquals(listOf("Italian Game", "Sicilian Defense"), report.black.map { it.name }, "equal counts go by name")
        val onlyBlack = OpeningStats.of(PlayerGames.of(rows, "Anna").filter { it.color == PieceColor.BLACK })
        assertEquals(emptyList(), onlyBlack.white)
        val najdorf = report.black[1].variations.single()
        assertEquals(listOf(1L), najdorf.games.map { it.gameId })
        val both =
            OpeningStats
                .of(PlayerGames.of(rows, "Thomas"))
                .white[0]
                .variations
                .flatMap { it.games }
        assertEquals(listOf(2L, 1L), both.sortedByDescending { it.date }.map { it.gameId })
    }

    @Test
    fun aFamilyIsTheNameBeforeTheFirstColon() {
        assertEquals("Sicilian Defense", OpeningStats.family("Sicilian Defense: Najdorf Variation"))
        assertEquals("Italian Game", OpeningStats.family("Italian Game"))
        assertEquals("A", OpeningStats.family("A: B: C"))
    }

    @Test
    fun theOutcomeIsReadFromTheResultTag() {
        assertEquals(Outcome.WIN, PlayerGames.outcome("0-1", PieceColor.BLACK))
        assertEquals(Outcome.DRAW, PlayerGames.outcome("1/2-1/2", PieceColor.BLACK))
        assertEquals(Outcome.UNFINISHED, PlayerGames.outcome("*", PieceColor.WHITE))
        assertEquals(Outcome.UNFINISHED, PlayerGames.outcome(null, PieceColor.WHITE))
    }

    @Test
    fun theGamesOfEveryFixtureCanBeReadAsAReport() {
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            val game = Fixtures.game(name)
            store.save(
                NewGame(Fixtures.text(name), game, List(game.positionCount) { Eval.Cp(0) }, "t", 80.0, 70.0, game.overview().opening),
            )
        }
        val everyone = store.reportRows()
        assertTrue(everyone.size >= 10)
        val top = PlayerGames.names(everyone).first()
        val report = OpeningStats.of(PlayerGames.of(everyone, top.name))
        assertEquals(top.games, report.white.sumOf { it.record.games } + report.black.sumOf { it.record.games })
    }

    private fun app.postmortem.core.pgn.Game.overview() =
        app.postmortem.core.pgn.GameOverview
            .of(headers)

    @Test
    fun aRowReadsItsTagsAsAnOverviewOnce() {
        val row = rows.first()
        assertEquals(
            app.postmortem.core.pgn.GameOverview
                .of(row.headers),
            row.overview,
        )
        assertTrue(row.overview === row.overview)
    }
}
