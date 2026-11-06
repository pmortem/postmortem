package app.postmortem.core.store

/**
 * The little of SQL the game store needs, so the store and its schema live in `:core` and run the same way on the phone (Android's
 * SQLite) and in the JVM tests (a real SQLite file). Values are `Long`, `Double`, `String` or null; [query] gives each row by column name.
 */
interface SqlDb {
    fun execute(
        sql: String,
        args: List<Any?> = emptyList(),
    )

    fun query(
        sql: String,
        args: List<Any?> = emptyList(),
    ): List<Map<String, Any?>>

    /**
     * Runs [block] as one transaction: everything in it happens, or none of it if it throws. A call inside another one joins it, and
     * only the outermost commits.
     */
    fun <T> transaction(block: () -> T): T
}

/** The id of the row the last insert made. */
fun SqlDb.lastInsertId(): Long = (query("SELECT last_insert_rowid() AS id").single()["id"] as Number).toLong()

/** The schema version of the file, kept in SQLite's `user_version`. */
var SqlDb.userVersion: Int
    get() = (query("PRAGMA user_version").single().values.single() as Number).toInt()
    set(value) {
        execute("PRAGMA user_version = $value")
    }
