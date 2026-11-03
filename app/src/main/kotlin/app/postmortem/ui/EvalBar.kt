package app.postmortem.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.postmortem.R
import app.postmortem.UiTags
import app.postmortem.core.analysis.Eval
import app.postmortem.core.analysis.format
import app.postmortem.core.analysis.formatShort
import app.postmortem.core.analysis.winningChances
import app.postmortem.ui.theme.LocalChartPalette
import app.postmortem.ui.theme.Spacing

private val LABEL_SIZE: TextUnit = 10.sp

/**
 * A horizontal bar under the board: White fills from the left, Black from the right (swapped when [whiteOnLeft] is false, for a
 * flipped board), split by the same winning-chances scale as the chart. The number is written on the leader's side, small; after
 * the final move ([endOfGame]) it says RIP there instead, and the fill still shows who was winning.
 */
@Composable
fun EvalBar(
    eval: Eval,
    modifier: Modifier = Modifier,
    whiteOnLeft: Boolean = true,
    endOfGame: Boolean = false,
) {
    val palette = LocalChartPalette.current
    val chances = eval.winningChances().toFloat()
    val whiteShare by animateFloatAsState((chances + 1f) / 2f, tween(Spacing.evalBarMillis), label = "evalBar")
    val description = stringResource(if (endOfGame) R.string.result_eval_label_end else R.string.result_eval_label, eval.format())
    val whiteLeads = chances >= 0f
    val whiteSide = if (whiteOnLeft) Alignment.CenterStart else Alignment.CenterEnd
    val leaderSide = if (whiteLeads == whiteOnLeft) Alignment.CenterStart else Alignment.CenterEnd
    Box(
        modifier
            .testTag(UiTags.EVAL_BAR)
            .semantics { contentDescription = description }
            .background(palette.blackFill),
    ) {
        Box(
            Modifier
                .align(whiteSide)
                .fillMaxHeight()
                .fillMaxWidth(whiteShare)
                .background(palette.whiteFill),
        )
        // A faint tick in the middle: an equal position.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .width(Spacing.evalBarTick)
                .background(palette.zeroLine.copy(alpha = Spacing.evalBarTickAlpha)),
        )
        // The white half is the page colour in light mode, so the bar needs an edge.
        Box(Modifier.fillMaxSize().border(Spacing.evalBarBorder, palette.zeroLine))
        Text(
            if (endOfGame) stringResource(R.string.eval_bar_end) else eval.formatShort(),
            // The line height equals the font size: the default one is taller than the bar and pushed the number down.
            style = TextStyle(fontSize = LABEL_SIZE, lineHeight = LABEL_SIZE, fontWeight = if (endOfGame) FontWeight.Bold else null),
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            color = if (whiteLeads) palette.blackFill else palette.whiteFill,
            modifier = Modifier.align(leaderSide).padding(horizontal = Spacing.evalBarLabelPadding),
        )
    }
}
