package app.postmortem.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import app.postmortem.R
import app.postmortem.UiTags
import app.postmortem.core.board.BoardArrow
import app.postmortem.core.board.BoardCell
import app.postmortem.core.board.BoardLabel
import app.postmortem.core.board.BoardLabels
import app.postmortem.core.board.BoardLayout
import app.postmortem.core.board.BoardPiece
import app.postmortem.core.board.MoveTarget
import app.postmortem.core.board.PieceColor
import app.postmortem.core.board.PieceKind
import app.postmortem.ui.theme.BoardPalette
import app.postmortem.ui.theme.HintStyle
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

/** What the board shows and reports for playing moves: the picked-up piece, where it may go, and a promotion waiting for a piece. */
data class BoardInput(
    val selected: String? = null,
    val targets: List<MoveTarget> = emptyList(),
    val promotion: PromotionPrompt? = null,
    val onTap: (String) -> Unit = {},
    val onDrag: (String, String) -> Unit = { _, _ -> },
)

/** The pawn move to [square] waiting for a piece of [color]; [onChoose] gets `q`, `r`, `b` or `n`, [onCancel] a tap elsewhere. */
data class PromotionPrompt(
    val square: String,
    val color: PieceColor,
    val onChoose: (Char) -> Unit,
    val onCancel: () -> Unit,
)

private val promotionKinds =
    listOf('q' to PieceKind.QUEEN, 'r' to PieceKind.ROOK, 'b' to PieceKind.BISHOP, 'n' to PieceKind.KNIGHT)

/** Draws the cells of a FEN. It decides nothing about chess: [BoardLayout] does. */
@Composable
fun BoardView(
    fen: String,
    modifier: Modifier = Modifier,
    flipped: Boolean = false,
    /** The live engine's best moves, drawn above the pieces. */
    arrows: List<ArrowSpec> = emptyList(),
    /** The move that led to [fen] in UCI, whose two squares are tinted; null at the start position. */
    lastMove: String? = null,
    /** Playing moves: null leaves the board as a picture. */
    input: BoardInput? = null,
) {
    val cells: List<BoardCell> = remember(fen, flipped) { BoardLayout.cells(fen, flipped) }
    val labels = remember(flipped) { BoardLabels.all(flipped) }
    val moved = remember(lastMove, flipped) { lastMove?.let { BoardLayout.arrow(it, flipped) } }
    val palette = LocalBoardPalette.current
    var drag: Drag? by remember { mutableStateOf(null) }
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val square = maxWidth / BoardLayout.SIZE
        val squarePx = constraints.maxWidth.toFloat() / BoardLayout.SIZE

        fun squareAt(position: Offset): String? {
            val col = (position.x / squarePx).toInt()
            val row = (position.y / squarePx).toInt()
            return if (row in 0 until BoardLayout.SIZE && col in 0 until BoardLayout.SIZE) BoardLayout.squareAt(row, col, flipped) else null
        }
        val picking =
            if (input == null) {
                Modifier
            } else {
                Modifier.pointerInput(flipped, fen, input.targets, input.selected) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val from = squareAt(start)
                            val piece = cells.firstOrNull { BoardLayout.squareAt(it.row, it.col, flipped) == from }?.piece
                            drag = if (from != null && piece != null) Drag(from, piece, start) else null
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            drag = drag?.let { it.copy(at = it.at + amount) }
                        },
                        onDragEnd = {
                            drag?.let { held -> squareAt(held.at)?.let { to -> input.onDrag(held.from, to) } }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    )
                }
            }
        Box(Modifier.fillMaxSize().then(picking)) {
            // The squares and their coordinates are drawn together, under the pieces.
            Canvas(Modifier.fillMaxSize()) {
                drawSquaresAndLabels(
                    palette,
                    labels,
                    moved,
                    input?.selected?.let {
                        BoardLayout.position(it).let { (r, c) ->
                            cellOf(r, c, flipped)
                        }
                    },
                )
            }
            for (cell in cells) {
                val held = drag?.takeIf { it.from == BoardLayout.squareAt(cell.row, cell.col, flipped) }
                Image(
                    painter = painterResource(drawableOf(cell.piece)),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .offset(square * cell.col, square * cell.row)
                            .size(square)
                            .alpha(if (held != null) 0.35f else 1f),
                )
            }
            if (input != null && input.targets.isNotEmpty()) {
                Canvas(Modifier.fillMaxSize()) { drawHints(palette, input.targets, flipped) }
            }
            if (arrows.isNotEmpty()) Canvas(Modifier.fillMaxSize()) { drawArrows(palette, arrows) }
            if (input != null) {
                for (row in 0 until BoardLayout.SIZE) {
                    for (col in 0 until BoardLayout.SIZE) {
                        val name = BoardLayout.squareAt(row, col, flipped)
                        Box(
                            Modifier
                                .offset(square * col, square * row)
                                .size(square)
                                .testTag(UiTags.square(name))
                                .clickable(onClickLabel = name) { input.onTap(name) },
                        )
                    }
                }
            }
            drag?.let { held ->
                val size = square * HintStyle.DRAG_SCALE
                val half = with(LocalDensity.current) { (squarePx * HintStyle.DRAG_SCALE / 2).toDp() }
                Image(
                    painter = painterResource(drawableOf(held.piece)),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .offset(
                                with(LocalDensity.current) { held.at.x.toDp() } - half,
                                with(LocalDensity.current) { held.at.y.toDp() } - half,
                            ).size(size),
                )
            }
            input?.promotion?.let { prompt -> PromotionPicker(prompt, square, squarePx, flipped, palette) }
        }
    }
}

