package app.postmortem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.postmortem.core.board.MoveTarget
import app.postmortem.core.board.Moves

/**
 * Playing a move on the board by hand, the same on the result screen and in practice. A tap picks a piece up, puts it down or plays it
 * to a square it may go to; a drag plays it when legal; a pawn reaching the last rank first asks for its piece. What is picked up or
 * asked counts only while [fen] still gives the position it was picked on; null means no move can be made now. A legal move goes to
 * [onMove] with its promotion piece (`q`, `r`, `b`, `n`) or null.
 */
class MoveInput(
    private val fen: () -> String?,
    private val onMove: (from: String, to: String, promotion: Char?) -> Unit,
) {
    private var selection: BoardSelection? by mutableStateOf(null)
    private var asked: PromotionAsked? by mutableStateOf(null)

    /** The square of the picked-up piece on the position shown, or null. */
    val selected: String? get() = selection?.takeIf { it.fen == fen() }?.square

    /** Where the picked-up piece may go. */
    val targets: List<MoveTarget> get() = selection?.takeIf { it.fen == fen() }?.targets.orEmpty()

    /** The pawn move waiting for a piece to be chosen, or null. */
    val promotion: PromotionAsked? get() = asked?.takeIf { it.fen == fen() }

    /**
     * A tap on [square]: with a piece picked up, a square it may go to plays the move, another piece of the side to move picks that
     * one up, and anything else puts the piece down. With none picked up, a piece that can move is picked up.
     */
    fun tap(square: String) {
        val fen = fen() ?: return
        if (promotion != null) return
        val picked = selection?.takeIf { it.fen == fen }
        if (picked != null && picked.targets.any { it.square == square }) {
            attempt(fen, picked.square, square)
            return
        }
        val targets = Moves.legalTargets(fen, square)
        selection = if (targets.isEmpty() || picked?.square == square) null else BoardSelection(fen, square, targets)
    }

    /** A piece dragged from [from] and dropped on [to]: played when legal; otherwise nothing changes and the piece snaps back. */
    fun drag(
        from: String,
        to: String,
    ) {
        val fen = fen() ?: return
        if (promotion != null || from == to) return
        if (Moves.legalTargets(fen, from).none { it.square == to }) return
        attempt(fen, from, to)
    }

    private fun attempt(
        fen: String,
        from: String,
        to: String,
    ) {
        selection = null
        if (Moves.needsPromotion(fen, from, to)) asked = PromotionAsked(fen, from, to) else onMove(from, to, null)
    }

    /** The piece (`q`, `r`, `b` or `n`) chosen for the waiting promotion. */
    fun choose(piece: Char) {
        val waiting = promotion ?: return
        asked = null
        onMove(waiting.from, waiting.to, piece)
    }

    /** Drops the waiting promotion; true when there was one. */
    fun cancelPromotion(): Boolean {
        if (asked == null) return false
        asked = null
        return true
    }

    /** Puts the picked-up piece down. */
    fun putDown() {
        selection = null
    }

    /** Forgets the picked-up piece and the waiting promotion. */
    fun clear() {
        selection = null
        asked = null
    }
}

/** A piece picked up on [square] of the position [fen], with the squares it may go to. */
private data class BoardSelection(
    val fen: String,
    val square: String,
    val targets: List<MoveTarget>,
)

/** A pawn move from [from] to [to] on the position [fen] that needs a piece chosen. */
data class PromotionAsked(
    val fen: String,
    val from: String,
    val to: String,
)
