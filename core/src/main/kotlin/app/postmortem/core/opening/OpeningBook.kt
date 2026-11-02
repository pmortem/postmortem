package app.postmortem.core.opening

import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.PgnParser
import app.postmortem.core.pgn.PgnResult
import app.postmortem.core.pgn.STANDARD_START_FEN

/** An opening from the table: its ECO code and name, e.g. `C50` and `Italian Game`. */
data class Opening(
    val eco: String,
    val name: String,
)

/**
 * Lichess's opening table (chess-openings, CC0), looked up by position so that transpositions are found, not only the
 * exact move order. Building it plays every line of the table once; do that off the main thread.
 */
class OpeningBook private constructor(
    private val byPosition: Map<String, List<Entry>>,
) {
    /** A table line: the opening and the moves that lead to it. */
    private class Entry(
        val opening: Opening,
        val moves: List<String>,
    )

    val size: Int get() = byPosition.size

    /**
     * The deepest table entry whose position occurs in [game], or null when the game leaves the book at once (or starts
     * from a set-up position, where the table does not apply).
     */
    fun detect(game: Game): Opening? {
        if (positionKey(game.startFen) != START_KEY) return null
        for (ply in game.plies.size downTo 1) {
            val entries = byPosition[positionKey(game.plies[ply - 1].fen)] ?: continue
            // Two lines can reach one position under different names (1.d4 d5 2.c4 e6 and 1.c4 e6 2.d4 d5): the game's own
            // move order picks the name, and if it is neither, the longest line does.
            val played = game.plies.take(ply).map { it.uci }
            return (entries.firstOrNull { it.moves == played } ?: entries.maxBy { it.moves.size }).opening
        }
        return null
    }

    companion object {
        private const val RESOURCE = "openings.tsv"
        private const val FIELDS = 3
        private val START_KEY = positionKey(STANDARD_START_FEN)

        /**
         * Board, side to move and castling rights. The en passant square is left out on purpose: the same position carries it
         * or not depending on whether the last move was a double pawn push, even when no capture is possible.
         */
        private fun positionKey(fen: String): String = fen.split(' ').take(3).joinToString(" ")

        /** Reads tab-separated lines `eco`, `name`, `pgn` (no header line). A line that is not one or does not play is skipped. */
        fun parse(tsv: String): OpeningBook {
            val byPosition = HashMap<String, MutableList<Entry>>()
            for (line in tsv.lineSequence()) {
                val fields = line.split('\t')
                if (fields.size != FIELDS || fields[0] == "eco") continue
                val game = (PgnParser.parse(fields[2]) as? PgnResult.Success)?.game ?: continue
                if (game.plies.isEmpty()) continue
                val entry = Entry(Opening(fields[0], fields[1]), game.plies.map { it.uci })
                byPosition.getOrPut(positionKey(game.plies.last().fen)) { ArrayList(1) }.add(entry)
            }
            return OpeningBook(byPosition)
        }

        /** The table shipped with the app. */
        fun bundled(): OpeningBook {
            val text =
                checkNotNull(OpeningBook::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE is missing" }
                    .bufferedReader()
                    .use { it.readText() }
            return parse(text)
        }
    }
}
