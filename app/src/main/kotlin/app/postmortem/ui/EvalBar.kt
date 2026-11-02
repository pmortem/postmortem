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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * A vertical bar the height of the board: White fills from the bottom, Black from the top, split by the same winning-chances
 * scale as the chart. The number is written on the leader's side, small.
 */
@Composable
fun EvalBar(
    eval: Eval,
    modifier: Modifier = Modifier,
) {
    val palette = LocalChartPalette.current
    val chances = eval.winningChances().toFloat()
    val whiteShare by animateFloatAsState((chances + 1f) / 2f, tween(Spacing.evalBarMillis), label = "evalBar")
    val description = stringResource(R.string.result_eval_label, eval.format())
    val whiteLeads = chances >= 0f
    Box(
        modifier
            .testTag(UiTags.EVAL_BAR)
            .semantics { contentDescription = description }
            .background(palette.blackFill),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(whiteShare)
                .background(palette.whiteFill),
        )
        // The white half is the page colour in light mode, so the bar needs an edge.
        Box(Modifier.fillMaxSize().border(Spacing.evalBarBorder, palette.zeroLine))
        Box(Modifier.fillMaxSize().padding(vertical = Spacing.evalBarLabelPadding)) {
            Text(
                eval.formatShort(),
                fontSize = LABEL_SIZE,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                color = if (whiteLeads) palette.blackFill else palette.whiteFill,
                modifier = Modifier.fillMaxWidth().align(if (whiteLeads) Alignment.BottomCenter else Alignment.TopCenter),
            )
        }
    }
}
