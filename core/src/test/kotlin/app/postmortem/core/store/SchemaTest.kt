package app.postmortem.core.store

import app.postmortem.core.analysis.Eval
import app.postmortem.core.pgn.Fixtures
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SchemaTest {
    private fun tables(db: SqlDb) =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map { it["name"] }

    @Test
    fun anEmptyFileIsCreatedAtTheCurrentVersion() {
        JdbcSqlDb.open().use { db ->
            Schema.migrate(db)
            assertEquals(Schema.CURRENT_VERSION, db.userVersion)
            assertEquals(listOf("drill", "eval", "game", "node"), tables(db))
        }
    }

    @Test
    fun openingAnUpToDateFileChangesNothing() {
        JdbcSqlDb.open().use { db ->
            Schema.migrate(db)
            Schema.migrate(db)
            assertEquals(Schema.CURRENT_VERSION, db.userVersion)
        }
    }

    /** A file as the first version of the app wrote it: version 1, one saved game with its positions and evals. */
    private fun versionOneFileWithAGame(): Pair<String, Long> {
        val file = createTempFile("postmortem", ".sqlite").toFile().apply { deleteOnExit() }
        val game = Fixtures.game("scholars-mate")
        val id =
            JdbcSqlDb.open(file.path).use { db ->
                Schema.migrate(db, upTo = 1)
                db.execute(
                    "INSERT INTO game (game_key, pgn, headers_json, start_fen, created, updated, analysis_settings) VALUES (?, ?, ?, ?, 5, 5, 'old')",
                    listOf("key", Fixtures.text("scholars-mate"), HeadersJson.encode(game.headers), game.startFen),
                )
                val id = db.lastInsertId()
                for (position in 0 until game.positionCount) {
                    val ply = game.plies.getOrNull(position - 1)
                    db.execute(
                        "INSERT INTO node (game_id, ply, uci, san, fen, is_main_line, line_order) VALUES (?, ?, ?, ?, ?, 1, 0)",
                        listOf(id, position, ply?.uci, ply?.san, game.fenAt(position)),
                    )
                    db.execute("INSERT INTO eval (node_id, cp) VALUES (?, ?)", listOf(db.lastInsertId(), position))
                }
                assertEquals(1, db.userVersion)
                id
            }
        return file.path to id
    }

    @Test
    fun aVersionOneFileWithGamesMovesToVersionTwoWithItsGamesIntactAndAnEmptyDrillTable() {
        val (path, id) = versionOneFileWithAGame()
        JdbcSqlDb.open(path).use { db ->
            val store = GameStore(db)
            assertEquals(2, db.userVersion)
            assertEquals(emptyList(), db.query("SELECT * FROM drill"))
            val saved = assertNotNull(store.load(id))
            assertEquals(Fixtures.game("scholars-mate"), saved.game)
            assertEquals(List(saved.game.positionCount) { Eval.Cp(it) }, saved.evals)
            assertEquals(emptyMap(), saved.drill)
            store.recordDrill(id, 2, solved = false)
            assertEquals(1, store.drillProgress(listOf(id)).size)
        }
    }

    @Test
    fun aVersionTwoFileOpenedByThisCodeIsUntouched() {
        val (path, _) = versionOneFileWithAGame()
        JdbcSqlDb.open(path).use { GameStore(it).recordDrill(1, 2, solved = true) }
        JdbcSqlDb.open(path).use { db ->
            Schema.migrate(db)
            assertEquals(2, db.userVersion)
            assertEquals(1, db.query("SELECT * FROM drill").size, "the progress is still there")
        }
    }

    @Test
    fun aLaterStepMovesAVersionTwoFileForwardAndKeepsItsData() {
        val (path, id) = versionOneFileWithAGame()
        val future =
            listOf(
                listOf("ALTER TABLE game ADD COLUMN note TEXT NOT NULL DEFAULT ''", "UPDATE game SET note = 'migrated'"),
            )
        JdbcSqlDb.open(path).use { GameStore(it).recordDrill(id, 2, solved = true) }
        JdbcSqlDb.open(path).use { db ->
            Schema.migrate(db, upTo = 3, migrations = Schema.steps + future)
            assertEquals(3, db.userVersion)
            val row = db.query("SELECT note, pgn FROM game WHERE id = ?", listOf(id)).single()
            assertEquals("migrated", row["note"])
            assertEquals(Fixtures.text("scholars-mate"), row["pgn"])
            assertEquals(1, db.query("SELECT * FROM drill").size)
        }
    }

    @Test
    fun aFileFromANewerAppIsRefusedAndLeftAlone() {
        JdbcSqlDb.open().use { db ->
            Schema.migrate(db)
            db.userVersion = Schema.CURRENT_VERSION + 1
            assertFailsWith<IllegalStateException> { Schema.migrate(db) }
            assertEquals(Schema.CURRENT_VERSION + 1, db.userVersion)
            assertNotNull(db.query("SELECT 1 AS one").single())
        }
    }

    @Test
    fun aStepThatFailsLeavesTheFileAtTheVersionBefore() {
        JdbcSqlDb.open().use { db ->
            Schema.migrate(db)
            val broken = Schema.steps + listOf(listOf("ALTER TABLE game ADD COLUMN ok TEXT", "THIS IS NOT SQL"))
            assertFailsWith<Exception> { Schema.migrate(db, upTo = Schema.CURRENT_VERSION + 1, migrations = broken) }
            assertEquals(Schema.CURRENT_VERSION, db.userVersion)
            assertEquals(0, db.query("SELECT name FROM pragma_table_info('game') WHERE name = 'ok'").size, "the half step was rolled back")
        }
    }
}
