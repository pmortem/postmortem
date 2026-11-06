package app.postmortem.core.store

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.positionKey
import app.postmortem.core.board.PieceColor
import app.postmortem.core.line.Line
import app.postmortem.core.line.Lines
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.GameOverview
import app.postmortem.core.pgn.Ply

/** A game to save: what the analysis made of it. [evals] has one entry per position. */
data class NewGame(
    val pgn: String,
    val game: Game,
    val evals: List<Eval>,
    val analysisSettings: String,
    val whiteAccuracy: Double? = null,
    val blackAccuracy: Double? = null,
    val opening: String? = null,
)

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

    fun delete(id: Long)

    fun restore(saved: SavedGame)
}

/**
 * The saved games, in the SQLite file behind [db] (see [Schema]). Nothing here knows about Android: the same code runs on the phone
 * and in the tests. Every change is one transaction, so a game is never half saved.
 */
class GameStore(
    private val db: SqlDb,
    private val clock: () -> Long = System::currentTimeMillis,
) : GameLibrary {
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
            insertMainLine(db.lastInsertId(), new.game, new.evals)
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
    ): Long {
        var parent: Long? = null
        for (position in 0 until game.positionCount) {
            val ply = game.plies.getOrNull(position - 1)
            parent = insertNode(gameId, parent, position, ply?.uci, ply?.san, game.fenAt(position), mainLine = true, lineOrder = 0)
            evals.getOrNull(position)?.let { insertEval(parent, it) }
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
    ) {
        val (cp, mate, winner) =
            when (eval) {
                is Eval.Cp -> Triple(eval.centipawns, null, null)
                is Eval.Mate -> Triple(null, eval.moves, null)
                is Eval.Checkmate -> Triple(null, null, if (eval.winner == PieceColor.WHITE) "w" else "b")
            }
        db.execute("INSERT INTO eval (node_id, cp, mate, checkmate_winner) VALUES (?, ?, ?, ?)", listOf(node, cp, mate, winner))
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
                "SELECT n.ply, n.uci, n.san, n.fen, n.is_main_line, n.line_order, e.cp, e.mate, e.checkmate_winner " +
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
        )
    }

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

    override fun delete(id: Long) = db.execute("DELETE FROM game WHERE id = ?", listOf(id))

    /**
     * Adds every game of [other] this store does not have yet (the same key counts as the same game), with its lines and evals and the
     * times it had, in one transaction: either all of them are added or none. The games of [other] are only read.
     */
    fun mergeFrom(other: GameStore): MergeResult =
        db.transaction {
            var added = 0
            var skipped = 0
            for (id in other.ids()) {
                val saved = other.load(id) ?: continue
                if (findId(saved.game) != null) {
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
    private fun add(saved: SavedGame) {
        insertGame(
            null,
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
        val id = db.lastInsertId()
        insertMainLine(id, saved.game, saved.evals)
        saveLines(id, saved.lines, saved.lineEvals)
        db.execute("UPDATE game SET updated = ? WHERE id = ?", listOf(saved.updated, id))
    }

    /** Puts a game that was deleted back with the id, key, lines and times it had. */
    override fun restore(saved: SavedGame) =
        db.transaction {
            insertGame(
                saved.id,
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
            insertMainLine(saved.id, saved.game, saved.evals)
            saveLines(saved.id, saved.lines, saved.lineEvals)
            db.execute("UPDATE game SET updated = ? WHERE id = ?", listOf(saved.updated, saved.id))
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
