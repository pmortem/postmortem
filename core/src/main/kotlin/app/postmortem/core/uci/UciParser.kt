package app.postmortem.core.uci

/** An engine score from the point of view of the side to move. */
sealed interface Score {
    data class Cp(
        val centipawns: Int,
    ) : Score

    /** Mate in [moves] for the side to move; negative when the side to move gets mated; 0 when it is mated now. */
    data class Mate(
        val moves: Int,
    ) : Score
}

sealed interface BestMove {
    data class Move(
        val uci: String,
    ) : BestMove

    /** `bestmove (none)`: the position has no legal move. */
    data object None : BestMove
}

object UciParser {
    private const val NO_MOVE = "(none)"

    /** The score of an `info` line, or null for any other line and for lower and upper bounds. */
    fun score(line: String): Score? {
        val words = line.trim().split(' ').filter { it.isNotEmpty() }
        if (words.firstOrNull() != "info" || words.getOrNull(1) == "string") return null
        val at = words.indexOf("score")
        if (at < 0) return null
        val value = words.getOrNull(at + 2)?.toIntOrNull() ?: return null
        if (words.getOrNull(at + 3).let { it == "lowerbound" || it == "upperbound" }) return null
        return when (words[at + 1]) {
            "cp" -> Score.Cp(value)
            "mate" -> Score.Mate(value)
            else -> null
        }
    }

    fun bestMove(line: String): BestMove? {
        val words = line.trim().split(' ').filter { it.isNotEmpty() }
        if (words.firstOrNull() != "bestmove") return null
        val move = words.getOrNull(1) ?: return null
        return if (move == NO_MOVE) BestMove.None else BestMove.Move(move)
    }
}
