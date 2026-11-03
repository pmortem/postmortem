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

/** One principal variation an `info` line reports: line [multiPv] (1 is the best), at [depth], with its exact [score]. */
data class PvInfo(
    val multiPv: Int,
    val depth: Int,
    val score: Score,
    val nodes: Long,
    val timeMs: Long,
    val pv: List<String>,
) {
    /** Nodes per second, or null when the engine reported no time. */
    val nodesPerSecond: Long? get() = if (timeMs > 0) nodes * 1000 / timeMs else null
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

    /**
     * The line of a `MultiPV` `info` that carries a depth, an exact score and a move list, or null for any other line. A line
     * without `multipv` is line 1. Lower and upper bounds are ignored, as in [score].
     */
    fun pv(line: String): PvInfo? {
        val words = line.trim().split(' ').filter { it.isNotEmpty() }
        val score = score(line) ?: return null
        val depth = words.getOrNull(words.indexOf("depth") + 1)?.toIntOrNull() ?: return null
        val pvAt = words.indexOf("pv")
        if (pvAt < 0 || pvAt == words.lastIndex) return null
        val multiPv = if (words.contains("multipv")) words.getOrNull(words.indexOf("multipv") + 1)?.toIntOrNull() ?: return null else 1
        val (nodes, time) = effort(line) ?: (0L to 0L)
        return PvInfo(multiPv, depth, score, nodes, time, words.subList(pvAt + 1, words.size))
    }

    /** The `nodes` and `time` (ms) an `info` line reports, or null when it carries no such pair. */
    fun effort(line: String): Pair<Long, Long>? {
        val words = line.trim().split(' ').filter { it.isNotEmpty() }
        if (words.firstOrNull() != "info" || words.getOrNull(1) == "string") return null
        val nodes = words.getOrNull(words.indexOf("nodes") + 1)?.toLongOrNull() ?: return null
        val time = words.getOrNull(words.indexOf("time") + 1)?.toLongOrNull() ?: return null
        return nodes to time
    }

    fun bestMove(line: String): BestMove? {
        val words = line.trim().split(' ').filter { it.isNotEmpty() }
        if (words.firstOrNull() != "bestmove") return null
        val move = words.getOrNull(1) ?: return null
        return if (move == NO_MOVE) BestMove.None else BestMove.Move(move)
    }
}
