package app.postmortem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.postmortem.BuildConfig
import app.postmortem.R
import app.postmortem.UiState
import app.postmortem.UiTags
import app.postmortem.core.pgn.PgnError
import app.postmortem.core.pgn.plyLabel
import app.postmortem.ui.theme.Spacing

@Composable
fun PasteScreen(
    state: UiState.Paste,
    onPgnChange: (String) -> Unit,
    onAnalyse: () -> Unit,
) {
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
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            Text(stringResource(R.string.paste_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.paste_hint), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = state.pgn,
                onValueChange = onPgnChange,
                label = { Text(stringResource(R.string.paste_label)) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(UiTags.PASTE_INPUT),
            )
            StatusLine(state.error, state.engineFailed)
            Button(
                onClick = onAnalyse,
                enabled = state.canAnalyse,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.ANALYSE_BUTTON),
            ) { Text(stringResource(R.string.analyse_button)) }
            Text(
                stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.VERSION_LABEL),
            )
        }
    }
}

@Composable
private fun StatusLine(
    error: PgnError?,
    engineFailed: Boolean,
) {
    val message =
        when {
            error != null -> errorText(error)
            engineFailed -> stringResource(R.string.paste_engine_failed)
            else -> return
        }
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.testTag(UiTags.PASTE_STATUS),
    )
}

@Composable
private fun errorText(error: PgnError): String =
    when (error) {
        PgnError.Empty -> stringResource(R.string.paste_empty)
        PgnError.NoMoves -> stringResource(R.string.paste_no_moves)
        is PgnError.BadFen -> stringResource(R.string.paste_bad_fen, error.fen)
        is PgnError.UnsupportedVariant -> stringResource(R.string.paste_variant, error.variant)
        is PgnError.IllegalMove -> stringResource(R.string.paste_illegal_move, plyLabel(error.ply, error.san), error.ply)
    }
