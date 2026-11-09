package app.postmortem.core.board

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/**
 * Static exchange evaluation: how much material the side to move wins by taking on a square and then letting both sides
 * keep taking there, each with its least valuable piece and each free to stop. Values are in pawns (P1 N3 B3 R5 Q9).
 * The exchange is played on the real board, so a pinned piece, an x-ray behind a capturer and en passant all count as the
 * rules say. A promotion on the way is played as a queen, and its extra value is not counted.
 */
object StaticExchange {
    private const val PAWN = 1
    private const val MINOR = 3
    private const val ROOK = 5
    private const val QUEEN = 9
    private const val KING = 100

    private fun value(piece: Piece): Int =
        when (piece.pieceType) {
            PieceType.PAWN -> PAWN
            PieceType.KNIGHT, PieceType.BISHOP -> MINOR
            PieceType.ROOK -> ROOK
            PieceType.QUEEN -> QUEEN
            PieceType.KING -> KING
            else -> 0
        }

    /** The captures on [square] the side to move may make, the one with the least valuable capturer first. */
    private fun capturesOn(
        board: Board,
        square: Square,
    ): List<Move> =
        board
            .legalMoves()
            .filter { it.to == square && board.capturedBy(it) != null }
            .sortedWith(compareBy({ value(board.getPiece(it.from)) }, { if (it.promotion.pieceType == PieceType.QUEEN) 0 else 1 }))

    /** What the side to move wins on [square] with the best play of both sides, never less than 0 (it may decline to take). */
    private fun exchange(
        board: Board,
        square: Square,
    ): Int {
        val move = capturesOn(board, square).firstOrNull() ?: return 0
        val gain = value(board.capturedBy(move)!!)
        board.doMove(move)
        val reply = exchange(board, square)
        board.undoMove()
        return maxOf(0, gain - reply)
    }

    /** The most the side to move in [fen] wins by taking on any one square; 0 when it wins nothing by capturing. */
    fun bestGain(fen: String): Int {
        val board = boardOf(fen)
        val squares =
            board
                .legalMoves()
                .filter { board.capturedBy(it) != null }
                .map { it.to }
                .distinct()
        return squares
            .maxOfOrNull { square ->
                val move = capturesOn(board, square).first()
                val gain = value(board.capturedBy(move)!!)
                board.doMove(move)
                val reply = exchange(board, square)
                board.undoMove()
                gain - reply
            }?.coerceAtLeast(0) ?: 0
    }

    /** The value of the piece the move [uci] takes in [fen], 0 when it takes nothing (or is not legal there). */
    fun captured(
        fen: String,
        uci: String,
    ): Int {
        val board = boardOf(fen)
        val move = board.legalMove(uci) ?: return 0
        return board.capturedBy(move)?.let(::value) ?: 0
    }
}
