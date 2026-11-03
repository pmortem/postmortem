package app.postmortem.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.postmortem.R
import app.postmortem.ResultTab
import app.postmortem.UiState
import app.postmortem.UiTags
import app.postmortem.core.analysis.LiveUpdate
import app.postmortem.core.analysis.format
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.plyLabel
import app.postmortem.ui.theme.LocalBoardPalette
import app.postmortem.ui.theme.Spacing

@Composable
fun ResultScreen(
    state: UiState.Result,
    onSelect: (Int) -> Unit,
    onStep: (Int) -> Unit,
    onTab: (ResultTab) -> Unit,
    onFlip: () -> Unit,
    live: LiveUpdate? = null,
    engineOn: Boolean = true,
    onToggleEngine: () -> Unit = {},
) {
    var showReadout by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.safeDrawingPadding().padding(Spacing.screen),
            verticalArrangement = Arrangement.spacedBy(Spacing.gap),
        ) {
            BoardWithEvalBar(state, arrowsOf(live?.lines.orEmpty(), state.flipped))
            if (showReadout && engineOn) EngineReadout(live)
            StepButtons(state, onStep, onFlip, engineOn, onToggleEngine, onToggleReadout = { showReadout = !showReadout })
            ResultTabs(state.tab, onTab)
            when (state.tab) {
                ResultTab.Moves -> MoveList(state, onSelect, Modifier.weight(1f).fillMaxWidth())
                ResultTab.Chart -> EvalChart(state, onSelect, Modifier.weight(1f).fillMaxWidth())
                ResultTab.Summary -> SummaryScreen(state, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The board at the full width, with the eval bar right under it. */
@Composable
private fun BoardWithEvalBar(
    state: UiState.Result,
    arrows: List<ArrowSpec>,
) {
    Column {
        val description = stringResource(R.string.result_board_description, positionName(state))
        BoardView(
            fen = state.game.fenAt(state.selectedPly),
            flipped = state.flipped,
            arrows = arrows,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.RESULT_BOARD)
                    .semantics { contentDescription = description },
        )
        // The side at the bottom of the board fills from the left.
        EvalBar(
            state.selectedEval,
            Modifier.fillMaxWidth().height(Spacing.evalBarHeight),
            whiteOnLeft = !state.flipped,
            endOfGame = state.atEndOfGame,
        )
    }
}

private fun ResultTab.label(): Int =
    when (this) {
        ResultTab.Moves -> R.string.tab_moves
        ResultTab.Chart -> R.string.tab_chart
        ResultTab.Summary -> R.string.tab_summary
    }

private fun ResultTab.tag(): String =
    when (this) {
        ResultTab.Moves -> UiTags.TAB_MOVES
        ResultTab.Chart -> UiTags.TAB_CHART
        ResultTab.Summary -> UiTags.TAB_SUMMARY
    }

@Composable
private fun ResultTabs(
    selected: ResultTab,
    onTab: (ResultTab) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = selected.ordinal, containerColor = Color.Transparent) {
        ResultTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onTab(tab) },
                text = { Text(stringResource(tab.label()), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false) },
                modifier = Modifier.testTag(tab.tag()),
            )
        }
    }
}

@Composable
private fun positionName(state: UiState.Result): String =
    if (state.selectedPly == 0) {
        stringResource(R.string.result_start_position)
    } else {
        state.game.plies[state.selectedPly - 1].let { plyLabel(state.selectedPly, it.san) }
    }

/** Depth and speed of the live search, small, under the board: a help for judging heat and battery. */
@Composable
private fun EngineReadout(live: LiveUpdate?) {
    val text =
        live?.let {
            stringResource(
                R.string.engine_readout,
                it.depth,
                it.nodesPerSecond?.div(NODES_PER_KILONODE) ?: 0L,
                stringResource(if (it.finished) R.string.engine_readout_done else R.string.engine_readout_searching),
            )
        } ?: stringResource(R.string.engine_readout_idle)
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.testTag(UiTags.ENGINE_READOUT),
    )
}

private const val NODES_PER_KILONODE = 1000L

@Composable
private fun StepButtons(
    state: UiState.Result,
    onStep: (Int) -> Unit,
    onFlip: () -> Unit,
    engineOn: Boolean,
    onToggleEngine: () -> Unit,
    onToggleReadout: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onStep(-1) },
            enabled = state.canStepBack,
            modifier = Modifier.testTag(UiTags.PREVIOUS_BUTTON),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.result_previous)) }
        // The flip button and the engine toggle sit together in the middle, the toggle to the right of the flip button.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onFlip, modifier = Modifier.testTag(UiTags.FLIP_BUTTON)) {
                Icon(painterResource(R.drawable.ic_flip_board), stringResource(R.string.result_flip))
            }
            // A tap turns the arrows on and off; a long press shows or hides the readout of the live search.
            Box(
                Modifier
                    .size(Spacing.touchTarget)
                    .clip(CircleShape)
                    .combinedClickable(role = Role.Button, onClick = onToggleEngine, onLongClick = onToggleReadout)
                    .testTag(UiTags.ENGINE_TOGGLE),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_engine_arrows),
                    stringResource(R.string.result_engine_toggle),
                    modifier = Modifier.size(Spacing.engineToggleIcon),
                    tint =
                        if (engineOn) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = Spacing.engineOffAlpha)
                        },
                )
            }
        }
        IconButton(
            onClick = { onStep(1) },
            enabled = state.canStepForward,
            modifier = Modifier.testTag(UiTags.NEXT_BUTTON),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.result_next)) }
    }
}

/** One row per full move: the number, White's ply and Black's ply. */
@Composable
private fun MoveList(
    state: UiState.Result,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
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
                        eval =
                            state.evals
                                .getOrNull(ply)
                                ?.format()
                                .orEmpty(),
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
private fun MoveCell(
    text: String?,
    eval: String,
    ply: Int,
    selected: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val palette = LocalBoardPalette.current
    if (text == null) {
        Box(modifier)
        return
    }
    val color = if (selected) palette.onSelectedMove else MaterialTheme.colorScheme.onBackground
    Row(
        modifier =
            modifier
                .testTag(UiTags.move(ply))
                .background(if (selected) palette.selectedMove else Color.Transparent)
                .clickable { onSelect(ply) }
                .padding(Spacing.moveCell),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
        Text(eval, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
