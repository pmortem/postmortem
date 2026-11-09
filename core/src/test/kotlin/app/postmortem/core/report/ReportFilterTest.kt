package app.postmortem.core.report

import app.postmortem.core.analysis.Eval
import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.store.GameStore
import app.postmortem.core.store.JdbcSqlDb
import app.postmortem.core.store.NewGame
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportFilterTest {
    private val today = LocalDate.of(2025, 6, 15)

    @Test
    fun theTimeControlGivesTheSpeedByLichessEstimateOfBasePlusFortyIncrements() {
        assertEquals(Speed.BULLET, TimeControl.of("60+0"))
        assertEquals(Speed.BULLET, TimeControl.of("120+1"), "160 s")
        assertEquals(Speed.BLITZ, TimeControl.of("180+0"), "180 s is the first blitz")
        assertEquals(Speed.BLITZ, TimeControl.of("180+2"), "260 s")
        assertEquals(Speed.BLITZ, TimeControl.of("180"), "no increment")
        assertEquals(Speed.RAPID, TimeControl.of("600+5"), "800 s")
        assertEquals(Speed.RAPID, TimeControl.of("480+0"))
        assertEquals(Speed.CLASSICAL, TimeControl.of("1800+0"))
        assertEquals(Speed.RAPID, TimeControl.of("900+10"), "1300 s is rapid")
        assertEquals(Speed.CLASSICAL, TimeControl.of("1100+10"), "1500 s is classical")
        assertEquals(Speed.CLASSICAL, TimeControl.of("1/259200"), "correspondence")
        assertNull(TimeControl.of("-"))
        assertNull(TimeControl.of(null))
        assertNull(TimeControl.of(""))
        assertNull(TimeControl.of("fast"))
        assertNull(TimeControl.of("180+x"))
    }

    @Test
    fun everyFixtureTimeControlIsKnown() {
        for (name in Fixtures.names) {
            val tag = Regex("""\[TimeControl "([^"]*)"]""").find(Fixtures.text(name))?.groupValues?.get(1)
            if (tag != null) assertEquals(true, TimeControl.of(tag) != null, "$name: $tag")
        }
    }

    @Test
    fun aDateTagIsParsedWithUnknownPartsAsTheFirstAndASavedGameFallsBackToTheDaySaved() {
        assertEquals(LocalDate.of(2025, 3, 12), parseDate("2025.03.12"))
        assertEquals(LocalDate.of(2025, 3, 1), parseDate("2025.03"))
        assertEquals(LocalDate.of(2025, 1, 1), parseDate("2025"))
        assertNull(parseDate(null))
        assertNull(parseDate("????.??.??"))
        assertNull(parseDate("2025.13.40"))
        val saved =
            LocalDate
                .of(2025, 5, 2)
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli() + 3_600_000
        assertEquals(LocalDate.of(2025, 5, 2), playedOn(null, saved))
        assertEquals(LocalDate.of(2025, 3, 12), playedOn("2025.03.12", saved), "the tag wins")
    }

    private fun game(
        id: Long,
        day: LocalDate,
        speed: Speed?,
    ) = PlayerGame(id, PieceColor.WHITE, null, Outcome.WIN, null, null, null, speed, day)

    private val games =
        listOf(
            game(1, today.minusDays(100), Speed.BLITZ),
            game(2, today.minusDays(30), Speed.BLITZ),
            game(3, today.minusDays(31), Speed.RAPID),
            game(4, today.minusDays(5), Speed.BULLET),
            game(5, today.minusDays(1), null),
            game(6, today.minusDays(1), Speed.BLITZ),
        )

    @Test
    fun allLetsEverythingThroughIncludingUnknownSpeeds() {
        assertEquals(games, games.filtered(ReportFilter(), today))
    }

    @Test
    fun aTimeControlKeepsOnlyThatSpeedAndLeavesOutTheUnknownOnes() {
        assertEquals(listOf(1L, 2L, 6L), games.filtered(ReportFilter(speed = Speed.BLITZ), today).map { it.gameId })
        assertEquals(listOf(3L), games.filtered(ReportFilter(speed = Speed.RAPID), today).map { it.gameId })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), games.filtered(ReportFilter(), today).map { it.gameId })
    }

    @Test
    fun theLastThirtyDaysByTheDatePlayedIncludingTheThirtiethDay() {
        assertEquals(listOf(2L, 4L, 5L, 6L), games.filtered(ReportFilter(Period.LAST_30_DAYS), today).map { it.gameId })
        assertEquals(listOf(2L, 6L), games.filtered(ReportFilter(Period.LAST_30_DAYS, Speed.BLITZ), today).map { it.gameId }, "together")
    }

    @Test
    fun theLastHundredGamesAreTheNewestByDayThenByOrderSavedOfWhatTheTimeControlLeftAndKeepTheOrder() {
        val many = (1L..130L).map { game(it, LocalDate.of(2025, 1, 1).plusDays(it / 2), if (it % 2 == 0L) Speed.BLITZ else Speed.RAPID) }
        val last = many.filtered(ReportFilter(Period.LAST_100_GAMES), today)
        assertEquals(100, last.size)
        assertEquals((31L..130L).toList(), last.map { it.gameId }, "the 30 oldest are out, the order is kept")
        val blitz = many.filtered(ReportFilter(Period.LAST_100_GAMES, Speed.BLITZ), today)
        assertEquals(65, blitz.size, "130 games, half of them blitz: all 65 are among the last 100")
        val sameDay = (1L..120L).map { game(it, LocalDate.of(2025, 2, 2), null) }
        assertEquals(
            (21L..120L).toList(),
            sameDay.filtered(ReportFilter(Period.LAST_100_GAMES), today).map {
                it.gameId
            },
            "ties go to the later save",
        )
    }

    @Test
    fun aGameWithNoDateTagUsesTheDayItWasSavedAndTheSpeedOfItsTimeControl() {
        val db = JdbcSqlDb.open()
        try {
            val store =
                GameStore(db) {
                    LocalDate
                        .of(2025, 6, 10)
                        .atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant()
                        .toEpochMilli()
                }
            val base = Fixtures.game("lichess-short-resign")
            val headers = (base.headers - "Date" - "UTCDate") + mapOf("White" to "Me", "Black" to "You", "TimeControl" to "180+2")
            val game = base.copy(headers = headers)
            store.save(NewGame(Fixtures.text("lichess-short-resign"), game, List(game.positionCount) { Eval.Cp(0) }, "t"))
            val mine = PlayerGames.of(store.reportRows(), "Me").single()
            assertEquals(LocalDate.of(2025, 6, 10), mine.day)
            assertEquals(Speed.BLITZ, mine.speed)
            assertEquals(1, listOf(mine).filtered(ReportFilter(Period.LAST_30_DAYS, Speed.BLITZ), today).size)
        } finally {
            db.close()
        }
    }
}
