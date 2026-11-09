package app.postmortem.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.postmortem.R
import app.postmortem.UiTags
import app.postmortem.core.queue.BackgroundSettings
import app.postmortem.ui.theme.Spacing

/**
 * The Background analysis section of Settings: games at once, engines in the background, full speed while the app is open. The
 * defaults are gentle, and the section says so. The first time a value leaves the defaults a dialog explains the cost and asks
 * (Cancel keeps the old value); while any value is off the defaults a warning stays, with a button that resets all three.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSection(
    settings: BackgroundSettings,
    maxEngines: Int,
    onChange: (BackgroundSettings) -> Unit,
) {
    var asked: BackgroundSettings? by remember { mutableStateOf(null) }
    val shown = settings.coerced(maxEngines)

    fun change(new: BackgroundSettings) {
        if (settings.needsWarning(new)) asked = new else onChange(new)
    }
    Column(
        Modifier.fillMaxWidth().padding(Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Text(stringResource(R.string.bg_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.bg_gentle), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.bg_games_at_once), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag(UiTags.BG_GAMES_AT_ONCE)) {
            val choices = (1..BackgroundSettings.MAX_GAMES).toList()
            choices.forEachIndexed { index, games ->
                SegmentedButton(
                    selected = shown.gamesAtOnce == games,
                    onClick = { change(shown.copy(gamesAtOnce = games)) },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                ) { Text(games.toString()) }
            }
        }
        Text(stringResource(R.string.bg_engines), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag(UiTags.BG_ENGINES)) {
            val choices = (1..maxEngines.coerceAtLeast(1)).toList()
            choices.forEachIndexed { index, engines ->
                SegmentedButton(
                    selected = shown.engines == engines,
                    onClick = { change(shown.copy(engines = engines)) },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                ) { Text(engines.toString()) }
            }
        }
        Text(
            stringResource(R.string.bg_engines_shared),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.bg_full_speed), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = shown.fullSpeedOpen,
                onCheckedChange = { change(shown.copy(fullSpeedOpen = it)) },
                modifier = Modifier.testTag(UiTags.BG_FULL_SPEED),
            )
        }
        if (!settings.isGentle) {
            Text(
                stringResource(R.string.bg_not_gentle),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(UiTags.BG_WARNING),
            )
            OutlinedButton(onClick = { onChange(BackgroundSettings()) }, modifier = Modifier.testTag(UiTags.BG_RESET)) {
                Text(stringResource(R.string.bg_reset))
            }
        }
        Text(
            stringResource(R.string.bg_applies_next),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    asked?.let { wanted ->
        AlertDialog(
            onDismissRequest = { asked = null },
            title = { Text(stringResource(R.string.bg_warning_title)) },
            text = { Text(stringResource(R.string.bg_warning_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asked = null
                        onChange(wanted)
                    },
                ) { Text(stringResource(R.string.bg_warning_change)) }
            },
            dismissButton = { TextButton(onClick = { asked = null }) { Text(stringResource(R.string.bg_warning_cancel)) } },
        )
    }
}
