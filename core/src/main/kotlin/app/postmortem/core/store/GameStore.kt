package app.postmortem.core.store

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.GameReport
import app.postmortem.core.analysis.Judgement
import app.postmortem.core.analysis.positionKey
import app.postmortem.core.board.PieceColor
import app.postmortem.core.line.Line
import app.postmortem.core.line.Lines
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.GameOverview
import app.postmortem.core.pgn.Ply
import app.postmortem.core.pgn.overview
import app.postmortem.core.pgn.whiteToMove

/** A game to save: what the analysis made of it. [evals] has one entry per position; [bestMoves] (UCI) as many, or none at all. */
data class NewGame(
    val pgn: String,
    val game: Game,
    val evals: List<Eval>,
    val analysisSettings: String,
    val whiteAccuracy: Double? = null,
    val blackAccuracy: Double? = null,
    val opening: String? = null,
    val bestMoves: List<String?> = emptyList(),
) {
    companion object {
        /**
         * The game as a finished analysis saves it: its evals and best moves, the accuracies of both sides (Lichess's, scoring the first
         * move from 15 cp in a game from the standard start, as the Summary does) and the opening its tags name.
         */
        fun analysed(
            pgn: String,
            game: Game,
            evals: List<Eval>,
            bestMoves: List<String?>,
            analysisSettings: String,
        ): NewGame {
            val start = if (game.startsFromStandardPosition) Eval.Cp(GameReport.LICHESS_START_CP) else null
            val report = GameReport.of(evals, game.whiteMovesFirst, startEval = start)
            return NewGame(
                pgn = pgn,
                game = game,
                evals = evals,
                analysisSettings = analysisSettings,
                whiteAccuracy = report.white.accuracy,
                blackAccuracy = report.black.accuracy,
                opening = game.overview.opening,
                bestMoves = bestMoves,
            )
        }
    }
}

/** A game waiting in the background queue, as stored: the PGN, when it was added, and `waiting` or `running`. */
data class QueuedRow(
    val id: Long,
    val pgn: String,
    val added: Long,
    val state: String,
)

/** Where the background queue keeps its games, so it carries on after the app was killed. */
interface QueueStore {
    /** The queued games in the order they were added. */
    fun queuedRows(): List<QueuedRow>

    /** Adds [pgn] at the end of the queue and returns its id. */
    fun addQueued(
        pgn: String,
        added: Long,
    ): Long

    fun setQueuedState(
        id: Long,
        state: String,
    )

    fun removeQueued(id: Long)
}

/** A game as stored: the analysis and the lines the user played, with the evals found for them (by `positionKey`). */
data class SavedGame(
    val id: Long,
    val key: String,
    val pgn: String,
    val game: Game,
    val evals: List<Eval>,
    val analysisSettings: String,
    val lines: Lines,
    val lineEvals: Map<String, Eval>,
    val whiteAccuracy: Double?,
    val blackAccuracy: Double?,
    val opening: String?,
    val created: Long,
    val updated: Long,
    /** The engine's best move (UCI) per main-line position; null where the game was saved without it or the position has no move. */
    val bestMoves: List<String?> = emptyList(),
    /** The progress of "Learn from your mistakes", by main-line position. */
    val drill: Map<Int, DrillProgress> = emptyMap(),
)

/** Where "Learn from your mistakes" stands for one position: [solved] or not, how many times it was tried or skipped, and when last. */
data class DrillProgress(
    val solved: Boolean,
    val attempts: Int,
    val lastTried: Long,
)

/** A practised position: the game and the main-line position (ply count from the start) before the player's mistake. */
data class DrillKey(
    val gameId: Long,
    val position: Int,
)

/** What the Library shows of a game. */
data class GameSummary(
    val id: Long,
    val overview: GameOverview,
    val opening: String?,
    val whiteAccuracy: Double?,
    val blackAccuracy: Double?,
    val lineCount: Int,
    val created: Long,
    val updated: Long,
)

