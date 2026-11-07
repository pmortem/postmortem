package app.postmortem.ui

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.postmortem.DeletedLine
import app.postmortem.R
import app.postmortem.ResultTab
import app.postmortem.UiState
import app.postmortem.UiTags
import app.postmortem.core.analysis.LiveUpdate
import app.postmortem.core.analysis.format
import app.postmortem.core.analysis.formatShort
import app.postmortem.core.line.Line
import app.postmortem.core.line.moveLabels
import app.postmortem.core.pgn.Game
import app.postmortem.core.pgn.plyLabel
import app.postmortem.ui.theme.JudgementColors
import app.postmortem.ui.theme.LocalBoardPalette
import app.postmortem.ui.theme.Spacing

/** What the Moves tab does with the lines the user played: select and delete (with Undo). */
data class LineActions(
    val deleted: DeletedLine? = null,
    val onSelectMove: (Int, Int) -> Unit = { _, _ -> },
    val onSelectLine: (Int) -> Unit = {},
    val onDelete: (Int) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onUndoExpired: () -> Unit = {},
)

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
    onSettings: () -> Unit = {},
    lineActions: LineActions = LineActions(),
    boardInput: BoardInput? = null,
    openedFromLibrary: Boolean = false,
    onNoticeShown: () -> Unit = {},
) {
    var showReadout by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true },
        color = MaterialTheme.colorScheme.background,
    ) {
        val snackbars = remember { SnackbarHostState() }
        val deletedMessage = stringResource(R.string.line_deleted)
        val undoLabel = stringResource(R.string.line_undo)
        val openedMessage = stringResource(R.string.opened_from_library)
        LaunchedEffect(openedFromLibrary) {
            if (openedFromLibrary) showOpenedNotice(snackbars, openedMessage, onNoticeShown)
        }
        LaunchedEffect(lineActions.deleted) {
            if (lineActions.deleted == null) return@LaunchedEffect
            when (snackbars.showSnackbar(deletedMessage, undoLabel, duration = SnackbarDuration.Short)) {
                SnackbarResult.ActionPerformed -> lineActions.onUndo()
                SnackbarResult.Dismissed -> lineActions.onUndoExpired()
            }
        }
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(Spacing.screen),
                verticalArrangement = Arrangement.spacedBy(Spacing.gap),
            ) {
                BoardWithEvalBar(state, arrowsOf(live?.lines.orEmpty(), state.flipped), boardInput)
                if (showReadout && engineOn) EngineReadout(live)
                StepButtons(state, onStep, onFlip, onSettings, engineOn, onToggleEngine, onToggleReadout = { showReadout = !showReadout })
                ResultTabs(state.tab, onTab)
                when (state.tab) {
                    ResultTab.Moves -> MoveList(state, onSelect, lineActions, Modifier.weight(1f).fillMaxWidth())
                    ResultTab.Chart -> EvalChart(state, onSelect, Modifier.weight(1f).fillMaxWidth())
                    ResultTab.Summary -> SummaryScreen(state, Modifier.weight(1f).fillMaxWidth())
                }
            }
            SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).safeDrawingPadding()) { data ->
                Snackbar(
                    action = {
                        TextButton(onClick = { data.performAction() }, modifier = Modifier.testTag(UiTags.UNDO)) {
                            Text(data.visuals.actionLabel.orEmpty())
                        }
                    },
                ) { Text(data.visuals.message) }
            }
        }
    }
}

/**
 * Shows [message] and only then calls [onShown] (which clears the flag the effect is keyed on): clearing it first restarts the
 * effect and cancels the bar before anyone sees it.
 */
internal suspend fun showOpenedNotice(
    snackbars: SnackbarHostState,
    message: String,
    onShown: () -> Unit,
) {
    snackbars.showSnackbar(message, duration = SnackbarDuration.Short)
    onShown()
}

