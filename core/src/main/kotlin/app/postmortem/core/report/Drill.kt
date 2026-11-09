package app.postmortem.core.report

import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.GameReport
import app.postmortem.core.analysis.Judgement
import app.postmortem.core.analysis.winningChancesFor
import app.postmortem.core.board.PieceColor
import app.postmortem.core.board.moverAt
import app.postmortem.core.store.DrillKey
import app.postmortem.core.store.DrillProgress
import app.postmortem.core.store.MainLine

/** Why a position is in the queue. The order is the order of the queue: missed wins and missed mates first, then blunders, then mistakes. */
enum class DrillKind { MISSED_WIN, MISSED_MATE, BLUNDER, MISTAKE }

/**
 * A position from one of the player's own games to find a better move in: the position after [position] plies of the game, with the
 * player to move. [san] is the move they played there, [before] and [after] the evals of the position and of the one after that move
 * (White's point of view).
 */
data class DrillItem(
    val game: PlayerGame,
    val position: Int,
    val kind: DrillKind,
    val san: String,
    val before: Eval,
    val after: Eval,
    val progress: DrillProgress?,
) {
    /** The ply of the move the player made here (counting from 1). */
    val ply: Int get() = position + 1
}

/** What is left to practise, in the order to practise it, and how many positions were solved already. */
data class DrillQueue(
    val items: List<DrillItem>,
    val solved: Int,
)

/** Building the queue of positions to practise from the player's games, and judging an answer. */
object Drill {
    /** The answer is judged by [GameReport]'s inaccuracy drop (Lichess's rule), so it is the same number as the Moves tab's marks. */
    const val GOOD_ENOUGH_DROP = GameReport.INACCURACY_DROP

    /**
     * The positions before each of the player's moves that [GameReport] marks as a mistake or a blunder, plus the turning move of every
     * missed win and every missed mate, ordered as the [DrillKind]s say (newest game first within a kind). Solved positions are not in
     * [DrillQueue.items] (they are counted in [DrillQueue.solved]); positions tried or skipped before come after the fresh ones, the
     * longest ago first. [missed] is the missed-wins report of the same games, when the caller has it already.
     */
    fun queue(
        games: List<PlayerGame>,
        lines: Map<Long, MainLine>,
        progress: Map<DrillKey, DrillProgress>,
        missed: MissedReport = MissedWins.of(games, lines),
    ): DrillQueue {
        val special = HashMap<Pair<Long, Int>, DrillKind>()
        missed.mates.forEach { special[it.game.gameId to it.ply] = DrillKind.MISSED_MATE }
        missed.wins.forEach { special[it.game.gameId to it.turningPly] = DrillKind.MISSED_WIN }
        val all = ArrayList<DrillItem>()
        for (game in games) {
            val line = lines[game.gameId] ?: continue
            if (line.evals.size < 2) continue
            val judgements = line.judgements
            for (ply in 1..line.evals.size - 1) {
                if (moverAt(ply, line.whiteMovesFirst) != game.color) continue
                val kind =
                    special[game.gameId to ply] ?: when (judgements[ply - 1]) {
                        Judgement.BLUNDER -> DrillKind.BLUNDER
                        Judgement.MISTAKE -> DrillKind.MISTAKE
                        else -> null
                    } ?: continue
                all +=
                    DrillItem(
                        game,
                        ply - 1,
                        kind,
                        line.sans[ply - 1],
                        line.evals[ply - 1],
                        line.evals[ply],
                        progress[DrillKey(game.gameId, ply - 1)],
                    )
            }
        }
        val open = all.filter { it.progress?.solved != true }
        val fresh = open.filter { it.progress == null }.sortedWith(freshOrder)
        val again = open.filter { it.progress != null }.sortedBy { it.progress!!.lastTried }
        return DrillQueue(fresh + again, all.size - open.size)
    }

    private val freshOrder: Comparator<DrillItem> =
        compareBy<DrillItem> { it.kind.ordinal }
            .then { a, b -> PlayerGame.newestFirst.compare(a.game, b.game) }
            .thenBy { it.position }

    /** How an answer went: the engine's best move, a move nearly as good, or not good enough. */
    enum class Verdict { RIGHT, GOOD, WRONG }

    /** True when [played] is not the best move, so the position after it has to be evaluated to say whether it is good enough. */
    fun needsEval(
        best: String?,
        played: String,
    ): Boolean = best == null || best != played

    /**
     * The verdict on [played] in a position the engine [best] move of which is known: right when it is that move; otherwise good when the
     * mover's winning chances after it ([after]) fall by less than [GOOD_ENOUGH_DROP] from what the position was worth ([before], with
     * best play), and wrong when they fall by more.
     */
    fun verdict(
        best: String?,
        played: String,
        before: Eval,
        after: Eval,
        mover: PieceColor,
    ): Verdict {
        if (best != null && best == played) return Verdict.RIGHT
        val drop = before.winningChancesFor(mover) - after.winningChancesFor(mover)
        return if (drop < GOOD_ENOUGH_DROP) Verdict.GOOD else Verdict.WRONG
    }
}
