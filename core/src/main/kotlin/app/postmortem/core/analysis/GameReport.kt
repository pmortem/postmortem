package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class Judgement { INACCURACY, MISTAKE, BLUNDER }

/** One player's side of the report; the numbers are null when the player made no move. */
data class PlayerReport(
    val accuracy: Double?,
    val acpl: Double?,
    val inaccuracies: Int,
    val mistakes: Int,
    val blunders: Int,
)

/** [judgements] has one entry per ply (null for a move that is fine). */
data class GameReport(
    val white: PlayerReport,
    val black: PlayerReport,
    val judgements: List<Judgement?>,
) {
    /** How many moves of both sides got [judgement]. */
    fun count(judgement: Judgement): Int = judgements.count { it == judgement }

    companion object {
        private const val CP_LIMIT = 1000
        private const val ACCURACY_SCALE = 103.1668
        private const val ACCURACY_DECAY = -0.04354
        private const val ACCURACY_OFFSET = -3.1669
        private const val MAX_ACCURACY = 100.0
        private const val MIN_WEIGHT = 0.5
        private const val MAX_WEIGHT = 12.0
        private const val MIN_WINDOW = 2
        private const val MAX_WINDOW = 8
        private const val MOVES_PER_WINDOW = 10

        // Drops in winning chances (on the -1..1 scale) that Lichess calls a blunder, a mistake and an inaccuracy.
        private const val BLUNDER_DROP = 0.3
        private const val MISTAKE_DROP = 0.2
        private const val INACCURACY_DROP = 0.1

        // Lichess's mate advice: how winning (in centipawns, from the mover's side) the position still is.
        private const val STILL_WINNING_CP = 999
        private const val CLEARLY_WINNING_CP = 700

        /**
         * The report for a game whose positions are evaluated in [evals] (one per position, White's point of view, so one
         * more than there are moves). [whiteMovesFirst] is false for a game that starts with Black to move.
         * The formulas are Lichess's: win percent, move accuracy, game accuracy and the `Advice` judgements.
         */
        fun of(
            evals: List<Eval>,
            whiteMovesFirst: Boolean = true,
        ): GameReport {
            val moves = evals.size - 1
            if (moves <= 0) return GameReport(emptyPlayer(), emptyPlayer(), emptyList())

            fun mover(ply: Int) = if ((ply % 2 == 1) == whiteMovesFirst) PieceColor.WHITE else PieceColor.BLACK

            // Centipawns from White's point of view, with mates and checkmates at the limit.
            val cps = evals.map { it.clampedCentipawns() }
            val winWhite = cps.map(::winPercent)
            val judgements =
                (1..moves).map { ply ->
                    judge(evals[ply - 1], evals[ply], mover(ply))
                }
            val weights = windowWeights(winWhite, moves)

            fun player(color: PieceColor): PlayerReport {
                val plies = (1..moves).filter { mover(it) == color }
                if (plies.isEmpty()) return emptyPlayer()
                val sign = if (color == PieceColor.WHITE) 1 else -1
                val accuracies = plies.map { moveAccuracy(winPercentFor(winWhite[it - 1], sign), winPercentFor(winWhite[it], sign)) }
                val losses = plies.map { min(CP_LIMIT, max(0, sign * (cps[it - 1] - cps[it]))) }
                val judged = plies.mapNotNull { judgements[it - 1] }
                return PlayerReport(
                    accuracy = gameAccuracy(accuracies, plies.map { weights.getOrElse(it - 1) { MIN_WEIGHT } }),
                    acpl = losses.average(),
                    inaccuracies = judged.count { it == Judgement.INACCURACY },
                    mistakes = judged.count { it == Judgement.MISTAKE },
                    blunders = judged.count { it == Judgement.BLUNDER },
                )
            }
            return GameReport(player(PieceColor.WHITE), player(PieceColor.BLACK), judgements)
        }

        private fun emptyPlayer() = PlayerReport(null, null, 0, 0, 0)

        private fun Eval.clampedCentipawns(): Int =
            when (this) {
                is Eval.Cp -> centipawns.coerceIn(-CP_LIMIT, CP_LIMIT)
                is Eval.Mate -> if (moves > 0) CP_LIMIT else -CP_LIMIT
                is Eval.Checkmate -> if (winner == PieceColor.WHITE) CP_LIMIT else -CP_LIMIT
            }

        /** 0 to 100, from White's point of view. */
        private fun winPercent(cp: Int): Double = 50 + 50 * chances(cp)

        private fun winPercentFor(
            white: Double,
            sign: Int,
        ): Double = if (sign > 0) white else 100 - white

        private fun moveAccuracy(
            before: Double,
            after: Double,
        ): Double = (ACCURACY_SCALE * exp(ACCURACY_DECAY * (before - after)) + ACCURACY_OFFSET).coerceIn(0.0, MAX_ACCURACY)

        /**
         * Lichess weights each move by how volatile the game is around it: the standard deviation of the win percent in a
         * sliding window, kept between 0.5 and 12. Index 0 is the weight of the first move.
         */
        private fun windowWeights(
            winWhite: List<Double>,
            moves: Int,
        ): List<Double> {
            val size = (moves / MOVES_PER_WINDOW).coerceIn(MIN_WINDOW, MAX_WINDOW)
            val leading = List(max(0, min(size, winWhite.size) - 2)) { winWhite.take(size) }
            val sliding = if (winWhite.size < size) listOf(winWhite) else winWhite.windowed(size)
            return (leading + sliding).map { standardDeviation(it).coerceIn(MIN_WEIGHT, MAX_WEIGHT) }
        }

        private fun standardDeviation(values: List<Double>): Double {
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }

        /** The mean of the weighted mean and the harmonic mean, as Lichess's `gameAccuracy`. */
        private fun gameAccuracy(
            accuracies: List<Double>,
            weights: List<Double>,
        ): Double {
            val weighted = accuracies.zip(weights).sumOf { (a, w) -> a * w } / weights.sum()
            val harmonic = if (accuracies.any { it <= 0.0 }) 0.0 else accuracies.size / accuracies.sumOf { 1 / it }
            return (weighted + harmonic) / 2
        }

        /** Lichess's `Advice`: a drop in winning chances, or one of the mate rules, from [mover]'s point of view. */
        private fun judge(
            before: Eval,
            after: Eval,
            mover: PieceColor,
        ): Judgement? {
            if (after is Eval.Checkmate) return null // the mating move is the best move there is
            val sign = if (mover == PieceColor.WHITE) 1 else -1
            val mateBefore = (before as? Eval.Mate)?.moves?.times(sign)
            val mateAfter = (after as? Eval.Mate)?.moves?.times(sign)
            val cpBefore = sign * before.clampedCentipawns()
            val cpAfter = sign * after.clampedCentipawns()
            return when {
                mateBefore == null && mateAfter != null && mateAfter < 0 -> {
                    // The move allows a forced mate against the mover.
                    when {
                        cpBefore < -STILL_WINNING_CP -> Judgement.INACCURACY
                        cpBefore < -CLEARLY_WINNING_CP -> Judgement.MISTAKE
                        else -> Judgement.BLUNDER
                    }
                }

                mateBefore != null && mateBefore > 0 && mateAfter == null -> {
                    // The mover had a forced mate and let it go.
                    when {
                        cpAfter > STILL_WINNING_CP -> Judgement.INACCURACY
                        cpAfter > CLEARLY_WINNING_CP -> Judgement.MISTAKE
                        else -> Judgement.BLUNDER
                    }
                }

                mateBefore != null && mateBefore > 0 && mateAfter != null && mateAfter < 0 -> {
                    Judgement.BLUNDER
                }

                mateBefore != null || mateAfter != null -> {
                    null
                }

                else -> {
                    val drop = chances(cpBefore) - chances(cpAfter)
                    when {
                        drop >= BLUNDER_DROP -> Judgement.BLUNDER
                        drop >= MISTAKE_DROP -> Judgement.MISTAKE
                        drop >= INACCURACY_DROP -> Judgement.INACCURACY
                        else -> null
                    }
                }
            }
        }

        /** Winning chances on the -1..1 scale, the same curve as the chart. */
        private fun chances(cp: Int): Double = Eval.Cp(cp).winningChances()
    }
}
