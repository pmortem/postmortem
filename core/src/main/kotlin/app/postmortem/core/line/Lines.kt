package app.postmortem.core.line

import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.Ply

/**
 * A line the user played from the game: [plies] are its moves, starting from the position after [branchPly] half-moves of its
 * parent. [parentId] null means the parent is the game; a parent id makes the lines a tree, so nested lines need no new model.
 */
data class Line(
    val id: Int,
    val parentId: Int?,
    val branchPly: Int,
    val plies: List<Ply>,
)

/**
 * The moves of the line as Lichess writes a line: the first with its number (`3... Nf6`), then a number before each White move
 * (`4. Nf3`) and none before a Black one (`Qe7`). Numbers follow [Line.branchPly] the way the game's own move list counts.
 */
fun Line.moveLabels(): List<String> =
    plies.mapIndexed { index, ply ->
        val absolute = branchPly + 1 + index
        val number = (absolute + 1) / 2
        when {
            absolute % 2 == 1 -> "$number. ${ply.san}"
            index == 0 -> "$number... ${ply.san}"
            else -> ply.san
        }
    }

/**
 * Where the board is: on the game (`lineId` null) or on a line, after [ply] half-moves of it. On a line, [ply] is at least 1: its
 * 0 is the branch position, which is a game position, so it is written as the game's cursor there.
 */
data class Cursor(
    val lineId: Int?,
    val ply: Int,
) {
    companion object {
        fun game(ply: Int = 0) = Cursor(null, ply)
    }
}

/** The result of [Lines.play]: the lines after the move and where the board is now. */
data class Played(
    val lines: Lines,
    val cursor: Cursor,
)

/**
 * The lines played from one game, in the order they were made. Immutable: every change returns a new [Lines]. The game itself is
 * never changed, so its analysis, chart and summary stay as they are.
 */
data class Lines(
    val all: List<Line> = emptyList(),
    private val nextId: Int = 1,
) {
    fun find(id: Int): Line? = all.firstOrNull { it.id == id }

    /** The lines that branch off the game right after [gamePly] half-moves, in the order made. */
    fun branchingAt(gamePly: Int): List<Line> = all.filter { it.parentId == null && it.branchPly == gamePly }

    /** The game position the board shows at [cursor]: the one it is on, or the branch of its line. */
    fun gamePly(cursor: Cursor): Int = cursor.lineId?.let { find(it)?.branchPly } ?: cursor.ply

    /** The FEN at [cursor]. */
    fun fen(
        game: Game,
        cursor: Cursor,
    ): String = cursor.lineId?.let { id -> find(id)?.plies?.get(cursor.ply - 1)?.fen } ?: game.fenAt(cursor.ply)

    /** The move that led to [cursor], or null on the start position. */
    fun lastMove(
        game: Game,
        cursor: Cursor,
    ): String? = cursor.lineId?.let { id -> find(id)?.plies?.get(cursor.ply - 1)?.uci } ?: game.lastMoveAt(cursor.ply)

    fun canStepBack(cursor: Cursor): Boolean = cursor.lineId != null || cursor.ply > 0

    fun canStepForward(
        game: Game,
        cursor: Cursor,
    ): Boolean = cursor.ply < (cursor.lineId?.let { find(it)?.plies?.size } ?: game.plies.size)

    /** One half-move back or forward along what the cursor is on; the end of a line or of the game stays where it is. */
    fun step(
        game: Game,
        cursor: Cursor,
        delta: Int,
    ): Cursor {
        val line = cursor.lineId?.let { find(it) }
        if (line == null) return Cursor.game((cursor.ply + delta).coerceIn(0, game.plies.size))
        val ply = (cursor.ply + delta).coerceAtLeast(0)
        return when {
            ply == 0 -> Cursor.game(line.branchPly)
            ply > line.plies.size -> cursor
            else -> Cursor(line.id, ply)
        }
    }

    /** Back from a line goes to the game position it branched from; on the game it stays. */
    fun leave(cursor: Cursor): Cursor = cursor.lineId?.let { Cursor.game(gamePly(cursor)) } ?: cursor

    /**
     * Plays [move] at [cursor]:
     * - the next move of what the cursor is on only steps forward and makes no line;
     * - a move at the end of a line extends it;
     * - any other move starts a new line from the game position the cursor is on or branches from, with the line's moves up to
     *   the cursor copied in, unless a line from there already starts the same way, which is then selected instead.
     */
    fun play(
        game: Game,
        cursor: Cursor,
        move: Ply,
    ): Played {
        val line = cursor.lineId?.let { find(it) }
        if (line == null) {
            if (game.plies.getOrNull(cursor.ply)?.uci == move.uci) return Played(this, Cursor.game(cursor.ply + 1))
            return branch(cursor.ply, emptyList(), move)
        }
        val next = line.plies.getOrNull(cursor.ply)
        return when {
            next?.uci == move.uci -> {
                Played(this, Cursor(line.id, cursor.ply + 1))
            }

            next == null -> {
                Played(
                    copy(
                        all =
                            all.map {
                                if (it.id ==
                                    line.id
                                ) {
                                    it.copy(plies = it.plies + move)
                                } else {
                                    it
                                }
                            },
                    ),
                    Cursor(line.id, cursor.ply + 1),
                )
            }

            else -> {
                branch(line.branchPly, line.plies.take(cursor.ply), move)
            }
        }
    }

    /** [Lines] without [lineId]. */
    fun delete(lineId: Int): Lines = copy(all = all.filter { it.id != lineId })

    /** Puts a deleted [line] back exactly as it was, at its place among the lines in the order they were made. */
    fun restore(line: Line): Lines = if (find(line.id) != null) this else copy(all = (all + line).sortedBy { it.id })

    private fun branch(
        branchPly: Int,
        before: List<Ply>,
        move: Ply,
    ): Played {
        val plies = before + move
        val same = branchingAt(branchPly).firstOrNull { it.plies.take(plies.size).map(Ply::uci) == plies.map(Ply::uci) }
        if (same != null) return Played(this, Cursor(same.id, plies.size))
        val line = Line(nextId, null, branchPly, plies)
        return Played(copy(all = all + line, nextId = nextId + 1), Cursor(line.id, plies.size))
    }
}
