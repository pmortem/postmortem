package app.postmortem.core.pgn

/** One half-move: the SAN as written, the UCI move and the position (FEN) after it. */
data class Ply(
    val san: String,
    val uci: String,
    val fen: String,
)

/** A parsed game. Position `n` is [startFen] for 0 and `plies[n - 1].fen` after that. */
data class Game(
    val startFen: String,
    val plies: List<Ply>,
    val headers: Map<String, String>,
) {
    val positionCount: Int get() = plies.size + 1

    fun fenAt(position: Int): String = if (position == 0) startFen else plies[position - 1].fen
}

/** Why a text is not a game. The UI turns each case into a message. */
sealed interface PgnError {
    data object Empty : PgnError

    data object NoMoves : PgnError

    data class BadFen(
        val fen: String,
    ) : PgnError

    data class UnsupportedVariant(
        val variant: String,
    ) : PgnError

    /** [ply] counts from 1 over the main line. */
    data class IllegalMove(
        val ply: Int,
        val san: String,
    ) : PgnError
}

sealed interface PgnResult {
    data class Success(
        val game: Game,
    ) : PgnResult

    data class Failure(
        val error: PgnError,
    ) : PgnResult
}

/** Full moves in the game: 3 plies are 2 moves, as a player counts them. */
val Game.moveCount: Int get() = (plies.size + 1) / 2

/** `12.Nf3` for White's ply 23 and `12...Nf6` for Black's ply 24. [ply] counts from 1. */
fun plyLabel(
    ply: Int,
    san: String,
): String {
    val number = (ply + 1) / 2
    return if (ply % 2 == 1) "$number.$san" else "$number...$san"
}
