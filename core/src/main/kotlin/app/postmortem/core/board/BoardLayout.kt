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

    /** The file letter of screen column [col]: `a` at 0 up to `h` at 7, or `h` to `a` when [flipped]. */
    fun fileName(
        col: Int,
        flipped: Boolean = false,
    ): Char {
        require(col in 0 until SIZE) { "column $col is off the board" }
        return 'a' + if (flipped) SIZE - 1 - col else col
    }

    /** The rank number of screen row [row]: `8` at the top row 0 down to `1` at row 7, or `1` to `8` when [flipped]. */
    fun rankName(
        row: Int,
        flipped: Boolean = false,
    ): Char {
        require(row in 0 until SIZE) { "row $row is off the board" }
        return '1' + if (flipped) row else SIZE - 1 - row
    }

    /** The pieces of [fen] where they are drawn: rotated half a turn when [flipped], so Black is at the bottom. */
    fun cells(
        fen: String,
        flipped: Boolean = false,
    ): List<BoardCell> {
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
                    val piece = BoardPiece(if (c.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK, kind)
                    cells += if (flipped) BoardCell(SIZE - 1 - row, SIZE - 1 - col, piece) else BoardCell(row, col, piece)
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

/**
 * A coordinate drawn in the corner of an edge square: a rank digit on the left file or a file letter on the bottom rank,
 * placed as v1 and Lichess place them, so they stay clear of the piece in the middle of the square.
 * [row] and [col] are the screen square the label sits in.
 */
data class BoardLabel(
    val text: Char,
    val row: Int,
    val col: Int,
    val isRank: Boolean,
) {
    /** Left edge of the text, for a square of [square] pixels. */
    fun x(square: Float): Float =
        if (isRank) {
            (col + BoardLabels.RANK_LEFT) * square
        } else {
            (col + 1 - BoardLabels.FILE_RIGHT_INSET) * square
        }

    /** The text's baseline. */
    fun y(square: Float): Float =
        if (isRank) {
            (row + BoardLabels.RANK_BASELINE) * square
        } else {
            (row + 1 - BoardLabels.FILE_BASELINE_INSET) * square
        }
}

/** The 16 coordinates of a board, and where they go, as fractions of a square (v1's numbers). */
object BoardLabels {
    /** Text size as a fraction of the square. */
    const val TEXT_SIZE = 0.2f

    /** A rank digit: from the left edge, baseline this far below the top of its square. */
    const val RANK_LEFT = 0.06f
    const val RANK_BASELINE = 0.22f

    /** A file letter: starts this far left of the square's right edge, baseline this far above its bottom. */
    const val FILE_RIGHT_INSET = 0.2f
    const val FILE_BASELINE_INSET = 0.07f

    /** Ranks down the left file (top to bottom), then files along the bottom rank (left to right). */
    fun all(flipped: Boolean = false): List<BoardLabel> =
        (0 until BoardLayout.SIZE).map { row -> BoardLabel(BoardLayout.rankName(row, flipped), row, 0, isRank = true) } +
            (0 until BoardLayout.SIZE).map { col ->
                BoardLabel(BoardLayout.fileName(col, flipped), BoardLayout.SIZE - 1, col, isRank = false)
            }
}
