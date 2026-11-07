package app.postmortem.core.store

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.positionKey
import app.postmortem.core.board.Moves
import app.postmortem.core.board.PieceColor
import app.postmortem.core.line.Cursor
import app.postmortem.core.line.Lines
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.pgn.Game
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameStoreTest {
    private val db = JdbcSqlDb.open()
    private var now = 1_000L
    private val store = GameStore(db) { now++ }

    @AfterTest
    fun close() = db.close()

    /** One eval per position, using every kind: centipawns, a mate for each side, and the mate on the board at the end. */
    private fun evalsOf(game: Game): List<Eval> =
        List(game.positionCount) { position ->
            when {
                position == game.positionCount - 1 -> Eval.Checkmate(PieceColor.WHITE)
                position % 5 == 3 -> Eval.Mate(if (position % 2 == 0) -2 else 3)
                else -> Eval.Cp(position * 7 - 40)
            }
        }

    private fun newGame(
        name: String,
        pgn: String = Fixtures.text(name),
    ): NewGame {
        val game = Fixtures.game(name)
        return NewGame(
            pgn,
            game,
            evalsOf(game),
            "test engine, 1000 nodes",
            whiteAccuracy = 91.5,
            blackAccuracy = 78.25,
            opening = "Italian Game",
        )
    }

    private fun Lines.play(
        game: Game,
        cursor: Cursor,
        uci: String,
    ) = play(game, cursor, checkNotNull(Moves.play(fen(game, cursor), uci.substring(0, 2), uci.substring(2, 4), uci.getOrNull(4))))

    @Test
    fun aSavedGameComesBackEqualWithItsEvalsOfEveryKind() {
        val new = newGame("lichess-short-resign")
        val id = store.save(new)
        val saved = assertNotNull(store.load(id))
        assertEquals(new.game, saved.game)
        assertEquals(new.evals, saved.evals)
        assertEquals(new.pgn, saved.pgn)
        assertEquals(new.analysisSettings, saved.analysisSettings)
        assertEquals(91.5, saved.whiteAccuracy)
        assertEquals(78.25, saved.blackAccuracy)
        assertEquals("Italian Game", saved.opening)
        assertEquals(gameKey(new.game), saved.key)
        assertTrue(saved.lines.all.isEmpty())
        assertTrue(new.evals.any { it is Eval.Mate } && new.evals.any { it is Eval.Checkmate })
    }

    /** The game's own next move as each position's "best move" (the last position has none): a stand-in with real, legal UCI. */
    private fun bestMovesOf(game: Game): List<String?> = game.plies.map { it.uci } + null

    @Test
    fun bestMovesComeBackWithTheGameAndAGameSavedWithoutThemHasNone() {
        val new = newGame("lichess-short-resign")
        val withMoves = store.save(new.copy(bestMoves = bestMovesOf(new.game)))
        assertEquals(bestMovesOf(new.game), store.load(withMoves)?.bestMoves)
        val without = store.save(newGame("scholars-mate"))
        assertEquals(List(Fixtures.game("scholars-mate").positionCount) { null }, store.load(without)?.bestMoves)
    }

    @Test
    fun aBestMoveCanBeReadAndFilledInLater() {
        val new = newGame("scholars-mate")
        val id = store.save(new)
        assertEquals(null, store.bestMove(id, 2))
        store.setBestMove(id, 2, "d2d4")
        assertEquals("d2d4", store.bestMove(id, 2))
        assertEquals(null, store.bestMove(id, 3), "the others stay as they were")
        assertEquals("d2d4", store.load(id)?.bestMoves?.get(2))
        assertEquals(null, store.bestMove(id + 99, 2))
    }

    @Test
    fun bestMovesSurviveADeleteAndRestore() {
        val new = newGame("lichess-short-resign")
        val id = store.save(new.copy(bestMoves = bestMovesOf(new.game)))
        val saved = assertNotNull(store.load(id))
        store.delete(id)
        store.restore(saved)
        assertEquals(bestMovesOf(new.game), store.load(id)?.bestMoves)
    }

    @Test
    fun everyFixtureGameRoundTrips() {
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            val new = newGame(name)
            assertEquals(new.game, store.load(store.save(new))!!.game, name)
        }
    }

    @Test
    fun linesAndTheirEvalsAreSavedRebuiltAndCanBeSavedAgainAfterAChange() {
        val new = newGame("scholars-mate")
        val id = store.save(new)
        val game = new.game
        var played = Lines().play(game, Cursor.game(4), "d2d4")
        played = played.lines.play(game, played.cursor, "d7d5")
        played = played.lines.play(game, played.cursor, "c2c4")
        val second = played.lines.play(game, Cursor.game(4), "g1f3")
        val lines = second.lines
        val evals =
            lines.all
                .flatMap { it.plies }
                .withIndex()
                .associate { (i, ply) -> positionKey(ply.fen) to Eval.Cp(i * 11) }
        store.saveLines(id, lines, evals)
        val saved = store.load(id)!!
        assertEquals(lines.all, saved.lines.all)
        assertEquals(evals, saved.lineEvals)
        // the next line made after loading gets a fresh number
        val third = saved.lines.play(game, Cursor.game(2), "g1f3")
        assertEquals(
            3,
            third.lines.all
                .last()
                .id,
        )
        // delete the first line, save, load: it is gone and the other stays
        store.saveLines(id, lines.delete(1), evals)
        assertEquals(
            listOf(2),
            store
                .load(id)!!
                .lines.all
                .map { it.id },
        )
        assertEquals(new.evals, store.load(id)!!.evals, "the game's own evals are untouched")
    }

    @Test
    fun aLineWithoutAnEvalYetIsKeptAndTheEvalIsMissingOnLoad() {
        val new = newGame("scholars-mate")
        val id = store.save(new)
        val played = Lines().play(new.game, Cursor.game(4), "d2d4")
        store.saveLines(id, played.lines, emptyMap())
        val saved = store.load(id)!!
        assertEquals(1, saved.lines.all.size)
        assertTrue(saved.lineEvals.isEmpty())
    }

    @Test
    fun theSameGameHasTheSameKeyWhateverTheOrderOfItsTagsAndAChangedTagOrMoveMakesAnother() {
        val game = Fixtures.game("lichess-short-resign")
        assertEquals(gameKey(game), gameKey(Fixtures.game("lichess-short-resign")))
        assertEquals(
            gameKey(game),
            gameKey(
                game.copy(
                    headers =
                        game.headers.entries
                            .reversed()
                            .associate { it.key to it.value },
                ),
            ),
        )
        assertNotEquals(gameKey(game), gameKey(game.copy(headers = game.headers + ("White" to "Somebody else"))))
        assertNotEquals(gameKey(game), gameKey(game.copy(plies = game.plies.dropLast(1))))
        assertNotEquals(gameKey(game), gameKey(game.copy(startFen = game.startFen.replace(" w ", " b "))))
        assertNotEquals(gameKey(Fixtures.game("scholars-mate")), gameKey(game))
    }

    @Test
    fun aSavedGameIsFoundByItsKeyAndAnotherGameIsNot() {
        val new = newGame("lichess-short-resign")
        assertNull(store.findId(new.game))
        val id = store.save(new)
        assertEquals(id, store.findId(Fixtures.game("lichess-short-resign")))
        assertNull(store.findId(Fixtures.game("scholars-mate")))
    }

    @Test
    fun aSavedGameIsFoundWithItsLinesByTheGameThatWasPasted() {
        val new = newGame("scholars-mate")
        assertNull(store.findSaved(new.game))
        val id = store.save(new)
        val played = Lines().play(new.game, Cursor.game(4), "d2d4")
        store.saveLines(id, played.lines, emptyMap())
        val found = assertNotNull(store.findSaved(Fixtures.game("scholars-mate")))
        assertEquals(id, found.id)
        assertEquals(played.lines.all, found.lines.all)
    }

    @Test
    fun theSameGameCannotBeSavedTwice() {
        val new = newGame("scholars-mate")
        store.save(new)
        assertFailsWith<Exception> { store.save(new) }
        assertEquals(1, store.list().size, "the failed save left nothing behind")
        assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM node WHERE ply = 0").single()["n"])
    }

    @Test
    fun theListIsNewestWorkedOnFirstWithPlayersAccuraciesAndNumberOfLines() {
        val first = store.save(newGame("scholars-mate"))
        val second = store.save(newGame("lichess-short-resign"))
        assertEquals(listOf(second, first), store.list().map { it.id })
        val game = Fixtures.game("scholars-mate")
        store.saveLines(
            first,
            Lines().play(game, Cursor.game(4), "d2d4").lines.let { it.play(game, Cursor.game(2), "g1f3").lines },
            emptyMap(),
        )
        val list = store.list()
        assertEquals(listOf(first, second), list.map { it.id }, "working on a game moves it up")
        assertEquals(2, list.first().lineCount)
        assertEquals(0, list.last().lineCount)
        val summary = list.last()
        assertEquals(Fixtures.game("lichess-short-resign").headers["White"], summary.overview.white)
        assertEquals(91.5, summary.whiteAccuracy)
        assertEquals("Italian Game", summary.opening)
    }

    @Test
    fun deletingAGameRemovesItsPositionsAndEvalsAndRestoringPutsItBackExactly() {
        val new = newGame("lichess-short-resign")
        val id = store.save(new)
        val game = new.game
        val played = Lines().play(game, Cursor.game(6), "a2a3")
        store.saveLines(
            id,
            played.lines,
            mapOf(
                positionKey(
                    played.lines.all
                        .single()
                        .plies
                        .single()
                        .fen,
                ) to Eval.Cp(5),
            ),
        )
        val before = store.load(id)!!
        store.delete(id)
        assertNull(store.load(id))
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM node").single()["n"])
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM eval").single()["n"])
        store.restore(before)
        assertEquals(before, store.load(id))
        assertEquals(listOf(id), store.list().map { it.id })
    }

    @Test
    fun theBestMoveColumnIsPlainSqlForAnyTool() {
        val new = newGame("scholars-mate")
        store.save(new.copy(bestMoves = bestMovesOf(new.game)))
        val row = db.query("SELECT count(*) AS n, count(best_move) AS moves FROM eval").single()
        assertEquals(new.game.positionCount.toLong(), (row["n"] as Number).toLong())
        assertEquals(new.game.positionCount - 1L, (row["moves"] as Number).toLong(), "every position but the last has a move")
    }

    @Test
    fun theOpeningCanBeSetLater() {
        val id = store.save(newGame("scholars-mate").copy(opening = null))
        assertNull(store.load(id)!!.opening)
        store.setOpening(id, "King's Pawn Game")
        assertEquals("King's Pawn Game", store.load(id)!!.opening)
    }

    @Test
    fun aFailedChangeInsideATransactionLeavesNothingBehind() {
        val new = newGame("scholars-mate")
        assertFailsWith<IllegalStateException> {
            db.transaction {
                store.save(new)
                error("the app was killed here")
            }
        }
        assertTrue(store.list().isEmpty())
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM node").single()["n"])
    }

    @Test
    fun theTreeIsAStandardSqliteTableAnyToolCanRead() {
        val id = store.save(newGame("scholars-mate"))
        val root = db.query("SELECT * FROM node WHERE game_id = ? AND ply = 0", listOf(id)).single()
        assertNull(root["parent_id"])
        assertNull(root["uci"])
        val first = db.query("SELECT * FROM node WHERE game_id = ? AND ply = 1", listOf(id)).single()
        assertEquals(root["id"], first["parent_id"])
        assertEquals("e2e4", first["uci"])
        assertEquals(1L, first["is_main_line"])
        val headers = db.query("SELECT headers_json AS json FROM game").single()["json"] as String
        assertTrue(headers.startsWith("{") && "\"Result\":" in headers, headers)
    }
}
