package app.postmortem.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import app.postmortem.core.analysis.LiveLine
import app.postmortem.core.board.BoardArrow
import app.postmortem.core.board.BoardLayout
import app.postmortem.ui.theme.ArrowStyle
import app.postmortem.ui.theme.BoardPalette
import kotlin.math.hypot

/** One of the live engine's best moves as the board draws it: the squares, its [rank] (1 is the best) and the eval to show. */
data class ArrowSpec(
    val arrow: BoardArrow,
    val rank: Int,
    val label: String,
)

/** The arrows for the [lines], where the board shows them when it is [flipped]. */
fun arrowsOf(
    lines: List<LiveLine>,
    flipped: Boolean,
): List<ArrowSpec> = lines.map { ArrowSpec(BoardLayout.arrow(it.move, flipped), it.rank, it.scoreText()) }

/**
 * For each arrow, how many arrows before it end on the same square. Their labels would sit on top of each other, so the board
 * moves the label of the n-th one up by n label heights.
 */
fun labelSlots(arrows: List<ArrowSpec>): List<Int> =
    arrows.mapIndexed { index, spec ->
        arrows.take(index).count { it.arrow.toRow == spec.arrow.toRow && it.arrow.toCol == spec.arrow.toCol }
    }

/** Draws the arrows from the worst to the best, so the best is on top, then their labels. */
fun DrawScope.drawArrows(
    palette: BoardPalette,
    arrows: List<ArrowSpec>,
) {
    if (arrows.isEmpty()) return
    val square = size.width / BoardLayout.SIZE
    arrows.sortedByDescending { it.rank }.forEach { drawArrow(palette, it, square) }
    val slots = labelSlots(arrows)
    arrows.forEachIndexed { index, spec -> drawLabel(palette, spec, slots[index], square) }
}

private fun DrawScope.drawArrow(
    palette: BoardPalette,
    spec: ArrowSpec,
    square: Float,
) {
    val a = spec.arrow
    val from = Offset((a.fromCol + 0.5f) * square, (a.fromRow + 0.5f) * square)
    val to = Offset((a.toCol + 0.5f) * square, (a.toRow + 0.5f) * square)
    val length = hypot(to.x - from.x, to.y - from.y)
    if (length == 0f) return
    val dir = Offset((to.x - from.x) / length, (to.y - from.y) / length)
    val normal = Offset(-dir.y, dir.x)
    val half = square * ArrowStyle.SHAFT * ArrowStyle.width(spec.rank) / 2
    val headLength = square * ArrowStyle.HEAD_LENGTH
    val headHalf = square * ArrowStyle.HEAD_WIDTH * ArrowStyle.width(spec.rank) / 2 + half
    val start = from + dir * (square * ArrowStyle.START_GAP)
    val tip = to - dir * (square * ArrowStyle.TIP_GAP)
    val base = tip - dir * headLength
    val path =
        Path().apply {
            moveTo(start.x + normal.x * half, start.y + normal.y * half)
            lineTo(base.x + normal.x * half, base.y + normal.y * half)
            lineTo(base.x + normal.x * headHalf, base.y + normal.y * headHalf)
            lineTo(tip.x, tip.y)
            lineTo(base.x - normal.x * headHalf, base.y - normal.y * headHalf)
            lineTo(base.x - normal.x * half, base.y - normal.y * half)
            lineTo(start.x - normal.x * half, start.y - normal.y * half)
            close()
        }
    drawPath(path, palette.arrow.copy(alpha = ArrowStyle.alpha(spec.rank)))
}

/** A semi-transparent pill with the eval above the arrow's head; it moves down instead when the head is on the top row. */
private fun DrawScope.drawLabel(
    palette: BoardPalette,
    spec: ArrowSpec,
    slot: Int,
    square: Float,
) {
    val scale = ArrowStyle.labelScale(spec.rank)
    val paint =
        Paint().apply {
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
            textSize = square * ArrowStyle.LABEL_TEXT * scale
            textAlign = Paint.Align.CENTER
            color = palette.onArrowLabel.toArgb()
        }
    val width = paint.measureText(spec.label) + 2 * square * ArrowStyle.LABEL_PADDING_X
    val height = paint.textSize + 2 * square * ArrowStyle.LABEL_PADDING_Y
    val centreX = ((spec.arrow.toCol + 0.5f) * square).coerceIn(width / 2, size.width - width / 2)
    val targetY = (spec.arrow.toRow + 0.5f) * square
    val above = targetY - square * ArrowStyle.LABEL_RISE - slot * height
    val centreY = if (above - height / 2 >= 0f) above else targetY + square * ArrowStyle.LABEL_RISE + slot * height
    val top = (centreY - height / 2).coerceIn(0f, size.height - height)
    drawRoundRect(palette.arrowLabel, Offset(centreX - width / 2, top), Size(width, height), CornerRadius(height / 2))
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(spec.label, centreX, top + height / 2 - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
