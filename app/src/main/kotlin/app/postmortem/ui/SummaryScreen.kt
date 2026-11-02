package app.postmortem.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.postmortem.R
import app.postmortem.UiState
import app.postmortem.UiTags
import app.postmortem.core.analysis.PlayerReport
import app.postmortem.core.opening.Opening
import app.postmortem.core.opening.OpeningBook
import app.postmortem.core.pgn.GameOverview
import app.postmortem.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LABEL_WEIGHT = 1.4f
private const val VALUE_WEIGHT = 1f

/** The game report: what the PGN tags say, then accuracy, ACPL and the judged moves for each side. */
@Composable
fun SummaryScreen(
    state: UiState.Result,
    modifier: Modifier = Modifier,
) {
    val report = state.report
    val detected = detectedOpening(state)
    // An Opening tag in the PGN always wins; otherwise the opening found from the moves fills the gap.
    val overview =
        state.overview.let {
            if (it.opening != null || detected == null) it else it.copy(opening = detected.name, eco = it.eco ?: detected.eco)
        }
    Column(
        modifier
            .testTag(UiTags.SUMMARY)
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.gap),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        // The opening and the table first: the tab is short, and they are what the Summary is for.
        openingText(overview)?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(UiTags.OPENING))
        }
        PlayerTable(report.white, report.black)
        if (!overview.copy(opening = null, eco = null).isEmpty) {
            HorizontalDivider()
            Overview(overview)
        }
        if (state.analysisSettings.isNotEmpty()) {
            Text(
                stringResource(R.string.summary_analysed_with, state.analysisSettings),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.ANALYSIS_SETTINGS),
            )
        }
    }
}

/** The opening found from the moves, once the table is loaded (null until then, and when the PGN names its opening). */
@Composable
private fun detectedOpening(state: UiState.Result): Opening? {
    val named = state.overview.opening != null
    val detected by produceState<Opening?>(null, state.game) {
        if (!named) value = withContext(Dispatchers.Default) { bundledBook.detect(state.game) }
    }
    return detected
}

/** The table is built once per process: it plays every line of it. */
private val bundledBook: OpeningBook by lazy { OpeningBook.bundled() }

@Composable
private fun Overview(overview: GameOverview) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        overview.white?.let { InfoRow(R.string.summary_white, withElo(it, overview.whiteElo)) }
        overview.black?.let { InfoRow(R.string.summary_black, withElo(it, overview.blackElo)) }
        overview.date?.let { InfoRow(R.string.summary_date, it) }
        overview.event?.let { InfoRow(R.string.summary_event, it) }
        overview.site?.let { InfoRow(R.string.summary_site, it) }
        overview.result?.let { InfoRow(R.string.summary_result, it) }
        overview.termination?.let { InfoRow(R.string.summary_termination, it) }
    }
}

@Composable
private fun withElo(
    name: String,
    elo: String?,
): String = if (elo == null) name else stringResource(R.string.summary_with_elo, name, elo)

@Composable
private fun openingText(overview: GameOverview): String? {
    val name = overview.opening
    val eco = overview.eco
    return if (name != null && eco != null) stringResource(R.string.summary_opening_with_eco, name, eco) else name ?: eco
}

@Composable
private fun InfoRow(
    @StringRes label: Int,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(LABEL_WEIGHT),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(VALUE_WEIGHT * 2))
    }
}

@Composable
private fun PlayerTable(
    white: PlayerReport,
    black: PlayerReport,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        TableRow(null, stringResource(R.string.summary_white), stringResource(R.string.summary_black), header = true)
        TableRow(R.string.summary_accuracy, percent(white.accuracy), percent(black.accuracy))
        TableRow(R.string.summary_acpl, number(white.acpl), number(black.acpl))
        TableRow(R.string.summary_inaccuracies, number(white.inaccuracies), number(black.inaccuracies))
        TableRow(R.string.summary_mistakes, number(white.mistakes), number(black.mistakes))
        TableRow(R.string.summary_blunders, number(white.blunders), number(black.blunders))
    }
}

@Composable
private fun percent(value: Double?): String =
    if (value ==
        null
    ) {
        stringResource(R.string.summary_none)
    } else {
        stringResource(R.string.summary_percent, value)
    }

@Composable
private fun number(value: Int?): String =
    if (value ==
        null
    ) {
        stringResource(R.string.summary_none)
    } else {
        stringResource(R.string.summary_number, value)
    }

@Composable
private fun TableRow(
    @StringRes label: Int?,
    white: String,
    black: String,
    header: Boolean = false,
) {
    val style = if (header) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        Text(label?.let { stringResource(it) }.orEmpty(), style = style, modifier = Modifier.weight(LABEL_WEIGHT))
        Text(white, style = style, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.weight(VALUE_WEIGHT))
        Text(black, style = style, fontWeight = weight, textAlign = TextAlign.End, modifier = Modifier.weight(VALUE_WEIGHT))
    }
}
