package app.postmortem.core.store

/**
 * The tables of `postmortem.sqlite`, as versioned steps: step n takes a file from version n-1 to n. A file is only ever moved
 * forward, each step in its own transaction, and one with a newer version than this code knows is refused, never changed.
 *
 * - `game`: one row per analysed game: the duplicate [GameKey], the PGN as pasted, the tags as JSON, the start FEN, when it was
 *   saved and last changed, the analysis settings line and the two accuracies.
 * - `node`: every position as a tree. The root (ply 0, no move) is the start position; the game's moves are a chain under it
 *   (`is_main_line` 1); a line the user played branches from a main-line node (`is_main_line` 0, `line_order` its number).
 *   `ply` counts half-moves from the start, so a line's first node has the branch ply + 1.
 * - `eval`: the evaluation of a node's position, in White's point of view: `cp`, or `mate` (moves, negative for Black), or
 *   `checkmate_winner` (`w` or `b`) for a mate on the board. `best_move` and `nodes` are kept for later.
 */
object Schema {
    const val CURRENT_VERSION = 1

    /** The steps in order; the tests add a later one to move a real file forward. */
    internal val steps: List<List<String>> =
        listOf(
            listOf(
                """
                CREATE TABLE game (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    game_key TEXT NOT NULL UNIQUE,
                    pgn TEXT NOT NULL,
                    headers_json TEXT NOT NULL,
                    start_fen TEXT NOT NULL,
                    created INTEGER NOT NULL,
                    updated INTEGER NOT NULL,
                    analysis_settings TEXT NOT NULL,
                    white_accuracy REAL,
                    black_accuracy REAL,
                    opening TEXT
                )
                """.trimIndent(),
                """
                CREATE TABLE node (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    game_id INTEGER NOT NULL REFERENCES game(id) ON DELETE CASCADE,
                    parent_id INTEGER REFERENCES node(id) ON DELETE CASCADE,
                    ply INTEGER NOT NULL,
                    uci TEXT,
                    san TEXT,
                    fen TEXT NOT NULL,
                    is_main_line INTEGER NOT NULL,
                    line_order INTEGER NOT NULL
                )
                """.trimIndent(),
                "CREATE INDEX node_game ON node(game_id)",
                """
                CREATE TABLE eval (
                    node_id INTEGER PRIMARY KEY REFERENCES node(id) ON DELETE CASCADE,
                    cp INTEGER,
                    mate INTEGER,
                    checkmate_winner TEXT,
                    best_move TEXT,
                    nodes INTEGER
                )
                """.trimIndent(),
            ),
        )

    /** Brings [db] to [CURRENT_VERSION], creating it when it is empty. A newer file than this code knows is an error. */
    fun migrate(
        db: SqlDb,
        upTo: Int = CURRENT_VERSION,
        migrations: List<List<String>> = steps,
    ) {
        db.execute("PRAGMA foreign_keys = ON")
        val found = db.userVersion
        check(found <= migrations.size) { "the database is version $found, this app knows up to ${migrations.size}" }
        for (version in found + 1..upTo) {
            db.transaction {
                migrations[version - 1].forEach { db.execute(it) }
                db.userVersion = version
            }
        }
    }
}
