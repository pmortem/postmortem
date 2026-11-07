package app.postmortem.core.analysis

import app.postmortem.core.board.Moves
import app.postmortem.core.board.PieceColor
import app.postmortem.core.board.StaticExchange
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Judgement { INACCURACY, MISTAKE, BLUNDER }

/** One player's side of the report; the numbers are null when the player made no move. [acpl] is rounded, as Lichess shows it. */
data class PlayerReport(
    val accuracy: Double?,
    val acpl: Int?,
    val inaccuracies: Int,
    val mistakes: Int,
    val blunders: Int,
    val brilliants: Int = 0,
)

/** A move as the brilliancy rule needs to see it: the position before and after, and the move itself (UCI). */
data class PlayedMove(
    val fenBefore: String,
    val uci: String,
    val fenAfter: String,
)

/**
 * [judgements] has one entry per ply (null for a move that is fine). [brilliant] says per ply whether the move is a sound
 * sacrifice (see [of]); a move that has a judgement is never brilliant.
 */
data class GameReport(
    val white: PlayerReport,
    val black: PlayerReport,
    val judgements: List<Judgement?>,
    val brilliant: List<Boolean> = judgements.map { false },
) {
    /** How many moves of both sides got [judgement]. */
    fun count(judgement: Judgement): Int = judgements.count { it == judgement }

    companion object {
        private const val CP_LIMIT = 1000

        // Lichess's `AccuracyPercent.fromWinPercents`, to the digits it has.
        private const val ACCURACY_SCALE = 103.1668100711649
        private const val ACCURACY_DECAY = -0.04354415386753951
        private const val ACCURACY_OFFSET = -3.166924740191411

        /** Added to every move that lost win percent: "uncertainty bonus (due to imperfect analysis)". */
        private const val UNCERTAINTY_BONUS = 1.0
        private const val MAX_ACCURACY = 100.0

        /** The eval Lichess gives the start position (`legacyInitial`), whatever an engine says about it. */
        const val LICHESS_START_CP = 15
        private const val MIN_WEIGHT = 0.5
        private const val MAX_WEIGHT = 12.0
        private const val MIN_WINDOW = 2
        private const val MAX_WINDOW = 8
        private const val MOVES_PER_WINDOW = 10

        // Drops in winning chances (on the -1..1 scale) that Lichess calls a blunder, a mistake and an inaccuracy.
        private const val BLUNDER_DROP = 0.3
        private const val MISTAKE_DROP = 0.2
        const val INACCURACY_DROP = 0.1

        // Lichess's mate advice: how winning (in centipawns, from the mover's side) the position still is.
        private const val STILL_WINNING_CP = 999
        private const val CLEARLY_WINNING_CP = 700

        // The brilliancy rule (our own: chess.com's has no published definition). Win percents run 0..100.

        /** A brilliant move loses at most this much win percent: it is as good as the best move, or nearly. */
        const val BRILLIANT_MAX_DROP = 2.0

        /** A brilliant move is not played from a position already this won: giving up a rook when far ahead is not brilliant. */
        const val BRILLIANT_MAX_WIN_BEFORE = 90.0

        /** A brilliant move is not played from a position already lost: nobody calls a desperate piece sacrifice brilliant. */
        const val BRILLIANT_MIN_WIN_BEFORE = 25.0

        /** The opponent must be able to win at least this much (in pawns) by a static exchange: a minor piece, net of what the move took. */
        const val BRILLIANT_MIN_SACRIFICE = 3

        /**
         * The report for a game whose positions are evaluated in [evals] (one per position, White's point of view, so one
         * more than there are moves). [whiteMovesFirst] is false for a game that starts with Black to move.
         * The formulas are Lichess's: win percent, move accuracy, game accuracy and the `Advice` judgements. Lichess scores
         * the first move against a start position of [LICHESS_START_CP], so a caller that starts from the standard position
         * passes `startEval = Eval.Cp(LICHESS_START_CP)` to do the same; null keeps the engine's own eval of position 0.
         * With [moves] (one per move: the positions around it and its UCI) it also marks the brilliant moves: sound (the mover's win
         * percent drops by at most [BRILLIANT_MAX_DROP]), a real sacrifice (the opponent wins at least [BRILLIANT_MIN_SACRIFICE]
         * pawns by a static exchange, over and above what the move itself took), not from an already won or lost position
         * ([BRILLIANT_MAX_WIN_BEFORE], [BRILLIANT_MIN_WIN_BEFORE]), not the only legal move and not an answer to a check. Without it nothing is brilliant.
         */
        fun of(
            evalsOfPositions: List<Eval>,
            whiteMovesFirst: Boolean = true,
            startEval: Eval? = null,
            moves: List<PlayedMove>? = null,
        ): GameReport {
            val evals =
                if (startEval != null &&
                    evalsOfPositions.isNotEmpty()
                ) {
                    listOf(startEval) + evalsOfPositions.drop(1)
                } else {
                    evalsOfPositions
                }
            val moveCount = evals.size - 1
            if (moveCount <= 0) return GameReport(emptyPlayer(), emptyPlayer(), emptyList())

            fun mover(ply: Int) = if ((ply % 2 == 1) == whiteMovesFirst) PieceColor.WHITE else PieceColor.BLACK

            // Centipawns from White's point of view, with mates and checkmates at the limit.
            val cps = evals.map { it.clampedCentipawns() }
            val winWhite = cps.map(::winPercent)
            val judgements =
                (1..moveCount).map { ply ->
                    judge(evals[ply - 1], evals[ply], mover(ply))
                }
            val brilliant =
                (1..moveCount).map { ply ->
                    val played = moves?.getOrNull(ply - 1)
                    val sign = if (mover(ply) == PieceColor.WHITE) 1 else -1
                    played != null &&
                        judgements[ply - 1] == null &&
                        evals[ply] !is Eval.Checkmate &&
                        isBrilliant(winPercentFor(winWhite[ply - 1], sign), winPercentFor(winWhite[ply], sign), played)
                }
            val weights = windowWeights(winWhite, moveCount)

            fun player(color: PieceColor): PlayerReport {
                val plies = (1..moveCount).filter { mover(it) == color }
                if (plies.isEmpty()) return emptyPlayer()
                val sign = if (color == PieceColor.WHITE) 1 else -1
                val accuracies = plies.map { moveAccuracy(winPercentFor(winWhite[it - 1], sign), winPercentFor(winWhite[it], sign)) }
                // Lichess's `AccuracyCP`: the evals are clamped to +-1000, the loss itself is not capped.
                val losses = plies.map { max(0, sign * (cps[it - 1] - cps[it])) }
                val judged = plies.mapNotNull { judgements[it - 1] }
                return PlayerReport(
                    accuracy = gameAccuracy(accuracies, plies.map { weights.getOrElse(it - 1) { MIN_WEIGHT } }),
                    acpl = losses.average().roundToInt(),
                    inaccuracies = judged.count { it == Judgement.INACCURACY },
                    mistakes = judged.count { it == Judgement.MISTAKE },
                    blunders = judged.count { it == Judgement.BLUNDER },
                    brilliants = plies.count { brilliant[it - 1] },
                )
            }
            return GameReport(player(PieceColor.WHITE), player(PieceColor.BLACK), judgements, brilliant)
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
        ): Double =
            if (after >= before) {
                MAX_ACCURACY
            } else {
                (ACCURACY_SCALE * exp(ACCURACY_DECAY * (before - after)) + ACCURACY_OFFSET + UNCERTAINTY_BONUS).coerceIn(0.0, MAX_ACCURACY)
            }

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

        /** The brilliancy rule for one move, with the mover's win percent before and after it. */
        private fun isBrilliant(
            winBefore: Double,
            winAfter: Double,
            move: PlayedMove,
        ): Boolean =
            winBefore - winAfter <= BRILLIANT_MAX_DROP &&
                winBefore in BRILLIANT_MIN_WIN_BEFORE..BRILLIANT_MAX_WIN_BEFORE &&
                !Moves.inCheck(move.fenBefore) &&
                Moves.legalMoveCount(move.fenBefore) > 1 &&
                StaticExchange.bestGain(move.fenAfter) - StaticExchange.captured(move.fenBefore, move.uci) >= BRILLIANT_MIN_SACRIFICE

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
