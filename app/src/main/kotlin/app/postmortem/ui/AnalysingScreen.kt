package app.postmortem.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import app.postmortem.R
import app.postmortem.UiState
import app.postmortem.UiTags
import app.postmortem.ui.theme.Spacing

/** A ring that fills clockwise with the percentage in its centre and the positions done below it; Back stops the analysis. */
@Composable
fun AnalysingScreen(state: UiState.Analysing) {
    val fraction by animateFloatAsState(state.fraction, tween(Spacing.ringMillis), label = "analysisRing")
    val description = stringResource(R.string.analysis_progress_description, state.percent)
    Surface(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap * 2, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.analysing_title), style = MaterialTheme.typography.headlineMedium)
            Box(
                Modifier
                    .size(Spacing.ringSize)
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(Spacing.ringSize),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = Spacing.ringTrackAlpha),
                    strokeWidth = Spacing.ringStroke,
                    strokeCap = StrokeCap.Round,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.analysis_percent, state.percent),
                        style = MaterialTheme.typography.displayMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        stringResource(R.string.analysis_count, state.done, state.total),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Text(stringResource(R.string.analysing_hint), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
