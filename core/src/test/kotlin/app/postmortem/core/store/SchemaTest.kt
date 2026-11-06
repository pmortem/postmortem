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
            assertEquals(listOf("eval", "game", "node"), tables(db))
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

    @Test
    fun aVersionOneFileWithGamesMovesToTheNextVersionAndKeepsItsData() {
        val file = createTempFile("postmortem", ".sqlite").toFile().apply { deleteOnExit() }
        val game = Fixtures.game("scholars-mate")
        val future =
            listOf(
                listOf("ALTER TABLE game ADD COLUMN note TEXT NOT NULL DEFAULT ''", "UPDATE game SET note = 'migrated'"),
            )
        val id =
            JdbcSqlDb.open(file.path).use { db ->
                val id =
                    GameStore(
                        db,
                    ) { 5L }.save(NewGame(Fixtures.text("scholars-mate"), game, List(game.positionCount) { Eval.Cp(it) }, "test"))
                assertEquals(1, db.userVersion)
                id
            }
        JdbcSqlDb.open(file.path).use { db ->
            Schema.migrate(db, upTo = 2, migrations = Schema.steps + future)
            assertEquals(2, db.userVersion)
            val row = db.query("SELECT note, pgn FROM game WHERE id = ?", listOf(id)).single()
            assertEquals("migrated", row["note"])
            assertEquals(Fixtures.text("scholars-mate"), row["pgn"])
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
            assertFailsWith<Exception> { Schema.migrate(db, upTo = 2, migrations = broken) }
            assertEquals(1, db.userVersion)
            assertEquals(0, db.query("SELECT name FROM pragma_table_info('game') WHERE name = 'ok'").size, "the half step was rolled back")
        }
    }
}
