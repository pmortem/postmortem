package app.postmortem.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.postmortem.DatabaseUi
import app.postmortem.DbMessage
import app.postmortem.R
import app.postmortem.UiTags
import app.postmortem.data.DbFileInfo
import app.postmortem.data.DbProblem
import app.postmortem.ui.theme.Spacing

/** What the Settings page asks for: the Library, and the database files. */
data class SettingsActions(
    /** Switches to the Library tab; null (no row) where Settings is not a tab of the start page. */
    val onLibrary: (() -> Unit)? = null,
    val onRename: (String) -> Unit = {},
    val onExport: () -> Unit = {},
    val onOpenFile: (String) -> Unit = {},
    val onBrowse: () -> Unit = {},
    val onImport: () -> Unit = {},
)

/**
 * The Settings page: a top bar (with Back when [onBack] is given, as over the Result), the way to the Library when it is a tab of
 * the start page, and the Database section (name, export, open, import).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    database: DatabaseUi,
    actions: SettingsActions,
    onBack: (() -> Unit)? = null,
) {
    PageScaffold(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        backTag = UiTags.SETTINGS_BACK,
        insets = if (onBack == null) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(UiTags.SETTINGS)) {
            item {
                actions.onLibrary?.let { openLibrary ->
                    Text(
                        stringResource(R.string.library_title),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(UiTags.SETTINGS_LIBRARY)
                                .clickable(onClick = openLibrary)
                                .padding(horizontal = Spacing.screen, vertical = Spacing.gap),
                    )
                    HorizontalDivider()
                }
            }
            item { DatabaseSection(database, actions) }
        }
    }
}

@Composable
private fun DatabaseSection(
    database: DatabaseUi,
    actions: SettingsActions,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(Spacing.screen)
            .testTag(UiTags.SETTINGS_DB),
        verticalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Text(stringResource(R.string.db_title), style = MaterialTheme.typography.titleMedium)
        var typed by remember(database.activeName) { mutableStateOf(database.activeName) }
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(R.string.db_name_label)) },
            singleLine = true,
            enabled = !database.busy,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.DB_NAME),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gap), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { actions.onRename(typed) },
                enabled = !database.busy && typed.isNotBlank() && typed != database.activeName,
                modifier = Modifier.testTag(UiTags.DB_NAME_SAVE),
            ) { Text(stringResource(R.string.db_name_save)) }
            OutlinedButton(onClick = actions.onExport, enabled = !database.busy, modifier = Modifier.testTag(UiTags.DB_EXPORT)) {
                Text(stringResource(R.string.db_export))
            }
            if (database.busy) CircularProgressIndicator(Modifier.size(Spacing.pendingSpinner * 2), strokeWidth = Spacing.pendingStroke)
        }
        database.message?.let { Message(it) }
        HorizontalDivider()
        Text(
            stringResource(R.string.db_open_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(UiTags.DB_OPEN),
        )
        Column {
            database.files.forEachIndexed { index, file -> FileRow(index, file, database.busy, actions.onOpenFile) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.gap)) {
            OutlinedButton(onClick = actions.onBrowse, enabled = !database.busy, modifier = Modifier.testTag(UiTags.DB_BROWSE)) {
                Text(stringResource(R.string.db_browse))
            }
            OutlinedButton(onClick = actions.onImport, enabled = !database.busy, modifier = Modifier.testTag(UiTags.DB_IMPORT)) {
                Text(stringResource(R.string.db_import))
            }
        }
    }
}

@Composable
private fun FileRow(
    index: Int,
    file: DbFileInfo,
    busy: Boolean,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val games =
        file.games?.let { pluralStringResource(R.plurals.db_games, it, it) } ?: stringResource(R.string.db_file_unreadable)
    val activeLabel = stringResource(R.string.db_file_active)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(UiTags.dbFile(index))
            .clickable(enabled = !busy) { onOpen(file.fileName) }
            .padding(vertical = Spacing.gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
    ) {
        Column(Modifier.weight(1f)) {
            Text(file.fileName, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.db_file_summary, Formatter.formatShortFileSize(context, file.sizeBytes), games),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (file.active) Icon(Icons.Filled.Check, activeLabel, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Message(message: DbMessage) {
    val text =
        when (message) {
            is DbMessage.Renamed -> stringResource(R.string.db_msg_renamed, message.fileName)
            is DbMessage.Opened -> stringResource(R.string.db_msg_opened, message.fileName)
            is DbMessage.Imported -> stringResource(R.string.db_msg_imported, message.added, message.skipped)
            is DbMessage.Failed -> stringResource(message.problem.text())
        }
    val failed = message is DbMessage.Failed
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag(UiTags.DB_MESSAGE),
    )
}

private fun DbProblem.text(): Int =
    when (this) {
        DbProblem.EMPTY_NAME -> R.string.db_err_empty
        DbProblem.BAD_CHARACTERS -> R.string.db_err_bad_characters
        DbProblem.TOO_LONG -> R.string.db_err_too_long
        DbProblem.EXISTS -> R.string.db_err_exists
        DbProblem.NOT_FOUND -> R.string.db_err_not_found
        DbProblem.NOT_SQLITE -> R.string.db_err_not_sqlite
        DbProblem.NOT_OURS -> R.string.db_err_not_ours
        DbProblem.NEWER -> R.string.db_err_newer
        DbProblem.FAILED -> R.string.db_err_failed
    }
