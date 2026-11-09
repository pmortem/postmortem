package app.postmortem.core.board

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

// The chesslib plumbing the board package shares: loading a FEN, naming squares and moves, and what a move takes.

/** The promotion letters of UCI, and the piece each one makes. */
internal val promotionLetters = mapOf('q' to PieceType.QUEEN, 'r' to PieceType.ROOK, 'b' to PieceType.BISHOP, 'n' to PieceType.KNIGHT)

internal fun boardOf(fen: String): Board = Board().apply { loadFromFen(fen) }

/** The square called [name] (`e4`, either case), or null for anything else. */
internal fun squareOf(name: String): Square? =
    Square.values().firstOrNull {
        it != Square.NONE &&
            it.value().equals(name, ignoreCase = true)
    }

/**
 * The legal move from [from] to [to] in this position, with [promotion] (`q`, `r`, `b` or `n`) for a pawn reaching the last rank,
 * or null when there is none. A promotion without a piece is not a move.
 */
internal fun Board.legalMove(
    from: Square,
    to: Square,
    promotion: Char?,
): Move? {
    val piece = promotion?.lowercaseChar()?.let { promotionLetters[it] }
    return legalMoves().firstOrNull { candidate ->
        candidate.from == from &&
            candidate.to == to &&
            if (candidate.promotion == Piece.NONE) piece == null else candidate.promotion.pieceType == piece
    }
}

/** The legal move written [uci] (`e2e4`, `b7a8q`) in this position, or null. */
internal fun Board.legalMove(uci: String): Move? {
    if (uci.length < 4) return null
    val from = squareOf(uci.substring(0, 2)) ?: return null
    val to = squareOf(uci.substring(2, 4)) ?: return null
    return legalMove(from, to, uci.getOrNull(4))
}

/** The piece [move] takes in this position: the one on its target square, or the pawn taken en passant; null when it takes nothing. */
internal fun Board.capturedBy(move: Move): Piece? {
    val there = getPiece(move.to)
    if (there != Piece.NONE) return there
    val mover = getPiece(move.from)
    val enPassant = mover.pieceType == PieceType.PAWN && move.from.file != move.to.file
    return if (enPassant) Piece.make(mover.pieceSide.flip(), PieceType.PAWN) else null
}
