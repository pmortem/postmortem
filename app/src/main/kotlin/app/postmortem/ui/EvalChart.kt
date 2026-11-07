package app.postmortem.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.postmortem.R
import app.postmortem.UiState
import app.postmortem.UiTags
import app.postmortem.core.analysis.Judgement
import app.postmortem.core.analysis.format
import app.postmortem.core.analysis.nearestPly
import app.postmortem.core.analysis.winningChances
import app.postmortem.core.pgn.plyLabel
import app.postmortem.ui.theme.ChartPalette
import app.postmortem.ui.theme.JudgementColors
import app.postmortem.ui.theme.LocalChartPalette
import app.postmortem.ui.theme.Spacing
import kotlin.math.roundToInt

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
    val position = selectedPositionName(state)
    val evalText = state.selectedEval.format()
    val selection = stringResource(R.string.chart_selected, position, evalText)
    val report = state.report
    val counts =
        listOf(
            pluralStringResource(R.plurals.judged_blunders, report.count(Judgement.BLUNDER), report.count(Judgement.BLUNDER)),
            pluralStringResource(R.plurals.judged_mistakes, report.count(Judgement.MISTAKE), report.count(Judgement.MISTAKE)),
            pluralStringResource(R.plurals.judged_inaccuracies, report.count(Judgement.INACCURACY), report.count(Judgement.INACCURACY)),
        ).filterIndexed { index, _ -> report.count(Judgement.entries.reversed()[index]) > 0 }
    val brilliants = report.white.brilliants + report.black.brilliants
    val brilliantCount =
        if (brilliants >
            0
        ) {
            listOf(pluralStringResource(R.plurals.judged_brilliants, brilliants, brilliants))
        } else {
            emptyList()
        }
    val description =
        (listOf(pluralStringResource(R.plurals.chart_description, moves, moves)) + brilliantCount + counts + selection).joinToString(", ")
    Box(
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
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawChart(palette, chances, state.selectedPly, report.judgements, report.brilliant) }
        MarkerLabel(
            text =
                labelText(
                    position.takeIf { state.selectedPly > 0 },
                    evalText,
                    report.judgements.getOrNull(state.selectedPly - 1),
                    report.brilliant.getOrNull(state.selectedPly - 1) == true,
                ),
            ply = state.selectedPly,
            positions = chances.size,
            modifier = Modifier.testTag(UiTags.CHART_LABEL),
        )
    }
}

/** `4.Qxf7#  1-0`, with the judgement's marks (`?!`, `?`, `??`) or `!!` for a brilliant move after it, in their colour. At the start position [position] is null and only the eval shows. */
@Composable
private fun labelText(
    position: String?,
    evalText: String,
    judgement: Judgement?,
    brilliant: Boolean,
): AnnotatedString {
    val marks = judgement?.let { stringResource(it.marksRes()) }.orEmpty()
    val brilliantMarks = stringResource(R.string.marks_brilliant)
    return buildAnnotatedString {
        if (position == null) {
            append(evalText)
            return@buildAnnotatedString
        }
        append(position)
        if (judgement != null) withStyle(SpanStyle(color = judgement.color())) { append(marks) }
        if (brilliant) withStyle(SpanStyle(color = JudgementColors.brilliant)) { append(brilliantMarks) }
        append(stringResource(R.string.chart_label_eval, evalText))
    }
}

internal fun Judgement.color(): Color =
    when (this) {
        Judgement.INACCURACY -> JudgementColors.inaccuracy
        Judgement.MISTAKE -> JudgementColors.mistake
        Judgement.BLUNDER -> JudgementColors.blunder
    }

@StringRes
internal fun Judgement.marksRes(): Int =
    when (this) {
        Judgement.INACCURACY -> R.string.marks_inaccuracy
        Judgement.MISTAKE -> R.string.marks_mistake
        Judgement.BLUNDER -> R.string.marks_blunder
    }

/** Where the selected position is named: `21.Nf3`, or the start position. */
@Composable
private fun selectedPositionName(state: UiState.Result): String =
    if (state.selectedPly == 0) {
        stringResource(R.string.result_start_position)
    } else {
        plyLabel(state.selectedPly, state.game.plies[state.selectedPly - 1].san)
    }

/** The selected move and its eval, beside the marker at the top of the chart; it flips to the other side near an edge. */
@Composable
private fun MarkerLabel(
    text: AnnotatedString,
    ply: Int,
    positions: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.inverseOnSurface,
        maxLines = 1,
        softWrap = false,
        modifier =
            modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val chartWidth = constraints.maxWidth.toFloat()
                    val markerX = if (positions > 1) ply * chartWidth / (positions - 1) else 0f
                    val x = labelX(markerX, placeable.width.toFloat(), chartWidth, Spacing.chartLabelGap.toPx())
                    layout(constraints.maxWidth, placeable.height) { placeable.place(x.roundToInt(), 0) }
                }.padding(top = Spacing.chartLabelInset)
                .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(Spacing.chartLabelCorner))
                .padding(horizontal = Spacing.chartLabelPaddingX, vertical = Spacing.chartLabelPaddingY),
    )
}

private fun DrawScope.drawChart(
    palette: ChartPalette,
    chances: List<Float>,
    selectedPly: Int,
    judgements: List<Judgement?>,
    brilliant: List<Boolean>,
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
        // A dot on the line at the position after each judged move; the selected move's dot is larger.
        judgements.forEachIndexed { index, judgement ->
            val color = judgement?.color() ?: JudgementColors.brilliant.takeIf { brilliant.getOrNull(index) == true }
            if (color == null) return@forEachIndexed
            val ply = index + 1
            val radius = (if (ply == selectedPly) Spacing.chartDotSelected else Spacing.chartDot).toPx()
            drawCircle(palette.dotOutline, radius + Spacing.chartDotOutline.toPx(), points[ply])
            drawCircle(color, radius, points[ply])
        }
    }
}