/** A piece being dragged: where it started, what it is and where the finger is. */
private data class Drag(
    val from: String,
    val piece: BoardPiece,
    val at: Offset,
)

/** The picker over the promotion square: four pieces in a card, and a tap anywhere else on the board cancels. */
@Composable
private fun PromotionPicker(
    prompt: PromotionPrompt,
    square: Dp,
    squarePx: Float,
    flipped: Boolean,
    palette: BoardPalette,
) {
    val (row, col) = BoardLayout.position(prompt.square).let { (r, c) -> cellOf(r, c, flipped) }
    val first = col.coerceAtMost(BoardLayout.SIZE - promotionKinds.size)
    Box(
        Modifier.fillMaxSize().clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
        ) { prompt.onCancel() },
    )
    Row(Modifier.offset(square * first, square * row).background(palette.promotionCard)) {
        for ((letter, kind) in promotionKinds) {
            Image(
                painter = painterResource(drawableOf(BoardPiece(prompt.color, kind))),
                contentDescription = letter.toString(),
                modifier =
                    Modifier
                        .size(square)
                        .clickable { prompt.onChoose(letter) },
            )
        }
    }
}

/** The screen cell of the cell (row, col) of an upright board, turned half a round when [flipped]. */
private fun cellOf(
    row: Int,
    col: Int,
    flipped: Boolean,
): Pair<Int, Int> = if (flipped) (BoardLayout.SIZE - 1 - row) to (BoardLayout.SIZE - 1 - col) else row to col

/** Dots on the empty squares the picked-up piece may go to, rings on the squares where it captures. */
private fun DrawScope.drawHints(
    palette: BoardPalette,
    targets: List<MoveTarget>,
    flipped: Boolean,
) {
    val square = size.width / BoardLayout.SIZE
    for (target in targets) {
        val (row, col) = BoardLayout.position(target.square).let { (r, c) -> cellOf(r, c, flipped) }
        val center = Offset((col + 0.5f) * square, (row + 0.5f) * square)
        if (target.capture) {
            drawCircle(
                palette.moveHint,
                radius = (HintStyle.RING_RADIUS - HintStyle.RING_WIDTH / 2) * square,
                center = center,
                style =
                    Stroke(HintStyle.RING_WIDTH * square),
            )
        } else {
            drawCircle(palette.moveHint, radius = HintStyle.DOT * square, center = center)
        }
    }
}

/** Squares, the last move's tint, then the coordinates as the Lichess app draws them: regular text at a baseline in a corner, in the other square's colour. */
private fun DrawScope.drawSquaresAndLabels(
    palette: BoardPalette,
    labels: List<BoardLabel>,
    lastMove: BoardArrow?,
    selected: Pair<Int, Int>?,
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
    if (lastMove != null) {
        for ((row, col) in listOf(lastMove.fromRow to lastMove.fromCol, lastMove.toRow to lastMove.toCol)) {
            drawRect(palette.lastMove, topLeft = Offset(col * square, row * square), size = Size(square, square))
        }
    }
    if (selected != null) {
        drawRect(palette.selection, topLeft = Offset(selected.second * square, selected.first * square), size = Size(square, square))
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
