package app.postmortem.core.report

import app.postmortem.core.analysis.Eval
import app.postmortem.core.board.PieceColor
import app.postmortem.core.opening.OpeningBook
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.pgn.STANDARD_START_FEN
import app.postmortem.core.store.MainLine
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Six games of one player, counted by hand. Each is a real fixture game with made-up evals (15 cp everywhere, so a move loses nothing
 * unless the test says so); the result, the accuracy, the Elos, the time control and the termination are set here.
 *
 * | id | game               | side  | result | acc | Elo / opp | speed     | day   |
 * | 1  | scholars-mate      | White | win    | 90  | 1500/1700 | blitz     | 01-01 |
 * | 2  | lichess-short-resign | Black | loss   | 60  | 1500/1550 | bullet    | 01-02 |
 * | 3  | lichess-draw       | White | draw   | 80  | none      | rapid     | 01-03 |
 * | 4  | chesscom-short-resign | Black | win    | 70  | 1500/1400 | blitz     | 01-04 |
 * | 5  | lichess-mate-castle | White | win    | 50  | 1500/1600 | unknown   | 01-05 |
 * | 6  | fools-mate (no evals) | Black | unfinished | none | 1500/1499 | classical | 01-06 |
 */
class PlayerStatsTest {
    private val book = OpeningBook.shared

    private fun lineOf(
        name: String,
        eval: (Int) -> Eval = { Eval.Cp(15) },
    ): MainLine {
        val game = Fixtures.game(name)
        return MainLine(
            game.plies.map {
                it.san
            },
            List(game.positionCount, eval),
            game.whiteMovesFirst,
            List(game.positionCount) { game.fenAt(it) },
        )
    }

    private fun played(
        id: Long,
        color: PieceColor,
        outcome: Outcome,
        accuracy: Double?,
        elo: Int?,
        opponentElo: Int?,
        speed: Speed?,
        termination: String?,
    ) = PlayerGame(
        id,
        color,
        "Opp",
        outcome,
        null,
        accuracy,
        null,
        speed,
        LocalDate.of(2025, 1, 1).plusDays(id),
        elo,
        opponentElo,
        termination,
    )

    private val games =
        listOf(
            played(1, PieceColor.WHITE, Outcome.WIN, 90.0, 1500, 1700, Speed.BLITZ, "Normal"),
            played(2, PieceColor.BLACK, Outcome.LOSS, 60.0, 1500, 1550, Speed.BULLET, "Normal"),
            played(3, PieceColor.WHITE, Outcome.DRAW, 80.0, null, null, Speed.RAPID, "Normal"),
            played(4, PieceColor.BLACK, Outcome.WIN, 70.0, 1500, 1400, Speed.BLITZ, "Hikaru won by resignation"),
            played(5, PieceColor.WHITE, Outcome.WIN, 50.0, 1500, 1600, null, "Normal"),
            played(6, PieceColor.BLACK, Outcome.UNFINISHED, null, 1500, 1499, Speed.CLASSICAL, null),
        )

    // game 1: White's third move (ply 5) goes from +0.15 to -3.00 and the position stays there: one blunder, 315 cp lost
    private val lines =
        mapOf(
            1L to lineOf("scholars-mate") { if (it >= 5) Eval.Cp(-300) else Eval.Cp(15) },
            2L to lineOf("lichess-short-resign"),
            3L to lineOf("lichess-draw"),
            4L to lineOf("chesscom-short-resign"),
            5L to lineOf("lichess-mate-castle"),
        )

    private val stats = PlayerStats.of(games, lines, book)

    @Test
    fun theTotalsAreCountedByHand() {
        val t = stats.totals
        assertEquals(6, t.games)
        assertEquals(5, t.analysed, "game 6 has no stored evals")
        assertEquals(Wdl(2, 1, 0), t.white)
        assertEquals(Wdl(1, 0, 1), t.black)
        assertEquals(Wdl(3, 1, 1), t.both)
        assertEquals(5, t.both.decided, "the unfinished game is left out")
        assertEquals(70.0, t.scorePercent!!, 1e-9, "(3 + 0.5) / 5")
        assertEquals(83.333333, t.white.scorePercent!!, 1e-4, "(2 + 0.5) / 3")
        assertEquals(50.0, t.black.scorePercent!!, 1e-9)
        assertEquals(70.0, t.accuracy!!, 1e-9, "(90 + 60 + 80 + 70 + 50) / 5")
        assertEquals(15.8, t.acpl!!, 1e-9, "game 1 loses 315 cp in 4 moves = 78.75, which rounds to 79; the others 0; (79 / 5)")
        assertEquals(0.2, t.blundersPerGame!!, 1e-9)
        assertEquals(0.0, t.mistakesPerGame!!, 1e-9)
        assertEquals(0.0, t.inaccuraciesPerGame!!, 1e-9)
    }

