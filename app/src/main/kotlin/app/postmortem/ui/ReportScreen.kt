package app.postmortem.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import app.postmortem.R
import app.postmortem.UiTags

/** The Report tab. Empty for now: the reports across your games come with their own pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen() {
    Scaffold(
        modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.report_title)) }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.report_coming_soon),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center).testTag(UiTags.REPORT_PLACEHOLDER),
            )
        }
    }
}
