package app.postmortem.core.board

import app.postmortem.core.pgn.Ply
import app.postmortem.core.pgn.whiteToMove
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/** A square a piece can move to: [capture] when it takes something (en passant too), [promotion] when the move needs a piece chosen. */
data class MoveTarget(
    val square: String,
    val capture: Boolean,
    val promotion: Boolean,
)

/**
 * Playing moves on a position: which squares a piece may go to, and the [Ply] (SAN, UCI and the FEN after it) of a move. Only legal
 * moves are accepted, so a UI built on it cannot reach a wrong position. The UI never imports chesslib.
 */
object Moves {
    private const val KINGSIDE_JUMP = 2

    /** The squares the piece on [from] may legally move to; empty when there is no piece of the side to move there. */
    fun legalTargets(
        fen: String,
        from: String,
    ): List<MoveTarget> {
        val board = boardOf(fen)
        val origin = squareOf(from) ?: return emptyList()
        return board
            .legalMoves()
            .filter { it.from == origin }
            .groupBy { it.to }
            .map { (to, moves) ->
                MoveTarget(
                    square = to.value().lowercase(),
                    capture = isCapture(board, moves.first()),
                    promotion = moves.first().promotion != Piece.NONE,
                )
            }.sortedBy { it.square }
    }

    /** True when moving the piece from [from] to [to] is legal and needs a promotion piece to be chosen. */
    fun needsPromotion(
        fen: String,
        from: String,
        to: String,
    ): Boolean = legalTargets(fen, from).any { it.square == to && it.promotion }

    /**
     * The move from [from] to [to] ([promotion] is `q`, `r`, `b` or `n`), or null when it is not legal. A promotion without a piece
     * chosen is not a move yet.
     */
    fun play(
        fen: String,
        from: String,
        to: String,
        promotion: Char? = null,
    ): Ply? {
        val board = boardOf(fen)
        val origin = squareOf(from) ?: return null
        val target = squareOf(to) ?: return null
        val move = board.legalMove(origin, target, promotion) ?: return null
        return ply(board, move)
    }

    private fun isCapture(
        board: Board,
        move: Move,
    ): Boolean = board.capturedBy(move) != null

    private fun ply(
        board: Board,
        move: Move,
    ): Ply {
        val san = sanBefore(board, move)
        board.doMove(move)
        val suffix =
            if (board.isMated) {
                "#"
            } else if (board.isKingAttacked) {
                "+"
            } else {
                ""
            }
        val promotion = if (move.promotion == Piece.NONE) "" else promotionLetter(move.promotion.pieceType).toString()
        return Ply(san + suffix, move.from.value().lowercase() + move.to.value().lowercase() + promotion, board.fen)
    }

    private fun promotionLetter(type: PieceType): Char = promotionLetters.entries.first { it.value == type }.key

    /** The SAN of [move] in the position of [board], without the check or mate mark. */
    private fun sanBefore(
        board: Board,
        move: Move,
    ): String {
        val mover = board.getPiece(move.from)
        val kind = mover.pieceType
        val capture = isCapture(board, move)
        val target = move.to.value().lowercase()
        if (kind == PieceType.KING && kotlin.math.abs(move.to.file.ordinal - move.from.file.ordinal) == KINGSIDE_JUMP) {
            return if (move.to.file.ordinal > move.from.file.ordinal) "O-O" else "O-O-O"
        }
        val promotion = if (move.promotion == Piece.NONE) "" else "=${promotionLetter(move.promotion.pieceType).uppercaseChar()}"
        if (kind == PieceType.PAWN) {
            val prefix = if (capture) "${move.from.value().lowercase()[0]}x" else ""
            return "$prefix$target$promotion"
        }
        val rivals =
            board.legalMoves().filter {
                it.to == move.to && it.from != move.from && board.getPiece(it.from) == mover
            }
        val sameFile = rivals.any { it.from.file == move.from.file }
        val sameRank = rivals.any { it.from.rank == move.from.rank }
        val origin = move.from.value().lowercase()
        val disambiguation =
            when {
                rivals.isEmpty() -> ""
                !sameFile -> origin.substring(0, 1)
                !sameRank -> origin.substring(1, 2)
                else -> origin
            }
        val letter = if (kind == PieceType.KNIGHT) "N" else kind.name.first().toString()
        return "$letter$disambiguation${if (capture) "x" else ""}$target"
    }

    /** How many legal moves the side to move has in [fen]. */
    fun legalMoveCount(fen: String): Int = boardOf(fen).legalMoves().size

    /** True when the side to move in [fen] is in check. */
    fun inCheck(fen: String): Boolean = boardOf(fen).isKingAttacked

    /** Who moves in [fen]: the colour of the pieces a player picks up, and of the piece a promotion makes. */
    fun sideToMove(fen: String): PieceColor = if (whiteToMove(fen)) PieceColor.WHITE else PieceColor.BLACK
}
