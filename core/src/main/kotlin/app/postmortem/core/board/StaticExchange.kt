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

    private fun board(fen: String) = Board().apply { loadFromFen(fen) }

    private fun value(piece: Piece): Int =
        when (piece.pieceType) {
            PieceType.PAWN -> PAWN
            PieceType.KNIGHT, PieceType.BISHOP -> MINOR
            PieceType.ROOK -> ROOK
            PieceType.QUEEN -> QUEEN
            PieceType.KING -> KING
            else -> 0
        }

    /** What taking on the move's target square wins, [victim] first; null when the move takes nothing. */
    private fun victim(
        board: Board,
        move: Move,
    ): Piece? {
        val there = board.getPiece(move.to)
        if (there != Piece.NONE) return there
        val mover = board.getPiece(move.from)
        val enPassant = mover.pieceType == PieceType.PAWN && move.from.file != move.to.file
        return if (enPassant) Piece.make(mover.pieceSide.flip(), PieceType.PAWN) else null
    }

    /** The captures on [square] the side to move may make, the one with the least valuable capturer first. */
    private fun capturesOn(
        board: Board,
        square: Square,
    ): List<Move> =
        board
            .legalMoves()
            .filter { it.to == square && victim(board, it) != null }
            .sortedWith(compareBy({ value(board.getPiece(it.from)) }, { if (it.promotion.pieceType == PieceType.QUEEN) 0 else 1 }))

    /** What the side to move wins on [square] with the best play of both sides, never less than 0 (it may decline to take). */
    private fun exchange(
        board: Board,
        square: Square,
    ): Int {
        val move = capturesOn(board, square).firstOrNull() ?: return 0
        val gain = value(victim(board, move)!!)
        board.doMove(move)
        val reply = exchange(board, square)
        board.undoMove()
        return maxOf(0, gain - reply)
    }

    /** The most the side to move in [fen] wins by taking on any one square; 0 when it wins nothing by capturing. */
    fun bestGain(fen: String): Int {
        val board = board(fen)
        val squares =
            board
                .legalMoves()
                .filter { victim(board, it) != null }
                .map { it.to }
                .distinct()
        return squares
            .maxOfOrNull { square ->
                val move = capturesOn(board, square).first()
                val gain = value(victim(board, move)!!)
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
        val board = board(fen)
        val move =
            board.legalMoves().firstOrNull {
                val promotion =
                    it.promotion.pieceType
                        ?.name
                        ?.get(0)
                        ?.lowercaseChar()
                val base = it.from.value().lowercase() + it.to.value().lowercase()
                if (promotion == null) uci == base else uci == base + (if (promotion == 'k') 'n' else promotion)
            } ?: return 0
        return victim(board, move)?.let(::value) ?: 0
    }
}
