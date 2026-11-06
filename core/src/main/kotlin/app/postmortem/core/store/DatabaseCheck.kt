package app.postmortem.core.store

/**
 * Looks at a file before it is used as a database, without changing it. A file that is not ours, or from a newer app, is never opened
 * for writing; an older one is migrated on a copy by the caller.
 */
sealed interface DatabaseCheck {
    /** The first bytes are not SQLite's. */
    data object NotSqlite : DatabaseCheck

    /** A SQLite file without our tables. */
    data object NotOurs : DatabaseCheck

    /** Written by a newer app than this one, which would not know what it holds. */
    data class Newer(
        val version: Int,
    ) : DatabaseCheck

    /** A database of ours with [games] games; [needsMigration] when its schema is older than the app's. */
    data class Ok(
        val version: Int,
        val games: Int,
    ) : DatabaseCheck {
        val needsMigration: Boolean get() = version < Schema.CURRENT_VERSION
    }

    companion object {
        private val HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        private val TABLES = setOf("game", "node", "eval")

        /** True when [firstBytes] (at least the first 16 bytes of the file) start like a SQLite file. */
        fun hasSqliteHeader(firstBytes: ByteArray): Boolean =
            firstBytes.size >= HEADER.size && firstBytes.copyOf(HEADER.size).contentEquals(HEADER)

        /** What [db], an open SQLite file, is: [NotOurs], [Newer], or [Ok]. ([NotSqlite] is found from the header, before opening.) */
        fun inspect(db: SqlDb): DatabaseCheck {
            val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it["name"] as String }.toSet()
            if (!tables.containsAll(TABLES)) return NotOurs
            val version = db.userVersion
            if (version > Schema.CURRENT_VERSION) return Newer(version)
            val games = (db.query("SELECT COUNT(*) AS n FROM game").single()["n"] as Number).toInt()
            return Ok(version, games)
        }
    }
}
