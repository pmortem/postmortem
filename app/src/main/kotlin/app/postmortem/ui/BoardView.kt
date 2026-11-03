package app.postmortem.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import app.postmortem.R
import app.postmortem.core.board.BoardCell
import app.postmortem.core.board.BoardLabel
import app.postmortem.core.board.BoardLabels
import app.postmortem.core.board.BoardLayout
import app.postmortem.core.board.BoardPiece
import app.postmortem.core.board.PieceColor
import app.postmortem.core.board.PieceKind
import app.postmortem.ui.theme.BoardPalette
import app.postmortem.ui.theme.LocalBoardPalette

@DrawableRes
private fun drawableOf(piece: BoardPiece): Int =
    when (piece.color) {
        PieceColor.WHITE -> {
            when (piece.kind) {
                PieceKind.KING -> R.drawable.piece_white_king
                PieceKind.QUEEN -> R.drawable.piece_white_queen
                PieceKind.ROOK -> R.drawable.piece_white_rook
                PieceKind.BISHOP -> R.drawable.piece_white_bishop
                PieceKind.KNIGHT -> R.drawable.piece_white_knight
                PieceKind.PAWN -> R.drawable.piece_white_pawn
            }
        }

        PieceColor.BLACK -> {
            when (piece.kind) {
                PieceKind.KING -> R.drawable.piece_black_king
                PieceKind.QUEEN -> R.drawable.piece_black_queen
                PieceKind.ROOK -> R.drawable.piece_black_rook
                PieceKind.BISHOP -> R.drawable.piece_black_bishop
                PieceKind.KNIGHT -> R.drawable.piece_black_knight
                PieceKind.PAWN -> R.drawable.piece_black_pawn
            }
        }
    }

/** Draws the cells of a FEN. It decides nothing about chess: [BoardLayout] does. */
@Composable
fun BoardView(
    fen: String,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    /** The live engine's best moves, drawn above the pieces. */
    arrows: List<ArrowSpec> = emptyList(),
) {
    val cells: List<BoardCell> = remember(fen, flipped) { BoardLayout.cells(fen, flipped) }
    val labels = remember(flipped) { BoardLabels.all(flipped) }
    val palette = LocalBoardPalette.current
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val square = maxWidth / BoardLayout.SIZE
        // The squares and their coordinates are drawn together, under the pieces.
        Canvas(Modifier.fillMaxSize()) { drawSquaresAndLabels(palette, labels) }
        for (cell in cells) {
            Image(
                painter = painterResource(drawableOf(cell.piece)),
                contentDescription = null,
                modifier = Modifier.offset(square * cell.col, square * cell.row).size(square),
            )
        }
        if (arrows.isNotEmpty()) Canvas(Modifier.fillMaxSize()) { drawArrows(palette, arrows) }
    }
}

/** Squares, then the coordinates as the Lichess app draws them: regular text at a baseline in a corner, in the other square's colour. */
private fun DrawScope.drawSquaresAndLabels(
    palette: BoardPalette,
    labels: List<BoardLabel>,
) {
    val square = size.width / BoardLayout.SIZE
    for (row in 0 until BoardLayout.SIZE) {
        for (col in 0 until BoardLayout.SIZE) {
            drawRect(
                if (isLight(row, col)) palette.lightSquare else palette.darkSquare,
                topLeft = Offset(col * square, row * square),
                size = Size(square, square),
            )
        }
    }
    val paint =
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.DEFAULT
            textSize = square * BoardLabels.TEXT_SIZE
        }
    drawIntoCanvas { canvas ->
        for (label in labels) {
            paint.color = (if (isLight(label.row, label.col)) palette.darkSquare else palette.lightSquare).toArgb()
            paint.textAlign = if (label.isRank) Paint.Align.RIGHT else Paint.Align.LEFT
            canvas.nativeCanvas.drawText(label.text.toString(), label.x(square), label.y(square), paint)
        }
    }
}

private fun isLight(
    row: Int,
    col: Int,
): Boolean = (row + col) % 2 == 0