/** What the Report needs of a saved game: its tags, opening and accuracies, read without its positions. */
data class ReportRow(
    val id: Long,
    val headers: Map<String, String>,
    val opening: String?,
    val whiteAccuracy: Double?,
    val blackAccuracy: Double?,
    /** When the game was saved (milliseconds): the date of a game whose `Date` tag says nothing. */
    val created: Long = 0,
) {
    /** The tags read as an overview, worked out once per row: the Report asks for it on every keystroke. */
    val overview: GameOverview by lazy(LazyThreadSafetyMode.PUBLICATION) { GameOverview.of(headers) }
}

/**
 * The main line of a game for the Report: the SAN of each ply (index 0 is ply 1) and the eval of each position (index n is after ply
 * n), with who moves first. Lines the user played are not in it.
 */
data class MainLine(
    val sans: List<String>,
    val evals: List<Eval>,
    val whiteMovesFirst: Boolean,
) {
    /** [GameReport]'s judgement of each move (null for a move that is fine), worked out once per line for every report that needs it. */
    val judgements: List<Judgement?> by lazy(LazyThreadSafetyMode.PUBLICATION) { GameReport.of(evals, whiteMovesFirst).judgements }
}

/** How a merge went: [added] games came in, [skipped] were already there. */
data class MergeResult(
    val added: Int,
    val skipped: Int,
)

/** What the app needs of the saved games; [GameStore] is the real one, tests and screens can use a stand-in. */
interface GameLibrary {
    /** The saved game with the same key as [game] (same start, moves and tags), or null. */
    fun findSaved(game: Game): SavedGame?

    fun save(new: NewGame): Long

    fun saveLines(
        id: Long,
        lines: Lines,
        lineEvals: Map<String, Eval>,
    )

    fun setOpening(
        id: Long,
        opening: String?,
    )

    fun load(id: Long): SavedGame?

    fun list(): List<GameSummary>

    /** What the Report reads of every saved game; the positions are not loaded. */
    fun reportRows(): List<ReportRow> = emptyList()

    /** The progress of the practised positions of the games with these [ids]. */
    fun drillProgress(ids: List<Long>): Map<DrillKey, DrillProgress> = emptyMap()

    /** Notes a try (or a skip) of the position [position] of game [gameId]: one more attempt, [solved] or not, now. */
    fun recordDrill(
        gameId: Long,
        position: Int,
        solved: Boolean,
    ) = Unit

    /** The best move (UCI) stored for the main-line position after [ply] plies of game [gameId], or null when there is none. */
    fun bestMove(
        gameId: Long,
        ply: Int,
    ): String? = null

    /** Stores [uci] as the best move of that position (a game saved before best moves were kept is filled in this way). */
    fun setBestMove(
        gameId: Long,
        ply: Int,
        uci: String,
    ) = Unit

    /** The main lines (moves and evals, nothing else) of the games with these [ids]; a game with a position without an eval is left out. */
    fun mainLines(ids: List<Long>): Map<Long, MainLine> = emptyMap()

    fun delete(id: Long)

    fun restore(saved: SavedGame)
}

private const val SQL_VARIABLES = 400

/** Where [GameStore]'s chunked queries put the list of ids. */
private const val IDS = "(:ids)"

/**
 * The saved games, in the SQLite file behind [db] (see [Schema]). Nothing here knows about Android: the same code runs on the phone
 * and in the tests. Every change is one transaction, so a game is never half saved.
 */
