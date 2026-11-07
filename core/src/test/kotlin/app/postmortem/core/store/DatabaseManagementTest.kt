package app.postmortem.core.store

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.positionKey
import app.postmortem.core.board.Moves
import app.postmortem.core.line.Cursor
import app.postmortem.core.line.Lines
import app.postmortem.core.pgn.Fixtures
import kotlin.io.path.createTempFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatabaseManagementTest {
    private val opened = ArrayList<JdbcSqlDb>()

    @AfterTest
    fun close() = opened.forEach { it.close() }

    private fun memory() = JdbcSqlDb.open().also { opened += it }

    private fun file(): String = createTempFile("postmortem", ".sqlite").toFile().apply { deleteOnExit() }.path

    private fun newGame(name: String): NewGame {
        val game = Fixtures.game(name)
        return NewGame(Fixtures.text(name), game, List(game.positionCount) { Eval.Cp(it * 3) }, "test", 90.0, 70.0, "Opening of $name")
    }

    private fun GameStore.withLine(
        id: Long,
        name: String,
    ): Lines {
        val game = Fixtures.game(name)
        val move = checkNotNull(Moves.play(game.fenAt(2), "g1", "f3"))
        val played = Lines().play(game, Cursor.game(2), move)
        saveLines(id, played.lines, mapOf(positionKey(move.fen) to Eval.Cp(44)))
        return played.lines
    }

    @Test
    fun aNameGetsTheExtensionWhenItIsLeftOutAndKeepsItWhenItIsThere() {
        assertEquals(DatabaseName.Valid("club.sqlite"), DatabaseName.parse("club"))
        assertEquals(DatabaseName.Valid("club.sqlite"), DatabaseName.parse("club.sqlite"))
        assertEquals(DatabaseName.Valid("club.sqlite"), DatabaseName.parse("  club.SQLITE  "))
        assertEquals(DatabaseName.Valid("online games 2025.sqlite"), DatabaseName.parse("online games 2025"))
        assertEquals(DatabaseName.Valid("my-db_1.sqlite"), DatabaseName.parse("my-db_1"))
        assertEquals(DatabaseName.Valid("Schach Übung.sqlite"), DatabaseName.parse("Schach Übung"))
        assertEquals(DatabaseName.DEFAULT, (DatabaseName.parse("postmortem") as DatabaseName.Valid).fileName)
    }

    @Test
    fun aNameWithAnythingButLettersDigitsDashUnderscoreAndSpacesIsRefused() {
        for (bad in listOf("a/b", "..", "../x", "a.b", "a:b", "a*b", "a\\b", "a\nb", "club.sqlite.db", "x;drop")) {
            assertEquals(DatabaseName.Invalid(DatabaseName.Reason.BAD_CHARACTERS), DatabaseName.parse(bad), bad)
        }
        for (empty in listOf("", "   ", ".sqlite", " .sqlite ")) {
            assertEquals(DatabaseName.Invalid(DatabaseName.Reason.EMPTY), DatabaseName.parse(empty), empty)
        }
        assertEquals(DatabaseName.Invalid(DatabaseName.Reason.TOO_LONG), DatabaseName.parse("x".repeat(DatabaseName.MAX_LENGTH + 1)))
        assertIs<DatabaseName.Valid>(DatabaseName.parse("x".repeat(DatabaseName.MAX_LENGTH)))
    }

    @Test
    fun theSqliteHeaderIsRecognisedAndAnythingElseIsNot() {
        val real = createTempFile().toFile().apply { deleteOnExit() }
        JdbcSqlDb.open(real.path).use { GameStore(it) }
        assertTrue(DatabaseCheck.hasSqliteHeader(real.readBytes().copyOf(16)))
        assertTrue(!DatabaseCheck.hasSqliteHeader("This is a PGN, not a database".toByteArray()))
        assertTrue(!DatabaseCheck.hasSqliteHeader(ByteArray(0)))
        assertTrue(!DatabaseCheck.hasSqliteHeader(ByteArray(8)))
    }

    @Test
    fun aDatabaseOfOursIsOkWithItsVersionAndNumberOfGames() {
        val db = memory()
        val store = GameStore(db)
        assertEquals(DatabaseCheck.Ok(Schema.CURRENT_VERSION, 0), DatabaseCheck.inspect(db))
        store.save(newGame("scholars-mate"))
        store.save(newGame("lichess-short-resign"))
        val ok = assertIs<DatabaseCheck.Ok>(DatabaseCheck.inspect(db))
        assertEquals(2, ok.games)
        assertTrue(!ok.needsMigration)
    }

    @Test
    fun aSqliteFileWithoutOurTablesIsNotOurs() {
        val db = memory()
        db.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, text TEXT)")
        assertEquals(DatabaseCheck.NotOurs, DatabaseCheck.inspect(db))
        db.execute("CREATE TABLE game (id INTEGER PRIMARY KEY)")
        assertEquals(DatabaseCheck.NotOurs, DatabaseCheck.inspect(db), "a game table alone is not enough")
    }

    @Test
    fun aFileFromANewerAppIsRefusedAndStaysAsItWas() {
        val db = memory()
        GameStore(db).save(newGame("scholars-mate"))
        db.userVersion = Schema.CURRENT_VERSION + 3
        assertEquals(DatabaseCheck.Newer(Schema.CURRENT_VERSION + 3), DatabaseCheck.inspect(db))
        assertEquals(Schema.CURRENT_VERSION + 3, db.userVersion)
        assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM game").single()["n"])
    }

    @Test
    fun aSchemaOlderThanTheAppsNeedsMigrationAndTheCurrentOneDoesNot() {
        assertTrue(DatabaseCheck.Ok(Schema.CURRENT_VERSION - 1, 3).needsMigration)
        assertTrue(!DatabaseCheck.Ok(Schema.CURRENT_VERSION, 3).needsMigration)
    }

    @Test
    fun anOlderFileIsMigratedOnACopyAndThePickedFileItselfIsNeverChanged() {
        val picked = file()
        JdbcSqlDb.open(picked).use { GameStore(it).save(newGame("scholars-mate")) }
        val before =
            java.io
                .File(picked)
                .readBytes()
                .toList()
        // the copy is the one that is migrated (here to a pretend next version), then merged from
        val copy = file()
        java.io.File(picked).copyTo(java.io.File(copy), overwrite = true)
        val future = listOf(listOf("ALTER TABLE game ADD COLUMN note TEXT NOT NULL DEFAULT ''"))
        JdbcSqlDb.open(copy).use { db ->
            Schema.migrate(db, upTo = 2, migrations = Schema.steps + future)
            assertEquals(2, db.userVersion)
        }
        assertEquals(
            before,
            java.io
                .File(picked)
                .readBytes()
                .toList(),
        )
        JdbcSqlDb.open(picked).use { assertEquals(Schema.CURRENT_VERSION, it.userVersion) }
    }

    @Test
    fun mergingCarriesTheBestMoves() {
        val mine = GameStore(memory())
        val theirs = GameStore(memory())
        val game = Fixtures.game("lichess-short-resign")
        val moves = game.plies.map { it.uci } + null
        theirs.save(newGame("lichess-short-resign").copy(bestMoves = moves))
        mine.mergeFrom(theirs)
        assertEquals(moves, mine.load(mine.list().single().id)?.bestMoves)
    }

    @Test
    fun mergingAddsTheNewGamesSkipsTheOnesAlreadyThereAndCarriesLinesAndEvals() {
        val mine = GameStore(memory())
        val theirsDb = memory()
        val theirs = GameStore(theirsDb)
        val shared = mine.save(newGame("scholars-mate"))
        val sharedTheirs = theirs.save(newGame("scholars-mate"))
        val onlyTheirs = theirs.save(newGame("lichess-short-resign"))
        val lines = theirs.withLine(onlyTheirs, "lichess-short-resign")
        theirs.withLine(sharedTheirs, "scholars-mate")
        val result = mine.mergeFrom(theirs)
        assertEquals(MergeResult(added = 1, skipped = 1), result)
        assertEquals(2, mine.list().size)
        val added = mine.load(mine.list().first { it.id != shared }.id)!!
        assertEquals(Fixtures.game("lichess-short-resign"), added.game)
        assertEquals(lines.all, added.lines.all)
        assertEquals(
            mapOf(
                positionKey(
                    lines.all
                        .single()
                        .plies
                        .single()
                        .fen,
                ) to Eval.Cp(44),
            ),
            added.lineEvals,
        )
        assertEquals(theirs.load(onlyTheirs)!!.evals, added.evals)
        assertEquals(theirs.load(onlyTheirs)!!.created, added.created)
        assertEquals(theirs.load(onlyTheirs)!!.updated, added.updated)
        assertEquals(90.0, added.whiteAccuracy)
        assertEquals("Opening of lichess-short-resign", added.opening)
        assertTrue(
            mine
                .load(shared)!!
                .lines.all
                .isEmpty(),
            "a game that was already here is not changed by the merge",
        )
        assertEquals(2, theirs.list().size, "the merged file is only read")
    }

    @Test
    fun mergingTheSameFileAgainAddsNothing() {
        val mine = GameStore(memory())
        val theirs = GameStore(memory())
        theirs.save(newGame("scholars-mate"))
        theirs.save(newGame("lichess-short-resign"))
        assertEquals(MergeResult(2, 0), mine.mergeFrom(theirs))
        assertEquals(MergeResult(0, 2), mine.mergeFrom(theirs))
        assertEquals(2, mine.list().size)
    }

    @Test
    fun aMergeThatFailsHalfWayAddsNothing() {
        val mine = GameStore(memory())
        val theirsDb = memory()
        val theirs = GameStore(theirsDb)
        theirs.save(newGame("scholars-mate"))
        val second = theirs.save(newGame("lichess-short-resign"))
        // damage the second game so that reading it fails after the first has been added
        theirsDb.execute("DELETE FROM eval WHERE node_id IN (SELECT id FROM node WHERE game_id = ?)", listOf(second))
        assertFailsWith<IllegalStateException> { mine.mergeFrom(theirs) }
        assertTrue(mine.list().isEmpty())
        assertNotNull(theirs.list().firstOrNull())
    }

    @Test
    fun twoDatabasesStayApartWhateverIsDoneToOne() {
        val a = GameStore(memory())
        val b = GameStore(memory())
        a.save(newGame("scholars-mate"))
        assertTrue(b.list().isEmpty())
        b.save(newGame("lichess-short-resign"))
        assertEquals(1, a.list().size)
        assertEquals(1, b.list().size)
        assertEquals(null, a.findId(Fixtures.game("lichess-short-resign")))
    }
}