    @Test
    fun theSeriesIsOldestFirstWithARollingAverageOfTheLastTenGames() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), stats.series.map { it.gameId })
        val expected = listOf(90.0, 75.0, 230.0 / 3, 75.0, 70.0, 70.0)
        stats.series.map { it.rolling!! }.forEachIndexed { index, value -> assertEquals(expected[index], value, 1e-9, "game ${index + 1}") }
        assertEquals(1500, stats.series[0].elo)
        assertEquals(1700, stats.series[0].opponentElo)
        assertNull(stats.series[2].elo)
        assertEquals(Outcome.UNFINISHED, stats.series[5].outcome)
        assertNull(stats.series[5].accuracy)
    }

    @Test
    fun theRollingAverageOnlyLooksAtTheLastTenGames() {
        val many = (1L..12L).map { played(it, PieceColor.WHITE, Outcome.WIN, if (it <= 2) 0.0 else 100.0, null, null, null, null) }
        val rolling = PlayerStats.of(many, emptyMap(), book).series.map { it.rolling!! }
        assertEquals(0.0, rolling[0])
        assertEquals(0.0, rolling[1])
        assertEquals(100.0 * 8 / 10, rolling[9], 1e-9, "games 1..10: two zeros")
        assertEquals(90.0, rolling[10], 1e-9, "games 2..11: one zero")
        assertEquals(100.0, rolling[11], 1e-9, "games 3..12")
    }

    @Test
    fun byTimeControlEveryGroupHasItsGamesScoreAndAccuracy() {
        val groups = stats.byTimeControl.associateBy { it.key }
        assertEquals(listOf(Speed.BULLET, Speed.BLITZ, Speed.RAPID, Speed.CLASSICAL, null), stats.byTimeControl.map { it.key })
        assertEquals(Group<Speed?>(Speed.BLITZ, 2, 100.0, 80.0), groups.getValue(Speed.BLITZ))
        assertEquals(Group<Speed?>(Speed.BULLET, 1, 0.0, 60.0), groups.getValue(Speed.BULLET))
        assertEquals(Group<Speed?>(Speed.RAPID, 1, 50.0, 80.0), groups.getValue(Speed.RAPID))
        assertEquals(Group<Speed?>(Speed.CLASSICAL, 1, null, null), groups.getValue(Speed.CLASSICAL), "an unfinished game: no score")
        assertEquals(Group<Speed?>(null, 1, 100.0, 50.0), groups.getValue(null))
    }

    @Test
    fun byOpponentStrengthTheEloDifferenceDecidesAndAHundredIsTheLimit() {
        val groups = stats.byStrength.associateBy { it.key }
        assertEquals(Group(Strength.STRONGER, 2, 100.0, 70.0), groups.getValue(Strength.STRONGER), "+200 and exactly +100")
        assertEquals(Group(Strength.EQUAL, 2, 0.0, 60.0), groups.getValue(Strength.EQUAL), "+50 and -1")
        assertEquals(Group(Strength.WEAKER, 1, 100.0, 70.0), groups.getValue(Strength.WEAKER), "exactly -100")
        assertEquals(Group(Strength.UNKNOWN, 1, 50.0, 80.0), groups.getValue(Strength.UNKNOWN), "no Elo")
    }

    @Test
    fun thePlayersMovesAreSplitIntoPhasesAndAddUp() {
        // moves of the player in the five analysed games: White 4 + Black 6 + White 45 + Black 29 + White 25 = 109... counted from the games
        var expected = 0
        for ((id, line) in lines) {
            val color = games.first { it.gameId == id }.color
            expected +=
                (1 until line.evals.size).count {
                    app.postmortem.core.board
                        .moverAt(it, line.whiteMovesFirst) == color
                }
        }
        assertEquals(expected, stats.byPhase.values.sumOf { it.moves })
        assertTrue(stats.byPhase.getValue(Phase.OPENING).moves >= 4 + 6, "the short games are all opening")
        assertTrue(stats.byPhase.getValue(Phase.MIDDLEGAME).moves > 0, "the long games leave the opening")
        // the one blunder is in game 1, ply 5, inside the opening: 315 cp over the opening's moves of the player
        val opening = stats.byPhase.getValue(Phase.OPENING)
        assertEquals(1, opening.blunders)
        assertEquals(0, stats.byPhase.getValue(Phase.MIDDLEGAME).blunders + stats.byPhase.getValue(Phase.ENDGAME).blunders)
        assertEquals(315.0 / opening.moves, opening.acpl!!, 1e-9)
    }

    @Test
    fun theJudgementsPerGameAreThePlayersOwnAsTheSummaryShowsThem() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), stats.perGame.map { it.gameId })
        assertEquals(1, stats.perGame.first().blunders)
        assertEquals(0, stats.perGame.drop(1).sumOf { it.blunders + it.mistakes + it.inaccuracies })
        // the same numbers as that game's report (what its Summary tab shows)
        val report = lines.getValue(1L).report
        assertEquals(report.white.blunders, stats.perGame.first().blunders)
    }

    @Test
    fun howGamesEndedByOutcome() {
        assertEquals(mapOf(Ending.CHECKMATE to 2, Ending.RESIGNATION to 1), stats.endings.getValue(Outcome.WIN))
        assertEquals(mapOf(Ending.RESIGNATION to 1), stats.endings.getValue(Outcome.LOSS))
        assertEquals(
            1,
            stats.endings
                .getValue(Outcome.DRAW)
                .values
                .sum(),
        )
        assertTrue(Outcome.UNFINISHED !in stats.endings)
    }

    @Test
    fun aPlayerWithNoGamesHasEmptyStats() {
        val none = PlayerStats.of(emptyList(), emptyMap(), book)
        assertEquals(0, none.totals.games)
        assertNull(none.totals.scorePercent)
        assertNull(none.totals.accuracy)
        assertTrue(none.series.isEmpty())
        assertEquals(0, none.byPhase.getValue(Phase.OPENING).moves)
        assertNull(none.byPhase.getValue(Phase.OPENING).acpl)
    }

    @Test
    fun phasesJustInsideAndOutsideEachRule() {
        val middle = "r4rk1/pp3ppp/2n1bn2/3q4/3P4/2N1BN2/PP3PPP/R2Q1RK1 w - - 0 15"
        // 12th move: ply 23 and 24 are opening, ply 25 is not (no book: depth 0)
        assertEquals(Phase.OPENING, GamePhases.of(23, middle, 0))
        assertEquals(Phase.OPENING, GamePhases.of(24, middle, 0))
        assertEquals(Phase.MIDDLEGAME, GamePhases.of(25, middle, 0), "13th move, queens and rooks on")
        // the book still knows the position before the move: the opening goes on past move 12
        assertEquals(Phase.OPENING, GamePhases.of(30, middle, 29))
        assertEquals(Phase.MIDDLEGAME, GamePhases.of(31, middle, 29))
        // queens traded: an endgame, but not before the 13th move
        val noQueens = "r4rk1/pp3ppp/2n1bn2/8/3P4/2N1BN2/PP3PPP/R4RK1 w - - 0 15"
        assertEquals(Phase.OPENING, GamePhases.of(20, noQueens, 0), "queens traded on move 10 are still the opening")
        assertEquals(Phase.ENDGAME, GamePhases.of(26, noQueens, 0))
    }

    @Test
    fun anEndgameIsNoQueensOrAtMostThirteenPointsOfPiecesForEachSide() {
        // queen and knight each: 12 points
        assertTrue(GamePhases.isEndgame("4k3/pp3ppp/2n5/3q4/8/2N5/PP3PPP/3QK3 w - - 0 30"))
        // White rook, rook and knight is 13 points, Black's queen 9: both within the limit
        assertTrue(GamePhases.isEndgame("4k3/pp3ppp/8/3q4/8/2N5/PP3PPP/R3K2R w - - 0 30"))
        // White queen and rook is 14 points: over the limit by one
        assertTrue(!GamePhases.isEndgame("4k3/pp3ppp/8/3q4/8/8/PP3PPP/R2QK3 w - - 0 30"))
        // queen and two knights against queen and knight: 15 against 12
        assertTrue(!GamePhases.isEndgame("4k3/pp3ppp/2n5/3q4/8/2N5/PP3PPP/2NQK3 w - - 0 30"))
        // no queens at all, whatever else is left
        assertTrue(GamePhases.isEndgame("r3k2r/ppp2ppp/2n1bn2/8/8/2N1BN2/PPP2PPP/R3K2R w KQkq - 0 30"))
        assertTrue(!GamePhases.isEndgame(STANDARD_START_FEN))
    }

    @Test
    fun howARealGameEndsFromItsTagsAndLastPosition() {
        fun ending(
            name: String,
            outcome: Outcome,
        ) = lineOf(name).let { line ->
            GamePhases.ending(
                Regex("""\[Termination "([^"]*)"]""").find(Fixtures.text(name))?.groupValues?.get(1),
                outcome,
                line.sans.last(),
                line.fens,
            )
        }
        assertEquals(Ending.CHECKMATE, ending("lichess-mate-castle", Outcome.WIN))
        assertEquals(Ending.CHECKMATE, ending("scholars-mate", Outcome.WIN))
        assertEquals(Ending.RESIGNATION, ending("lichess-short-resign", Outcome.WIN), "Normal, a decisive result and no mate")
        assertEquals(Ending.RESIGNATION, ending("chesscom-short-resign", Outcome.WIN))
        assertEquals(Ending.INSUFFICIENT_MATERIAL, ending("chesscom-promotion-long", Outcome.DRAW))
        assertEquals(Ending.TIME, GamePhases.ending("Time forfeit", Outcome.LOSS, "Qe2", listOf(STANDARD_START_FEN)))
        assertEquals(Ending.TIME, GamePhases.ending("Anna won on time", Outcome.WIN, "Qe2", listOf(STANDARD_START_FEN)))
        assertEquals(Ending.AGREEMENT, GamePhases.ending("Game drawn by agreement", Outcome.DRAW, "Qe2", listOf(STANDARD_START_FEN)))
        assertEquals(Ending.REPETITION, GamePhases.ending("Game drawn by repetition", Outcome.DRAW, "Qe2", listOf(STANDARD_START_FEN)))
        assertEquals(Ending.AGREEMENT, GamePhases.ending("Normal", Outcome.DRAW, "Qe2", listOf(STANDARD_START_FEN)))
        assertEquals(Ending.OTHER, GamePhases.ending("Abandoned", Outcome.WIN, "Qe2", listOf(STANDARD_START_FEN)))
        assertEquals(Ending.OTHER, GamePhases.ending("Normal", Outcome.UNFINISHED, "Qe2", listOf(STANDARD_START_FEN)))
        // stalemate on the board, whatever the tag says
        val stalemate = "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"
        assertEquals(Ending.STALEMATE, GamePhases.ending("Normal", Outcome.DRAW, "Qf7", listOf(STANDARD_START_FEN, stalemate)))
        // a position three times: the same placement, side to move and castling rights
        val a = "4k3/8/8/8/8/8/4Q3/4K3 w - - 0 1"
        val b = "4k3/8/8/8/8/8/3Q4/4K3 b - - 1 1"
        assertEquals(Ending.REPETITION, GamePhases.ending("Normal", Outcome.DRAW, "Qe2", listOf(a, b, a, b, a)))
    }

    @Test
    fun fiveHundredGamesOfFortyMovesAreWorkedOutInUnderASecond() {
        val base = Fixtures.game("chesscom-short-resign")
        val fens = List(base.positionCount) { base.fenAt(it) }
        val evals = List(base.positionCount) { Eval.Cp((it % 7) * 20 - 40) }
        val many =
            (1L..500L).map { id ->
                played(
                    id,
                    if (id % 2 ==
                        0L
                    ) {
                        PieceColor.WHITE
                    } else {
                        PieceColor.BLACK
                    },
                    Outcome.entries[(id % 3).toInt()],
                    50.0 + id % 50,
                    1500,
                    1500 + id.toInt() % 300 - 150,
                    Speed.entries[(id % 4).toInt()],
                    "Normal",
                )
            }
        val lines = many.associate { it.gameId to MainLine(base.plies.map { p -> p.san }, evals, true, fens) }
        PlayerStats.of(many.take(5), lines, book) // warms the code up, as the app's first Report does not matter here
        val started = System.nanoTime()
        val result = PlayerStats.of(many, lines, book)
        val millis = (System.nanoTime() - started) / 1_000_000
        println("PlayerStats: 500 games of ${base.plies.size} plies in $millis ms")
        assertEquals(500, result.totals.games)
        assertTrue(millis < 1_000, "took $millis ms: something is quadratic")
    }
}
