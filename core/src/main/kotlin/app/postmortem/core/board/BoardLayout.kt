package app.postmortem.core.board

enum class PieceColor { WHITE, BLACK }

enum class PieceKind { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

data class BoardPiece(
    val color: PieceColor,
    val kind: PieceKind,
)

/** A piece on screen: [row] 0 is the top row (rank 8 with White at the bottom), [col] 0 is the left column (file a). */
data class BoardCell(
    val row: Int,
    val col: Int,
    val piece: BoardPiece,
)

/** Maps a FEN to the cells Compose draws, White at the bottom, so a wrong position is a logic bug a test catches. */
object BoardLayout {
    const val SIZE = 8

    private val kinds =
        mapOf(
            'k' to PieceKind.KING,
            'q' to PieceKind.QUEEN,
            'r' to PieceKind.ROOK,
            'b' to PieceKind.BISHOP,
            'n' to PieceKind.KNIGHT,
            'p' to PieceKind.PAWN,
        )

    fun cells(fen: String): List<BoardCell> {
        val ranks = fen.substringBefore(' ').split('/')
        require(ranks.size == SIZE) { "FEN needs $SIZE ranks: $fen" }
        val cells = ArrayList<BoardCell>()
        ranks.forEachIndexed { row, rank ->
            var col = 0
            for (c in rank) {
                if (c.isDigit()) {
                    col += c.digitToInt()
                } else {
                    val kind = requireNotNull(kinds[c.lowercaseChar()]) { "bad piece '$c' in $fen" }
                    require(col < SIZE) { "rank too long in $fen" }
                    cells += BoardCell(row, col, BoardPiece(if (c.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK, kind))
                    col++
                }
            }
            require(col == SIZE) { "rank '$rank' does not fill $SIZE files in $fen" }
        }
        return cells
    }

    /** The cell of an algebraic square such as `e4`, as (row, col). */
    fun position(square: String): Pair<Int, Int> = (SIZE - (square[1] - '0')) to (square[0] - 'a')
}
