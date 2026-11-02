package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import kotlin.math.exp
import kotlin.math.roundToInt

/** Lichess's winning-chances curve: centipawns to the range -1 (Black wins) to +1 (White wins). */
private const val WINNING_CHANCES_SLOPE = 0.00368208

/** Where [this] sits on the evaluation chart, from -1 to +1 and from White's point of view. Mates and checkmates are the extremes. */
fun Eval.winningChances(): Double =
    when (this) {
        is Eval.Cp -> 2 / (1 + exp(-WINNING_CHANCES_SLOPE * centipawns)) - 1
        is Eval.Mate -> if (moves > 0) 1.0 else -1.0
        is Eval.Checkmate -> if (winner == PieceColor.WHITE) 1.0 else -1.0
    }

/**
 * The position (ply) nearest to the horizontal touch position [x] on a chart [width] wide that spreads [positions] points
 * from the left edge to the right edge. The first position is at 0 and the last at [width]. Outside the chart it clamps; with
 * no positions or no width it is 0.
 */
fun nearestPly(
    x: Float,
    width: Float,
    positions: Int,
): Int {
    if (positions <= 1 || width <= 0f) return 0
    val last = positions - 1
    return (x / width * last).roundToInt().coerceIn(0, last)
}
