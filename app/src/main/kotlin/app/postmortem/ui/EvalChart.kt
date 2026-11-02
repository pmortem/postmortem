package app.postmortem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.postmortem.R
import app.postmortem.UiState
import app.postmortem.UiTags
import app.postmortem.core.analysis.nearestPly
import app.postmortem.core.analysis.winningChances
import app.postmortem.ui.theme.ChartPalette
import app.postmortem.ui.theme.LocalChartPalette
import app.postmortem.ui.theme.Spacing

/**
 * The evaluation over the whole game, one point per position from left to right, on the Lichess winning-chances scale.
 * Touching or dragging selects the nearest position through [onSelect]; the marker shows [UiState.Result.selectedPly].
 */
@Composable
fun EvalChart(
    state: UiState.Result,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalChartPalette.current
    val chances = state.evals.map { it.winningChances().toFloat() }
    val moves = state.game.plies.size
    val description = pluralStringResource(R.plurals.chart_description, moves, moves)
    Canvas(
        modifier
            .testTag(UiTags.EVAL_CHART)
            .semantics { contentDescription = description }
            .pointerInput(chances.size) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSelect(nearestPly(down.position.x, size.width.toFloat(), chances.size))
                    drag(down.id) { change ->
                        onSelect(nearestPly(change.position.x, size.width.toFloat(), chances.size))
                        change.consume()
                    }
                }
            }.fillMaxSize(),
    ) {
        drawChart(palette, chances, state.selectedPly)
    }
}

private fun DrawScope.drawChart(
    palette: ChartPalette,
    chances: List<Float>,
    selectedPly: Int,
) {
    val middle = size.height / 2
    drawRect(palette.background, size = size)
    if (chances.isNotEmpty()) {
        val step = if (chances.size > 1) size.width / (chances.lastIndex) else 0f
        val points = chances.mapIndexed { index, y -> Offset(index * step, middle - y * middle) }
        val area =
            Path().apply {
                moveTo(points.first().x, middle)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, middle)
                close()
            }
        clipRect(0f, 0f, size.width, middle) { drawPath(area, palette.whiteFill) }
        clipRect(0f, middle, size.width, size.height) { drawPath(area, palette.blackFill) }
        drawLine(palette.zeroLine, Offset(0f, middle), Offset(size.width, middle), Spacing.chartZeroLine.toPx())
        val line = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        drawPath(line, palette.line, style = Stroke(Spacing.chartLine.toPx()))
        val markerX = selectedPly * step
        drawLine(palette.marker, Offset(markerX, 0f), Offset(markerX, size.height), Spacing.chartMarker.toPx())
    }
}
