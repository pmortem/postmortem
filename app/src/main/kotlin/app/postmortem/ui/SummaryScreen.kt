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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.postmortem.R
import app.postmortem.UiState
import app.postmortem.UiTags
import app.postmortem.core.analysis.PlayerReport
import app.postmortem.core.pgn.GameOverview
import app.postmortem.ui.theme.Spacing
import kotlin.math.roundToInt

private const val LABEL_WEIGHT = 1.4f
private const val VALUE_WEIGHT = 1f

/** The game report: what the PGN tags say, then accuracy, ACPL and the judged moves for each side. */
@Composable
fun SummaryScreen(
    state: UiState.Result,
    modifier: Modifier = Modifier,
) {
    val overview = state.overview
    val report = state.report
    Column(
        modifier
            .testTag(UiTags.SUMMARY)
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.gap),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        if (!overview.isEmpty) {
            Overview(overview)
            HorizontalDivider()
        }
        PlayerTable(report.white, report.black)
    }
}

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
        openingText(overview)?.let { InfoRow(R.string.summary_opening, it) }
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
        TableRow(R.string.summary_acpl, number(white.acpl?.roundToInt()), number(black.acpl?.roundToInt()))
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