/** The board at the full width, with the eval bar right under it. */
@Composable
private fun BoardWithEvalBar(
    state: UiState.Result,
    arrows: List<ArrowSpec>,
    boardInput: BoardInput?,
) {
    Column {
        val description = stringResource(R.string.result_board_description, positionName(state))
        BoardView(
            fen = state.fen,
            flipped = state.flipped,
            arrows = arrows,
            lastMove = state.lastMove,
            input = boardInput,
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
private fun positionName(state: UiState.Result): String {
    val line = state.cursor.lineId?.let { state.lines.find(it) }
    return when {
        line != null -> plyLabel(line.branchPly + state.cursor.ply, line.plies[state.cursor.ply - 1].san)
        state.cursor.ply == 0 -> stringResource(R.string.result_start_position)
        else -> plyLabel(state.cursor.ply, state.game.plies[state.cursor.ply - 1].san)
    }
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
    onSettings: () -> Unit,
    engineOn: Boolean,
    onToggleEngine: () -> Unit,
    onToggleReadout: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        StepArrow(state.canStepBack, UiTags.PREVIOUS_BUTTON, { onStep(-1) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.result_previous), tint = it)
        }
        // The settings cog, the flip button and the engine toggle sit together in the middle, the cog left of the flip button.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSettings, modifier = Modifier.testTag(UiTags.SETTINGS_BUTTON)) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.result_settings))
            }
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
        StepArrow(state.canStepForward, UiTags.NEXT_BUTTON, { onStep(1) }) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.result_next), tint = it)
        }
    }
}

/** An arrow button: a tap steps once, holding it keeps stepping (see [repeatingClick]); it dims when there is nothing to step to. */
@Composable
private fun StepArrow(
    enabled: Boolean,
    tag: String,
    onStep: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Box(
        Modifier
            .size(Spacing.touchTarget)
            .clip(CircleShape)
            .repeatingClick(enabled, onStep = onStep)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        icon(MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else Spacing.DISABLED_ALPHA))
    }
}

/** What the Moves tab does while stepping: a hold steps faster than a scroll can animate, so the list jumps instead. */
private const val RAPID_STEP_MS = 250L

/** What the list shows, in order: a full-move row of the game, or a line that branches at it. */
private sealed interface MoveListItem {
    data class Row(
        val row: Int,
    ) : MoveListItem

    data class LineRow(
        val line: Line,
    ) : MoveListItem
}

/**
 * One row per full move of the game: the number, White's ply and Black's ply. A line the user played is its own indented row right
 * under the full move where it branches, in the order the lines were made.
 */
@Composable
private fun MoveList(
    state: UiState.Result,
    onSelect: (Int) -> Unit,
    actions: LineActions,
    modifier: Modifier,
) {
    val game: Game = state.game
    val rows = (game.plies.size + 1) / 2
    val items =
        remember(rows, state.lines) {
            (0 until rows).flatMap { row ->
                listOf<MoveListItem>(MoveListItem.Row(row)) +
                    state.lines.all
                        .filter { it.parentId == null && it.branchPly / 2 == row }
                        .map { MoveListItem.LineRow(it) }
            }
        }
    val listState = rememberLazyListState()
    var lastScroll by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.cursor) {
        val target =
            items.indexOfFirst {
                when (it) {
                    is MoveListItem.Row -> state.cursor.lineId == null && state.cursor.ply > 0 && it.row == (state.cursor.ply - 1) / 2
                    is MoveListItem.LineRow -> it.line.id == state.cursor.lineId
                }
            }
        if (target >= 0) {
            val now = SystemClock.uptimeMillis()
            // two selections within a quarter of a second are a hold: follow instantly, never lag behind with an animation
            if (now - lastScroll < RAPID_STEP_MS) listState.scrollToItem(target) else listState.animateScrollToItem(target)
            lastScroll = now
        }
    }
    LazyColumn(modifier.testTag(UiTags.MOVE_LIST), state = listState) {
        items(items, key = { if (it is MoveListItem.LineRow) "line${it.line.id}" else "row${(it as MoveListItem.Row).row}" }) { item ->
            when (item) {
                is MoveListItem.Row -> GameRow(item.row, state, onSelect)
                is MoveListItem.LineRow -> LineRow(item.line, state, actions)
            }
        }
    }
}

