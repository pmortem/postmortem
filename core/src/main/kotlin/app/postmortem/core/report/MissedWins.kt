package app.postmortem.core.report

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.GameReport
import app.postmortem.core.analysis.Judgement
import app.postmortem.core.analysis.formatShort
import app.postmortem.core.analysis.winningChancesFor
import app.postmortem.core.board.PieceColor
import app.postmortem.core.board.moverAt
import app.postmortem.core.board.sign
import app.postmortem.core.store.MainLine

/** A game the player was winning for [PLIES_HELD] moves in a row and did not win: where it went wrong, and from how high. */
data class MissedWin(
    val game: PlayerGame,
    /** The best the player's position got after they held the win (White's point of view, like every [Eval]). */
    val peak: Eval,
    /** The player's move after the peak that lost the most winning chances. */
    val turningPly: Int,
    val san: String,
    /** The mark Lichess's rules give that move (`?!`, `?`, `??`), if any. */
    val judgement: Judgement?,
    val after: Eval,
)

/** A move that threw away a forced mate the player had, of at most [MissedWins.MAX_MISSED_MATE] moves. */
data class MissedMate(
    val game: PlayerGame,
    val ply: Int,
    val san: String,
    val judgement: Judgement?,
    /** The mate the player had, in moves. */
    val mateIn: Int,
)

/** [decided] is the number of games with a result; the share of missed wins is out of those. */
data class MissedReport(
    val decided: Int,
    val wins: List<MissedWin>,
    val mates: List<MissedMate>,
)

/**
 * The first insights on the games of one player: games they were winning and did not win, and forced mates they let go. Both are
 * read off the stored main-line evals only. The thresholds are named constants here, so they can be tuned in one place.
 */
object MissedWins {
    /** The player's winning chances (the eval chart's -1..1 scale, about +3.0 at 0.5) from which a position counts as winning. */
    const val WINNING_CHANCES = 0.5

    /** How many of the player's moves in a row must leave them winning. */
    const val PLIES_HELD = 2

    /** A forced mate longer than this is too hard to call "missed". */
    const val MAX_MISSED_MATE = 5

    fun of(
        games: List<PlayerGame>,
        lines: Map<Long, MainLine>,
    ): MissedReport {
        val wins = ArrayList<MissedWin>()
        val mates = ArrayList<MissedMate>()
        for (game in games) {
            val line = lines[game.gameId] ?: continue
            if (line.evals.size < 2) continue
            val judgements = line.judgements
            missedWin(game, line, judgements)?.let(wins::add)
            mates += missedMates(game, line, judgements)
        }
        return MissedReport(
            decided = games.count { it.outcome != Outcome.UNFINISHED },
            wins = wins.sortedWith { a, b -> PlayerGame.newestFirst.compare(a.game, b.game) },
            mates = mates.sortedWith(Comparator<MissedMate> { a, b -> PlayerGame.newestFirst.compare(a.game, b.game) }.thenBy { it.ply }),
        )
    }

    private fun missedWin(
        game: PlayerGame,
        line: MainLine,
        judgements: List<Judgement?>,
    ): MissedWin? {
        if (game.outcome != Outcome.DRAW && game.outcome != Outcome.LOSS) return null
        val moves = line.evals.size - 1

        fun chances(position: Int) = line.evals[position].winningChancesFor(game.color)
        val mine = (1..moves).filter { moverAt(it, line.whiteMovesFirst) == game.color }
        val held =
            (0..mine.size - PLIES_HELD).firstOrNull { start ->
                (start until start + PLIES_HELD).all { chances(mine[it]) >= WINNING_CHANCES }
            } ?: return null
        val peak = (mine[held]..moves).maxByOrNull { chances(it) } ?: return null
        val turning =
            mine
                .filter { it > peak }
                .map { it to chances(it - 1) - chances(it) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }
                ?.first ?: return null
        return MissedWin(game, line.evals[peak], turning, line.sans[turning - 1], judgements[turning - 1], line.evals[turning])
    }

    private fun missedMates(
        game: PlayerGame,
        line: MainLine,
        judgements: List<Judgement?>,
    ): List<MissedMate> {
        val sign = game.color.sign
        val moves = line.evals.size - 1
        return (1..moves)
            .filter { moverAt(it, line.whiteMovesFirst) == game.color }
            .mapNotNull { ply ->
                val before = line.evals[ply - 1] as? Eval.Mate ?: return@mapNotNull null
                val mateIn = before.moves * sign
                if (mateIn !in 1..MAX_MISSED_MATE) return@mapNotNull null
                val after = line.evals[ply]
                val stillMating = (after is Eval.Mate && after.moves * sign > 0) || (after is Eval.Checkmate && after.winner == game.color)
                if (stillMating) null else MissedMate(game, ply, line.sans[ply - 1], judgements[ply - 1], mateIn)
            }
    }
}

/** [this] from [color]'s point of view and short: `+4.2` when [color] is ahead by 4.2 pawns, `M3` for a mate they give. */
fun Eval.formatShortFor(color: PieceColor): String =
    when (this) {
        is Eval.Cp -> if (color == PieceColor.WHITE) formatShort() else Eval.Cp(-centipawns).formatShort()
        is Eval.Mate -> if (color == PieceColor.WHITE) formatShort() else Eval.Mate(-moves).formatShort()
        is Eval.Checkmate -> formatShort()
    }
