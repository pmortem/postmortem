package app.postmortem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import android.content.res.Configuration
import app.postmortem.R
import app.postmortem.UiState
import app.postmortem.UiTags
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.plyLabel
import app.postmortem.ui.theme.LocalBoardPalette
import app.postmortem.ui.theme.Spacing

@Composable
fun ResultScreen(state: UiState.Result, onSelect: (Int) -> Unit, onStep: (Int) -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background,
    ) {
        val board: @Composable (Modifier) -> Unit = { modifier ->
            val description = stringResource(R.string.result_board_description, positionName(state))
            BoardView(
                fen = state.game.fenAt(state.selectedPly),
                modifier = modifier
                    .testTag(UiTags.RESULT_BOARD)
                    .semantics { contentDescription = description },
            )
        }
        val controls: @Composable () -> Unit = { StepButtons(state, onStep) }
        val moves: @Composable (Modifier) -> Unit = { modifier -> MoveList(state, onSelect, modifier) }

        if (landscape) {
            Row(
                Modifier.safeDrawingPadding().padding(Spacing.screen),
                horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                Column(Modifier.fillMaxHeight().weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.gap)) {
                    board(Modifier.fillMaxWidth())
                    controls()
                }
                moves(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(
                Modifier.safeDrawingPadding().padding(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                board(Modifier.fillMaxWidth())
                controls()
                moves(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun positionName(state: UiState.Result): String =
    if (state.selectedPly == 0) stringResource(R.string.result_start_position)
    else state.game.plies[state.selectedPly - 1].let { plyLabel(state.selectedPly, it.san) }

@Composable
private fun StepButtons(state: UiState.Result, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
        OutlinedButton(
            onClick = { onStep(-1) },
            enabled = state.canStepBack,
            modifier = Modifier.weight(1f).testTag(UiTags.PREVIOUS_BUTTON),
        ) { Text(stringResource(R.string.result_previous)) }
        OutlinedButton(
            onClick = { onStep(1) },
            enabled = state.canStepForward,
            modifier = Modifier.weight(1f).testTag(UiTags.NEXT_BUTTON),
        ) { Text(stringResource(R.string.result_next)) }
    }
}

/** One row per full move: the number, White's ply and Black's ply. */
@Composable
private fun MoveList(state: UiState.Result, onSelect: (Int) -> Unit, modifier: Modifier) {
    val game: Game = state.game
    val rows = (game.plies.size + 1) / 2
    val listState = rememberLazyListState()
    LaunchedEffect(state.selectedPly) {
        if (state.selectedPly > 0) listState.animateScrollToItem(((state.selectedPly - 1) / 2).coerceAtMost(rows - 1))
    }
    LazyColumn(modifier.testTag(UiTags.MOVE_LIST), state = listState) {
        items((0 until rows).toList()) { row ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "${row + 1}.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(Spacing.moveNumberWidth).padding(Spacing.moveCell),
                )
                for (ply in listOf(row * 2 + 1, row * 2 + 2)) {
                    MoveCell(
                        text = game.plies.getOrNull(ply - 1)?.san,
                        ply = ply,
                        selected = ply == state.selectedPly,
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoveCell(text: String?, ply: Int, selected: Boolean, onSelect: (Int) -> Unit, modifier: Modifier) {
    val palette = LocalBoardPalette.current
    if (text == null) {
        Box(modifier)
        return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (selected) palette.onSelectedMove else MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .testTag(UiTags.move(ply))
            .background(if (selected) palette.selectedMove else Color.Transparent)
            .clickable { onSelect(ply) }
            .padding(Spacing.moveCell),
    )
}