class GameStore(
    private val db: SqlDb,
    private val clock: () -> Long = System::currentTimeMillis,
) : GameLibrary,
    QueueStore {
    init {
        Schema.migrate(db)
    }

    /** The id of the saved game with the same key as [game] (same start, moves and tags), or null. */
    fun findId(game: Game): Long? = db.query("SELECT id FROM game WHERE game_key = ?", listOf(gameKey(game))).firstOrNull()?.long("id")

    override fun findSaved(game: Game): SavedGame? = findId(game)?.let(::load)

    /** Saves a new game with its main-line positions and evals, and returns its id. */
    override fun save(new: NewGame): Long =
        db.transaction {
            val now = clock()
            insertGame(
                null,
                gameKey(new.game),
                new.pgn,
                new.game,
                now,
                now,
                new.analysisSettings,
                new.whiteAccuracy,
                new.blackAccuracy,
                new.opening,
            )
            insertMainLine(db.lastInsertId(), new.game, new.evals, new.bestMoves)
        }

    @Suppress("LongParameterList")
    private fun insertGame(
        id: Long?,
        key: String,
        pgn: String,
        game: Game,
        created: Long,
        updated: Long,
        settings: String,
        whiteAccuracy: Double?,
        blackAccuracy: Double?,
        opening: String?,
    ) = db.execute(
        "INSERT INTO game (id, game_key, pgn, headers_json, start_fen, created, updated, " +
            "analysis_settings, white_accuracy, black_accuracy, opening) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        listOf(
            id,
            key,
            pgn,
            HeadersJson.encode(game.headers),
            game.startFen,
            created,
            updated,
            settings,
            whiteAccuracy,
            blackAccuracy,
            opening,
        ),
    )

    private fun insertMainLine(
        gameId: Long,
        game: Game,
        evals: List<Eval>,
        bestMoves: List<String?>,
    ): Long {
        var parent: Long? = null
        for (position in 0 until game.positionCount) {
            val ply = game.plies.getOrNull(position - 1)
            parent = insertNode(gameId, parent, position, ply?.uci, ply?.san, game.fenAt(position), mainLine = true, lineOrder = 0)
            evals.getOrNull(position)?.let { insertEval(parent, it, bestMoves.getOrNull(position)) }
        }
        return gameId
    }

    private fun insertNode(
        gameId: Long,
        parent: Long?,
        ply: Int,
        uci: String?,
        san: String?,
        fen: String,
        mainLine: Boolean,
        lineOrder: Int,
    ): Long {
        db.execute(
            "INSERT INTO node (game_id, parent_id, ply, uci, san, fen, is_main_line, line_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(gameId, parent, ply, uci, san, fen, if (mainLine) 1L else 0L, lineOrder),
        )
        return db.lastInsertId()
    }

    private fun insertEval(
        node: Long,
        eval: Eval,
        bestMove: String? = null,
    ) {
        val (cp, mate, winner) =
            when (eval) {
                is Eval.Cp -> Triple(eval.centipawns, null, null)
                is Eval.Mate -> Triple(null, eval.moves, null)
                is Eval.Checkmate -> Triple(null, null, if (eval.winner == PieceColor.WHITE) "w" else "b")
            }
        db.execute(
            "INSERT INTO eval (node_id, cp, mate, checkmate_winner, best_move) VALUES (?, ?, ?, ?, ?)",
            listOf(node, cp, mate, winner, bestMove),
        )
    }

    /**
     * Makes the stored lines of [id] these [lines], with the evals found for their positions in [lineEvals]: the old line positions go,
     * the new ones are written. The game's own positions are never touched.
     */
    override fun saveLines(
        id: Long,
        lines: Lines,
        lineEvals: Map<String, Eval>,
    ) = db.transaction {
        db.execute("DELETE FROM node WHERE game_id = ? AND is_main_line = 0", listOf(id))
        val mainNodes =
            db.query("SELECT id, ply FROM node WHERE game_id = ? AND is_main_line = 1", listOf(id)).associate {
                it.int("ply") to
                    it.long("id")
            }
        for (line in lines.all.sortedBy { it.id }) {
            var parent = mainNodes[line.branchPly] ?: error("game $id has no position at ply ${line.branchPly}")
            line.plies.forEachIndexed { index, ply ->
                val node =
                    insertNode(id, parent, line.branchPly + 1 + index, ply.uci, ply.san, ply.fen, mainLine = false, lineOrder = line.id)
                lineEvals[positionKey(ply.fen)]?.let { insertEval(node, it) }
                parent = node
            }
        }
        db.execute("UPDATE game SET updated = ? WHERE id = ?", listOf(clock(), id))
    }

    override fun setOpening(
        id: Long,
        opening: String?,
    ) = db.execute("UPDATE game SET opening = ? WHERE id = ?", listOf(opening, id))

    /** The game with its lines, or null when there is none with [id]. */
    override fun load(id: Long): SavedGame? {
        val row = db.query("SELECT * FROM game WHERE id = ?", listOf(id)).firstOrNull() ?: return null
        val nodes =
            db.query(
                "SELECT n.ply, n.uci, n.san, n.fen, n.is_main_line, n.line_order, e.cp, e.mate, e.checkmate_winner, e.best_move " +
                    "FROM node n LEFT JOIN eval e ON e.node_id = n.id WHERE n.game_id = ? ORDER BY n.is_main_line DESC, n.line_order, n.ply",
                listOf(id),
            )
        val main = nodes.filter { it.long("is_main_line") == 1L }
        val game =
            Game(
                startFen = row.string("start_fen"),
                plies = main.filter { it.int("ply") > 0 }.map { Ply(it.string("san"), it.string("uci"), it.string("fen")) },
                headers = HeadersJson.decode(row.string("headers_json")),
            )
        val evals = main.mapNotNull { it.evalOrNull() }
        check(evals.size == main.size) { "game $id has ${main.size} positions but ${evals.size} evals" }
        val lineEvals = HashMap<String, Eval>()
        val lines =
            nodes
                .filter { it.long("is_main_line") == 0L }
                .groupBy { it.int("line_order") }
                .map { (order, lineNodes) ->
                    for (node in lineNodes) node.evalOrNull()?.let { lineEvals[positionKey(node.string("fen"))] = it }
                    Line(
                        order,
                        null,
                        lineNodes.first().int("ply") - 1,
                        lineNodes.map { Ply(it.string("san"), it.string("uci"), it.string("fen")) },
                    )
                }
        return SavedGame(
            id = id,
            key = row.string("game_key"),
            pgn = row.string("pgn"),
            game = game,
            evals = evals,
            analysisSettings = row.string("analysis_settings"),
            lines = Lines(lines, nextId = (lines.maxOfOrNull { it.id } ?: 0) + 1),
            lineEvals = lineEvals,
            whiteAccuracy = row["white_accuracy"] as Double?,
            blackAccuracy = row["black_accuracy"] as Double?,
            opening = row["opening"] as String?,
            created = row.long("created"),
            updated = row.long("updated"),
            bestMoves = main.map { it["best_move"] as String? },
            drill = drillProgress(listOf(id)).entries.associate { (key, progress) -> key.position to progress },
        )
    }

    override fun drillProgress(ids: List<Long>): Map<DrillKey, DrillProgress> =
        queryIn(
            "SELECT n.game_id, n.ply, d.solved, d.attempts, d.last_tried FROM drill d JOIN node n ON n.id = d.node_id WHERE n.game_id IN $IDS",
            ids,
        ).associate {
            DrillKey(it.long("game_id"), it.int("ply")) to
                DrillProgress(it.long("solved") == 1L, it.int("attempts"), it.long("last_tried"))
        }

    /** Runs [sql] once per chunk of [ids], with the chunk's placeholders where [IDS] is: SQLite takes only so many arguments at once. */
    private fun queryIn(
        sql: String,
        ids: List<Long>,
    ): List<Map<String, Any?>> =
        ids.chunked(SQL_VARIABLES).flatMap { chunk ->
            db.query(sql.replace(IDS, chunk.joinToString(",", "(", ")") { "?" }), chunk)
        }

    override fun recordDrill(
        gameId: Long,
        position: Int,
        solved: Boolean,
    ) = db.execute(
        "INSERT INTO drill (node_id, solved, attempts, last_tried) " +
            "SELECT id, ?, 1, ? FROM node WHERE game_id = ? AND is_main_line = 1 AND ply = ? " +
            "ON CONFLICT(node_id) DO UPDATE SET solved = excluded.solved, attempts = attempts + 1, last_tried = excluded.last_tried",
        listOf(if (solved) 1L else 0L, clock(), gameId, position),
    )

    /** Writes the progress rows of a game that was just inserted (or brought back): only for positions the game has and has no row for. */
    private fun insertDrill(
        gameId: Long,
        drill: Map<Int, DrillProgress>,
    ) {
        for ((position, progress) in drill) {
            db.execute(
                "INSERT OR IGNORE INTO drill (node_id, solved, attempts, last_tried) " +
                    "SELECT id, ?, ?, ? FROM node WHERE game_id = ? AND is_main_line = 1 AND ply = ?",
                listOf(if (progress.solved) 1L else 0L, progress.attempts, progress.lastTried, gameId, position),
            )
        }
    }

    override fun bestMove(
        gameId: Long,
        ply: Int,
    ): String? =
        db
            .query(
                "SELECT e.best_move FROM eval e JOIN node n ON n.id = e.node_id WHERE n.game_id = ? AND n.is_main_line = 1 AND n.ply = ?",
                listOf(gameId, ply),
            ).firstOrNull()
            ?.get("best_move") as String?

    override fun setBestMove(
        gameId: Long,
        ply: Int,
        uci: String,
    ) = db.execute(
        "UPDATE eval SET best_move = ? WHERE node_id = (SELECT id FROM node WHERE game_id = ? AND is_main_line = 1 AND ply = ?)",
        listOf(uci, gameId, ply),
    )

    /** The saved games, the one worked on last first. */
    override fun list(): List<GameSummary> =
        db
            .query(
                "SELECT g.id, g.headers_json, g.opening, g.white_accuracy, g.black_accuracy, g.created, g.updated, " +
                    "(SELECT COUNT(DISTINCT line_order) FROM node WHERE game_id = g.id AND is_main_line = 0) AS line_count " +
                    "FROM game g ORDER BY g.updated DESC, g.id DESC",
            ).map { row ->
                GameSummary(
                    id = row.long("id"),
                    overview = GameOverview.of(HeadersJson.decode(row.string("headers_json"))),
                    opening = row["opening"] as String?,
                    whiteAccuracy = row["white_accuracy"] as Double?,
                    blackAccuracy = row["black_accuracy"] as Double?,
                    lineCount = row.int("line_count"),
                    created = row.long("created"),
                    updated = row.long("updated"),
                )
            }

    override fun reportRows(): List<ReportRow> =
        db.query("SELECT id, headers_json, opening, white_accuracy, black_accuracy, created FROM game ORDER BY id").map {
            ReportRow(
                it.long("id"),
                HeadersJson.decode(it.string("headers_json")),
                it["opening"] as String?,
                it["white_accuracy"] as Double?,
                it["black_accuracy"] as Double?,
                it.long("created"),
            )
        }

    override fun mainLines(ids: List<Long>): Map<Long, MainLine> =
        queryIn(
            "SELECT n.game_id, g.start_fen, n.ply, n.san, e.cp, e.mate, e.checkmate_winner FROM node n JOIN game g ON g.id = n.game_id " +
                "LEFT JOIN eval e ON e.node_id = n.id WHERE n.is_main_line = 1 AND n.game_id IN $IDS ORDER BY n.game_id, n.ply",
            ids,
        ).groupBy { it.long("game_id") }
            .mapNotNull { (id, rows) ->
                val evals = rows.mapNotNull { it.evalOrNull() }
                if (evals.size != rows.size) return@mapNotNull null
                id to
                    MainLine(
                        sans = rows.filter { it.int("ply") > 0 }.map { it.string("san") },
                        evals = evals,
                        whiteMovesFirst = whiteToMove(rows.first().string("start_fen")),
                    )
            }.toMap()

    override fun queuedRows(): List<QueuedRow> =
        db.query("SELECT id, pgn, added, state FROM queued ORDER BY added, id").map {
            QueuedRow(it.long("id"), it.string("pgn"), it.long("added"), it.string("state"))
        }

    override fun addQueued(
        pgn: String,
        added: Long,
    ): Long =
        db.transaction {
            db.execute("INSERT INTO queued (pgn, added, state) VALUES (?, ?, 'waiting')", listOf(pgn, added))
            db.lastInsertId()
        }

    override fun setQueuedState(
        id: Long,
        state: String,
    ) = db.execute("UPDATE queued SET state = ? WHERE id = ?", listOf(state, id))

    override fun removeQueued(id: Long) = db.execute("DELETE FROM queued WHERE id = ?", listOf(id))

    override fun delete(id: Long) = db.execute("DELETE FROM game WHERE id = ?", listOf(id))

    /**
     * Adds every game of [other] this store does not have yet (the same key counts as the same game), with its lines and evals and the
     * times it had, in one transaction: either all of them are added or none. The games of [other] are only read. For a game both
     * have, only the practised positions this store has no row for are taken over: an existing row is kept.
     */
    fun mergeFrom(other: GameStore): MergeResult =
        db.transaction {
            var added = 0
            var skipped = 0
            for (id in other.ids()) {
                val saved = other.load(id) ?: continue
                val mine = findId(saved.game)
                if (mine != null) {
                    insertDrill(mine, saved.drill)
                    skipped++
                } else {
                    add(saved)
                    added++
                }
            }
            MergeResult(added, skipped)
        }

    private fun ids(): List<Long> = db.query("SELECT id FROM game ORDER BY id").map { it.long("id") }

    /** Saves [saved] as a new game here (a new id), with its lines, evals, accuracies and times. */
    private fun add(saved: SavedGame) = insertSaved(null, saved)

    /** Puts a game that was deleted back with the id, key, lines and times it had. */
    override fun restore(saved: SavedGame) = db.transaction { insertSaved(saved.id, saved) }

    /** Writes the whole of [saved] (the game, its positions and evals, practice progress and lines) under [id], or a new id when null. */
    private fun insertSaved(
        id: Long?,
        saved: SavedGame,
    ) {
        insertGame(
            id,
            saved.key,
            saved.pgn,
            saved.game,
            saved.created,
            saved.updated,
            saved.analysisSettings,
            saved.whiteAccuracy,
            saved.blackAccuracy,
            saved.opening,
        )
        val gameId = id ?: db.lastInsertId()
        insertMainLine(gameId, saved.game, saved.evals, saved.bestMoves)
        insertDrill(gameId, saved.drill)
        saveLines(gameId, saved.lines, saved.lineEvals)
        // saving the lines touched the game: it keeps the time it had
        db.execute("UPDATE game SET updated = ? WHERE id = ?", listOf(saved.updated, gameId))
    }

    private fun Map<String, Any?>.string(name: String): String = this[name] as String

    private fun Map<String, Any?>.long(name: String): Long = (this[name] as Number).toLong()

    private fun Map<String, Any?>.int(name: String): Int = (this[name] as Number).toInt()

    private fun Map<String, Any?>.evalOrNull(): Eval? =
        when {
            this["cp"] != null -> {
                Eval.Cp(int("cp"))
            }

            this["mate"] != null -> {
                Eval.Mate(int("mate"))
            }

            this["checkmate_winner"] != null -> {
                Eval.Checkmate(
                    if (string("checkmate_winner") ==
                        "w"
                    ) {
                        PieceColor.WHITE
                    } else {
                        PieceColor.BLACK
                    },
                )
            }

            else -> {
                null
            }
        }
}