@Composable
private fun GameRow(
    row: Int,
    state: UiState.Result,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            "${row + 1}.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(Spacing.moveNumberWidth).padding(Spacing.moveCell),
        )
        for (ply in listOf(row * 2 + 1, row * 2 + 2)) {
            MoveCell(
                text =
                    state.game.plies
                        .getOrNull(ply - 1)
                        ?.san,
                brilliant = state.report.brilliant.getOrNull(ply - 1) == true,
                eval =
                    state.evals
                        .getOrNull(ply)
                        ?.format()
                        .orEmpty(),
                ply = ply,
                selected = state.cursor.lineId == null && ply == state.cursor.ply,
                onSelect = onSelect,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A line as Lichess writes it (`3... Nf6 4. Nf3 Qe7`), indented; each move can be tapped, and the row itself selects the whole line.
 * After a move come its mark (`?!`, `?`, `??`, as on the chart) and its eval, a spinner while the eval is being worked out.
 * Swiping the row right deletes the line (with Undo).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineRow(
    line: Line,
    state: UiState.Result,
    actions: LineActions,
) {
    val dismiss =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { target ->
                when (target) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        actions.onDelete(line.id)
                        true
                    }

                    SwipeToDismissBoxValue.EndToStart, SwipeToDismissBoxValue.Settled -> {
                        false
                    }
                }
            },
        )
    SwipeToDismissBox(
        state = dismiss,
        backgroundContent = { SwipeBackground(dismiss.dismissDirection) },
        enableDismissFromEndToStart = false,
        modifier = Modifier.testTag(UiTags.lineRow(line.id)),
    ) {
        LineRowContent(line, state, actions)
    }
}

/** What shows behind a row being swiped right: a delete icon on red. */
@Composable
internal fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    if (direction != SwipeToDismissBoxValue.StartToEnd) return
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = Spacing.swipeIconPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.line_delete_action))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineRowContent(
    line: Line,
    state: UiState.Result,
    actions: LineActions,
) {
    val palette = LocalBoardPalette.current
    val labels = line.moveLabels()
    val judgements = state.judgementsOf(line)
    val brilliants = state.brilliantOf(line)
    val onRow = state.cursor.lineId == line.id
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .background(if (onRow) palette.selectedMove.copy(alpha = Spacing.SELECTED_ROW_ALPHA) else Color.Transparent)
            .clickable { actions.onSelectLine(line.id) }
            .padding(start = Spacing.lineIndent),
    ) {
        FlowRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                val selected = onRow && state.cursor.ply == index + 1
                val color = if (selected) palette.onSelectedMove else MaterialTheme.colorScheme.onBackground
                val fen = line.plies[index].fen
                val judgement = judgements.getOrNull(index)
                val brilliant = brilliants.getOrNull(index) == true
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                    modifier =
                        Modifier
                            .testTag(UiTags.lineMove(line.id, index + 1))
                            .background(if (selected) palette.selectedMove else Color.Transparent)
                            .clickable { actions.onSelectMove(line.id, index + 1) }
                            .padding(Spacing.moveCell),
                ) {
                    Text(
                        buildAnnotatedString {
                            append(label)
                            if (judgement != null) {
                                withStyle(SpanStyle(color = judgement.color())) { append(stringResource(judgement.marksRes())) }
                            }
                            if (brilliant) {
                                withStyle(
                                    SpanStyle(color = JudgementColors.brilliant),
                                ) { append(stringResource(R.string.marks_brilliant)) }
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = color,
                    )
                    val eval = state.evalOf(fen)
                    when {
                        eval != null -> {
                            Text(eval.formatShort(), style = MaterialTheme.typography.bodySmall, color = color)
                        }

                        state.isPending(fen) -> {
                            val description = stringResource(R.string.line_eval_pending)
                            CircularProgressIndicator(
                                modifier = Modifier.size(Spacing.pendingSpinner).semantics { contentDescription = description },
                                strokeWidth = Spacing.pendingStroke,
                                color = color,
                            )
                        }

                        else -> {
                            Text(stringResource(R.string.line_eval_missing), style = MaterialTheme.typography.bodySmall, color = color)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveCell(
    text: String?,
    brilliant: Boolean,
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
        Text(
            buildAnnotatedString {
                append(text)
                if (brilliant) withStyle(SpanStyle(color = JudgementColors.brilliant)) { append(stringResource(R.string.marks_brilliant)) }
            },
            style = MaterialTheme.typography.bodyLarge,
            color = color,
        )
        Text(eval, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
