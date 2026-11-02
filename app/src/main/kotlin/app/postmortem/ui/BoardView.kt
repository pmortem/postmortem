package app.postmortem.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.postmortem.R
import app.postmortem.core.board.BoardCell
import app.postmortem.core.board.BoardLayout
import app.postmortem.core.board.BoardPiece
import app.postmortem.core.board.PieceColor
import app.postmortem.core.board.PieceKind
import app.postmortem.ui.theme.LocalBoardPalette
import app.postmortem.ui.theme.Spacing

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
) {
    val cells: List<BoardCell> = remember(fen) { BoardLayout.cells(fen) }
    val palette = LocalBoardPalette.current
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val square = maxWidth / BoardLayout.SIZE
        Box(Modifier.fillMaxSize()) {
            for (row in 0 until BoardLayout.SIZE) {
                for (col in 0 until BoardLayout.SIZE) {
                    val light = (row + col) % 2 == 0
                    val label = if (light) palette.darkSquare else palette.lightSquare
                    Box(
                        Modifier
                            .offset(square * col, square * row)
                            .size(square)
                            .background(if (light) palette.lightSquare else palette.darkSquare),
                    ) {
                        // Lichess style: ranks in the top-left of the left file, files in the bottom-right of the bottom rank.
                        if (col == 0) Coordinate(BoardLayout.rankName(row), label, square, Modifier.align(Alignment.TopStart))
                        if (row == BoardLayout.SIZE - 1) {
                            Coordinate(BoardLayout.fileName(col), label, square, Modifier.align(Alignment.BottomEnd))
                        }
                    }
                }
            }
            for (cell in cells) {
                Image(
                    painter = painterResource(drawableOf(cell.piece)),
                    contentDescription = null,
                    modifier = Modifier.offset(square * cell.col, square * cell.row).size(square),
                )
            }
        }
    }
}

@Composable
private fun Coordinate(
    name: Char,
    color: Color,
    square: Dp,
    modifier: Modifier,
) {
    val size = (square.value * Spacing.coordinateTextRatio).sp
    Text(
        name.toString(),
        color = color,
        // The line height is the font size: the default line height is taller than the square's corner, which centred the glyph.
        style = TextStyle(fontSize = size, lineHeight = size, fontWeight = FontWeight.Bold),
        modifier = modifier.padding(Spacing.coordinatePadding),
    )
}
