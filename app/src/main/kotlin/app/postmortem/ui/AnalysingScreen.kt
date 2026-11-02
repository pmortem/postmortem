package app.postmortem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.postmortem.R
import app.postmortem.UiState
import app.postmortem.UiTags
import app.postmortem.ui.theme.Spacing

@Composable
fun AnalysingScreen(state: UiState.Analysing) {
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
                    .safeDrawingPadding()
                    .padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.analysing_title), style = MaterialTheme.typography.headlineMedium)
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.analysing_progress, state.done, state.total),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag(UiTags.ANALYSING_PROGRESS),
            )
            Text(stringResource(R.string.analysing_hint), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
