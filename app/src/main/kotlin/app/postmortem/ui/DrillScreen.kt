package app.postmortem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.postmortem.DrillController
import app.postmortem.DrillPhase
import app.postmortem.DrillUi
import app.postmortem.R
import app.postmortem.UiTags
import app.postmortem.core.board.BoardLayout
import app.postmortem.core.board.Moves
import app.postmortem.core.report.formatShortFor
import app.postmortem.ui.theme.Spacing

/** "Learn from your mistakes": the board from the player's side, the question, and the buttons that fit the moment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrillScreen(
    drill: DrillController,
    onOpenGame: () -> Unit,
) {
    val ui = drill.ui
    PageScaffold(title = stringResource(R.string.drill_title), onBack = drill::close) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.screen)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            if (ui.phase == DrillPhase.DONE) {
                Done(drill, ui)
            } else {
                Question(drill, ui, onOpenGame)
            }
        }
    }
}

@Composable
private fun Done(
    drill: DrillController,
    ui: DrillUi,
) {
    Text(
        stringResource(R.string.drill_done, ui.solved, ui.failed),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Spacing.gap).testTag(UiTags.DRILL_RESULT),
    )
    if (ui.failed > 0) {
        Button(onClick = drill::startOver, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.drill_start_over))
        }
    }
}

@Composable
private fun Question(
    drill: DrillController,
    ui: DrillUi,
    onOpenGame: () -> Unit,
) {
    val item = ui.item ?: return
    val color = item.game.color
    Text(
        stringResource(R.string.drill_progress, ui.position + 1, ui.total),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (ui.fen.isNotEmpty() && ui.phase != DrillPhase.LOADING) {
        val arrows =
            ui.best
                ?.let { best ->
                    listOf(ArrowSpec(BoardLayout.arrow(best, ui.flipped), 1, item.before.formatShortFor(color)))
                }.orEmpty()
        val input =
            if (ui.phase == DrillPhase.ASKING) {
                BoardInput(
                    selected = drill.selected,
                    targets = drill.targets,
                    promotion =
                        drill.promotion?.let {
                            PromotionPrompt(it.to, Moves.sideToMove(ui.fen), drill::choosePromotion, drill::cancelPromotion)
                        },
                    onTap = drill::tapSquare,
                    onDrag = drill::dragMove,
                )
            } else {
                null
            }
        BoardView(
            fen = ui.fen,
            flipped = ui.flipped,
            arrows = arrows,
            lastMove = ui.lastMove,
            input = input,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.DRILL_BOARD),
        )
    }
    Message(drill, ui)
    Buttons(drill, ui, onOpenGame)
}

@Composable
private fun Message(
    drill: DrillController,
    ui: DrillUi,
) {
    val item = ui.item ?: return
    val color = item.game.color
    val checking = stringResource(R.string.drill_checking)
    val text =
        when (ui.phase) {
            DrillPhase.LOADING, DrillPhase.CHECKING -> {
                null
            }

            DrillPhase.ASKING -> {
                if (ui.engineFailed) {
                    stringResource(R.string.drill_engine_failed)
                } else {
                    stringResource(
                        R.string.drill_question,
                        item.game.opponent ?: stringResource(R.string.summary_none),
                        "${(item.ply + 1) / 2}",
                        item.san,
                        item.before.formatShortFor(color),
                        item.after.formatShortFor(color),
                    )
                }
            }

            DrillPhase.RIGHT -> {
                stringResource(R.string.drill_right)
            }

            DrillPhase.GOOD -> {
                stringResource(R.string.drill_good)
            }

            DrillPhase.WRONG -> {
                stringResource(R.string.drill_wrong)
            }

            DrillPhase.SHOWN -> {
                stringResource(R.string.drill_shown, ui.bestSan ?: ui.best.orEmpty(), item.before.formatShortFor(color))
            }

            DrillPhase.DONE -> {
                null
            }
        }
    if (text == null) {
        CircularProgressIndicator(Modifier.size(Spacing.pendingSpinner * 2).semantics { contentDescription = checking })
    } else {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(UiTags.DRILL_RESULT))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buttons(
    drill: DrillController,
    ui: DrillUi,
    onOpenGame: () -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        when (ui.phase) {
            DrillPhase.ASKING -> {
                OutlinedButton(
                    onClick = drill::skip,
                ) { Text(stringResource(R.string.drill_skip)) }
                OutlinedButton(
                    onClick = onOpenGame,
                ) { Text(stringResource(R.string.drill_open)) }
            }

            DrillPhase.WRONG -> {
                Button(
                    onClick = drill::tryAgain,
                ) { Text(stringResource(R.string.drill_retry)) }
                OutlinedButton(
                    onClick = drill::show,
                    modifier = Modifier.testTag(UiTags.DRILL_SHOW),
                ) { Text(stringResource(R.string.drill_show)) }
                OutlinedButton(
                    onClick = drill::skip,
                ) { Text(stringResource(R.string.drill_skip)) }
            }

            DrillPhase.RIGHT, DrillPhase.GOOD, DrillPhase.SHOWN -> {
                Button(onClick = drill::next, modifier = Modifier.testTag(UiTags.DRILL_NEXT)) { Text(stringResource(R.string.drill_next)) }
                OutlinedButton(
                    onClick = onOpenGame,
                ) { Text(stringResource(R.string.drill_open)) }
            }

            else -> {
                Unit
            }
        }
    }
}
