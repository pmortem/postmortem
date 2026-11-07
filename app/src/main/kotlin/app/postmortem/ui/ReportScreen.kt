package app.postmortem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import app.postmortem.FamilyKey
import app.postmortem.PlayerView
import app.postmortem.R
import app.postmortem.ReportController
import app.postmortem.UiTags
import app.postmortem.VariationKey
import app.postmortem.core.board.PieceColor
import app.postmortem.core.report.OpeningFamily
import app.postmortem.core.report.OpeningVariation
import app.postmortem.core.report.Outcome
import app.postmortem.core.report.PlayerGame
import app.postmortem.core.report.Record
import app.postmortem.ui.theme.ReportColors
import app.postmortem.ui.theme.Spacing
import kotlin.math.roundToInt

private const val MAX_SUGGESTIONS = 5

/**
 * The Report tab: pick a player, then see how they do with each opening as White and as Black. A tap on an opening with several
 * variations expands it, a tap on a variation lists its games, and a tap on a game opens it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    report: ReportController,
    onOpenGame: (Long) -> Unit,
) {
    val listed = report.listed
    val view = report.ui.view
    Scaffold(
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(if (listed != null) openingName(listed.name) else stringResource(R.string.report_title)) },
                navigationIcon = {
                    if (listed != null) {
                        IconButton(onClick = { report.closeList() }, modifier = Modifier.testTag(UiTags.REPORT_BACK)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.report_back))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (listed != null && view != null) {
                GameList(view, listed, onOpenGame)
            } else {
                ReportBody(report)
            }
        }
    }
}

@Composable
private fun openingName(name: String?): String = name ?: stringResource(R.string.report_unknown_opening)

@Composable
private fun ReportBody(report: ReportController) {
    val ui = report.ui
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(Spacing.screen),
    ) {
        item { PlayerPicker(report) }
        item {
            val view = ui.view
            when {
                !ui.loaded -> Unit
                ui.games == 0 -> Message(R.string.report_empty_db, UiTags.REPORT_EMPTY)
                ui.names.isEmpty() -> Message(R.string.report_no_names)
                ui.player.isBlank() -> Message(R.string.report_pick_hint)
                view == null -> Message(R.string.report_no_games)
                else -> Overview(view)
            }
        }
        ui.view?.let { view ->
            item { Section(R.string.report_as_white, UiTags.REPORT_WHITE, PieceColor.WHITE, view.openings.white, 0, report) }
            item {
                Section(
                    R.string.report_as_black,
                    UiTags.REPORT_BLACK,
                    PieceColor.BLACK,
                    view.openings.black,
                    rowsOf(view.openings.white),
                    report,
                )
            }
        }
    }
}

/** The most rows a section can show, so the rows of the Black section go on counting after the White ones for the test tags. */
private fun rowsOf(families: List<OpeningFamily>): Int = families.size + families.sumOf { it.variations.size }

@Composable
private fun Message(
    text: Int,
    tag: String? = null,
) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(vertical = Spacing.gap).let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
private fun PlayerPicker(report: ReportController) {
    val ui = report.ui
    val typed = ui.player.trim()
    val exact = ui.names.any { it.name.equals(typed, ignoreCase = true) }
    val suggestions =
        if (exact) {
            emptyList()
        } else {
            ui.names
                .filter {
                    typed.isEmpty() || it.name.contains(typed, ignoreCase = true)
                }.take(MAX_SUGGESTIONS)
        }
    Column {
        OutlinedTextField(
            value = ui.player,
            onValueChange = report::setPlayer,
            label = { Text(stringResource(R.string.report_player_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.REPORT_PLAYER),
        )
        suggestions.forEachIndexed { index, name ->
            Text(
                stringResource(R.string.report_suggestion, name.name, name.games),
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.reportSuggestion(index))
                        .clickable { report.setPlayer(name.name) }
                        .padding(vertical = Spacing.gap, horizontal = Spacing.small),
            )
        }
    }
}

@Composable
private fun Overview(view: PlayerView) {
    val record = view.record
    val text =
        stringResource(
            R.string.report_overview,
            pluralStringResource(R.plurals.report_games, record.games, record.games),
            record.wins,
            record.draws,
            record.losses,
        ) + (record.accuracy?.let { stringResource(R.string.report_overview_accuracy, it.roundToInt()) } ?: "")
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = Spacing.gap).testTag(UiTags.REPORT_OVERVIEW),
    )
}

