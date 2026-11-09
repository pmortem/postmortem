package app.postmortem.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.postmortem.R
import app.postmortem.UiTags
import app.postmortem.core.analysis.Judgement
import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.plyLabel
import app.postmortem.core.report.MissedMate
import app.postmortem.core.report.MissedReport
import app.postmortem.core.report.MissedWin
import app.postmortem.core.report.Outcome
import app.postmortem.core.report.PlayerGame
import app.postmortem.core.report.formatShortFor
import app.postmortem.ui.theme.Spacing
import kotlin.math.roundToInt

private const val PERCENT = 100.0

/**
 * Missed wins and missed mates of the player: how many of their decided games they were winning and did not win, and the list of
 * them, newest first. A tap opens the game at the move where it went wrong.
 */
@Composable
fun MissedCard(
    report: MissedReport,
    onOpenGame: (Long, Int) -> Unit,
) {
    val share = if (report.decided == 0) 0 else (PERCENT * report.wins.size / report.decided).roundToInt()
    Column(Modifier.fillMaxWidth().padding(top = Spacing.gap).testTag(UiTags.REPORT_MISSED)) {
        Text(
            stringResource(R.string.report_missed_title, report.wins.size, report.decided, share),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(stringResource(R.string.report_missed_mates, report.mates.size), style = MaterialTheme.typography.titleMedium)
        HorizontalDivider(Modifier.padding(vertical = Spacing.small))
        if (report.wins.isEmpty() && report.mates.isEmpty()) {
            Text(stringResource(R.string.report_missed_none), style = MaterialTheme.typography.bodyMedium)
        }
        report.wins.forEachIndexed { index, win ->
            MissedRow(win.game, UiTags.reportMissedWin(index), { onOpenGame(win.game.gameId, win.turningPly) }) { WinDetail(win) }
        }
        report.mates.forEachIndexed { index, mate ->
            MissedRow(mate.game, UiTags.reportMissedMate(index), { onOpenGame(mate.game.gameId, mate.ply) }) { MateDetail(mate) }
        }
    }
}

@Composable
private fun MissedRow(
    game: PlayerGame,
    tag: String,
    onClick: () -> Unit,
    detail: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.small),
    ) {
        val opponent = game.opponent?.let { stringResource(R.string.report_vs, it) } ?: stringResource(R.string.report_vs_unknown)
        val colour = stringResource(if (game.color == PieceColor.WHITE) R.string.summary_white else R.string.summary_black)
        Text(
            stringResource(R.string.report_row_header, opponent, game.date ?: stringResource(R.string.summary_none), colour),
            style = MaterialTheme.typography.bodyLarge,
        )
        detail()
    }
}

@Composable
private fun WinDetail(win: MissedWin) {
    val color = win.game.color
    Text(
        stringResource(R.string.report_missed_row, win.peak.formatShortFor(color), moveText(win.turningPly, win.san, win.judgement)) +
            stringResource(R.string.report_missed_after, win.after.formatShortFor(color), outcomeText(win.game.outcome)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MateDetail(mate: MissedMate) {
    Text(
        stringResource(R.string.report_mate_row, mate.mateIn, moveText(mate.ply, mate.san, mate.judgement)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** `31.Qxb7??`: the move with its number and its mark. */
@Composable
private fun moveText(
    ply: Int,
    san: String,
    judgement: Judgement?,
): String = plyLabel(ply, san) + (judgement?.let { stringResource(it.marksRes()) } ?: "")
