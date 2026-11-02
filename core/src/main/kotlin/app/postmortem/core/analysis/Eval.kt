package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.uci.Score
import java.util.Locale

/** An evaluation from White's point of view. */
sealed interface Eval {
    data class Cp(
        val centipawns: Int,
    ) : Eval

    /** White mates in [moves] when positive, Black mates in `-moves` when negative. Never 0. */
    data class Mate(
        val moves: Int,
    ) : Eval

    /** The game ended in checkmate on the board; [winner] gave it. */
    data class Checkmate(
        val winner: PieceColor,
    ) : Eval

    companion object {
        /** Turns an engine score for the side to move into White's point of view. */
        fun fromScore(
            score: Score,
            whiteToMove: Boolean,
        ): Eval {
            val sign = if (whiteToMove) 1 else -1
            return when (score) {
                is Score.Cp -> {
                    Cp(sign * score.centipawns)
                }

                is Score.Mate -> {
                    if (score.moves == 0) {
                        Checkmate(if (whiteToMove) PieceColor.BLACK else PieceColor.WHITE)
                    } else {
                        Mate(sign * score.moves)
                    }
                }
            }
        }
    }
}

private const val CENTIPAWNS_PER_PAWN = 100.0

/** `+0.35`, `-1.20`, `M3` (White mates in 3), `-M3` (Black mates in 3), `1-0` or `0-1` for a mate on the board. */
fun Eval.format(): String =
    when (this) {
        is Eval.Cp -> if (centipawns == 0) "0.00" else String.format(Locale.ROOT, "%+.2f", centipawns / CENTIPAWNS_PER_PAWN)
        is Eval.Mate -> if (moves > 0) "M$moves" else "-M${-moves}"
        is Eval.Checkmate -> if (winner == PieceColor.WHITE) "1-0" else "0-1"
    }
