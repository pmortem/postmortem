package app.postmortem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.postmortem.R
import app.postmortem.UiTags
import app.postmortem.core.store.GameSummary
import app.postmortem.ui.theme.Spacing

/**
 * The saved games, the one worked on last first: players, result, date, opening, both accuracies and the number of lines. A tap
 * reopens the game with no analysis, a swipe to the right deletes it with an Undo. [games] is null while they are being read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    games: List<GameSummary>?,
    deletedOffered: Boolean,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onUndo: () -> Unit,
    onUndoExpired: () -> Unit,
) {
    val snackbars = remember { SnackbarHostState() }
    val message = stringResource(R.string.library_game_deleted)
    val undo = stringResource(R.string.line_undo)
    LaunchedEffect(deletedOffered) {
        if (!deletedOffered) return@LaunchedEffect
        when (snackbars.showSnackbar(message, undo, duration = SnackbarDuration.Short)) {
            SnackbarResult.ActionPerformed -> onUndo()
            SnackbarResult.Dismissed -> onUndoExpired()
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.library_title)) }) },
        snackbarHost = {
            SnackbarHost(snackbars) { data ->
                Snackbar(
                    action = {
                        TextButton(onClick = { data.performAction() }, modifier = Modifier.testTag(UiTags.UNDO)) {
                            Text(data.visuals.actionLabel.orEmpty())
                        }
                    },
                ) { Text(data.visuals.message) }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).testTag(UiTags.LIBRARY)) {
            when {
                games == null -> {
                    Unit
                }

                games.isEmpty() -> {
                    Text(
                        stringResource(R.string.library_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(games, key = { _, game -> game.id }) { index, game ->
                            GameRow(index, game, onOpen, onDelete)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameRow(
    index: Int,
    game: GameSummary,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val dismiss =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { target ->
                if (target == SwipeToDismissBoxValue.StartToEnd) onDelete(game.id)
                target == SwipeToDismissBoxValue.StartToEnd
            },
        )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromEndToStart = false,
        backgroundContent = { SwipeBackground(dismiss.dismissDirection) },
        modifier = Modifier.testTag(UiTags.libraryItem(index)),
    ) {
        val overview = game.overview
        val separator = stringResource(R.string.library_separator)
        val players =
            stringResource(
                R.string.library_players,
                overview.white ?: stringResource(R.string.summary_white),
                overview.black ?: stringResource(R.string.summary_black),
            )
        val what = listOfNotNull(overview.result, overview.date, game.opening ?: overview.opening).joinToString(separator)
        val whiteAccuracy = game.whiteAccuracy
        val blackAccuracy = game.blackAccuracy
        val numbers =
            listOfNotNull(
                if (whiteAccuracy != null && blackAccuracy != null) {
                    stringResource(R.string.library_accuracies, whiteAccuracy, blackAccuracy)
                } else {
                    null
                },
                game.lineCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.library_lines, it, it) },
            ).joinToString(separator)
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .clickable { onOpen(game.id) }
                .padding(horizontal = Spacing.screen, vertical = Spacing.gap),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(players, style = MaterialTheme.typography.titleMedium)
            if (what.isNotEmpty()) Text(what, style = MaterialTheme.typography.bodyMedium)
            if (numbers.isNotEmpty()) {
                Text(numbers, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