@Composable
private fun Section(
    title: Int,
    tag: String,
    color: PieceColor,
    families: List<OpeningFamily>,
    firstRow: Int,
    report: ReportController,
) {
    Column(Modifier.fillMaxWidth().padding(top = Spacing.gap).testTag(tag)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        HorizontalDivider(Modifier.padding(vertical = Spacing.small))
        if (families.isEmpty()) {
            Text(stringResource(R.string.report_no_games), style = MaterialTheme.typography.bodyMedium)
        }
        var row = firstRow
        for (family in families) {
            val key = FamilyKey(color, family.name)
            val single = family.variations.singleOrNull()
            OpeningRow(
                name = openingName(family.name),
                record = family.record,
                indent = false,
                tag = UiTags.openingRow(row++),
                onClick = { if (single != null) report.list(VariationKey(color, single.name)) else report.toggleFamily(key) },
            )
            if (single == null && key in report.expanded) {
                for (variation in family.variations) {
                    OpeningRow(
                        name = openingName(variation.name),
                        record = variation.record,
                        indent = true,
                        tag = UiTags.openingRow(row++),
                        onClick = { report.list(VariationKey(color, variation.name)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OpeningRow(
    name: String,
    record: Record,
    indent: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(start = if (indent) Spacing.lineIndent else 0.dp, top = Spacing.small, bottom = Spacing.small),
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        Text(statsLine(record), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (record.decided > 0) ResultBar(record)
    }
}

@Composable
private fun statsLine(record: Record): String {
    val separator = stringResource(R.string.report_separator)
    val parts =
        listOfNotNull(
            pluralStringResource(R.plurals.report_games, record.games, record.games),
            if (record.decided > 0) {
                stringResource(
                    R.string.report_wdl,
                    (record.winPercent ?: 0.0).roundToInt(),
                    (record.drawPercent ?: 0.0).roundToInt(),
                    (record.lossPercent ?: 0.0).roundToInt(),
                )
            } else {
                stringResource(R.string.report_not_finished)
            },
            record.accuracy?.let { stringResource(R.string.report_accuracy, it.roundToInt()) },
        )
    return parts.joinToString(separator)
}

/** Wins, draws and losses side by side, as wide as their share of the finished games. */
@Composable
private fun ResultBar(record: Record) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.small)
            .height(Spacing.reportBar)
            .clip(MaterialTheme.shapes.extraSmall),
    ) {
        listOf(record.wins to ReportColors.win, record.draws to ReportColors.draw, record.losses to ReportColors.loss)
            .filter { (count, _) -> count > 0 }
            .forEach { (count, color) -> Box(Modifier.weight(count.toFloat()).fillMaxSize().background(color)) }
    }
}

@Composable
private fun GameList(
    view: PlayerView,
    key: VariationKey,
    onOpenGame: (Long) -> Unit,
) {
    val families = if (key.color == PieceColor.WHITE) view.openings.white else view.openings.black
    val games: List<PlayerGame> =
        families
            .flatMap { it.variations }
            .firstOrNull { it.name == key.name }
            ?.games
            .orEmpty()
    LazyColumn(Modifier.fillMaxSize()) {
        items(games, key = { it.gameId }) { game ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.reportGame(game.gameId))
                    .clickable { onOpenGame(game.gameId) }
                    .padding(horizontal = Spacing.screen, vertical = Spacing.gap),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        game.opponent?.let {
                            stringResource(R.string.report_vs, it)
                        } ?: stringResource(R.string.report_vs_unknown),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(outcomeText(game.outcome), style = MaterialTheme.typography.bodyLarge, color = outcomeColor(game.outcome))
                }
                Text(
                    listOfNotNull(game.date, game.accuracy?.let { stringResource(R.string.report_accuracy, it.roundToInt()) })
                        .joinToString(stringResource(R.string.report_separator)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun outcomeText(outcome: Outcome): String =
    stringResource(
        when (outcome) {
            Outcome.WIN -> R.string.report_win
            Outcome.DRAW -> R.string.report_draw
            Outcome.LOSS -> R.string.report_loss
            Outcome.UNFINISHED -> R.string.report_unfinished
        },
    )

@Composable
private fun outcomeColor(outcome: Outcome) =
    when (outcome) {
        Outcome.WIN -> ReportColors.win
        Outcome.LOSS -> ReportColors.loss
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
