package app.postmortem.core.report

import com.github.bhlangonijr.chesslib.Board

/** The part of a game a move was played in. */
enum class Phase { OPENING, MIDDLEGAME, ENDGAME }

/** How a game ended, as far as the tags and the last position say. */
enum class Ending { CHECKMATE, RESIGNATION, TIME, AGREEMENT, STALEMATE, REPETITION, INSUFFICIENT_MATERIAL, OTHER }

/** Which phase a move belongs to, read from the position it was played in, and how a game ended. */
object GamePhases {
    /** A game is in its opening while the book knows the position, or for its first 12 moves (24 plies). */
    const val OPENING_PLIES = 24

    /** An endgame is when no queens are left, or each side has at most this many points of pieces (N and B 3, R 5, Q 9; pawns and kings do not count). */
    const val ENDGAME_POINTS = 13

    private const val KNIGHT_BISHOP = 3
    private const val ROOK = 5
    private const val QUEEN = 9
    private const val REPETITIONS = 3

    /**
     * The phase of the move of [ply] (counting from 1) played in the position [fenBefore]. [bookDepth] is how many plies the opening book
     * knew of the game ([app.postmortem.core.opening.OpeningBook.depthOf]). The opening wins over the endgame: queens traded on move 10
     * are still the opening.
     */
    fun of(
        ply: Int,
        fenBefore: String,
        bookDepth: Int,
    ): Phase =
        when {
            ply - 1 <= bookDepth || ply <= OPENING_PLIES -> Phase.OPENING
            isEndgame(fenBefore) -> Phase.ENDGAME
            else -> Phase.MIDDLEGAME
        }

    /** True when there are no queens on the board, or each side has at most [ENDGAME_POINTS] points of pieces. Read from the text of the FEN. */
    fun isEndgame(fen: String): Boolean {
        val placement = fen.substringBefore(' ')
        var queens = 0
        var white = 0
        var black = 0
        for (c in placement) {
            val points =
                when (c.lowercaseChar()) {
                    'n', 'b' -> KNIGHT_BISHOP
                    'r' -> ROOK
                    'q' -> QUEEN
                    else -> 0
                }
            if (c.lowercaseChar() == 'q') queens++
            if (c.isUpperCase()) white += points else black += points
        }
        return queens == 0 || (white <= ENDGAME_POINTS && black <= ENDGAME_POINTS)
    }

    /**
     * How a game ended. The last position and move come first (checkmate, stalemate, insufficient material, a position that occurred
     * three times); then the `Termination` tag (Lichess writes `Normal` or `Time forfeit`, chess.com writes `Anna won by resignation`
     * and the like); then the result: a decisive game that is not a mate and not on time is a resignation, a drawn one an agreement.
     * Anything else is [Ending.OTHER].
     */
    fun ending(
        termination: String?,
        result: Outcome,
        lastSan: String?,
        fens: List<String>,
    ): Ending {
        val last = fens.lastOrNull()
        val text = termination?.lowercase().orEmpty()
        return when {
            lastSan?.endsWith("#") == true -> {
                Ending.CHECKMATE
            }

            last != null && isStalemate(last) -> {
                Ending.STALEMATE
            }

            last != null && isInsufficient(last) && result == Outcome.DRAW -> {
                Ending.INSUFFICIENT_MATERIAL
            }

            "insufficient" in text -> {
                Ending.INSUFFICIENT_MATERIAL
            }

            "repetition" in text || (last != null && repeated(fens)) -> {
                Ending.REPETITION
            }

            "stalemate" in text -> {
                Ending.STALEMATE
            }

            "checkmate" in text -> {
                Ending.CHECKMATE
            }

            "time" in text -> {
                Ending.TIME
            }

            "resign" in text -> {
                Ending.RESIGNATION
            }

            "agree" in text -> {
                Ending.AGREEMENT
            }

            text.isEmpty() || text == "normal" -> {
                when (result) {
                    Outcome.WIN, Outcome.LOSS -> Ending.RESIGNATION
                    Outcome.DRAW -> Ending.AGREEMENT
                    Outcome.UNFINISHED -> Ending.OTHER
                }
            }

            else -> {
                Ending.OTHER
            }
        }
    }

    private fun isStalemate(fen: String): Boolean = Board().apply { loadFromFen(fen) }.isStaleMate

    private fun isInsufficient(fen: String): Boolean = Board().apply { loadFromFen(fen) }.isInsufficientMaterial

    /** True when the last position occurred [REPETITIONS] times (same pieces, side to move and castling rights). */
    private fun repeated(fens: List<String>): Boolean {
        val key =
            fens
                .last()
                .split(' ')
                .take(3)
                .joinToString(" ")
        return fens.count { it.split(' ').take(3).joinToString(" ") == key } >= REPETITIONS
    }
}
